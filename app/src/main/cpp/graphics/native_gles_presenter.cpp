#include "native_gles_presenter.h"
#include "pixel_format.h"

#include <android/log.h>
#include <chrono>
#include <cstring>
#include <vector>

#define LOG_TAG "OrchestellaGles"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace orchestella {

namespace {

const char* const kVertexShaderSource = R"glsl(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
)glsl";

const char* const kFragmentShaderSource = R"glsl(#version 300 es
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTexture;

void main() {
    vec4 c = texture(uTexture, vTexCoord);
    fragColor = c;
}
)glsl";

// Quad vertices: position (x, y) and texture coordinate (u, v)
// Top-left is (0, 0) in Wayland texture space; bottom-right is (1, 1).
const float kQuadVertices[] = {
    //  x,     y,     u,    v
    -1.0f,  1.0f,  0.0f, 0.0f, // Top-Left
    -1.0f, -1.0f,  0.0f, 1.0f, // Bottom-Left
     1.0f,  1.0f,  1.0f, 0.0f, // Top-Right
     1.0f, -1.0f,  1.0f, 1.0f  // Bottom-Right
};

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) {
        LOGE("Failed to create shader type %u", type);
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(static_cast<size_t>(infoLen));
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Shader compilation error: %s", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

} // namespace

NativeGlesPresenter::NativeGlesPresenter() = default;

NativeGlesPresenter::~NativeGlesPresenter() {
    release();
}

bool NativeGlesPresenter::initEgl() {
    if (eglDisplay_ != EGL_NO_DISPLAY && eglContext_ != EGL_NO_CONTEXT) {
        return true;
    }

    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed: 0x%x", eglGetError());
        return false;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(eglDisplay_, &major, &minor)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        return false;
    }
    LOGI("EGL initialized successfully: version %d.%d", major, minor);

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay_, configAttribs, &eglConfig_, 1, &numConfigs) || numConfigs <= 0) {
        LOGE("eglChooseConfig failed: 0x%x", eglGetError());
        return false;
    }

    // EGL Robustness attributes (M4 Ticket 57 / ADR-0019)
    #ifndef EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT
    #define EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT 0x30BE
    #endif
    #ifndef EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT
    #define EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT 0x31BD
    #endif
    #ifndef EGL_LOSE_CONTEXT_ON_RESET_EXT
    #define EGL_LOSE_CONTEXT_ON_RESET_EXT 0x31BE
    #endif

    const EGLint robustAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT, EGL_TRUE,
        EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT, EGL_LOSE_CONTEXT_ON_RESET_EXT,
        EGL_NONE
    };

    eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, robustAttribs);
    if (eglContext_ == EGL_NO_CONTEXT) {
        LOGW("Robust EGL context creation failed (0x%x); falling back to standard EGL context", eglGetError());
        const EGLint fallbackAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
        };
        eglContext_ = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, fallbackAttribs);
        if (eglContext_ == EGL_NO_CONTEXT) {
            LOGE("Standard eglCreateContext also failed: 0x%x", eglGetError());
            return false;
        }
    } else {
        LOGI("NativeGlesPresenter: EGL context initialized with Robustness (ROBUST_ACCESS + LOSE_CONTEXT_ON_RESET)");
    }

    return true;
}

void NativeGlesPresenter::terminateEgl() {
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglContext_ != EGL_NO_CONTEXT) {
            eglDestroyContext(eglDisplay_, eglContext_);
            eglContext_ = EGL_NO_CONTEXT;
        }
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
        eglConfig_ = nullptr;
    }
}

bool NativeGlesPresenter::initGlProgram() {
    if (program_ != 0) {
        return true;
    }

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertexShaderSource);
    if (!vs) {
        return false;
    }
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragmentShaderSource);
    if (!fs) {
        glDeleteShader(vs);
        return false;
    }

    program_ = glCreateProgram();
    glAttachShader(program_, vs);
    glAttachShader(program_, fs);
    glLinkProgram(program_);

    glDeleteShader(vs);
    glDeleteShader(fs);

    GLint linked = 0;
    glGetProgramiv(program_, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program_, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> infoLog(static_cast<size_t>(infoLen));
            glGetProgramInfoLog(program_, infoLen, nullptr, infoLog.data());
            LOGE("Program linking error: %s", infoLog.data());
        }
        glDeleteProgram(program_);
        program_ = 0;
        return false;
    }

    posLoc_ = glGetAttribLocation(program_, "aPosition");
    texCoordLoc_ = glGetAttribLocation(program_, "aTexCoord");
    samplerLoc_ = glGetUniformLocation(program_, "uTexture");

    // Initialize full-screen quad VBO
    glGenBuffers(1, &vbo_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    LOGI("GLES3 Shader program initialized successfully");
    return true;
}

void NativeGlesPresenter::deleteGlResources() {
    if (eglDisplay_ != EGL_NO_DISPLAY && eglContext_ != EGL_NO_CONTEXT) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, eglContext_);

        if (pboIds_[0] != 0 || pboIds_[1] != 0) {
            glDeleteBuffers(2, pboIds_);
            pboIds_[0] = 0;
            pboIds_[1] = 0;
        }
        if (targetTexture_ != 0) {
            glDeleteTextures(1, &targetTexture_);
            targetTexture_ = 0;
        }
        if (vbo_ != 0) {
            glDeleteBuffers(1, &vbo_);
            vbo_ = 0;
        }
        if (program_ != 0) {
            glDeleteProgram(program_);
            program_ = 0;
        }
    }

    pboIds_[0] = 0;
    pboIds_[1] = 0;
    targetTexture_ = 0;
    vbo_ = 0;
    program_ = 0;
    pboInitialized_ = false;
    pboCapacityBytes_ = 0;
    textureWidth_ = 0;
    textureHeight_ = 0;
    pboIndex_ = 0;
    firstFrame_ = true;
}

bool NativeGlesPresenter::onSurfaceCreated(ANativeWindow* window) {
    if (!window) {
        LOGE("onSurfaceCreated called with null ANativeWindow");
        return false;
    }

    if (window_ == window && eglSurface_ != EGL_NO_SURFACE && isReady_) {
        LOGI("NativeGlesPresenter::onSurfaceCreated: window already bound and active");
        ANativeWindow_release(window);
        return true;
    }

    // JNI supplies a newly acquired window reference on every attempt, including same-pointer
    // recovery after eglGetDisplay/eglInitialize failed before a context existed.
    if (!isReady_ && (window_ != nullptr || eglDisplay_ != EGL_NO_DISPLAY || eglContext_ != EGL_NO_CONTEXT)) {
        onSurfaceDestroyed();
    }
    if (eglSurface_ != EGL_NO_SURFACE) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(eglDisplay_, eglSurface_);
        eglSurface_ = EGL_NO_SURFACE;
    }
    if (window_ != nullptr && window_ != window) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    window_ = window;

    if (!initEgl()) {
        LOGE("initEgl failed during onSurfaceCreated");
        return false;
    }

    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, window_, nullptr);
    if (eglSurface_ == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    // Configure eglSwapInterval(1) for hardware VSYNC synchronization
    if (!eglSwapInterval(eglDisplay_, 1)) {
        LOGW("eglSwapInterval(1) returned false (error 0x%x)", eglGetError());
    } else {
        LOGI("eglSwapInterval(1) hardware VSYNC locked successfully");
    }

    if (!initGlProgram()) {
        LOGE("initGlProgram failed in onSurfaceCreated");
        return false;
    }

    isReady_ = true;
    firstFrame_ = true;
    LOGI("NativeGlesPresenter::onSurfaceCreated completed successfully");
    return true;
}

void NativeGlesPresenter::onSurfaceChanged(int width, int height) {
    surfaceWidth_ = width;
    surfaceHeight_ = height;
    LOGI("NativeGlesPresenter::onSurfaceChanged dimensions: %dx%d", width, height);
}

void NativeGlesPresenter::onSurfaceDestroyed() {
    LOGI("NativeGlesPresenter::onSurfaceDestroyed");
    isReady_ = false;
    deleteGlResources();
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) {
            eglDestroySurface(eglDisplay_, eglSurface_);
            eglSurface_ = EGL_NO_SURFACE;
        }
    }
    if (window_) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    terminateEgl();
    lastPresentTimeMs_ = -1.0f;
}

void NativeGlesPresenter::release() {
    LOGI("NativeGlesPresenter::release");
    onSurfaceDestroyed();
    deleteGlResources();
    terminateEgl();
}

bool NativeGlesPresenter::present(const int32_t* pixels, int width, int height,
                                 int destX, int destY, int destWidth, int destHeight,
                                 int viewportWidth, int viewportHeight) {
    lastPresentTimeMs_ = -1.0f;
    if (!isReady_ || !pixels || width <= 0 || height <= 0 || width > 4096 || height > 4096
        || static_cast<int64_t>(width) * height > 4194304 || destWidth <= 0 || destHeight <= 0) {
        return false;
    }

    const auto startTime = std::chrono::steady_clock::now();

    if (eglDisplay_ == EGL_NO_DISPLAY || eglSurface_ == EGL_NO_SURFACE
        || !eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_)) {
        isReady_ = false;
        return false;
    }

    const size_t requiredBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;

    // Check if texture or PBO ping-pong buffers need allocation/reallocation
    if (!pboInitialized_ || textureWidth_ != width || textureHeight_ != height || pboCapacityBytes_ < requiredBytes) {
        if (!pboInitialized_) {
            glGenBuffers(2, pboIds_);
            pboInitialized_ = true;
        }

        for (int i = 0; i < 2; ++i) {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboIds_[i]);
            glBufferData(GL_PIXEL_UNPACK_BUFFER, static_cast<GLsizeiptr>(requiredBytes), nullptr, GL_STREAM_DRAW);
        }
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

        if (targetTexture_ == 0) {
            glGenTextures(1, &targetTexture_);
        }
        glBindTexture(GL_TEXTURE_2D, targetTexture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glBindTexture(GL_TEXTURE_2D, 0);

        textureWidth_ = width;
        textureHeight_ = height;
        pboCapacityBytes_ = requiredBytes;
        pboIndex_ = 0;
        firstFrame_ = true;
        LOGI("Reallocated GLES3 texture and dual PBOs for %dx%d (%zu bytes)", width, height, requiredBytes);
    }

    const int nextIndex = pboIndex_;

    // 1. Asynchronously map next PBO and copy current frame CPU pixels via DMA-friendly buffer
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboIds_[nextIndex]);
    glBufferData(GL_PIXEL_UNPACK_BUFFER, static_cast<GLsizeiptr>(requiredBytes), nullptr, GL_STREAM_DRAW); // Orphan buffer
    void* mappedPtr = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, 0, static_cast<GLsizeiptr>(requiredBytes),
                                       GL_MAP_WRITE_BIT | GL_MAP_INVALIDATE_BUFFER_BIT);
    if (mappedPtr != nullptr) {
        auto* rgba = static_cast<uint8_t*>(mappedPtr);
        copyArgbToRgba(pixels, rgba, static_cast<size_t>(width) * height);
        if (!glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER)) { isReady_ = false; return false; }
    } else {
        std::vector<uint8_t> rgba(requiredBytes);
        copyArgbToRgba(pixels, rgba.data(), static_cast<size_t>(width) * height);
        glBufferSubData(GL_PIXEL_UNPACK_BUFFER, 0, static_cast<GLsizeiptr>(requiredBytes), rgba.data());
    }
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

    // 2. Trigger GPU DMA unpack upload
    glBindTexture(GL_TEXTURE_2D, targetTexture_);
    // Idle guests may never send a successor frame to flush a previous PBO.
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pboIds_[nextIndex]);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    firstFrame_ = false;
    glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);

    // Toggle ping-pong PBO index
    pboIndex_ = 1 - pboIndex_;

    // 3. Render texture to surface viewport
    if (surfaceWidth_ <= 0 || surfaceHeight_ <= 0) {
        if (viewportWidth > 0 && viewportHeight > 0) {
            surfaceWidth_ = viewportWidth;
            surfaceHeight_ = viewportHeight;
        } else {
            eglQuerySurface(eglDisplay_, eglSurface_, EGL_WIDTH, &surfaceWidth_);
            eglQuerySurface(eglDisplay_, eglSurface_, EGL_HEIGHT, &surfaceHeight_);
        }
    }

    // Clear whole surface to black (for letterbox / pillarbox margins)
    glViewport(0, 0, surfaceWidth_, surfaceHeight_);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Set viewport for aspect-ratio mapping rectangle (Y is flipped in GL window coordinates)
    int glY = surfaceHeight_ - (destY + destHeight);
    if (glY < 0) {
        glY = 0;
    }
    glViewport(destX, glY, destWidth, destHeight);

    glUseProgram(program_);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, targetTexture_);
    glUniform1i(samplerLoc_, 0);

    glBindBuffer(GL_ARRAY_BUFFER, vbo_);
    glEnableVertexAttribArray(static_cast<GLuint>(posLoc_));
    glVertexAttribPointer(static_cast<GLuint>(posLoc_), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(float), reinterpret_cast<const void*>(0));

    glEnableVertexAttribArray(static_cast<GLuint>(texCoordLoc_));
    glVertexAttribPointer(static_cast<GLuint>(texCoordLoc_), 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(float), reinterpret_cast<const void*>(2 * sizeof(float)));

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(static_cast<GLuint>(posLoc_));
    glDisableVertexAttribArray(static_cast<GLuint>(texCoordLoc_));
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (glGetError() != GL_NO_ERROR || !eglSwapBuffers(eglDisplay_, eglSurface_)) {
        isReady_ = false;
        lastPresentTimeMs_ = -1.0f;
        return false;
    }

    const auto endTime = std::chrono::steady_clock::now();
    lastPresentTimeMs_ = std::chrono::duration<float, std::milli>(endTime - startTime).count();
    return true;
}

void NativeGlesPresenter::clear(int32_t argbColor) {
    if (!isReady_ || eglDisplay_ == EGL_NO_DISPLAY || eglSurface_ == EGL_NO_SURFACE) {
        return;
    }
    const float a = static_cast<float>((argbColor >> 24) & 0xFF) / 255.0f;
    const float r = static_cast<float>((argbColor >> 16) & 0xFF) / 255.0f;
    const float g = static_cast<float>((argbColor >> 8) & 0xFF) / 255.0f;
    const float b = static_cast<float>(argbColor & 0xFF) / 255.0f;

    if (surfaceWidth_ <= 0 || surfaceHeight_ <= 0) {
        eglQuerySurface(eglDisplay_, eglSurface_, EGL_WIDTH, &surfaceWidth_);
        eglQuerySurface(eglDisplay_, eglSurface_, EGL_HEIGHT, &surfaceHeight_);
    }

    glViewport(0, 0, surfaceWidth_, surfaceHeight_);
    glClearColor(r, g, b, a);
    glClear(GL_COLOR_BUFFER_BIT);
    eglSwapBuffers(eglDisplay_, eglSurface_);
}

} // namespace orchestella
