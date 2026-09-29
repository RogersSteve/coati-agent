// PP-OCRv5 mobile pipeline on ncnn Vulkan. See ppocrv5.h for provenance and license.

#include "ppocrv5.h"

#include <algorithm>
#include <cmath>

#include <android/log.h>

#include <gpu.h>

#define LOG_TAG "CoatiOcr"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// DB postprocess thresholds (PaddleOCR defaults).
constexpr float kDetThresh = 0.3f;
constexpr float kBoxThresh = 0.6f;
constexpr float kUnclipRatio = 1.5f;
constexpr int kMinSize = 3;
constexpr int kMaxCandidates = 1000;
constexpr int kStride = 32;

constexpr int kRecHeight = 48;
constexpr int kRecMinWidth = 16;
constexpr int kRecMaxWidth = 2048;

void configure_gpu(ncnn::Net& net)
{
    net.opt.use_vulkan_compute = true;
    // The mobile models are fp16-safe (the reference project runs them in fp16 on the GPU).
    net.opt.use_fp16_packed = true;
    net.opt.use_fp16_storage = true;
    net.opt.use_fp16_arithmetic = true;
    net.set_vulkan_device(ncnn::get_default_gpu_index());
}

bool on_gpu(const ncnn::Net& net)
{
    return net.opt.use_vulkan_compute && net.vulkan_device() != nullptr;
}

int load_dict(AAssetManager* mgr, const char* path, std::vector<std::string>& dict)
{
    AAsset* asset = AAssetManager_open(mgr, path, AASSET_MODE_BUFFER);
    if (!asset)
        return -1;
    const char* data = static_cast<const char*>(AAsset_getBuffer(asset));
    const off_t size = AAsset_getLength(asset);
    dict.clear();
    if (data)
    {
        off_t start = 0;
        for (off_t i = 0; i <= size; i++)
        {
            if (i == size || data[i] == '\n')
            {
                off_t end = i;
                if (end > start && data[end - 1] == '\r')
                    end--;
                if (i < size || end > start)
                    dict.emplace_back(data + start, data + end);
                start = i + 1;
            }
        }
    }
    AAsset_close(asset);
    return dict.empty() ? -1 : 0;
}

} // namespace

int PPOCRv5::load(AAssetManager* mgr, const char* det_param, const char* det_bin, const char* rec_param,
                  const char* rec_bin, const char* dict_path)
{
    det_.clear();
    rec_.clear();

    if (ncnn::get_gpu_count() == 0)
    {
        LOGW("no Vulkan GPU");
        return -1;
    }
    if (ncnn::get_gpu_info(ncnn::get_default_gpu_index()).type() == 3)
    {
        LOGW("default Vulkan device is a CPU implementation");
        return -1;
    }

    configure_gpu(det_);
    configure_gpu(rec_);

    if (det_.load_param(mgr, det_param) != 0 || det_.load_model(mgr, det_bin) != 0)
    {
        LOGW("failed to load %s", det_param);
        return -1;
    }
    if (rec_.load_param(mgr, rec_param) != 0 || rec_.load_model(mgr, rec_bin) != 0)
    {
        LOGW("failed to load %s", rec_param);
        return -1;
    }
    if (!on_gpu(det_) || !on_gpu(rec_))
    {
        LOGW("ncnn disabled Vulkan compute");
        return -1;
    }
    if (load_dict(mgr, dict_path, dict_) != 0)
    {
        LOGW("failed to load %s", dict_path);
        return -1;
    }
    return 0;
}

void PPOCRv5::set_target_size(int target_size)
{
    target_size_ = std::max(kStride, (target_size + kStride - 1) / kStride * kStride);
}

int PPOCRv5::detect(const unsigned char* rgba, int width, int height, int stride, std::vector<Box>& boxes)
{
    // Downscale so the long side fits target_size_ (never upscale), then pad to a multiple of 32.
    int w = width;
    int h = height;
    float scale = 1.f;
    if (std::max(w, h) > target_size_)
    {
        scale = (float)target_size_ / std::max(w, h);
        w = std::max(1, (int)std::lround(width * scale));
        h = std::max(1, (int)std::lround(height * scale));
    }
    const float sx = (float)w / width;
    const float sy = (float)h / height;

    ncnn::Mat in = ncnn::Mat::from_pixels_resize(rgba, ncnn::Mat::PIXEL_RGBA2BGR, width, height, stride, w, h);

    const int wpad = (w + kStride - 1) / kStride * kStride - w;
    const int hpad = (h + kStride - 1) / kStride * kStride - h;
    const int left = wpad / 2;
    const int top = hpad / 2;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, top, hpad - top, left, wpad - left, ncnn::BORDER_CONSTANT, 114.f);

    const float mean_vals[3] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
    const float norm_vals[3] = {1 / 0.229f / 255.f, 1 / 0.224f / 255.f, 1 / 0.225f / 255.f};
    in_pad.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = det_.create_extractor();
    ex.input("in0", in_pad);
    ncnn::Mat out;
    if (ex.extract("out0", out) != 0)
        return -1;

    // DB postprocess: 8-connected components of the binarized probability map, each scored by
    // its mean probability, turned into an axis-aligned box and unclipped by area*ratio/perimeter.
    // UI text is upright, so rotated boxes are not needed.
    const int mw = out.w;
    const int mh = out.h;
    const float* prob = out.channel(0);
    std::vector<unsigned char> visited(mw * mh, 0);
    std::vector<int> stack(mw * mh);
    for (int start = 0; start < mw * mh && (int)boxes.size() < kMaxCandidates; start++)
    {
        if (visited[start] || prob[start] <= kDetThresh)
            continue;
        visited[start] = 1;
        int sp = 0;
        stack[sp++] = start;
        int min_x = mw, min_y = mh, max_x = -1, max_y = -1;
        double sum = 0;
        int count = 0;
        while (sp > 0)
        {
            const int idx = stack[--sp];
            const int x = idx % mw;
            const int y = idx / mw;
            min_x = std::min(min_x, x);
            max_x = std::max(max_x, x);
            min_y = std::min(min_y, y);
            max_y = std::max(max_y, y);
            sum += prob[idx];
            count++;
            for (int dy = -1; dy <= 1; dy++)
            {
                const int ny = y + dy;
                if (ny < 0 || ny >= mh)
                    continue;
                for (int dx = -1; dx <= 1; dx++)
                {
                    const int nx = x + dx;
                    if (nx < 0 || nx >= mw)
                        continue;
                    const int n = ny * mw + nx;
                    if (!visited[n] && prob[n] > kDetThresh)
                    {
                        visited[n] = 1;
                        stack[sp++] = n;
                    }
                }
            }
        }

        const float bw = (float)(max_x - min_x + 1);
        const float bh = (float)(max_y - min_y + 1);
        if (std::min(bw, bh) < kMinSize)
            continue;
        const float score = (float)(sum / count);
        if (score < kBoxThresh)
            continue;
        const float d = bw * bh * kUnclipRatio / (2.f * (bw + bh));
        const float l = std::max(0.f, min_x - d);
        const float t = std::max(0.f, min_y - d);
        const float r = std::min((float)mw, max_x + 1 + d);
        const float b = std::min((float)mh, max_y + 1 + d);
        if (std::min(r - l, b - t) < kMinSize + 2)
            continue;

        Box box;
        box.x0 = std::clamp((int)std::floor((l - left) / sx), 0, width);
        box.y0 = std::clamp((int)std::floor((t - top) / sy), 0, height);
        box.x1 = std::clamp((int)std::ceil((r - left) / sx), 0, width);
        box.y1 = std::clamp((int)std::ceil((b - top) / sy), 0, height);
        box.score = score;
        if (box.x1 - box.x0 < 2 || box.y1 - box.y0 < 2)
            continue;
        boxes.push_back(box);
    }
    return 0;
}

int PPOCRv5::recognize(const unsigned char* rgba, int width, int height, int stride, const Box& box, OcrText& out)
{
    const int roiw = box.x1 - box.x0;
    const int roih = box.y1 - box.y0;
    const int target_w = std::clamp((int)std::lround((float)kRecHeight * roiw / roih), kRecMinWidth, kRecMaxWidth);

    ncnn::Mat in = ncnn::Mat::from_pixels_roi_resize(rgba, ncnn::Mat::PIXEL_RGBA2BGR, width, height, stride,
                                                     box.x0, box.y0, roiw, roih, target_w, kRecHeight);
    const float mean_vals[3] = {127.5f, 127.5f, 127.5f};
    const float norm_vals[3] = {1 / 127.5f, 1 / 127.5f, 1 / 127.5f};
    in.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor ex = rec_.create_extractor();
    ex.input("in0", in);
    ncnn::Mat probs;
    if (ex.extract("out0", probs) != 0)
        return -1;

    // probs: steps (h) x classes (w), softmax. CTC greedy decode: class 0 is blank, class i is
    // dict[i - 1], classes past the dictionary are the space; repeats collapse unless separated.
    std::string text;
    float conf_sum = 0.f;
    int emitted = 0;
    int last = 0;
    for (int t = 0; t < probs.h; t++)
    {
        const float* p = probs.row(t);
        int best = 0;
        float best_p = p[0];
        for (int c = 1; c < probs.w; c++)
        {
            if (p[c] > best_p)
            {
                best_p = p[c];
                best = c;
            }
        }
        if (best == last)
            continue;
        last = best;
        if (best == 0)
            continue;
        const size_t id = (size_t)(best - 1);
        if (id < dict_.size())
            text += dict_[id];
        else if (!text.empty() && text.back() != ' ')
            text += ' ';
        conf_sum += best_p;
        emitted++;
    }
    while (!text.empty() && text.back() == ' ')
        text.pop_back();

    out.text = text;
    out.prob = emitted > 0 ? conf_sum / emitted : 0.f;
    out.x0 = box.x0;
    out.y0 = box.y0;
    out.x1 = box.x1;
    out.y1 = box.y1;
    return 0;
}

int PPOCRv5::detect_and_recognize(const unsigned char* rgba, int width, int height, int stride,
                                  std::vector<OcrText>& results)
{
    std::vector<Box> boxes;
    if (detect(rgba, width, height, stride, boxes) != 0)
        return -1;
    // Sequential: each extractor submits to the GPU queue; the reference parallelizes on CPU cores.
    for (const Box& box : boxes)
    {
        OcrText line;
        if (recognize(rgba, width, height, stride, box, line) != 0)
            return -1;
        if (!line.text.empty())
            results.push_back(line);
    }
    return 0;
}
