/*
 * Copyright (C) 2014 Red Hat Inc.
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
#include "virgl_hw.h"
#include "virgl_android_gl_loader.h"
#include "virgl_memory_quota.h"
#include "drm/drm_fourcc.h"

#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "DroidLayerQemu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAX_RESOURCES 4096
#define MAX_CONTEXTS 64

struct virgl_resource_entry {
    uint32_t handle;
    uint32_t format;
    uint32_t width;
    uint32_t height;
    size_t allocated_size;
    GLuint texture_id;
    GLuint buffer_id;
    struct iovec *iov;
    uint32_t num_iovs;
    bool in_use;
};

struct virgl_context_entry {
    uint32_t handle;
    char name[64];
    bool in_use;
};

static pthread_mutex_t s_mutex = PTHREAD_MUTEX_INITIALIZER;
static bool s_renderer_initialized = false;
static bool s_robustness_active = false;
static void *s_cookie = NULL;
static struct virgl_renderer_callbacks s_callbacks;

static EGLDisplay s_egl_display = EGL_NO_DISPLAY;
static EGLConfig s_egl_config = NULL;
static EGLContext s_egl_context = EGL_NO_CONTEXT;
static EGLSurface s_egl_surface = EGL_NO_SURFACE;

static struct virgl_resource_entry s_resources[MAX_RESOURCES];
static struct virgl_context_entry s_contexts[MAX_CONTEXTS];

static size_t format_bytes_per_pixel(uint32_t format) {
    switch (format) {
        case VIRGL_FORMAT_B8G8R8A8_UNORM:
        case VIRGL_FORMAT_B8G8R8X8_UNORM:
        case VIRGL_FORMAT_A8R8G8B8_UNORM:
        case VIRGL_FORMAT_X8R8G8B8_UNORM:
        case VIRGL_FORMAT_R8G8B8A8_UNORM:
        case VIRGL_FORMAT_R8G8B8X8_UNORM:
            return 4;
        case VIRGL_FORMAT_R8G8B8_UNORM:
            return 3;
        case VIRGL_FORMAT_B5G6R5_UNORM:
        case VIRGL_FORMAT_B5G5R5A1_UNORM:
        case VIRGL_FORMAT_B4G4R4A4_UNORM:
        case VIRGL_FORMAT_R16_UNORM:
            return 2;
        case VIRGL_FORMAT_R8_UNORM:
            return 1;
        default:
            return 4;
    }
}

static bool create_egl_context_with_robustness(void) {
    if (s_egl_display == EGL_NO_DISPLAY) {
        s_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (s_egl_display == EGL_NO_DISPLAY) {
            LOGE("eglGetDisplay failed: 0x%x", eglGetError());
            return false;
        }
        EGLint major = 0, minor = 0;
        if (!eglInitialize(s_egl_display, &major, &minor)) {
            LOGE("eglInitialize failed: 0x%x", eglGetError());
            return false;
        }
    }

    const EGLint config_attribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_NONE
    };

    EGLint num_configs = 0;
    if (!eglChooseConfig(s_egl_display, config_attribs, &s_egl_config, 1, &num_configs) || num_configs <= 0) {
        LOGE("eglChooseConfig failed: 0x%x", eglGetError());
        return false;
    }

    // First attempt: EGL Robustness extensions (EGL_EXT_create_context_robustness)
    const EGLint robust_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT, EGL_TRUE,
        EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT, EGL_LOSE_CONTEXT_ON_RESET_EXT,
        EGL_NONE
    };

    s_egl_context = eglCreateContext(s_egl_display, s_egl_config, EGL_NO_CONTEXT, robust_attribs);
    if (s_egl_context != EGL_NO_CONTEXT) {
        s_robustness_active = true;
        LOGI("virgl_renderer: EGL context created with OPENGL_ROBUST_ACCESS_EXT and LOSE_CONTEXT_ON_RESET_EXT");
    } else {
        LOGW("virgl_renderer: Robust EGL context creation failed (0x%x); trying fallback standard GLES3", eglGetError());
        const EGLint fallback_attribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE
        };
        s_egl_context = eglCreateContext(s_egl_display, s_egl_config, EGL_NO_CONTEXT, fallback_attribs);
        s_robustness_active = false;
        if (s_egl_context == EGL_NO_CONTEXT) {
            LOGE("virgl_renderer: fallback eglCreateContext also failed: 0x%x", eglGetError());
            return false;
        }
    }

    const EGLint pbuffer_attribs[] = {
        EGL_WIDTH, 16,
        EGL_HEIGHT, 16,
        EGL_NONE
    };
    s_egl_surface = eglCreatePbufferSurface(s_egl_display, s_egl_config, pbuffer_attribs);
    if (s_egl_surface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: 0x%x", eglGetError());
        return false;
    }

    if (!eglMakeCurrent(s_egl_display, s_egl_surface, s_egl_surface, s_egl_context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        return false;
    }

    return true;
}

int virgl_renderer_init(void *cookie, int flags, struct virgl_renderer_callbacks *cb) {
    pthread_mutex_lock(&s_mutex);

    if (s_renderer_initialized) {
        pthread_mutex_unlock(&s_mutex);
        return VIRGL_RENDERER_OK;
    }

    // Step 1: Initialize micro Android GL loader (eliminates libepoxy)
    if (!virgl_android_gl_loader_init()) {
        LOGE("virgl_renderer_init failed: virgl_android_gl_loader_init failed");
        pthread_mutex_unlock(&s_mutex);
        return -1;
    }

    // Step 2: Initialize in-process EGL context with Robustness isolation
    if (!create_egl_context_with_robustness()) {
        LOGE("virgl_renderer_init failed: create_egl_context_with_robustness failed");
        pthread_mutex_unlock(&s_mutex);
        return -1;
    }

    // Step 3: Initialize memory tables and quota
    memset(s_resources, 0, sizeof(s_resources));
    memset(s_contexts, 0, sizeof(s_contexts));
    s_cookie = cookie;
    if (cb) {
        memcpy(&s_callbacks, cb, sizeof(struct virgl_renderer_callbacks));
    }

    s_renderer_initialized = true;

    int quota_mb = (int)(virgl_quota_get_max() / (1024 * 1024));
    LOGI("virgl_renderer_init succeeded: in-process VirGL 3D context initialized with EGL robustness (memory quota: %d MB)", quota_mb);

    pthread_mutex_unlock(&s_mutex);
    return VIRGL_RENDERER_OK;
}

void virgl_renderer_cleanup(void *cookie) {
    (void)cookie;
    pthread_mutex_lock(&s_mutex);

    if (!s_renderer_initialized) {
        pthread_mutex_unlock(&s_mutex);
        return;
    }

    // Free all resources
    for (size_t i = 0; i < MAX_RESOURCES; i++) {
        if (s_resources[i].in_use) {
            if (s_resources[i].texture_id != 0) {
                glDeleteTextures(1, &s_resources[i].texture_id);
            }
            if (s_resources[i].buffer_id != 0) {
                glDeleteBuffers(1, &s_resources[i].buffer_id);
            }
            virgl_quota_release(s_resources[i].allocated_size);
            s_resources[i].in_use = false;
        }
    }

    if (s_egl_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(s_egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (s_egl_surface != EGL_NO_SURFACE) {
            eglDestroySurface(s_egl_display, s_egl_surface);
            s_egl_surface = EGL_NO_SURFACE;
        }
        if (s_egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(s_egl_display, s_egl_context);
            s_egl_context = EGL_NO_CONTEXT;
        }
        eglTerminate(s_egl_display);
        s_egl_display = EGL_NO_DISPLAY;
    }

    virgl_quota_reset();
    s_renderer_initialized = false;
    s_robustness_active = false;
    LOGI("virgl_renderer_cleanup completed");

    pthread_mutex_unlock(&s_mutex);
}

void virgl_renderer_poll(void) {
    // In single-process model, flush GL pipeline
    if (s_renderer_initialized) {
        glFlush();
    }
}

int virgl_renderer_context_create(uint32_t handle, uint32_t nlen, const char *name) {
    pthread_mutex_lock(&s_mutex);
    for (size_t i = 0; i < MAX_CONTEXTS; i++) {
        if (!s_contexts[i].in_use) {
            s_contexts[i].handle = handle;
            s_contexts[i].in_use = true;
            if (name && nlen > 0) {
                size_t cplen = (nlen < sizeof(s_contexts[i].name) - 1) ? nlen : (sizeof(s_contexts[i].name) - 1);
                memcpy(s_contexts[i].name, name, cplen);
                s_contexts[i].name[cplen] = '\0';
            } else {
                s_contexts[i].name[0] = '\0';
            }
            pthread_mutex_unlock(&s_mutex);
            return VIRGL_RENDERER_OK;
        }
    }
    pthread_mutex_unlock(&s_mutex);
    return -ENOMEM;
}

void virgl_renderer_context_destroy(uint32_t handle) {
    pthread_mutex_lock(&s_mutex);
    for (size_t i = 0; i < MAX_CONTEXTS; i++) {
        if (s_contexts[i].in_use && s_contexts[i].handle == handle) {
            s_contexts[i].in_use = false;
            break;
        }
    }
    pthread_mutex_unlock(&s_mutex);
}

int virgl_renderer_resource_create(struct virgl_renderer_resource_create_args *args, struct iovec *iov, uint32_t num_iovs) {
    if (!args || args->handle >= MAX_RESOURCES) {
        return VIRGL_RENDERER_ERROR_INVALID_PARAM;
    }

    pthread_mutex_lock(&s_mutex);

    size_t bpp = format_bytes_per_pixel(args->format);
    size_t width = args->width > 0 ? args->width : 1;
    size_t height = args->height > 0 ? args->height : 1;
    size_t depth = args->depth > 0 ? args->depth : 1;
    size_t bytes = width * height * depth * bpp;

    // Memory Quota Sentinel gate check: 1024MB fuse threshold
    if (!virgl_quota_try_allocate(bytes)) {
        LOGW("virgl_renderer: Memory allocation refused by MemoryQuotaSentinel for resource %u (request: %zu bytes)",
             args->handle, bytes);
        pthread_mutex_unlock(&s_mutex);
        return VIRGL_RENDERER_ERROR_OOM;
    }

    uint32_t h = args->handle;
    s_resources[h].handle = h;
    s_resources[h].format = args->format;
    s_resources[h].width = args->width;
    s_resources[h].height = args->height;
    s_resources[h].allocated_size = bytes;
    s_resources[h].iov = iov;
    s_resources[h].num_iovs = num_iovs;
    s_resources[h].in_use = true;

    // Allocate GL resource if context is active
    if (s_renderer_initialized && s_egl_context != EGL_NO_CONTEXT) {
        if (args->bind & VIRGL_RES_BIND_SAMPLER_VIEW || args->bind & VIRGL_RES_BIND_RENDER_TARGET) {
            glGenTextures(1, &s_resources[h].texture_id);
            glBindTexture(GL_TEXTURE_2D, s_resources[h].texture_id);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)width, (GLsizei)height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        } else {
            glGenBuffers(1, &s_resources[h].buffer_id);
            glBindBuffer(GL_ARRAY_BUFFER, s_resources[h].buffer_id);
            glBufferData(GL_ARRAY_BUFFER, (GLsizeiptr)bytes, NULL, GL_DYNAMIC_DRAW);
        }
    }

    pthread_mutex_unlock(&s_mutex);
    return VIRGL_RENDERER_OK;
}

void virgl_renderer_resource_unref(uint32_t res_handle) {
    if (res_handle >= MAX_RESOURCES) {
        return;
    }
    pthread_mutex_lock(&s_mutex);
    if (s_resources[res_handle].in_use) {
        if (s_resources[res_handle].texture_id != 0) {
            glDeleteTextures(1, &s_resources[res_handle].texture_id);
            s_resources[res_handle].texture_id = 0;
        }
        if (s_resources[res_handle].buffer_id != 0) {
            glDeleteBuffers(1, &s_resources[res_handle].buffer_id);
            s_resources[res_handle].buffer_id = 0;
        }
        virgl_quota_release(s_resources[res_handle].allocated_size);
        s_resources[res_handle].in_use = false;
    }
    pthread_mutex_unlock(&s_mutex);
}

int virgl_renderer_resource_attach_iov(int res_handle, struct iovec *iov, int num_iovs) {
    if (res_handle < 0 || (uint32_t)res_handle >= MAX_RESOURCES) {
        return -EINVAL;
    }
    pthread_mutex_lock(&s_mutex);
    if (s_resources[res_handle].in_use) {
        s_resources[res_handle].iov = iov;
        s_resources[res_handle].num_iovs = (uint32_t)num_iovs;
    }
    pthread_mutex_unlock(&s_mutex);
    return VIRGL_RENDERER_OK;
}

void virgl_renderer_resource_detach_iov(int res_handle, struct iovec **iov, int *num_iovs) {
    if (res_handle < 0 || (uint32_t)res_handle >= MAX_RESOURCES) {
        return;
    }
    pthread_mutex_lock(&s_mutex);
    if (s_resources[res_handle].in_use) {
        if (iov) *iov = s_resources[res_handle].iov;
        if (num_iovs) *num_iovs = (int)s_resources[res_handle].num_iovs;
        s_resources[res_handle].iov = NULL;
        s_resources[res_handle].num_iovs = 0;
    }
    pthread_mutex_unlock(&s_mutex);
}

int virgl_renderer_submit_cmd(void *buffer, int ctx_id, int bytes) {
    (void)ctx_id;
    if (!buffer || bytes <= 0) {
        return VIRGL_RENDERER_ERROR_INVALID_PARAM;
    }

    pthread_mutex_lock(&s_mutex);

    // Defense-in-depth: query whether underlying GL context was reset by driver
    GLenum reset_status = virgl_android_gl_get_reset_status();
    if (reset_status != GL_NO_ERROR) {
        LOGW("[EGL Robustness] Context lost detected via GL_LOSE_CONTEXT_ON_RESET_EXT (status 0x%x). Safe recovery engaged.",
             reset_status);
        // Cleanly recreate context without taking down host process
        if (s_egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(s_egl_display, s_egl_context);
            s_egl_context = EGL_NO_CONTEXT;
        }
        create_egl_context_with_robustness();
        pthread_mutex_unlock(&s_mutex);
        return VIRGL_RENDERER_ERROR_RESET;
    }

    pthread_mutex_unlock(&s_mutex);
    return VIRGL_RENDERER_OK;
}

int virgl_renderer_create_fence(int client_fence_id, uint32_t ctx_id) {
    (void)ctx_id;
    if (s_callbacks.write_fence) {
        s_callbacks.write_fence(s_cookie, (uint32_t)client_fence_id);
    }
    return VIRGL_RENDERER_OK;
}

int virgl_renderer_get_cursor_data(uint32_t resource_id, uint32_t *width, uint32_t *height) {
    if (resource_id >= MAX_RESOURCES || !s_resources[resource_id].in_use) {
        return -EINVAL;
    }
    if (width) *width = s_resources[resource_id].width;
    if (height) *height = s_resources[resource_id].height;
    return VIRGL_RENDERER_OK;
}

int virgl_renderer_get_rect(int resource_id, struct iovec *iov, unsigned int num_iovs,
                            uint32_t offset, int x, int y, int width, int height) {
    (void)resource_id; (void)iov; (void)num_iovs; (void)offset;
    (void)x; (void)y; (void)width; (void)height;
    return VIRGL_RENDERER_OK;
}

/* Extension APIs */
int virgl_renderer_get_memory_quota_mb(void) {
    return (int)(virgl_quota_get_max() / (1024 * 1024));
}

void virgl_renderer_set_memory_quota_mb(int quota_mb) {
    virgl_quota_set_max((int64_t)quota_mb * 1024LL * 1024LL);
}

int64_t virgl_renderer_get_allocated_bytes(void) {
    return virgl_quota_get_allocated();
}

bool virgl_renderer_is_robustness_enabled(void) {
    return s_robustness_active;
}

bool virgl_renderer_check_and_reset_if_lost(void) {
    pthread_mutex_lock(&s_mutex);
    GLenum status = virgl_android_gl_get_reset_status();
    bool lost = (status != GL_NO_ERROR);
    if (lost) {
        LOGW("[EGL Robustness] Resetting VirGL context on check_and_reset_if_lost (status 0x%x)", status);
        if (s_egl_context != EGL_NO_CONTEXT) {
            eglDestroyContext(s_egl_display, s_egl_context);
            s_egl_context = EGL_NO_CONTEXT;
        }
        create_egl_context_with_robustness();
    }
    pthread_mutex_unlock(&s_mutex);
    return lost;
}
