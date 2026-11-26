#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <wayland-client.h>

#include "wlr-virtual-pointer-unstable-v1-client-protocol.h"

#define DISPLAY_WIDTH 960
#define DISPLAY_HEIGHT 540
#define GRAB_X 120
#define GRAB_Y 150
#define STEPS_PER_SECOND 40
#define STEP_NS (1000000000 / STEPS_PER_SECOND)
#define LEFT_BUTTON 0x110

struct pointer_globals {
    struct zwlr_virtual_pointer_manager_v1 *manager;
    struct wl_seat *seat;
};

static volatile sig_atomic_t cancelled;

static void cancel_drag(int signo) {
    (void) signo;
    cancelled = 1;
}

static void register_global(void *data, struct wl_registry *registry,
        uint32_t name, const char *interface, uint32_t version) {
    (void) version;
    struct pointer_globals *globals = data;
    if (!globals->manager &&
            strcmp(interface, zwlr_virtual_pointer_manager_v1_interface.name) == 0) {
        globals->manager = wl_registry_bind(
                registry, name, &zwlr_virtual_pointer_manager_v1_interface, 1);
    } else if (!globals->seat && strcmp(interface, wl_seat_interface.name) == 0) {
        globals->seat = wl_registry_bind(registry, name, &wl_seat_interface, 1);
    }
}

static void remove_global(void *data, struct wl_registry *registry, uint32_t name) {
    (void) data;
    (void) registry;
    (void) name;
}

static const struct wl_registry_listener registry_listener = {
    .global = register_global,
    .global_remove = remove_global,
};

static uint32_t time_ms(struct timespec t) {
    return (uint32_t) ((uint64_t) t.tv_sec * 1000u + t.tv_nsec / 1000000u);
}

static int send_motion(struct wl_display *display,
        struct zwlr_virtual_pointer_v1 *pointer, struct timespec time, unsigned step) {
    // One second in each direction. The entire window stays inside the output.
    unsigned phase = step % (2 * STEPS_PER_SECOND);
    if (phase > STEPS_PER_SECOND) {
        phase = 2 * STEPS_PER_SECOND - phase;
    }
    zwlr_virtual_pointer_v1_motion_absolute(pointer, time_ms(time),
            GRAB_X + 4 * phase, GRAB_Y + 5 * phase / 2, DISPLAY_WIDTH, DISPLAY_HEIGHT);
    zwlr_virtual_pointer_v1_frame(pointer);
    return wl_display_flush(display);
}

static int run_drag(struct wl_display *display,
        struct zwlr_virtual_pointer_v1 *pointer, unsigned seconds) {
    struct timespec next;
    if (clock_gettime(CLOCK_MONOTONIC, &next) != 0 ||
            send_motion(display, pointer, next, 0) < 0 ||
            wl_display_roundtrip(display) < 0 ||
            clock_gettime(CLOCK_MONOTONIC, &next) != 0) {
        return -1;
    }
    if (cancelled) {
        return 0;
    }

    zwlr_virtual_pointer_v1_button(pointer, time_ms(next),
            LEFT_BUTTON, WL_POINTER_BUTTON_STATE_PRESSED);
    zwlr_virtual_pointer_v1_frame(pointer);
    if (wl_display_flush(display) < 0) {
        return -1;
    }

    int result = 0;
    for (unsigned step = 1; step <= seconds * STEPS_PER_SECOND && !cancelled; ++step) {
        next.tv_nsec += STEP_NS;
        if (next.tv_nsec >= 1000000000) {
            next.tv_nsec -= 1000000000;
            ++next.tv_sec;
        }
        int wait_result;
        do {
            wait_result = clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &next, NULL);
        } while (wait_result == EINTR && !cancelled);
        if (cancelled) {
            break;
        }
        if (wait_result != 0 || send_motion(display, pointer, next, step) < 0) {
            result = -1;
            break;
        }
    }

    struct timespec end = next;
    (void) clock_gettime(CLOCK_MONOTONIC, &end);
    zwlr_virtual_pointer_v1_button(pointer, time_ms(end),
            LEFT_BUTTON, WL_POINTER_BUTTON_STATE_RELEASED);
    zwlr_virtual_pointer_v1_frame(pointer);
    if (wl_display_roundtrip(display) < 0) {
        result = -1;
    }
    return result;
}

int main(int argc, char **argv) {
    char *end = NULL;
    long duration = argc == 2 ? strtol(argv[1], &end, 10) : 0;
    if (duration < 1 || duration > 120 || *end) {
        fputs("Usage: w405-drag SECONDS (1..120)\n", stderr);
        return 2;
    }

    struct sigaction action = {.sa_handler = cancel_drag};
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGINT, &action, NULL) < 0 || sigaction(SIGTERM, &action, NULL) < 0) {
        perror("sigaction");
        return 1;
    }

    struct wl_display *display = wl_display_connect(NULL);
    if (!display) {
        fputs("Cannot connect to the benchmark compositor\n", stderr);
        return 1;
    }

    struct pointer_globals globals = {0};
    struct wl_registry *registry = wl_display_get_registry(display);
    int result = 1;
    if (!registry || wl_registry_add_listener(registry, &registry_listener, &globals) < 0 ||
            wl_display_roundtrip(display) < 0 || !globals.manager || !globals.seat) {
        fputs("The benchmark compositor did not advertise a seat and virtual pointer\n", stderr);
    } else {
        struct zwlr_virtual_pointer_v1 *pointer =
                zwlr_virtual_pointer_manager_v1_create_virtual_pointer(globals.manager, globals.seat);
        if (pointer) {
            result = run_drag(display, pointer, (unsigned) duration) == 0 ? 0 : 1;
            zwlr_virtual_pointer_v1_destroy(pointer);
        }
        if (result != 0) {
            fputs("Virtual pointer motion could not complete\n", stderr);
        }
    }
    if (globals.seat) {
        wl_seat_destroy(globals.seat);
    }
    if (globals.manager) {
        zwlr_virtual_pointer_manager_v1_destroy(globals.manager);
    }
    if (registry) {
        wl_registry_destroy(registry);
    }
    wl_display_disconnect(display);
    return result;
}
