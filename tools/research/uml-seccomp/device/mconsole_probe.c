/*
 * mconsole_probe - minimal UML mconsole client for the adb-shell channel.
 *
 * C mirror of mconsole_probe.py (the shell domain has no python3).  The UML
 * mconsole wire format (arch/um/drivers/mconsole.h) is a fixed struct pair,
 * native endianness, not a text protocol:
 *   request: u32 magic, u32 version, u32 len, then len bytes of payload;
 *   reply:   u32 err, u32 more, u32 len, then a 512-byte data field.
 *
 * Usage: mconsole_probe --socket PATH [--timeout SEC] [--command version]
 *                       [--client-path PATH]
 * Prints MCONSOLE_OK ... on a well-formed err==0 reply and exits 0;
 * MCONSOLE_FAIL ... and exit 1 otherwise (same contract as the python probe).
 *
 * The client binds a return socket at <dir-of-socket>/mconsole-client-<pid>.sock
 * (override with --client-path).  The bind is best effort: an unbound AF_UNIX
 * dgram sender gets an abstract address and UML replies to it all the same.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MCONSOLE_MAGIC 0xCAFEBABEu
#define MCONSOLE_VERSION 2
#define MCONSOLE_MAX_DATA 512

static double now_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

static void msleep(long ms)
{
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000L;
    nanosleep(&ts, NULL);
}

/* Derive "<dir>" from "path/to/file" ("/tmp" for "/tmp/x.sock"). */
static void dir_of(const char *path, char *out, size_t outlen)
{
    const char *slash = strrchr(path, '/');
    if (!slash || slash == path)
        snprintf(out, outlen, "%s", slash ? "/" : ".");
    else
        snprintf(out, outlen, "%.*s", (int)(slash - path), path);
}

static void put_u32(unsigned char *p, uint32_t v)
{
    /* Native endianness: the kernel reads a plain u32 field. */
    memcpy(p, &v, sizeof(v));
}

static void get_u32(const unsigned char *p, uint32_t *v)
{
    memcpy(v, p, sizeof(*v));
}

int main(int argc, char **argv)
{
    const char *sock_path = NULL;
    const char *command = "version";
    const char *client_arg = NULL;
    char client_path[108];
    double timeout = 5.0;
    int i = 1;

    while (i < argc) {
        if (strcmp(argv[i], "--socket") == 0 && i + 1 < argc) {
            sock_path = argv[++i];
        } else if (strcmp(argv[i], "--command") == 0 && i + 1 < argc) {
            command = argv[++i];
        } else if (strcmp(argv[i], "--timeout") == 0 && i + 1 < argc) {
            timeout = strtod(argv[++i], NULL);
        } else if (strcmp(argv[i], "--client-path") == 0 && i + 1 < argc) {
            client_arg = argv[++i];
        } else {
            fprintf(stderr, "mconsole_probe: unknown argument %s\n", argv[i]);
            return 2;
        }
        i++;
    }
    if (!sock_path || sock_path[0] != '/') {
        fprintf(stderr, "usage: mconsole_probe --socket /abs/path [--timeout s] "
                        "[--command c] [--client-path p]\n");
        return 2;
    }
    if (strlen(sock_path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        fprintf(stderr, "mconsole_probe: socket path longer than sun_path\n");
        return 2;
    }
    if (client_arg) {
        snprintf(client_path, sizeof(client_path), "%s", client_arg);
    } else {
        char dir[108];
        dir_of(sock_path, dir, sizeof(dir));
        /* "/mconsole-client-<pid>.sock" needs ~24 bytes; refuse a dir that
         * would force truncation instead of silently binding a short path. */
        if (strlen(dir) + 24 >= sizeof(client_path)) {
            fprintf(stderr, "mconsole_probe: socket dir too long for the "
                            "client path (pass --client-path)\n");
            return 2;
        }
        snprintf(client_path, sizeof(client_path), "%s/mconsole-client-%d.sock",
                 dir, (int)getpid());
    }
    if (strlen(client_path) >= sizeof(((struct sockaddr_un *)0)->sun_path)) {
        fprintf(stderr, "mconsole_probe: client path longer than sun_path "
                        "(pass --client-path)\n");
        return 2;
    }

    double deadline = now_s() + timeout;
    while (now_s() < deadline) {
        struct stat sb;
        if (stat(sock_path, &sb) != 0) {
            msleep(50); /* socket appears after the kernel's mconsole_init */
            continue;
        }

        int s = socket(AF_UNIX, SOCK_DGRAM, 0);
        if (s < 0) {
            printf("MCONSOLE_FAIL errno=%s\n", strerror(errno));
            return 1;
        }
        struct sockaddr_un cl;
        memset(&cl, 0, sizeof(cl));
        cl.sun_family = AF_UNIX;
        memcpy(cl.sun_path, client_path, strlen(client_path));
        unlink(client_path);
        /* Best effort, like the python probe: on failure the socket stays
         * unbound and the kernel replies to the abstract address. */
        (void)bind(s, (struct sockaddr *)&cl, sizeof(cl));

        struct sockaddr_un dst;
        memset(&dst, 0, sizeof(dst));
        dst.sun_family = AF_UNIX;
        memcpy(dst.sun_path, sock_path, strlen(sock_path));

        size_t dlen = strlen(command);
        if (dlen > MCONSOLE_MAX_DATA)
            dlen = MCONSOLE_MAX_DATA;
        unsigned char req[12 + MCONSOLE_MAX_DATA];
        put_u32(req + 0, MCONSOLE_MAGIC);
        put_u32(req + 4, MCONSOLE_VERSION);
        put_u32(req + 8, (uint32_t)dlen);
        memcpy(req + 12, command, dlen);

        double left = deadline - now_s();
        if (left < 0.2)
            left = 0.2;
        struct timeval tv;
        tv.tv_sec = (time_t)left;
        tv.tv_usec = (suseconds_t)((left - (double)tv.tv_sec) * 1e6);
        setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

        ssize_t r = sendto(s, req, (ssize_t)(12 + dlen), 0,
                           (struct sockaddr *)&dst, sizeof(dst));
        unsigned char reply[12 + MCONSOLE_MAX_DATA];
        if (r >= 0)
            r = recv(s, reply, sizeof(reply), 0);
        close(s);
        unlink(client_path);

        if (r < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)
                continue; /* socket timed out: retry until the deadline */
            printf("MCONSOLE_FAIL errno=%s\n", strerror(errno));
            return 1;
        }
        if (r < 12) {
            printf("MCONSOLE_FAIL short-reply=%d\n", (int)r);
            return 1;
        }
        uint32_t err, more, len;
        get_u32(reply + 0, &err);
        get_u32(reply + 4, &more);
        get_u32(reply + 8, &len);
        if (err) {
            size_t blen = len;
            if (blen > (size_t)r - 12)
                blen = (size_t)r - 12;
            printf("MCONSOLE_FAIL err=%u body=%.*s\n", err, (int)blen,
                   (const char *)reply + 12);
            return 1;
        }
        size_t blen = len;
        if (blen > (size_t)r - 12)
            blen = (size_t)r - 12;
        printf("MCONSOLE_OK more=%u len=%u body=%.*s\n", more, len, (int)blen,
               (const char *)reply + 12);
        return 0;
    }
    printf("MCONSOLE_FAIL timeout\n");
    return 1;
}
