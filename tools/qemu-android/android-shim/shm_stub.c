/*
 * DroidLayer Ticket 26 - Android bionic POSIX-shm link shim (self-owned).
 *
 * bionic provides no POSIX shared-memory objects on any API level (verified by
 * the two read-only NDK sysroot symbol probes of 2026-09-24), while QEMU 11.1.1
 * hard-references shm_open()/shm_unlink() (util/oslib-posix.c:1013/1025) and
 * gates its build on meson cc.has_function('shm_open'). Per the user ruling of
 * 2026-09-25 (option 1) this shim satisfies the link reference only: every
 * call fails with ENOSYS so any runtime reach fails closed and loudly. If a
 * shipped acceptance set ever reaches these calls at runtime, the ruling
 * requires upgrading to a full file-backed/ASharedMemory implementation
 * (option 2).
 *
 * The droidlayer_shm_*_stub names are what shipped QEMU code actually calls
 * (shm_stub.h macro-redirects shm_open/shm_unlink because bionic declares
 * nothing and QEMU compiles with -Werror=implicit-function-declaration). The
 * plain POSIX names are exported only so toolchain probe links (meson
 * has_function) resolve.
 */
#include <errno.h>
#include <sys/mman.h>
#include <sys/stat.h>

int droidlayer_shm_open_stub(const char *name, int oflag, mode_t mode)
{
    (void)name;
    (void)oflag;
    (void)mode;
    errno = ENOSYS;
    return -1;
}

int droidlayer_shm_unlink_stub(const char *name)
{
    (void)name;
    errno = ENOSYS;
    return -1;
}

int shm_open(const char *name, int oflag, mode_t mode)
{
    return droidlayer_shm_open_stub(name, oflag, mode);
}

int shm_unlink(const char *name)
{
    return droidlayer_shm_unlink_stub(name);
}
