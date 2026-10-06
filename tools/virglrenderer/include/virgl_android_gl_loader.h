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

#ifndef VIRGL_ANDROID_GL_LOADER_H
#define VIRGL_ANDROID_GL_LOADER_H

#include <stdbool.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>

#ifdef __cplusplus
extern "C" {
#endif

/* EGL Robustness extensions (EGL_EXT_create_context_robustness) */
#ifndef EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT
#define EGL_CONTEXT_OPENGL_ROBUST_ACCESS_EXT 0x30BE
#endif

#ifndef EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT
#define EGL_CONTEXT_OPENGL_RESET_NOTIFICATION_STRATEGY_EXT 0x31BD
#endif

#ifndef EGL_NO_RESET_NOTIFICATION_EXT
#define EGL_NO_RESET_NOTIFICATION_EXT 0x31BE
#endif

#ifndef EGL_LOSE_CONTEXT_ON_RESET_EXT
#define EGL_LOSE_CONTEXT_ON_RESET_EXT 0x31BE
#endif

/* GL Robustness queries (GL_EXT_robustness) */
#ifndef GL_GUILTY_CONTEXT_RESET_EXT
#define GL_GUILTY_CONTEXT_RESET_EXT 0x8253
#endif

#ifndef GL_INNOCENT_CONTEXT_RESET_EXT
#define GL_INNOCENT_CONTEXT_RESET_EXT 0x8254
#endif

#ifndef GL_UNKNOWN_CONTEXT_RESET_EXT
#define GL_UNKNOWN_CONTEXT_RESET_EXT 0x8255
#endif

#ifndef GL_RESET_NOTIFICATION_STRATEGY_EXT
#define GL_RESET_NOTIFICATION_STRATEGY_EXT 0x8256
#endif

#ifndef GL_LOSE_CONTEXT_ON_RESET_EXT
#define GL_LOSE_CONTEXT_ON_RESET_EXT 0x8252
#endif

#ifndef GL_NO_RESET_NOTIFICATION_EXT
#define GL_NO_RESET_NOTIFICATION_EXT 0x8261
#endif

/* Robustness function pointer types */
typedef GLenum (*PFNGLGETGRAPHICSRESETSTATUSEXTOESPROC)(void);

/**
 * Initializes the micro Android GL/EGL dynamic loader.
 * Loads symbols directly from standard Android libEGL.so and libGLESv3.so,
 * using eglGetProcAddress for extensions, completely eliminating libepoxy.
 *
 * @return true if EGL and GLES3 symbols were successfully loaded, false otherwise.
 */
bool virgl_android_gl_loader_init(void);

/**
 * Queries whether the micro GL loader is initialized.
 */
bool virgl_android_gl_loader_is_ready(void);

/**
 * Queries the graphics reset status from the underlying EGL/GLES driver.
 * Returns GL_NO_ERROR if context is intact, or GL_GUILTY/INNOCENT/UNKNOWN_CONTEXT_RESET_EXT if lost.
 */
GLenum virgl_android_gl_get_reset_status(void);

#ifdef __cplusplus
}
#endif

#endif /* VIRGL_ANDROID_GL_LOADER_H */
