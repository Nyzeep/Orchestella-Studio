// SPDX-License-Identifier: GPL-3.0-or-later

#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <poll.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#include <wayland-client.h>

#include "wlr-screencopy-unstable-v1-client-protocol.h"

enum {
    DEFAULT_BAR_FD = 3,
    DEFAULT_FRAMES_FD = 4,
    SLOT_BYTES = 4 * 1024 * 1024,
    BAR_BYTES = 2 * SLOT_BYTES,
    MAGIC = 0x35303457,
    VERSION = 1,
    RGBA8 = 1,
    DAMAGE = 1,
    FRAME_INTERVAL_NS = 66666667,
    TRANSFER_TIMEOUT_MS = 10000,
};

struct frame_header {
    uint32_t magic;
    uint32_t version;
    uint32_t seq;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t format;
    uint32_t flags;
    uint8_t generation[16];
    uint8_t reserved[16];
};

_Static_assert(sizeof(struct frame_header) == 64, "frame header must occupy 64 bytes");
_Static_assert(offsetof(struct frame_header, generation) == 32, "invalid generation offset");
_Static_assert(__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__, "little-endian guest required");

struct buffer {
    struct wl_buffer *object;
    void *pixels;
    size_t size;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t format;
};

struct session {
    struct wl_display *display;
    struct wl_output *output;
    struct wl_shm *shm;
    struct zwlr_screencopy_manager_v1 *manager;
    uint32_t output_name;
    uint32_t version;
    int channel_fd;
    bool output_removed;
    struct buffer buffer;
};

struct capture {
    struct session *session;
    bool wait_for_damage;
    bool damaged;
    bool y_invert;
    bool done;
    bool ready;
};

static int hex_digit(char digit) {
    if ('0' <= digit && digit <= '9') {
        return digit - '0';
    }
    if ('a' <= digit && digit <= 'f') {
        return digit - 'a' + 10;
    }
    return -1;
}

static bool parse_generation(const char *hex, uint8_t generation[16]) {
    if (strlen(hex) != 32) {
        return false;
    }
    for (size_t index = 0; index < 16; ++index) {
        int high = hex_digit(hex[index * 2]);
        int low = hex_digit(hex[index * 2 + 1]);
        if (high < 0 || low < 0) {
            return false;
        }
        generation[index] = (uint8_t)((high << 4) | low);
    }
    return true;
}

static int parse_fd(const char *text) {
    size_t length = strlen(text);
    if (length == 0 || length > 4 || strspn(text, "0123456789") != length) {
        return -1;
    }
    int fd = atoi(text);
    return fd >= 3 && fd <= 1023 ? fd : -1;
}

static void destroy_buffer(struct buffer *buffer) {
    if (buffer->object) {
        wl_buffer_destroy(buffer->object);
        munmap(buffer->pixels, buffer->size);
        *buffer = (struct buffer){0};
    }
}

static bool ensure_buffer(struct session *session, uint32_t format, uint32_t width,
        uint32_t height, uint32_t stride) {
    struct buffer *buffer = &session->buffer;
    if (buffer->object && buffer->width == width && buffer->height == height &&
            buffer->stride == stride && buffer->format == format) {
        return true;
    }
    destroy_buffer(buffer);
    size_t size = (size_t)stride * height;
    int fd = memfd_create("w405-capture", MFD_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    if (ftruncate(fd, (off_t)size) != 0) {
        close(fd);
        return false;
    }
    void *pixels = mmap(NULL, size, PROT_READ, MAP_SHARED, fd, 0);
    if (pixels == MAP_FAILED) {
        close(fd);
        return false;
    }
    struct wl_shm_pool *pool = wl_shm_create_pool(session->shm, fd, (int32_t)size);
    close(fd);
    struct wl_buffer *object = NULL;
    if (pool) {
        object = wl_shm_pool_create_buffer(pool, 0, (int32_t)width, (int32_t)height,
                (int32_t)stride, format);
        wl_shm_pool_destroy(pool);
    }
    if (!object) {
        munmap(pixels, size);
        return false;
    }
    *buffer = (struct buffer){object, pixels, size, width, height, stride, format};
    return true;
}

static void on_buffer(void *data, struct zwlr_screencopy_frame_v1 *frame,
        uint32_t format, uint32_t width, uint32_t height, uint32_t stride) {
    struct capture *capture = data;
    uint64_t rgba_stride = (uint64_t)width * 4;
    uint64_t max_pixels = SLOT_BYTES - sizeof(struct frame_header);
    if ((format != WL_SHM_FORMAT_XRGB8888 && format != WL_SHM_FORMAT_ARGB8888) ||
            !width || !height || rgba_stride > stride || stride % 4 ||
            height > max_pixels / rgba_stride || (uint64_t)stride * height > BAR_BYTES) {
        fprintf(stderr, "W405BENCH capture_error=unsupported_buffer format=%" PRIu32
                " width=%" PRIu32 " height=%" PRIu32 " stride=%" PRIu32 "\n",
                format, width, height, stride);
        capture->done = true;
        return;
    }
    if (!ensure_buffer(capture->session, format, width, height, stride)) {
        fprintf(stderr, "W405BENCH capture_error=allocate_buffer errno=%d\n", errno);
        capture->done = true;
        return;
    }
    if (capture->wait_for_damage) {
        zwlr_screencopy_frame_v1_copy_with_damage(frame, capture->session->buffer.object);
    } else {
        zwlr_screencopy_frame_v1_copy(frame, capture->session->buffer.object);
    }
}

static void on_flags(void *data, struct zwlr_screencopy_frame_v1 *frame, uint32_t flags) {
    (void)frame;
    struct capture *capture = data;
    capture->y_invert = (flags & ZWLR_SCREENCOPY_FRAME_V1_FLAGS_Y_INVERT) != 0;
}

static void on_ready(void *data, struct zwlr_screencopy_frame_v1 *frame,
        uint32_t seconds_high, uint32_t seconds_low, uint32_t nanoseconds) {
    (void)frame;
    (void)seconds_high;
    (void)seconds_low;
    (void)nanoseconds;
    struct capture *capture = data;
    capture->ready = true;
    capture->done = true;
}

static void on_failed(void *data, struct zwlr_screencopy_frame_v1 *frame) {
    (void)frame;
    struct capture *capture = data;
    capture->done = true;
    fprintf(stderr, "W405BENCH capture_error=compositor_copy_failed\n");
}

static void on_damage(void *data, struct zwlr_screencopy_frame_v1 *frame,
        uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
    (void)frame;
    (void)x;
    (void)y;
    struct capture *capture = data;
    capture->damaged |= width && height;
}

static const struct zwlr_screencopy_frame_v1_listener frame_listener = {
    .buffer = on_buffer,
    .flags = on_flags,
    .ready = on_ready,
    .failed = on_failed,
    .damage = on_damage,
};

static void on_global(void *data, struct wl_registry *registry,
        uint32_t name, const char *interface, uint32_t version) {
    struct session *session = data;
    if (!session->output && !strcmp(interface, wl_output_interface.name)) {
        session->output = wl_registry_bind(registry, name, &wl_output_interface, 1);
        session->output_name = name;
    } else if (!session->shm && !strcmp(interface, wl_shm_interface.name)) {
        session->shm = wl_registry_bind(registry, name, &wl_shm_interface, 1);
    } else if (!session->manager && !strcmp(interface, zwlr_screencopy_manager_v1_interface.name)) {
        // Version 2 supplies wl_shm unconditionally and supports copy_with_damage.
        session->version = version >= 2 ? 2 : 1;
        session->manager = wl_registry_bind(registry, name,
                &zwlr_screencopy_manager_v1_interface, session->version);
    }
}

static void on_global_remove(void *data, struct wl_registry *registry, uint32_t name) {
    (void)registry;
    struct session *session = data;
    if (name == session->output_name) {
        session->output_removed = true;
    }
}

static const struct wl_registry_listener registry_listener = {
    .global = on_global,
    .global_remove = on_global_remove,
};

// Returns 1 for a ready frame, 0 for host disconnect, and -1 for failure.
static int wait_for_frame(struct capture *capture) {
    struct session *session = capture->session;
    struct wl_display *display = session->display;
    while (!capture->done && !session->output_removed) {
        if (wl_display_dispatch_pending(display) < 0) {
            return -1;
        }
        if (capture->done || session->output_removed) {
            break;
        }
        if (wl_display_prepare_read(display) != 0) {
            continue;
        }
        int flushed = wl_display_flush(display);
        if (flushed < 0 && errno != EAGAIN) {
            wl_display_cancel_read(display);
            return -1;
        }
        struct pollfd sockets[] = {
            { .fd = wl_display_get_fd(display), .events = POLLIN | (flushed < 0 ? POLLOUT : 0) },
            { .fd = session->channel_fd, .events = POLLIN },
        };
        int result = poll(sockets, 2, -1);
        if (result < 0) {
            wl_display_cancel_read(display);
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        if (sockets[1].revents) {
            wl_display_cancel_read(display);
            if (sockets[1].revents & (POLLHUP | POLLERR)) {
                return 0;
            }
            char unexpected;
            ssize_t count = read(session->channel_fd, &unexpected, 1);
            if (count > 0) {
                errno = EPROTO;
                return -1;
            }
            if (count == 0) {
                return 0;
            }
            if (errno != EAGAIN && errno != EINTR) {
                return -1;
            }
            continue;
        }
        if (sockets[0].revents & POLLIN) {
            if (wl_display_read_events(display) < 0) {
                return -1;
            }
        } else {
            wl_display_cancel_read(display);
            if (sockets[0].revents & (POLLHUP | POLLERR | POLLNVAL)) {
                errno = EPIPE;
                return -1;
            }
        }
    }
    if (capture->ready && !session->output_removed) {
        return 1;
    }
    errno = session->output_removed ? ENODEV : EIO;
    return -1;
}

static void sync_bar_stores(void) {
#if defined(__aarch64__)
    // PCI BAR stores must complete before publishing seq or notifying the host.
    __asm__ volatile("dsb oshst" ::: "memory");
#else
    atomic_thread_fence(memory_order_seq_cst);
#endif
}

static void fill_bar(uint8_t *bar, const struct capture *capture,
        uint32_t seq, const uint8_t generation[16]) {
    const struct buffer *buffer = &capture->session->buffer;
    uint8_t *slot = bar + ((seq - 1) % 2) * SLOT_BYTES;
    volatile struct frame_header *header = (volatile struct frame_header *)slot;
    // Aligned scalar volatile stores also work through a device-mapped BAR.
    volatile uint32_t *rgba = (volatile uint32_t *)(slot + sizeof(*header));
    const uint8_t *pixels = buffer->pixels;
    uint32_t opaque = buffer->format == WL_SHM_FORMAT_XRGB8888 ? 0xff000000 : 0;
    header->seq = 0;
    for (uint32_t row = 0; row < buffer->height; ++row) {
        uint32_t source_row = capture->y_invert ? buffer->height - 1 - row : row;
        const uint32_t *source = (const uint32_t *)(pixels + (size_t)source_row * buffer->stride);
        for (uint32_t column = 0; column < buffer->width; ++column) {
            uint32_t bgra = source[column];
            *rgba++ = opaque | (bgra & 0xff00ff00) |
                    ((bgra & 0x000000ff) << 16) | ((bgra & 0x00ff0000) >> 16);
        }
    }
    header->magic = MAGIC;
    header->version = VERSION;
    header->width = buffer->width;
    header->height = buffer->height;
    header->stride = buffer->width * 4;
    header->format = RGBA8;
    header->flags = capture->damaged ? DAMAGE : 0;
    for (size_t index = 0; index < sizeof(header->generation); ++index) {
        header->generation[index] = generation[index];
        header->reserved[index] = 0;
    }
    sync_bar_stores();
    header->seq = seq;
    sync_bar_stores();
}

static int wait_for_channel(int fd, short events) {
    struct pollfd channel = {.fd = fd, .events = events};
    int result;
    do {
        result = poll(&channel, 1, TRANSFER_TIMEOUT_MS);
    } while (result < 0 && errno == EINTR);
    if (result == 0) {
        errno = ETIMEDOUT;
        return -1;
    }
    if (result < 0) {
        return -1;
    }
    if (channel.revents & events) {
        return 1;
    }
    if (channel.revents & (POLLHUP | POLLERR)) {
        return 0;
    }
    errno = EBADF;
    return -1;
}

static int publish_and_ack(int fd, uint32_t seq) {
    char message[32];
    size_t size = (size_t)snprintf(message, sizeof(message), "P %" PRIu32 "\n", seq);
    for (size_t offset = 0; offset < size;) {
        int ready = wait_for_channel(fd, POLLOUT);
        if (ready <= 0) {
            return ready;
        }
        ssize_t count = write(fd, message + offset, size - offset);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) {
            continue;
        }
        if (count <= 0) {
            return count == 0 || errno == EPIPE ? 0 : -1;
        }
        offset += (size_t)count;
    }
    message[0] = 'A';
    for (size_t offset = 0; offset < size;) {
        int ready = wait_for_channel(fd, POLLIN);
        if (ready <= 0) {
            return ready;
        }
        char received[sizeof(message)];
        ssize_t count = read(fd, received, size - offset);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) {
            continue;
        }
        if (count <= 0) {
            return (int)count;
        }
        if (memcmp(received, message + offset, (size_t)count) != 0) {
            errno = EPROTO;
            return -1;
        }
        offset += (size_t)count;
    }
    return 1;
}

static int run_capture(struct session *session, uint8_t *bar, const uint8_t generation[16]) {
    for (uint32_t seq = 1; seq != 0; ++seq) {
        bool wait_for_damage = seq != 1 && session->version >= 2;
        struct capture capture = {
            .session = session,
            .wait_for_damage = wait_for_damage,
            .damaged = !wait_for_damage,
        };
        struct zwlr_screencopy_frame_v1 *frame = zwlr_screencopy_manager_v1_capture_output(
                session->manager, 1, session->output);
        if (!frame) {
            return -1;
        }
        zwlr_screencopy_frame_v1_add_listener(frame, &frame_listener, &capture);
        int captured = wait_for_frame(&capture);
        int capture_error = errno;
        zwlr_screencopy_frame_v1_destroy(frame);
        if (captured <= 0) {
            errno = capture_error;
            return captured;
        }
        fill_bar(bar, &capture, seq, generation);
        struct timespec next;
        if (clock_gettime(CLOCK_MONOTONIC, &next) != 0) {
            return -1;
        }
        next.tv_nsec += FRAME_INTERVAL_NS;
        if (next.tv_nsec >= 1000000000L) {
            ++next.tv_sec;
            next.tv_nsec -= 1000000000L;
        }
        int acknowledged = publish_and_ack(session->channel_fd, seq);
        if (acknowledged <= 0) {
            return acknowledged;
        }
        if (seq == 1) {
            fprintf(stderr, "W405BENCH capture_first_frame=%" PRIu32 "x%" PRIu32 "\n",
                    session->buffer.width, session->buffer.height);
        }
        int sleep_error;
        do {
            sleep_error = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        } while (sleep_error == EINTR);
        if (sleep_error) {
            errno = sleep_error;
            return -1;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    uint8_t generation[16];
    if ((argc != 2 && argc != 4) || !parse_generation(argv[1], generation) ||
            getuid() == 0 || geteuid() == 0) {
        fprintf(stderr, "W405BENCH capture_error=invalid_generation_or_root\n");
        return EXIT_FAILURE;
    }
    int bar_fd = argc == 4 ? parse_fd(argv[2]) : DEFAULT_BAR_FD;
    int channel_fd = argc == 4 ? parse_fd(argv[3]) : DEFAULT_FRAMES_FD;
    if (bar_fd < 0 || channel_fd < 0 || bar_fd == channel_fd) {
        fprintf(stderr, "W405BENCH capture_error=invalid_fds\n");
        return EXIT_FAILURE;
    }
    signal(SIGPIPE, SIG_IGN);
    int channel_status = fcntl(channel_fd, F_GETFL);
    if (channel_status < 0 || fcntl(channel_fd, F_SETFL, channel_status | O_NONBLOCK) < 0) {
        fprintf(stderr, "W405BENCH capture_error=channel_fd errno=%d\n", errno);
        return EXIT_FAILURE;
    }
    uint8_t *bar = mmap(NULL, BAR_BYTES, PROT_READ | PROT_WRITE, MAP_SHARED, bar_fd, 0);
    int map_error = errno;
    close(bar_fd);
    if (bar == MAP_FAILED) {
        fprintf(stderr, "W405BENCH capture_error=bar_map errno=%d\n", map_error);
        return EXIT_FAILURE;
    }
    struct session session = {.display = wl_display_connect(NULL), .channel_fd = channel_fd};
    int result = -1;
    if (session.display) {
        struct wl_registry *registry = wl_display_get_registry(session.display);
        if (registry) {
            wl_registry_add_listener(registry, &registry_listener, &session);
            if (wl_display_roundtrip(session.display) >= 0 &&
                    session.manager && session.output && session.shm) {
                fprintf(stderr, "W405BENCH capture_protocol=%" PRIu32 " capture_max_fps=15\n",
                        session.version);
                result = run_capture(&session, bar, generation);
            } else {
                errno = ENODEV;
            }
            wl_registry_destroy(registry);
        }
        if (result < 0) {
            fprintf(stderr, "W405BENCH capture_error=wayland_or_channel errno=%d\n", errno);
        }
        destroy_buffer(&session.buffer);
        if (session.manager) {
            zwlr_screencopy_manager_v1_destroy(session.manager);
        }
        if (session.output) {
            wl_output_destroy(session.output);
        }
        if (session.shm) {
            wl_shm_destroy(session.shm);
        }
        wl_display_disconnect(session.display);
    } else {
        fprintf(stderr, "W405BENCH capture_error=wayland_connect errno=%d\n", errno);
    }
    munmap(bar, BAR_BYTES);
    close(channel_fd);
    return result < 0 ? EXIT_FAILURE : EXIT_SUCCESS;
}
