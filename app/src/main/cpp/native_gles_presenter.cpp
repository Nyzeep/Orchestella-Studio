#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "graphics/native_gles_presenter.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeCreate(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    auto* presenter = new orchestella::NativeGlesPresenter();
    return reinterpret_cast<jlong>(presenter);
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeRelease(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle != 0) {
        auto* presenter = reinterpret_cast<orchestella::NativeGlesPresenter*>(handle);
        delete presenter;
    }
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeOnSurfaceCreated(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jobject surface) {
    if (handle == 0 || surface == nullptr) {
        return JNI_FALSE;
    }
    auto* presenter = reinterpret_cast<orchestella::NativeGlesPresenter*>(handle);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        return JNI_FALSE;
    }
    return presenter->onSurfaceCreated(window) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeOnSurfaceChanged(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint width, jint height) {
    if (handle != 0) {
        auto* presenter = reinterpret_cast<orchestella::NativeGlesPresenter*>(handle);
        presenter->onSurfaceChanged(width, height);
    }
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeOnSurfaceDestroyed(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle != 0) {
        auto* presenter = reinterpret_cast<orchestella::NativeGlesPresenter*>(handle);
        presenter->onSurfaceDestroyed();
    }
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativePresent(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jintArray pixels,
    jint width, jint height, jint destX, jint destY,
    jint destWidth, jint destHeight, jint viewportWidth, jint viewportHeight) {
    if (handle == 0 || pixels == nullptr) {
        return;
    }
    auto* presenter = reinterpret_cast<orchestella::NativeGlesPresenter*>(handle);
    void* ptr = env->GetPrimitiveArrayCritical(pixels, nullptr);
    if (ptr != nullptr) {
        presenter->present(reinterpret_cast<const int32_t*>(ptr), width, height,
                           destX, destY, destWidth, destHeight, viewportWidth, viewportHeight);
        env->ReleasePrimitiveArrayCritical(pixels, ptr, JNI_ABORT);
    } else {
        jint* fallback = env->GetIntArrayElements(pixels, nullptr);
        if (fallback != nullptr) {
            presenter->present(reinterpret_cast<const int32_t*>(fallback), width, height,
                               destX, destY, destWidth, destHeight, viewportWidth, viewportHeight);
            env->ReleaseIntArrayElements(pixels, fallback, JNI_ABORT);
        }
    }
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeClear(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint argbColor) {
    if (handle != 0) {
        auto* presenter = reinterpret_cast<orchestella::NativeGlesPresenter*>(handle);
        presenter->clear(argbColor);
    }
}

JNIEXPORT jfloat JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeGetLastPresentTimeMs(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle != 0) {
        auto* presenter = reinterpret_cast<orchestella::NativeGlesPresenter*>(handle);
        return presenter->getLastPresentTimeMs();
    }
    return 0.0f;
}

JNIEXPORT jstring JNICALL
Java_io_github_nyzeep_orchestella_NativeGlesSurfacePresenter_nativeGetVersion(
    JNIEnv* env, jclass /*clazz*/) {
    return env->NewStringUTF("GLES3-PBO-v1.0");
}

} // extern "C"
