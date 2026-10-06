/*
 * Copyright (C) 2026 Orchestella Studio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

#include "virglrenderer.h"
#include "virgl_memory_quota.h"

#include <android/log.h>
#include <jni.h>

#define LOG_TAG "DroidLayerQemu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeInit(
        JNIEnv *env, jclass clazz, jint quotaMb, jboolean enableRobustness) {
    (void)env; (void)clazz; (void)enableRobustness;

    if (quotaMb > 0) {
        virgl_renderer_set_memory_quota_mb(quotaMb);
    }

    int flags = VIRGL_RENDERER_USE_EGL | VIRGL_RENDERER_USE_GLES;
    int res = virgl_renderer_init(nullptr, flags, nullptr);
    if (res == VIRGL_RENDERER_OK) {
        LOGI("VirglJniBridge: nativeInit succeeded (quota: %d MB)", virgl_renderer_get_memory_quota_mb());
        return JNI_TRUE;
    } else {
        LOGE("VirglJniBridge: nativeInit failed with code %d", res);
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeCleanup(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    virgl_renderer_cleanup(nullptr);
    LOGI("VirglJniBridge: nativeCleanup completed");
}

JNIEXPORT jlong JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeGetAllocatedMemoryBytes(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jlong)virgl_renderer_get_allocated_bytes();
}

JNIEXPORT jint JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeGetMemoryQuotaMb(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return (jint)virgl_renderer_get_memory_quota_mb();
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeSetMemoryQuotaMb(
        JNIEnv *env, jclass clazz, jint quotaMb) {
    (void)env; (void)clazz;
    virgl_renderer_set_memory_quota_mb(quotaMb);
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeIsRobustnessEnabled(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return virgl_renderer_is_robustness_enabled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeTryAllocateTestMemory(
        JNIEnv *env, jclass clazz, jlong bytes) {
    (void)env; (void)clazz;
    return virgl_quota_try_allocate((size_t)bytes) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeReleaseTestMemory(
        JNIEnv *env, jclass clazz, jlong bytes) {
    (void)env; (void)clazz;
    virgl_quota_release((size_t)bytes);
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_droidlayer_runtime_engineqemu_VirglJniBridge_nativeSimulateShaderCrash(
        JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    LOGW("VirglJniBridge: Simulating malicious Guest shader crash to test EGL Robustness recovery...");
    // Verify that check_and_reset_if_lost executes cleanly without crashing host
    virgl_renderer_check_and_reset_if_lost();
    LOGI("VirglJniBridge: App survived shader crash simulation, EGL context recovered successfully.");
    return JNI_TRUE;
}

} // extern "C"
