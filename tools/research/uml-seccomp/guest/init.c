/*
 * U3 guest init: capability probes for the seccomp compatibility matrix.
 *
 * Static binary, runs as /init under hostfs or ubd0.  It mounts the usual
 * pseudo-filesystems, exercises the guest-visible capabilities the matrix
 * tracks, prints "CAP <name>=<ok|fail>" and "GUEST_READY uptime=<s>", then
 * powers the guest off.
 *
 * Guest-visible results are not host-syscall evidence: guest mount/clone/
 * personality/setrlimit are implemented by the UML kernel.  Host-side
 * dependency is established separately by running UML under a filter that
 * denies the host syscall (see run_uml_seccomp.sh --deny-extra).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/personality.h>
#include <sys/reboot.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static int failures;

static void cap(const char *name, int ok)
{
    printf("CAP %s=%s\n", name, ok ? "ok" : "fail");
    fflush(stdout);
    if (!ok)
        failures++;
}

static int read_file(const char *path, char *buf, size_t size)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0)
        return -1;
    ssize_t got = read(fd, buf, size - 1);
    close(fd);
    if (got < 0)
        return -1;
    buf[got] = '\0';
    return 0;
}

/* Return "hostfs", "ext4" or "unknown" for the root mount. */
static const char *root_fstype(void)
{
    static char buf[8192];
    if (read_file("/proc/mounts", buf, sizeof(buf)) < 0)
        return "unknown";
    char *line = strtok(buf, "\n");
    while (line) {
        char dev[256], mnt[256], type[64];
        if (sscanf(line, "%255s %255s %63s", dev, mnt, type) == 3 &&
            strcmp(mnt, "/") == 0) {
            if (strcmp(type, "ext4") == 0)
                return "ext4";
            if (strcmp(type, "hostfs") == 0)
                return "hostfs";
            return "other";
        }
        line = strtok(NULL, "\n");
    }
    return "unknown";
}

static int mount_all(void)
{
    int ok = 1;
    mkdir("/proc", 0755);
    mkdir("/sys", 0755);
    mkdir("/dev", 0755);
    mkdir("/dev/pts", 0755);
    mkdir("/tmp", 0777);
    struct { const char *src, *tgt, *type; } mnts[] = {
        {"proc", "/proc", "proc"},
        {"sysfs", "/sys", "sysfs"},
        {"devtmpfs", "/dev", "devtmpfs"},
        {"devpts", "/dev/pts", "devpts"},
        {"tmpfs", "/tmp", "tmpfs"},
    };
    for (unsigned i = 0; i < sizeof(mnts) / sizeof(mnts[0]); i++) {
        if (mount(mnts[i].src, mnts[i].tgt, mnts[i].type, 0, "") < 0) {
            printf("MOUNT %s type=%s errno=%d\n", mnts[i].tgt, mnts[i].type, errno);
            fflush(stdout);
            if (errno != EBUSY)
                ok = 0;
        }
    }
    return ok;
}

static int probe_fork(void)
{
    pid_t pid = fork();
    if (pid == 0)
        _exit(0);
    if (pid < 0)
        return 0;
    int status = 0;
    if (waitpid(pid, &status, 0) != pid)
        return 0;
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

static int probe_personality(void)
{
    int previous = personality(ADDR_NO_RANDOMIZE);
    if (previous == -1)
        return 0;
    (void)personality(previous);
    return 1;
}

static int probe_setrlimit(void)
{
    struct rlimit old, want;
    if (getrlimit(RLIMIT_NOFILE, &old) < 0)
        return 0;
    want = old;
    if (want.rlim_cur > 1024)
        want.rlim_cur = 1024;
    if (setrlimit(RLIMIT_NOFILE, &want) < 0)
        return 0;
    (void)setrlimit(RLIMIT_NOFILE, &old);
    return 1;
}

/* Guest-visible SMP width: the "processor" lines of /proc/cpuinfo.  The
 * ncpus-2/ncpus-4 matrix rows declare "CAP smp=<n>" as their marker; without
 * this probe the rows can never pass (the marker would be missing on any
 * boot, including a healthy SMP one).  A UP kernel, or an ncpus= knob the
 * kernel did not honor, reports 1 and fails both rows honestly. */
static int probe_smp_count(void)
{
    static char buf[16384];
    if (read_file("/proc/cpuinfo", buf, sizeof(buf)) < 0)
        return 1;
    int n = 0;
    for (char *line = strtok(buf, "\n"); line; line = strtok(NULL, "\n"))
        if (strncmp(line, "processor", 9) == 0)
            n++;
    return n > 0 ? n : 1;
}

int main(void)
{
    printf("GUEST_INIT start\n");
    fflush(stdout);

    cap("mount", mount_all());
    const char *fstype = root_fstype();
    printf("CAP rootfstype=%s\n", fstype);
    fflush(stdout);
    if (strcmp(fstype, "ext4") == 0)
        cap("ubd0", 1);
    else if (strcmp(fstype, "hostfs") == 0)
        cap("hostfs", 1);

    int forked = probe_fork();
    cap("clone", forked);
    cap("sigchld", forked);

    cap("personality", probe_personality());
    cap("setrlimit", probe_setrlimit());
    cap("vector", access("/sys/class/net/vec0", F_OK) == 0 || access("/sys/class/net/eth0", F_OK) == 0);
    printf("CAP smp=%d\n", probe_smp_count());
    fflush(stdout);

    char uptime[128] = "0.00";
    char buf[256];
    if (read_file("/proc/uptime", buf, sizeof(buf)) == 0)
        sscanf(buf, "%127s", uptime);
    printf("GUEST_READY uptime=%s failures=%d\n", uptime, failures);
    fflush(stdout);

    char cmdline[4096];
    if (read_file("/proc/cmdline", cmdline, sizeof(cmdline)) == 0) {
        char *wait = strstr(cmdline, "u3_wait_ms=");
        if (wait) {
            long ms = strtol(wait + strlen("u3_wait_ms="), NULL, 10);
            if (ms > 0 && ms <= 60000) {
                printf("GUEST_WAIT ms=%ld\n", ms);
                fflush(stdout);
                usleep((useconds_t)ms * 1000);
            }
        }
    }
    sync();
    reboot(RB_POWER_OFF);
    printf("GUEST_POWEROFF_FAILED\n");
    fflush(stdout);
    return 0;
}
