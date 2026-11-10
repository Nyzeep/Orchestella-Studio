/*
 * seccomp_launch - install an AOSP-like seccomp filter and exec a target.
 *
 * Modes:
 *   enforce  default-deny TRAP (AOSP-like: blocked syscall -> SIGSYS)
 *   trace    default-deny USER_NOTIF + CONTINUE; every syscall outside the
 *            profile is logged as "SECCOMP_DENY nr=N name=NAME ..." and then
 *            allowed to run. Diagnostic only: it does NOT enforce the profile.
 *   baseline no filter (control run)
 *
 * This is a research harness for tools/research/uml-seccomp.  It is not
 * product code and not part of the Android seccomp policy.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__x86_64__)
#define NATIVE_AUDIT_ARCH AUDIT_ARCH_X86_64
#elif defined(__aarch64__)
#define NATIVE_AUDIT_ARCH AUDIT_ARCH_AARCH64
#else
#error "seccomp_launch supports x86_64 and aarch64 hosts only"
#endif

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1 /* asm-generic/siginfo.h; glibc does not expose it */
#endif

#define MAX_SYSCALLS 512
#define MAX_NAME 64

struct entry {
    int nr;
    char name[MAX_NAME];
};

static struct entry allow_list[MAX_SYSCALLS];
static int allow_count;
static struct entry deny_list[MAX_SYSCALLS];
static int deny_count;
static struct entry name_list[1024];
static int name_count;
static int log_fd = STDERR_FILENO;

static void put_str(const char *s)
{
    if (s && *s)
        (void)write(log_fd, s, strlen(s));
}

static void put_uint(unsigned long long value)
{
    char buf[32];
    int pos = sizeof(buf);
    if (value == 0) {
        (void)write(log_fd, "0", 1);
        return;
    }
    while (value && pos > 0) {
        buf[--pos] = (char)('0' + (value % 10));
        value /= 10;
    }
    (void)write(log_fd, buf + pos, (size_t)(sizeof(buf) - pos));
}

static void put_hex(unsigned long long value)
{
    static const char digits[] = "0123456789abcdef";
    char buf[19];
    int pos = 0;
    buf[pos++] = '0';
    buf[pos++] = 'x';
    for (int shift = 60; shift >= 0; shift -= 4)
        buf[pos++] = digits[(value >> shift) & 0xf];
    (void)write(log_fd, buf, (size_t)pos);
}

static const char *lookup_name(int nr)
{
    for (int i = 0; i < name_count; i++)
        if (name_list[i].nr == nr)
            return name_list[i].name;
    for (int i = 0; i < allow_count; i++)
        if (allow_list[i].nr == nr)
            return allow_list[i].name;
    for (int i = 0; i < deny_count; i++)
        if (deny_list[i].nr == nr)
            return deny_list[i].name;
    return NULL;
}

static void log_denied(int nr, unsigned int arch, unsigned long long ip)
{
    const char *name = lookup_name(nr);
    put_str("SECCOMP_DENY nr=");
    put_uint((unsigned long long)(unsigned int)nr);
    put_str(" name=");
    put_str(name ? name : "?");
    put_str(" arch=");
    put_hex(arch);
    put_str(" ip=");
    put_hex(ip);
    put_str("\n");
}

static void on_sigsys(int sig, siginfo_t *info, void *ctx)
{
    (void)sig;
    (void)ctx;
    if (info && info->si_code == SYS_SECCOMP)
        log_denied(info->si_syscall, info->si_arch, (unsigned long long)(uintptr_t)info->si_addr);
    else
        put_str("SECCOMP_SIGSYS non-seccomp\n");
    _exit(159);
}

static int install_sigsys_handler(void)
{
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = on_sigsys;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);
    return sigaction(SIGSYS, &sa, NULL);
}

static int parse_filter(const char *path)
{
    FILE *fp = fopen(path, "r");
    char line[512];
    if (!fp) {
        fprintf(stderr, "seccomp_launch: cannot open filter %s: %s\n", path, strerror(errno));
        return -1;
    }
    while (fgets(line, sizeof(line), fp)) {
        char action[16], name[MAX_NAME];
        int nr;
        if (line[0] == '#' || line[0] == '\n')
            continue;
        if (sscanf(line, "%15s %d %63s", action, &nr, name) != 3)
            continue;
        if (strcmp(action, "allow") == 0) {
            if (allow_count >= MAX_SYSCALLS)
                goto overflow;
            allow_list[allow_count].nr = nr;
            snprintf(allow_list[allow_count].name, MAX_NAME, "%s", name);
            allow_count++;
        } else if (strcmp(action, "name") == 0) {
            if (name_count >= 1024)
                goto overflow;
            name_list[name_count].nr = nr;
            snprintf(name_list[name_count].name, MAX_NAME, "%s", name);
            name_count++;
        } else if (strcmp(action, "deny") == 0) {
            if (deny_count >= MAX_SYSCALLS)
                goto overflow;
            deny_list[deny_count].nr = nr;
            snprintf(deny_list[deny_count].name, MAX_NAME, "%s", name);
            deny_count++;
        }
    }
    fclose(fp);
    return 0;
overflow:
    fclose(fp);
    fprintf(stderr, "seccomp_launch: filter exceeds %d entries\n", MAX_SYSCALLS);
    return -1;
}

static int is_allowed(int nr)
{
    for (int i = 0; i < allow_count; i++)
        if (allow_list[i].nr == nr)
            return 1;
    return 0;
}

static void add_bootstrap_allow(int nr, const char *name)
{
    if (allow_count >= MAX_SYSCALLS || is_allowed(nr))
        return;
    allow_list[allow_count].nr = nr;
    snprintf(allow_list[allow_count].name, MAX_NAME, "%s", name);
    allow_count++;
}

/* The supervisor needs to pass the listener fd and read the ack after the
 * filter is installed.  These calls are already allowed by every AOSP app
 * profile; adding them keeps the notify bootstrap from deadlocking. */
static void add_notify_bootstrap(void)
{
    add_bootstrap_allow(SYS_read, "read");
    add_bootstrap_allow(SYS_write, "write");
    add_bootstrap_allow(SYS_close, "close");
    add_bootstrap_allow(SYS_sendmsg, "sendmsg");
    add_bootstrap_allow(SYS_recvmsg, "recvmsg");
    add_bootstrap_allow(SYS_exit, "exit");
    add_bootstrap_allow(SYS_exit_group, "exit_group");
    add_bootstrap_allow(SYS_rt_sigreturn, "rt_sigreturn");
}

static int build_filter(struct sock_filter *out, int capacity, uint32_t default_action)
{
    int n = 0;
    if (capacity < 2 * (allow_count + deny_count) + 4)
        return -1;
    out[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                                            offsetof(struct seccomp_data, arch));
    out[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                                            NATIVE_AUDIT_ARCH, 1, 0);
    out[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    out[n++] = (struct sock_filter)BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                                            offsetof(struct seccomp_data, nr));
    /* Denies come first so a conflicting hand-written filter fails closed. */
    for (int i = 0; i < deny_count; i++) {
        out[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                                                (uint32_t)deny_list[i].nr, 0, 1);
        out[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    }
    for (int i = 0; i < allow_count; i++) {
        out[n++] = (struct sock_filter)BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                                                (uint32_t)allow_list[i].nr, 0, 1);
        out[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    }
    out[n++] = (struct sock_filter)BPF_STMT(BPF_RET | BPF_K, default_action);
    return n;
}

static int set_nnp(void)
{
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0) {
        fprintf(stderr, "seccomp_launch: PR_SET_NO_NEW_PRIVS: %s\n", strerror(errno));
        return -1;
    }
    return 0;
}

static int install_filter(const struct sock_fprog *prog)
{
    return (int)syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, prog);
}

static void send_fd(int sock, int fd)
{
    char buf[1] = {'F'};
    struct iovec iov = {.iov_base = buf, .iov_len = 1};
    char control[CMSG_SPACE(sizeof(int))];
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd, sizeof(int));
    (void)sendmsg(sock, &msg, 0);
}

static int recv_fd(int sock)
{
    char buf[1];
    struct iovec iov = {.iov_base = buf, .iov_len = 1};
    char control[CMSG_SPACE(sizeof(int))];
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);
    if (recvmsg(sock, &msg, 0) <= 0)
        return -1;
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_type != SCM_RIGHTS)
        return -1;
    int fd;
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(int));
    return fd;
}

static int run_supervised(struct sock_fprog *prog, int trace_continue,
                          char *const argv[])
{
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sv) < 0) {
        fprintf(stderr, "seccomp_launch: socketpair: %s\n", strerror(errno));
        return 126;
    }
    pid_t pid = fork();
    if (pid < 0) {
        fprintf(stderr, "seccomp_launch: fork: %s\n", strerror(errno));
        return 126;
    }
    if (pid == 0) {
        close(sv[0]);
        if (set_nnp() < 0)
            _exit(126);
        int listener = (int)syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER,
                                    SECCOMP_FILTER_FLAG_NEW_LISTENER, prog);
        if (listener < 0) {
            fprintf(stderr, "seccomp_launch: seccomp NEW_LISTENER: %s\n", strerror(errno));
            _exit(126);
        }
        send_fd(sv[1], listener);
        char ack;
        (void)read(sv[1], &ack, 1);
        close(listener);
        close(sv[1]);
        execvp(argv[0], argv);
        fprintf(stderr, "seccomp_launch: exec %s: %s\n", argv[0], strerror(errno));
        _exit(126);
    }

    close(sv[1]);
    int listener = recv_fd(sv[0]);
    if (listener < 0) {
        fprintf(stderr, "seccomp_launch: did not receive listener fd\n");
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        return 126;
    }
    char ack = 'A';
    (void)write(sv[0], &ack, 1);
    close(sv[0]);

    int status = 0;
    for (;;) {
        pid_t done = waitpid(pid, &status, WNOHANG);
        if (done == pid)
            break;
        struct pollfd pfd = {.fd = listener, .events = POLLIN};
        int ready = poll(&pfd, 1, 100);
        if (ready <= 0)
            continue;
        struct seccomp_notif req;
        memset(&req, 0, sizeof(req));
        if (ioctl(listener, SECCOMP_IOCTL_NOTIF_RECV, &req) < 0)
            continue;
        log_denied(req.data.nr, req.data.arch, req.data.instruction_pointer);
        struct seccomp_notif_resp resp;
        memset(&resp, 0, sizeof(resp));
        resp.id = req.id;
        if (trace_continue) {
            resp.flags = SECCOMP_USER_NOTIF_FLAG_CONTINUE;
        } else {
            resp.error = -ENOSYS;
        }
        if (ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &resp) < 0 && errno != ENOENT) {
            put_str("SECCOMP_NOTIF_SEND_FAIL nr=");
            put_uint((unsigned long long)(unsigned int)req.data.nr);
            put_str(" errno=");
            put_uint((unsigned long long)errno);
            put_str("\n");
            memset(&resp, 0, sizeof(resp));
            resp.id = req.id;
            resp.error = -ENOSYS;
            (void)ioctl(listener, SECCOMP_IOCTL_NOTIF_SEND, &resp);
        }
    }
    close(listener);
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    if (WIFSIGNALED(status))
        return 128 + WTERMSIG(status);
    return 126;
}

static void usage(void)
{
    fprintf(stderr,
            "usage: seccomp_launch --filter FILE --mode enforce|trace|baseline "
            "[--log FILE] [--print] -- CMD [ARGS...]\n");
}

int main(int argc, char **argv)
{
    const char *filter_path = NULL;
    const char *log_path = NULL;
    const char *mode = "enforce";
    int print_only = 0;
    int i = 1;

    for (; i < argc; i++) {
        if (strcmp(argv[i], "--") == 0) {
            i++;
            break;
        } else if (strcmp(argv[i], "--filter") == 0 && i + 1 < argc) {
            filter_path = argv[++i];
        } else if (strcmp(argv[i], "--log") == 0 && i + 1 < argc) {
            log_path = argv[++i];
        } else if (strcmp(argv[i], "--mode") == 0 && i + 1 < argc) {
            mode = argv[++i];
        } else if (strcmp(argv[i], "--print") == 0) {
            print_only = 1;
        } else if (strcmp(argv[i], "--help") == 0) {
            usage();
            return 0;
        } else {
            usage();
            return 2;
        }
    }
    if (i >= argc) {
        usage();
        return 2;
    }

    if (log_path) {
        log_fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (log_fd < 0) {
            fprintf(stderr, "seccomp_launch: open log %s: %s\n", log_path, strerror(errno));
            return 2;
        }
    }

    if (strcmp(mode, "baseline") == 0 && !filter_path) {
        execvp(argv[i], &argv[i]);
        fprintf(stderr, "seccomp_launch: exec %s: %s\n", argv[i], strerror(errno));
        return 126;
    }
    if (!filter_path) {
        usage();
        return 2;
    }
    if (parse_filter(filter_path) < 0)
        return 2;

    if (print_only) {
        printf("seccomp_launch allow=%d deny=%d names=%d mode=%s arch=0x%x\n",
               allow_count, deny_count, name_count, mode, NATIVE_AUDIT_ARCH);
        return 0;
    }

    if (strcmp(mode, "baseline") == 0) {
        execvp(argv[i], &argv[i]);
        fprintf(stderr, "seccomp_launch: exec %s: %s\n", argv[i], strerror(errno));
        return 126;
    }

    int trace_continue;
    uint32_t default_action;
    if (strcmp(mode, "enforce") == 0) {
        trace_continue = 0;
        default_action = SECCOMP_RET_TRAP;
    } else if (strcmp(mode, "trace") == 0) {
        trace_continue = 1;
        default_action = SECCOMP_RET_USER_NOTIF;
    } else if (strcmp(mode, "kill") == 0) {
        trace_continue = 0;
        default_action = SECCOMP_RET_USER_NOTIF;
    } else {
        usage();
        return 2;
    }

    if (default_action == SECCOMP_RET_USER_NOTIF)
        add_notify_bootstrap();

    static struct sock_filter bpf[2 * MAX_SYSCALLS + 8];
    int bpf_len = build_filter(bpf, (int)(sizeof(bpf) / sizeof(bpf[0])), default_action);
    if (bpf_len < 0) {
        fprintf(stderr, "seccomp_launch: filter too large\n");
        return 2;
    }
    struct sock_fprog prog = {.len = (unsigned short)bpf_len, .filter = bpf};

    if (default_action == SECCOMP_RET_TRAP) {
        /* The handler only covers the pre-exec window: POSIX resets caught
         * dispositions on exec, so a denied syscall in the target is a bare
         * SIGSYS.  Use --mode trace to learn the syscall name. */
        if (install_sigsys_handler() < 0) {
            fprintf(stderr, "seccomp_launch: sigaction: %s\n", strerror(errno));
            return 2;
        }
        if (set_nnp() < 0)
            return 2;
        if (install_filter(&prog) < 0) {
            fprintf(stderr, "seccomp_launch: seccomp: %s\n", strerror(errno));
            return 2;
        }
        execvp(argv[i], &argv[i]);
        fprintf(stderr, "seccomp_launch: exec %s: %s\n", argv[i], strerror(errno));
        return 126;
    }

    return run_supervised(&prog, trace_continue, &argv[i]);
}
