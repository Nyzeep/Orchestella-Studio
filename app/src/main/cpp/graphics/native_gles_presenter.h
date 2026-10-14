#ifndef ORCHESTELLA_NATIVE_GLES_PRESENTER_H
#define ORCHESTELLA_NATIVE_GLES_PRESENTER_H

#include <android/native_window.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <cstdint>
#include <cstddef>

namespace orchestella {

class NativeGlesPresenter {
public:
    NativeGlesPresenter();
    ~NativeGlesPresenter();

    // Non-copyable
    NativeGlesPresenter(const NativeGlesPresenter&) = delete;
    NativeGlesPresenter& operator=(const NativeGlesPresenter&) = delete;

    bool onSurfaceCreated(ANativeWindow* window);
    void onSurfaceChanged(int width, int height);
    void onSurfaceDestroyed();
    void release();

    bool present(const int32_t* pixels, int width, int height,
                 int destX, int destY, int destWidth, int destHeight,
                 int viewportWidth, int viewportHeight);

    void clear(int32_t argbColor);

    bool isReady() const { return isReady_; }
    float getLastPresentTimeMs() const { return lastPresentTimeMs_; }

private:
    bool initEgl();
    void terminateEgl();
    bool initGlProgram();
    void deleteGlResources();

    ANativeWindow* window_ = nullptr;
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLConfig eglConfig_ = nullptr;

    int surfaceWidth_ = 0;
    int surfaceHeight_ = 0;

    // GL shader program and uniforms
    GLuint program_ = 0;
    GLint posLoc_ = -1;
    GLint texCoordLoc_ = -1;
    GLint samplerLoc_ = -1;
    GLuint vbo_ = 0;

    // Target texture for display
    GLuint targetTexture_ = 0;
    int textureWidth_ = 0;
    int textureHeight_ = 0;

    // Ping-pong PBO streaming buffers
    GLuint pboIds_[2] = {0, 0};
    int pboIndex_ = 0;
    bool pboInitialized_ = false;
    size_t pboCapacityBytes_ = 0;
    bool firstFrame_ = true;

    bool isReady_ = false;
    float lastPresentTimeMs_ = 0.0f;
};

} // namespace orchestella

#endif // ORCHESTELLA_NATIVE_GLES_PRESENTER_H
