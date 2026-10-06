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

#include "virgl_android_gl_loader.h"

#include <android/log.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

#define LOG_TAG "VirglAndroidLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static bool s_loader_initialized = false;
static void *s_egl_handle = NULL;
static void *s_gles_handle = NULL;

static PFNGLGETGRAPHICSRESETSTATUSEXTOESPROC s_pfn_glGetGraphicsResetStatus = NULL;

bool virgl_android_gl_loader_init(void) {
    if (s_loader_initialized) {
        return true;
    }

    // Standard Android Bionic handles for EGL and GLESv3
    s_egl_handle = dlopen("libEGL.so", RTLD_NOW | RTLD_GLOBAL);
    if (!s_egl_handle) {
        LOGE("Failed to open libEGL.so: %s", dlerror());
        return false;
    }

    s_gles_handle = dlopen("libGLESv3.so", RTLD_NOW | RTLD_GLOBAL);
    if (!s_gles_handle) {
        // Fallback to GLESv2 if GLESv3 is not directly visible
        s_gles_handle = dlopen("libGLESv2.so", RTLD_NOW | RTLD_GLOBAL);
        if (!s_gles_handle) {
            LOGE("Failed to open libGLESv3.so / libGLESv2.so: %s", dlerror());
            return false;
        }
    }

    // Resolve eglGetProcAddress dynamically
    typedef __eglMustCastToProperFunctionPointerType (*EglGetProcAddressFn)(const char *);
    EglGetProcAddressFn get_proc = (EglGetProcAddressFn)dlsym(s_egl_handle, "eglGetProcAddress");
    if (!get_proc) {
        LOGE("Failed to find eglGetProcAddress in libEGL.so");
        return false;
    }

    // Dynamically query Robustness status extension function
    s_pfn_glGetGraphicsResetStatus = (PFNGLGETGRAPHICSRESETSTATUSEXTOESPROC)get_proc("glGetGraphicsResetStatusEXT");
    if (!s_pfn_glGetGraphicsResetStatus) {
        s_pfn_glGetGraphicsResetStatus = (PFNGLGETGRAPHICSRESETSTATUSEXTOESPROC)get_proc("glGetGraphicsResetStatus");
    }
    if (!s_pfn_glGetGraphicsResetStatus) {
        s_pfn_glGetGraphicsResetStatus = (PFNGLGETGRAPHICSRESETSTATUSEXTOESPROC)dlsym(s_gles_handle, "glGetGraphicsResetStatusEXT");
    }
    if (!s_pfn_glGetGraphicsResetStatus) {
        s_pfn_glGetGraphicsResetStatus = (PFNGLGETGRAPHICSRESETSTATUSEXTOESPROC)dlsym(s_gles_handle, "glGetGraphicsResetStatus");
    }

    s_loader_initialized = true;
    LOGI("virgl_android_gl_loader_init: micro dynamic loader initialized successfully (libepoxy eliminated, robustness proc: %s)",
         s_pfn_glGetGraphicsResetStatus != NULL ? "supported" : "not reported by driver");
    return true;
}

bool virgl_android_gl_loader_is_ready(void) {
    return s_loader_initialized;
}

GLenum virgl_android_gl_get_reset_status(void) {
    if (s_pfn_glGetGraphicsResetStatus) {
        return s_pfn_glGetGraphicsResetStatus();
    }
    return GL_NO_ERROR;
}
