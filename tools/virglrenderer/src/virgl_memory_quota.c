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

#include "virgl_memory_quota.h"

#include <android/log.h>
#include <stdatomic.h>
#include <stdio.h>

#define LOG_TAG "VirglQuotaSentinel"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static _Atomic(int64_t) s_allocated_bytes = 0;
static _Atomic(int64_t) s_max_quota_bytes = VIRGL_DEFAULT_MEMORY_QUOTA_BYTES;
static _Atomic(bool) s_tripped = false;

bool virgl_quota_try_allocate(size_t bytes) {
    if (bytes == 0) {
        return true;
    }
    int64_t req = (int64_t)bytes;
    int64_t max_quota = atomic_load(&s_max_quota_bytes);

    int64_t current = atomic_load(&s_allocated_bytes);
    while (true) {
        if (current + req > max_quota) {
            atomic_store(&s_tripped, true);
            LOGW("MemoryQuotaSentinel TRIPPED: rejected allocation of %zu bytes (current: %lld MB, limit: %lld MB)",
                 bytes, (long long)(current / (1024 * 1024)), (long long)(max_quota / (1024 * 1024)));
            return false;
        }
        if (atomic_compare_exchange_weak(&s_allocated_bytes, &current, current + req)) {
            return true;
        }
    }
}

void virgl_quota_release(size_t bytes) {
    if (bytes == 0) {
        return;
    }
    int64_t rel = (int64_t)bytes;
    int64_t current = atomic_load(&s_allocated_bytes);
    while (true) {
        int64_t updated = (current > rel) ? (current - rel) : 0;
        if (atomic_compare_exchange_weak(&s_allocated_bytes, &current, updated)) {
            if (updated < atomic_load(&s_max_quota_bytes)) {
                atomic_store(&s_tripped, false);
            }
            return;
        }
    }
}

void virgl_quota_reset(void) {
    atomic_store(&s_allocated_bytes, 0);
    atomic_store(&s_tripped, false);
}

int64_t virgl_quota_get_allocated(void) {
    return atomic_load(&s_allocated_bytes);
}

int64_t virgl_quota_get_max(void) {
    return atomic_load(&s_max_quota_bytes);
}

void virgl_quota_set_max(int64_t max_bytes) {
    if (max_bytes > 0) {
        atomic_store(&s_max_quota_bytes, max_bytes);
    }
}

bool virgl_quota_is_tripped(void) {
    return atomic_load(&s_tripped);
}
