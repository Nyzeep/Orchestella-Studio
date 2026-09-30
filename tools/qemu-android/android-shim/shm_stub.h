/*
 * DroidLayer Ticket 26 - Android bionic POSIX-shm link shim declarations
 * (self-owned).
 *
 * bionic exposes no shm_open/shm_unlink prototypes on any API level, while
 * QEMU 11.1.1 calls them unconditionally in util/oslib-posix.c:1013/1025 and
 * compiles with -Werror=implicit-function-declaration. This header is
 * force-included (-include) into every target translation unit and
 * macro-redirects the POSIX names to the self-owned ENOSYS stub entry points;
 * the stub archive also exports the plain POSIX names so toolchain probe
 * links (meson has_function) resolve. The redirect must stay a plain macro:
 * a typed prototype of shm_open would conflict with meson's own probe
 * declaration (char shm_open(void)) and flip has_function back to NO.
 * User ruling 2026-09-25, option 1.
 */
#ifndef DROIDLAYER_SHM_STUB_H
#define DROIDLAYER_SHM_STUB_H

#include <sys/types.h>

int droidlayer_shm_open_stub(const char *name, int oflag, mode_t mode);
int droidlayer_shm_unlink_stub(const char *name);

#define shm_open droidlayer_shm_open_stub
#define shm_unlink droidlayer_shm_unlink_stub

#endif /* DROIDLAYER_SHM_STUB_H */
