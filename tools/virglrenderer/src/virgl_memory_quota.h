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

#ifndef VIRGL_MEMORY_QUOTA_H
#define VIRGL_MEMORY_QUOTA_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VIRGL_DEFAULT_MEMORY_QUOTA_BYTES (1024LL * 1024LL * 1024LL) // 1024 MB

/**
 * Attempts to allocate the specified number of bytes under the memory quota guard.
 * Returns true if allocation is within quota, or false if quota is exceeded (circuit breaker tripped).
 */
bool virgl_quota_try_allocate(size_t bytes);

/**
 * Releases previously allocated bytes back to the memory quota pool.
 */
void virgl_quota_release(size_t bytes);

/**
 * Resets allocated bytes counter and clears tripped state.
 */
void virgl_quota_reset(void);

/**
 * Returns current allocated memory bytes.
 */
int64_t virgl_quota_get_allocated(void);

/**
 * Returns current maximum quota ceiling in bytes.
 */
int64_t virgl_quota_get_max(void);

/**
 * Configures the maximum memory quota ceiling in bytes.
 */
void virgl_quota_set_max(int64_t max_bytes);

/**
 * Returns whether the quota circuit breaker has been tripped.
 */
bool virgl_quota_is_tripped(void);

#ifdef __cplusplus
}
#endif

#endif /* VIRGL_MEMORY_QUOTA_H */
