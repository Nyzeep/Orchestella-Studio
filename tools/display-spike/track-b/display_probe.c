// SPDX-License-Identifier: GPL-3.0-or-later

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/dma-heap.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "W405DisplayProbe", __VA_ARGS__)

static const uint8_t marker[] = {0x33, 0x66, 0x99, 0xff};

static bool has_extension(const char *extensions, const char *name) {
    size_t length = strlen(name);
    for (const char *match = extensions; (match = strstr(match, name)); match += length) {
        if ((match == extensions || match[-1] == ' ') &&
                (match[length] == ' ' || match[length] == '\0')) {
            return true;
        }
    }
    return false;
}

static void log_domain(void) {
    char context[256] = {0};
    int fd = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
    ssize_t length = -1;
    if (fd >= 0) {
        length = read(fd, context, sizeof(context) - 1);
        close(fd);
    }
    // Exclude the per-install SELinux categories from logs.
    const char *domain = "unavailable";
    char *type = length > 0 ? strstr(context, ":r:") : NULL;
    if (type) {
        type += 3;
        char *end = strchr(type, ':');
        if (end) {
            *end = '\0';
            domain = type;
        }
    }
    LOG("process domain=%s uid_is_root=%d page_bytes=%ld",
            domain, getuid() == 0, sysconf(_SC_PAGESIZE));
}

static int probe_open(const char *path, int flags) {
    int fd = open(path, flags | O_CLOEXEC);
    int error = fd < 0 ? errno : 0;
    LOG("direct_open path=%s mode=%s errno=%d",
            path, (flags & O_RDWR) ? "read_write" : "read_only", error);
    return fd;
}

static void probe_device(const char *path, int flags) {
    int fd = probe_open(path, flags);
    if (fd >= 0) {
        close(fd);
    }
}

static void probe_system_heap(void) {
    int heap = probe_open("/dev/dma_heap/system", O_RDONLY);
    if (heap < 0) {
        LOG("dma_heap_allocation status=open_failed");
        return;
    }
    long page_bytes = sysconf(_SC_PAGESIZE);
    if (page_bytes <= 0) {
        LOG("dma_heap_allocation status=page_size_unavailable");
        close(heap);
        return;
    }
    struct dma_heap_allocation_data allocation = {
        .len = (uint64_t)page_bytes,
        .fd_flags = O_RDWR | O_CLOEXEC,
    };
    int result = ioctl(heap, DMA_HEAP_IOCTL_ALLOC, &allocation);
    int error = result < 0 ? errno : 0;
    if (result == 0) {
        close((int)allocation.fd);
    }
    close(heap);
    LOG("dma_heap_allocation bytes=%ld errno=%d", page_bytes, error);
}

JNIEXPORT void JNICALL Java_io_orchestella_displayprobe_NativeProbe_inspectHost(
        JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    log_domain();
    probe_device("/dev/dma_heap", O_RDONLY | O_DIRECTORY);
    probe_device("/dev/dma_heap/system", O_RDWR);
    probe_system_heap();
    probe_device("/dev/dma_heap/system-uncached", O_RDWR);
    probe_device("/dev/ion", O_RDWR);
    probe_device("/dev/vndbinder", O_RDWR);

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major;
    EGLint minor;
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, &major, &minor)) {
        LOG("egl_initialize status=failed egl_error=0x%x", eglGetError());
        return;
    }
    LOG("egl_initialize status=ok version=%d.%d", major, minor);
    const char *extensions = eglQueryString(display, EGL_EXTENSIONS);
    if (extensions) {
        LOG("egl_display_extensions dma_buf_import=%d dma_buf_import_modifiers=%d"
                " android_image_native_buffer=%d android_get_native_client_buffer=%d",
                has_extension(extensions, "EGL_EXT_image_dma_buf_import"),
                has_extension(extensions, "EGL_EXT_image_dma_buf_import_modifiers"),
                has_extension(extensions, "EGL_ANDROID_image_native_buffer"),
                has_extension(extensions, "EGL_ANDROID_get_native_client_buffer"));
    } else {
        LOG("egl_display_extensions status=failed egl_error=0x%x", eglGetError());
    }
    eglTerminate(display);
    eglReleaseThread();
}

JNIEXPORT jboolean JNICALL Java_io_orchestella_displayprobe_NativeProbe_fill(
        JNIEnv *env, jclass type, jobject java_buffer) {
    (void)type;
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, java_buffer);
    if (!buffer) {
        LOG("ahb_fill status=invalid_handle");
        return JNI_FALSE;
    }
    AHardwareBuffer_Desc description;
    AHardwareBuffer_describe(buffer, &description);
    void *address = NULL;
    int result = AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_RARELY,
            -1, NULL, &address);
    if (result != 0) {
        LOG("ahb_fill status=lock_failed result=%d", result);
        return JNI_FALSE;
    }
    size_t stride_bytes = (size_t)description.stride * sizeof(marker);
    for (uint32_t row = 0; row < description.height; ++row) {
        uint8_t *line = (uint8_t *)address + row * stride_bytes;
        for (uint32_t column = 0; column < description.width; ++column) {
            memcpy(line + (size_t)column * sizeof(marker), marker, sizeof(marker));
        }
    }
    result = AHardwareBuffer_unlock(buffer, NULL);
    LOG("ahb_fill status=%s unlock_result=%d", result == 0 ? "ok" : "failed", result);
    return result == 0;
}

JNIEXPORT jboolean JNICALL Java_io_orchestella_displayprobe_NativeProbe_importAndRead(
        JNIEnv *env, jclass type, jobject java_buffer) {
    (void)type;
    AHardwareBuffer *buffer = AHardwareBuffer_fromHardwareBuffer(env, java_buffer);
    if (!buffer) {
        LOG("ahb_egl_import status=invalid_handle");
        return JNI_FALSE;
    }

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display, NULL, NULL)) {
        LOG("ahb_egl_import status=initialize_failed egl_error=0x%x", eglGetError());
        return JNI_FALSE;
    }
    const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE,
    };
    const EGLint context_attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    const EGLint surface_attributes[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    const EGLint image_attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    EGLConfig config;
    EGLint config_count = 0;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
    GLuint framebuffer = 0;
    bool matches = false;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC native_client_buffer =
            (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress(
                    "eglGetNativeClientBufferANDROID");
    PFNEGLCREATEIMAGEKHRPROC create_image =
            (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    PFNEGLDESTROYIMAGEKHRPROC destroy_image =
            (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC image_target =
            (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress(
                    "glEGLImageTargetTexture2DOES");
    if (!native_client_buffer || !create_image || !destroy_image || !image_target) {
        LOG("ahb_egl_import status=entry_point_unavailable");
        goto cleanup;
    }
    if (!eglBindAPI(EGL_OPENGL_ES_API) ||
            !eglChooseConfig(display, config_attributes, &config, 1, &config_count) ||
            config_count != 1) {
        LOG("ahb_egl_import status=config_failed egl_error=0x%x", eglGetError());
        goto cleanup;
    }
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
    surface = eglCreatePbufferSurface(display, config, surface_attributes);
    if (context == EGL_NO_CONTEXT || surface == EGL_NO_SURFACE ||
            !eglMakeCurrent(display, surface, surface, context)) {
        LOG("ahb_egl_import status=context_failed egl_error=0x%x", eglGetError());
        goto cleanup;
    }
    EGLClientBuffer client_buffer = native_client_buffer(buffer);
    if (!client_buffer) {
        LOG("ahb_egl_import status=native_client_buffer_failed");
        goto cleanup;
    }
    image = create_image(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
            client_buffer, image_attributes);
    if (image == EGL_NO_IMAGE_KHR) {
        LOG("ahb_egl_import status=create_image_failed egl_error=0x%x", eglGetError());
        goto cleanup;
    }
    LOG("ahb_egl_import status=ok");
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    image_target(GL_TEXTURE_2D, image);
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        LOG("ahb_gl_texture status=failed gl_error=0x%x", error);
        goto cleanup;
    }
    LOG("ahb_gl_texture status=ok");
    glGenFramebuffers(1, &framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
    GLenum completeness = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (completeness != GL_FRAMEBUFFER_COMPLETE) {
        LOG("ahb_readback status=framebuffer_incomplete gl_status=0x%x", completeness);
        goto cleanup;
    }
    uint8_t pixel[sizeof(marker)] = {0};
    glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
    error = glGetError();
    matches = error == GL_NO_ERROR && memcmp(pixel, marker, sizeof(marker)) == 0;
    LOG("ahb_readback status=%s gl_error=0x%x", matches ? "pixel_match" : "failed", error);

cleanup:
    if (framebuffer) {
        glDeleteFramebuffers(1, &framebuffer);
    }
    if (texture) {
        glDeleteTextures(1, &texture);
    }
    if (image != EGL_NO_IMAGE_KHR) {
        destroy_image(display, image);
    }
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (surface != EGL_NO_SURFACE) {
        eglDestroySurface(display, surface);
    }
    if (context != EGL_NO_CONTEXT) {
        eglDestroyContext(display, context);
    }
    eglTerminate(display);
    eglReleaseThread();
    return matches;
}
