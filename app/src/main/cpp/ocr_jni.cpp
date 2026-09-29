// JNI bridge for bot.coati.perception.NcnnOcrNative: PP-OCRv5 on ncnn's Vulkan backend.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <gpu.h>

#include "ppocrv5.h"

#define LOG_TAG "CoatiOcr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// The Vulkan instance is process-wide; engines hold a reference so it is destroyed only after the
// last engine's nets are gone.
std::mutex g_instance_mutex;
int g_instance_refs = 0;

bool acquire_gpu_instance()
{
    std::lock_guard<std::mutex> lock(g_instance_mutex);
    if (g_instance_refs == 0 && ncnn::create_gpu_instance() != 0)
    {
        LOGW("create_gpu_instance failed");
        ncnn::destroy_gpu_instance();
        return false;
    }
    g_instance_refs++;
    return true;
}

void release_gpu_instance()
{
    std::lock_guard<std::mutex> lock(g_instance_mutex);
    if (--g_instance_refs == 0)
        ncnn::destroy_gpu_instance();
}

struct Engine
{
    std::mutex mutex; // ncnn nets are used by one call at a time
    PPOCRv5 ocr;
};

// JNI's NewStringUTF expects modified UTF-8, which breaks on 4-byte sequences (emoji in the
// dictionary), so convert to UTF-16 explicitly.
jstring new_string(JNIEnv* env, const std::string& utf8)
{
    std::u16string out;
    out.reserve(utf8.size());
    size_t i = 0;
    while (i < utf8.size())
    {
        const unsigned char c = (unsigned char)utf8[i];
        uint32_t cp;
        size_t len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
        else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
        else if ((c >> 3) == 0x1E) { cp = c & 0x07; len = 4; }
        else { out.push_back(0xFFFD); i++; continue; }
        if (i + len > utf8.size()) { out.push_back(0xFFFD); break; }
        for (size_t k = 1; k < len; k++)
            cp = (cp << 6) | ((unsigned char)utf8[i + k] & 0x3F);
        i += len;
        if (cp >= 0x10000)
        {
            cp -= 0x10000;
            out.push_back((char16_t)(0xD800 + (cp >> 10)));
            out.push_back((char16_t)(0xDC00 + (cp & 0x3FF)));
        }
        else
        {
            out.push_back((char16_t)cp);
        }
    }
    return env->NewString(reinterpret_cast<const jchar*>(out.data()), (jsize)out.size());
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_bot_coati_perception_NcnnOcrNative_nativeCreate(JNIEnv* env, jclass, jobject asset_manager, jint det_size)
{
    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr)
        return 0;
    if (!acquire_gpu_instance())
        return 0;

    const int gpus = ncnn::get_gpu_count();
    if (gpus == 0)
    {
        LOGW("no Vulkan GPU, OCR disabled");
        release_gpu_instance();
        return 0;
    }
    const ncnn::GpuInfo& info = ncnn::get_gpu_info(ncnn::get_default_gpu_index());
    LOGI("Vulkan GPU: %s (type=%d, %d device(s))", info.device_name(), info.type(), gpus);

    std::unique_ptr<Engine> engine(new Engine());
    if (engine->ocr.load(mgr, "ocr/PP_OCRv5_mobile_det.ncnn.param", "ocr/PP_OCRv5_mobile_det.ncnn.bin",
                         "ocr/PP_OCRv5_mobile_rec.ncnn.param", "ocr/PP_OCRv5_mobile_rec.ncnn.bin",
                         "ocr/ppocrv5_dict.txt") != 0)
    {
        engine.reset();
        release_gpu_instance();
        return 0;
    }
    engine->ocr.set_target_size(det_size);
    return reinterpret_cast<jlong>(engine.release());
}

JNIEXPORT jstring JNICALL
Java_bot_coati_perception_NcnnOcrNative_nativeDeviceName(JNIEnv* env, jclass)
{
    std::lock_guard<std::mutex> lock(g_instance_mutex);
    if (g_instance_refs == 0 || ncnn::get_gpu_count() == 0)
        return nullptr;
    return new_string(env, ncnn::get_gpu_info(ncnn::get_default_gpu_index()).device_name());
}

// Returns [String[] texts, float[] conf,x0,y0,x1,y1 per line] or null on failure.
JNIEXPORT jobjectArray JNICALL
Java_bot_coati_perception_NcnnOcrNative_nativeRecognize(JNIEnv* env, jclass, jlong handle, jobject bitmap)
{
    Engine* engine = reinterpret_cast<Engine*>(handle);
    if (!engine)
        return nullptr;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
    {
        LOGW("bitmap must be ARGB_8888");
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels)
        return nullptr;

    std::vector<OcrText> lines;
    int rc;
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        rc = engine->ocr.detect_and_recognize(static_cast<const unsigned char*>(pixels), (int)info.width,
                                              (int)info.height, (int)info.stride, lines);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    if (rc != 0)
    {
        LOGW("inference failed: %d", rc);
        return nullptr;
    }

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray texts = env->NewObjectArray((jsize)lines.size(), string_class, nullptr);
    std::vector<jfloat> values(lines.size() * 5);
    for (size_t i = 0; i < lines.size(); i++)
    {
        jstring s = new_string(env, lines[i].text);
        env->SetObjectArrayElement(texts, (jsize)i, s);
        env->DeleteLocalRef(s);
        values[i * 5 + 0] = lines[i].prob;
        values[i * 5 + 1] = (jfloat)lines[i].x0;
        values[i * 5 + 2] = (jfloat)lines[i].y0;
        values[i * 5 + 3] = (jfloat)lines[i].x1;
        values[i * 5 + 4] = (jfloat)lines[i].y1;
    }
    jfloatArray boxes = env->NewFloatArray((jsize)values.size());
    env->SetFloatArrayRegion(boxes, 0, (jsize)values.size(), values.data());

    jclass object_class = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(2, object_class, nullptr);
    env->SetObjectArrayElement(result, 0, texts);
    env->SetObjectArrayElement(result, 1, boxes);
    return result;
}

JNIEXPORT void JNICALL
Java_bot_coati_perception_NcnnOcrNative_nativeRelease(JNIEnv*, jclass, jlong handle)
{
    Engine* engine = reinterpret_cast<Engine*>(handle);
    if (!engine)
        return;
    {
        // Wait for an in-flight recognize before tearing down the nets.
        std::lock_guard<std::mutex> lock(engine->mutex);
    }
    delete engine;
    release_gpu_instance();
}

} // extern "C"
