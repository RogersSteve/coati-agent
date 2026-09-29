// PP-OCRv5 mobile text detection + recognition on ncnn's Vulkan backend.
//
// Adapted from nihui/ncnn-android-ppocrv5 (ppocrv5.h/.cpp, Copyright (C) 2025 THL A29 Limited, a
// Tencent company, BSD 3-Clause License, https://opensource.org/licenses/BSD-3-Clause). Changes:
// no OpenCV (axis-aligned DB boxes from connected components, crops via ncnn pixel helpers),
// RGBA input, dictionary loaded from assets, GPU-only execution.

#ifndef COATI_PPOCRV5_H
#define COATI_PPOCRV5_H

#include <string>
#include <vector>

#include <android/asset_manager.h>

#include <net.h>

struct OcrText
{
    std::string text; // UTF-8
    float prob;       // mean probability of the emitted characters
    int x0, y0, x1, y1; // axis-aligned bounds in image pixels, x1/y1 exclusive
};

class PPOCRv5
{
public:
    // Loads det/rec models and the dictionary. Returns 0 on success. Fails when there is no usable
    // Vulkan GPU: inference never falls back to the CPU.
    int load(AAssetManager* mgr, const char* det_param, const char* det_bin, const char* rec_param,
             const char* rec_bin, const char* dict_path);

    // Detection long side, rounded up to a multiple of 32.
    void set_target_size(int target_size);

    // Runs detection then recognition on a tightly or loosely packed RGBA image.
    int detect_and_recognize(const unsigned char* rgba, int width, int height, int stride,
                             std::vector<OcrText>& results);

private:
    struct Box
    {
        int x0, y0, x1, y1;
        float score;
    };

    int detect(const unsigned char* rgba, int width, int height, int stride, std::vector<Box>& boxes);
    int recognize(const unsigned char* rgba, int width, int height, int stride, const Box& box, OcrText& out);

    ncnn::Net det_;
    ncnn::Net rec_;
    std::vector<std::string> dict_;
    int target_size_ = 960;
};

#endif // COATI_PPOCRV5_H
