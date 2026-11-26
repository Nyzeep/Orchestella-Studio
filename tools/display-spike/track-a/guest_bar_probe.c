#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <inttypes.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/reboot.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

enum {
    BAR_SIZE = 1024 * 1024,
    ROOT_OFFSET = 64,
    USER_OFFSET = 128,
    PROBE_UID = 1000,
    PROBE_GID = 1000,
};

static const char host_seed[] = "HOST_SEED_405";
static const char root_marker[] = "GUEST_ROOT_405";
static const char user_marker[] = "GUEST_USER_405";

static bool bar_equals(const volatile char *bar, const char *marker, size_t size) {
    for (size_t i = 0; i < size; ++i) {
        if (bar[i] != marker[i]) {
            return false;
        }
    }
    return true;
}

static void bar_write(volatile char *bar, const char *marker, size_t size) {
    for (size_t i = 0; i < size; ++i) {
        bar[i] = marker[i];
    }
}

static bool has_id(int directory, const char *name, const char *id) {
    int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd == -1) {
        return false;
    }
    char value[16];
    ssize_t length = read(fd, value, sizeof(value));
    close(fd);
    return length == 7 && memcmp(value, id, 6) == 0 && value[6] == '\n';
}

static int find_bar(void) {
    DIR *devices = opendir("/sys/bus/pci/devices");
    if (!devices) {
        printf("W405A guest_pci_directory_errno=%d\n", errno);
        return -1;
    }
    int device = -1;
    struct dirent *entry;
    while ((entry = readdir(devices)) != NULL) {
        if (entry->d_name[0] == '.') {
            continue;
        }
        int candidate = openat(dirfd(devices), entry->d_name, O_DIRECTORY | O_RDONLY | O_CLOEXEC);
        if (candidate == -1) {
            continue;
        }
        if (has_id(candidate, "vendor", "0x1af4") && has_id(candidate, "device", "0x1110")) {
            if (device != -1) {
                printf("W405A guest_pci_duplicate=1\n");
                close(candidate);
                close(device);
                device = -1;
                break;
            }
            printf("W405A guest_pci_bdf=%s vendor=0x1af4 device=0x1110 bar=2\n", entry->d_name);
            device = candidate;
        } else {
            close(candidate);
        }
    }
    closedir(devices);
    if (device == -1) {
        puts("W405A guest_pci_selected=0");
    }
    return device;
}

static int transfer_fd(int socket_fd, int bar_fd, pid_t child) {
    char ack;
    struct iovec iov = {.iov_base = &ack, .iov_len = sizeof(ack)};
    union {
        struct cmsghdr alignment;
        char bytes[CMSG_SPACE(sizeof(struct ucred))];
    } control;
    struct msghdr request = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = control.bytes,
        .msg_controllen = sizeof(control.bytes),
    };
    if (recvmsg(socket_fd, &request, 0) != 1 || ack != 'B' ||
            (request.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0) {
        return -1;
    }
    struct cmsghdr *credential = CMSG_FIRSTHDR(&request);
    if (!credential || credential->cmsg_level != SOL_SOCKET ||
            credential->cmsg_type != SCM_CREDENTIALS ||
            credential->cmsg_len != CMSG_LEN(sizeof(struct ucred))) {
        return -1;
    }
    struct ucred creds;
    memcpy(&creds, CMSG_DATA(credential), sizeof(creds));
    if (creds.uid != PROBE_UID || creds.gid != PROBE_GID || creds.pid != child) {
        return -1;
    }
    printf("W405A helper_peer_uid=%u helper_peer_gid=%u\n", creds.uid, creds.gid);

    union {
        struct cmsghdr alignment;
        char bytes[CMSG_SPACE(sizeof(int))];
    } fd_control = {0};
    struct msghdr reply = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = fd_control.bytes,
        .msg_controllen = sizeof(fd_control.bytes),
    };
    ack = 'F';
    struct cmsghdr *item = CMSG_FIRSTHDR(&reply);
    item->cmsg_level = SOL_SOCKET;
    item->cmsg_type = SCM_RIGHTS;
    item->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(item), &bar_fd, sizeof(bar_fd));
    return sendmsg(socket_fd, &reply, MSG_NOSIGNAL) == 1 ? 0 : -1;
}

static int receive_fd(int socket_fd) {
    char ack = 'B';
    if (send(socket_fd, &ack, sizeof(ack), MSG_NOSIGNAL) != 1) {
        return -1;
    }
    struct iovec iov = {.iov_base = &ack, .iov_len = sizeof(ack)};
    union {
        struct cmsghdr alignment;
        char bytes[CMSG_SPACE(sizeof(int))];
    } control;
    struct msghdr reply = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = control.bytes,
        .msg_controllen = sizeof(control.bytes),
    };
    if (recvmsg(socket_fd, &reply, MSG_CMSG_CLOEXEC) != 1 || ack != 'F' ||
            (reply.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0) {
        return -1;
    }
    struct cmsghdr *item = CMSG_FIRSTHDR(&reply);
    if (!item || item->cmsg_level != SOL_SOCKET || item->cmsg_type != SCM_RIGHTS ||
            item->cmsg_len != CMSG_LEN(sizeof(int))) {
        return -1;
    }
    int fd;
    memcpy(&fd, CMSG_DATA(item), sizeof(fd));
    return fd;
}

static bool ordinary_user_probe(int socket_fd, int device) {
    if (setgroups(0, NULL) == -1 || setgid(PROBE_GID) == -1 || setuid(PROBE_UID) == -1 ||
            prctl(PR_SET_NO_NEW_PRIVS, 1L, 0L, 0L, 0L) == -1) {
        printf("W405A guest_drop_privilege_errno=%d\n", errno);
        return false;
    }
    int direct_fd = openat(device, "resource2", O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    int direct_errno = direct_fd == -1 ? errno : 0;
    if (direct_fd != -1) {
        close(direct_fd);
    }
    close(device);
    printf("W405A guest_uid=%u guest_gid=%u guest_direct_bar_open_errno=%d\n",
            getuid(), getgid(), direct_errno);
    int fd = receive_fd(socket_fd);
    if (fd == -1) {
        puts("W405A guest_received_fd=0");
        return false;
    }
    puts("W405A guest_received_fd=1");
    char *bar = mmap(NULL, BAR_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    int map_errno = bar == MAP_FAILED ? errno : 0;
    close(fd);
    printf("W405A guest_fd_mmap_errno=%d\n", map_errno);
    if (bar == MAP_FAILED) {
        return false;
    }
    bool root_visible = bar_equals(bar + ROOT_OFFSET, root_marker, sizeof(root_marker));
    if (root_visible) {
        bar_write(bar + USER_OFFSET, user_marker, sizeof(user_marker));
    }
    bool unmapped = munmap(bar, BAR_SIZE) == 0;
    printf("W405A guest_user_observed_root=%d guest_user_unmapped=%d\n",
            root_visible, unmapped);
    return root_visible && unmapped && direct_errno == EACCES;
}

static bool fd_handoff_probe(int device, int bar_fd, char *root_bar) {
    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) == -1) {
        printf("W405A helper_socketpair_errno=%d\n", errno);
        return false;
    }
    int enable = 1;
    struct timeval timeout = {.tv_sec = 5};
    if (setsockopt(pair[0], SOL_SOCKET, SO_PASSCRED, &enable, sizeof(enable)) == -1 ||
            setsockopt(pair[0], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == -1 ||
            setsockopt(pair[1], SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) == -1) {
        printf("W405A helper_setsockopt_errno=%d\n", errno);
        close(pair[0]);
        close(pair[1]);
        return false;
    }
    pid_t child = fork();
    if (child == 0) {
        close(pair[0]);
        if (munmap(root_bar, BAR_SIZE) == -1 || close(bar_fd) == -1) {
            _exit(1);
        }
        _exit(ordinary_user_probe(pair[1], device) ? 0 : 1);
    }
    close(pair[1]);
    if (child == -1) {
        printf("W405A helper_fork_errno=%d\n", errno);
        close(pair[0]);
        return false;
    }
    int transfer_result = transfer_fd(pair[0], bar_fd, child);
    close(pair[0]);
    if (transfer_result == -1) {
        kill(child, SIGKILL);
    }
    int status = 0;
    pid_t finished = waitpid(child, &status, 0);
    bool child_ok = finished == child && WIFEXITED(status) && WEXITSTATUS(status) == 0;
    bool guest_visible = bar_equals(root_bar + USER_OFFSET, user_marker, sizeof(user_marker));
    printf("W405A helper_transferred_fd=%d helper_child_ok=%d helper_observed_user=%d\n",
            transfer_result == 0, child_ok, guest_visible);
    return transfer_result == 0 && child_ok && guest_visible;
}

static int read_pci_command(int device) {
    int config = openat(device, "config", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (config == -1) {
        return -1;
    }
    unsigned char bytes[2];
    ssize_t length = pread(config, bytes, sizeof(bytes), 4);
    close(config);
    return length == sizeof(bytes) ? bytes[0] | (bytes[1] << 8) : -1;
}

static bool set_pci_enable(int device, char value) {
    int fd = openat(device, "enable", O_WRONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd == -1) {
        return false;
    }
    bool written = write(fd, &value, 1) == 1;
    close(fd);
    return written;
}

static bool bar_probe(void) {
    if (mount("sysfs", "/sys", "sysfs", MS_NOSUID | MS_NOEXEC | MS_NODEV, NULL) == -1) {
        printf("W405A guest_mount_sysfs_errno=%d\n", errno);
        return false;
    }
    int device = find_bar();
    if (device == -1) {
        return false;
    }
    int command_before = read_pci_command(device);
    printf("W405A guest_pci_command_before=%d\n", command_before);
    if (command_before == -1) {
        close(device);
        return false;
    }
    bool enable_needed = (command_before & 2) == 0;
    if (enable_needed && !set_pci_enable(device, '1')) {
        printf("W405A guest_pci_enable_errno=%d\n", errno);
        close(device);
        return false;
    }
    int command_after = read_pci_command(device);
    printf("W405A guest_pci_command_after=%d\n", command_after);
    int fd = openat(device, "resource2", O_RDWR | O_CLOEXEC | O_NOFOLLOW);
    int open_errno = fd == -1 ? errno : 0;
    printf("W405A guest_root_bar_open_errno=%d\n", open_errno);
    if (fd == -1) {
        if (enable_needed) {
            set_pci_enable(device, '0');
        }
        close(device);
        return false;
    }
    struct stat info;
    if (fstat(fd, &info) == -1 || !S_ISREG(info.st_mode) || info.st_size != BAR_SIZE) {
        puts("W405A guest_bar_shape_valid=0");
        if (enable_needed) {
            set_pci_enable(device, '0');
        }
        close(fd);
        close(device);
        return false;
    }
    printf("W405A guest_bar_size=%" PRIdMAX " guest_bar_mode=%04o\n",
            (intmax_t)info.st_size, info.st_mode & 07777);
    char *bar = mmap(NULL, BAR_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    int map_errno = bar == MAP_FAILED ? errno : 0;
    printf("W405A guest_root_bar_mmap_errno=%d\n", map_errno);
    bool ok = false;
    if (bar != MAP_FAILED) {
        bool host_visible = bar_equals(bar, host_seed, sizeof(host_seed));
        printf("W405A guest_root_observed_host=%d\n", host_visible);
        if (host_visible) {
            bar_write(bar + ROOT_OFFSET, root_marker, sizeof(root_marker));
            ok = fd_handoff_probe(device, fd, bar);
        }
        bool unmapped = munmap(bar, BAR_SIZE) == 0;
        printf("W405A guest_root_unmapped=%d\n", unmapped);
        ok = ok && unmapped;
    }
    close(fd);
    bool restored = !enable_needed || set_pci_enable(device, '0');
    printf("W405A guest_pci_enable_restored=%d\n", restored);
    close(device);
    return ok && restored;
}

int main(void) {
    setvbuf(stdout, NULL, _IONBF, 0);
    struct utsname facts;
    if (uname(&facts) == 0) {
        printf("W405A guest_kernel=%s\n", facts.release);
    }
    bool ok = bar_probe();
    printf("W405A guest_complete=%s\n", ok ? "pass" : "fail");
    if (reboot(RB_POWER_OFF) == -1) {
        printf("W405A guest_poweroff_errno=%d\n", errno);
    }
    for (;;) {
        pause();
    }
}
