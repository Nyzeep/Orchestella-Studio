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

#ifndef VIRGLRENDERER_H
#define VIRGLRENDERER_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <sys/uio.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VIRGL_RENDERER_CALLBACKS_VERSION 4

#define VIRGL_RENDERER_USE_EGL           (1 << 0)
#define VIRGL_RENDERER_THREAD_SYNC       (1 << 1)
#define VIRGL_RENDERER_USE_GLX           (1 << 2)
#define VIRGL_RENDERER_ASYNC_FENCE_CB    (1 << 3)
#define VIRGL_RENDERER_USE_SURFACELESS   (1 << 4)
#define VIRGL_RENDERER_USE_GLES          (1 << 5)

/* Error codes */
#define VIRGL_RENDERER_OK                 0
#define VIRGL_RENDERER_ERROR_OOM         -1
#define VIRGL_RENDERER_ERROR_RESET       -2
#define VIRGL_RENDERER_ERROR_INVALID_PARAM -3

struct virgl_box {
    int32_t x, y, z;
    int32_t w, h, d;
};

struct virgl_renderer_gl_ctx_param {
    int version;
    bool shared;
    int major_ver;
    int minor_ver;
};

typedef void *virgl_renderer_gl_context;

struct virgl_renderer_callbacks {
    int version;
    void (*write_fence)(void *cookie, uint32_t fence);
    virgl_renderer_gl_context (*create_gl_context)(void *cookie, int scanout_idx, struct virgl_renderer_gl_ctx_param *params);
    void (*destroy_gl_context)(void *cookie, virgl_renderer_gl_context ctx);
    int (*make_current)(void *cookie, int scanout_idx, virgl_renderer_gl_context ctx);
    int (*get_drm_fd)(void *cookie);
    void (*write_context_fence)(void *cookie, uint32_t ctx_id, uint32_t ring_idx, uint64_t fence_id);
    void (*get_server_zombie_resources)(void *cookie, int *num_resources, uint32_t **res_ids);
};

struct virgl_renderer_resource_create_args {
    uint32_t handle;
    uint32_t target;
    uint32_t format;
    uint32_t bind;
    uint32_t width;
    uint32_t height;
    uint32_t depth;
    uint32_t array_size;
    uint32_t last_level;
    uint32_t nr_samples;
    uint32_t flags;
};

int virgl_renderer_init(void *cookie, int flags, struct virgl_renderer_callbacks *cb);
void virgl_renderer_cleanup(void *cookie);
void virgl_renderer_poll(void);
int virgl_renderer_submit_cmd(void *buffer, int ctx_id, int bytes);
int virgl_renderer_context_create(uint32_t handle, uint32_t nlen, const char *name);
void virgl_renderer_context_destroy(uint32_t handle);
int virgl_renderer_resource_create(struct virgl_renderer_resource_create_args *args, struct iovec *iov, uint32_t num_iovs);
void virgl_renderer_resource_unref(uint32_t res_handle);
int virgl_renderer_resource_attach_iov(int res_handle, struct iovec *iov, int num_iovs);
void virgl_renderer_resource_detach_iov(int res_handle, struct iovec **iov, int *num_iovs);
int virgl_renderer_create_fence(int client_fence_id, uint32_t ctx_id);
int virgl_renderer_get_cursor_data(uint32_t resource_id, uint32_t *width, uint32_t *height);
int virgl_renderer_get_rect(int resource_id, struct iovec *iov, unsigned int num_iovs, uint32_t offset, int x, int y, int width, int height);

/* Orchestella Studio Native GPU Guard APIs */
int virgl_renderer_get_memory_quota_mb(void);
void virgl_renderer_set_memory_quota_mb(int quota_mb);
int64_t virgl_renderer_get_allocated_bytes(void);
bool virgl_renderer_is_robustness_enabled(void);
bool virgl_renderer_check_and_reset_if_lost(void);

#ifdef __cplusplus
}
#endif

#endif /* VIRGLRENDERER_H */
