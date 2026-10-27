// jni.cpp -- the Kotlin side's door to studio.cpp (`app.spektralab.engine.NativeStudio`).
//
// One Studio for the process, as the iOS app holds one `Engine` actor: one
// engine handle, one open frame. Every entry takes the same lock, so a call
// from the wrong thread waits rather than racing; the app makes them all from
// one worker thread anyway (`EngineThread`). Failures are thrown as
// `app.spektralab.engine.EngineException` with the engine's own message;
// nothing native unwinds into the JVM.

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include "studio.hpp"

using spkandroid::Finish;
using spkandroid::Image8;
using spkandroid::Studio;

namespace {

std::mutex g_lock;
std::unique_ptr<Studio> g_studio;
double g_last_ms = 0;

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void raise(JNIEnv* env, const std::string& message) {
    __android_log_print(ANDROID_LOG_WARN, "SpektraLab", "%s", message.c_str());
    jclass cls = env->FindClass("app/spektralab/engine/EngineException");
    if (!cls) return;   // FindClass already threw
    env->ThrowNew(cls, message.c_str());
}

Studio* studio(JNIEnv* env) {
    if (!g_studio) raise(env, "the render engine is not running");
    return g_studio.get();
}

// Exposure, contrast, saturation, highlights, shadows, temperature, tint,
// vignette: `Finish.toArray()` on the Kotlin side, in this order.
Finish finish_of(JNIEnv* env, jdoubleArray a) {
    Finish f;
    if (!a || env->GetArrayLength(a) < 8) return f;
    jdouble v[8];
    env->GetDoubleArrayRegion(a, 0, 8, v);
    f.exposure = v[0]; f.contrast = v[1]; f.saturation = v[2]; f.highlights = v[3];
    f.shadows = v[4]; f.temperature = v[5]; f.tint = v[6]; f.vignette = v[7];
    return f;
}

// An ARGB_8888 Bitmap (RGBA bytes in memory) holding `image`.
jobject bitmap_of(JNIEnv* env, const Image8& image) {
    if (!image.width || !image.height) return nullptr;
    jclass config_cls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb = env->GetStaticFieldID(config_cls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(config_cls, argb);
    jclass bitmap_cls = env->FindClass("android/graphics/Bitmap");
    jmethodID create = env->GetStaticMethodID(bitmap_cls, "createBitmap",
                                              "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmap_cls, create, jint(image.width), jint(image.height), config);
    if (!bitmap || env->ExceptionCheck()) return nullptr;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        raise(env, "could not write the print into a bitmap");
        return nullptr;
    }
    for (uint32_t y = 0; y < image.height; ++y)
        std::memcpy(static_cast<uint8_t*>(pixels) + size_t(y) * info.stride, &image.rgba[size_t(y) * image.width * 4],
                    size_t(image.width) * 4);
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_app_spektralab_engine_NativeStudio_buildInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(spk_build_info());
}

JNIEXPORT jstring JNICALL Java_app_spektralab_engine_NativeStudio_start(JNIEnv* env, jobject, jstring resources) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (!g_studio) {
        auto s = std::make_unique<Studio>();
        std::string error;
        if (!s->start(str(env, resources), error)) {
            raise(env, error);
            return nullptr;
        }
        g_studio = std::move(s);
    }
    return env->NewStringUTF(g_studio->engine_info().c_str());
}

JNIEXPORT jstring JNICALL Java_app_spektralab_engine_NativeStudio_openFile(
    JNIEnv* env, jobject, jstring path, jstring wb, jstring delta, jlong max_pixels, jint preview_edge) {
    std::lock_guard<std::mutex> lock(g_lock);
    Studio* s = studio(env);
    if (!s) return nullptr;
    std::string reply, error;
    if (!s->open_file(str(env, path), str(env, wb), str(env, delta), uint64_t(std::max<jlong>(0, max_pixels)),
                      uint32_t(std::max<jint>(0, preview_edge)), reply, error)) {
        raise(env, error);
        return nullptr;
    }
    return env->NewStringUTF(reply.c_str());
}

JNIEXPORT jstring JNICALL Java_app_spektralab_engine_NativeStudio_openPixels(
    JNIEnv* env, jobject, jobject bitmap, jstring meta, jstring wb, jstring delta, jlong max_pixels,
    jint preview_edge) {
    std::lock_guard<std::mutex> lock(g_lock);
    Studio* s = studio(env);
    if (!s) return nullptr;
    AndroidBitmapInfo info{};
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        raise(env, "the photo was not decoded to 8-bit RGBA");
        return nullptr;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        raise(env, "could not read the decoded photo");
        return nullptr;
    }
    std::string reply, error;
    const bool ok = s->open_pixels(static_cast<const uint8_t*>(pixels), info.width, info.height, info.stride,
                                   str(env, meta), str(env, wb), str(env, delta),
                                   uint64_t(std::max<jlong>(0, max_pixels)), uint32_t(std::max<jint>(0, preview_edge)),
                                   reply, error);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (!ok) {
        raise(env, error);
        return nullptr;
    }
    return env->NewStringUTF(reply.c_str());
}

JNIEXPORT jobject JNICALL Java_app_spektralab_engine_NativeStudio_render(JNIEnv* env, jobject, jstring delta,
                                                                        jdoubleArray finish) {
    std::lock_guard<std::mutex> lock(g_lock);
    Studio* s = studio(env);
    if (!s) return nullptr;
    Image8 image;
    std::string error;
    double ms = 0;
    if (!s->render(str(env, delta), finish_of(env, finish), image, ms, error)) {
        raise(env, error);
        return nullptr;
    }
    g_last_ms = ms;
    return bitmap_of(env, image);
}

JNIEXPORT jobject JNICALL Java_app_spektralab_engine_NativeStudio_refinish(JNIEnv* env, jobject, jdoubleArray finish) {
    std::lock_guard<std::mutex> lock(g_lock);
    Studio* s = studio(env);
    if (!s) return nullptr;
    Image8 image;
    if (!s->refinish(finish_of(env, finish), image)) return nullptr;
    return bitmap_of(env, image);
}

JNIEXPORT jobject JNICALL Java_app_spektralab_engine_NativeStudio_original(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    Studio* s = studio(env);
    if (!s) return nullptr;
    return bitmap_of(env, s->original());
}

JNIEXPORT jdouble JNICALL Java_app_spektralab_engine_NativeStudio_lastRenderMs(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    return g_last_ms;
}

JNIEXPORT jdouble JNICALL Java_app_spektralab_engine_NativeStudio_meteredEv(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    Studio* s = studio(env);
    double ev = 0;
    if (!s || !s->metered_ev(ev)) return std::nan("");
    return ev;
}

JNIEXPORT jstring JNICALL Java_app_spektralab_engine_NativeStudio_export(
    JNIEnv* env, jobject, jstring path, jstring delta, jdoubleArray finish, jstring format, jint long_edge,
    jint quality) {
    std::lock_guard<std::mutex> lock(g_lock);
    Studio* s = studio(env);
    if (!s) return nullptr;
    std::string reply, error;
    if (!s->export_file(str(env, path), str(env, delta), finish_of(env, finish), str(env, format),
                        uint32_t(std::max<jint>(0, long_edge)), int(quality), reply, error)) {
        raise(env, error);
        return nullptr;
    }
    return env->NewStringUTF(reply.c_str());
}

JNIEXPORT void JNICALL Java_app_spektralab_engine_NativeStudio_close(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_studio) g_studio->close();
}

}  // extern "C"
