/*
 * run_with_fd - exec a command with a pre-opened unix socketpair fd.
 *
 * C mirror of run_with_fd.py for the adb-shell channel: the shell domain has
 * no python3, so the vector-net fd transport needs a static helper.  Behavior
 * is byte-for-byte the same as the python original:
 *   - socketpair(AF_UNIX, dgram|stream) creates both ends;
 *   - one end is dup2'ed onto --fd (dup2 clears CLOEXEC);
 *   - the peer is NOT closed and carries no CLOEXEC, so both ends survive
 *     execve and the exec'd UML kernel holds fd=<n> plus its peer;
 *   - the process then becomes the command via execvp.
 *
 * Usage: run_with_fd [--fd N] [--type dgram|stream] -- CMD [ARGS...]
 * Exit: 0/child status via exec replacement; 2 usage; 126 socket/exec failure.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <unistd.h>

static void usage(void)
{
    fprintf(stderr, "usage: run_with_fd [--fd N] [--type dgram|stream] -- CMD [ARGS...]\n");
}

int main(int argc, char **argv)
{
    int fd = 3;
    int type = SOCK_DGRAM;
    int i = 1;

    while (i < argc) {
        if (strcmp(argv[i], "--fd") == 0 && i + 1 < argc) {
            fd = atoi(argv[++i]);
            i++;
        } else if (strcmp(argv[i], "--type") == 0 && i + 1 < argc) {
            i++;
            if (strcmp(argv[i], "stream") == 0)
                type = SOCK_STREAM;
            else if (strcmp(argv[i], "dgram") != 0) {
                usage();
                return 2;
            }
            i++;
        } else if (strcmp(argv[i], "--") == 0) {
            i++;
            break;
        } else {
            break; /* first command token; mirrors argparse REMAINDER */
        }
    }
    if (i >= argc) {
        usage();
        return 2;
    }

    int sv[2];
    if (socketpair(AF_UNIX, type, 0, sv) < 0) {
        fprintf(stderr, "run_with_fd: socketpair: %s\n", strerror(errno));
        return 126;
    }
    if (dup2(sv[0], fd) < 0) {
        fprintf(stderr, "run_with_fd: dup2: %s\n", strerror(errno));
        return 126;
    }
    /* Keep the peer open so fd has a valid peer after exec: neither end may
     * carry CLOEXEC (dup2 already clears it on the target; socketpair(2) sets
     * no CLOEXEC on either descriptor). */
    execvp(argv[i], &argv[i]);
    fprintf(stderr, "run_with_fd: exec %s: %s\n", argv[i], strerror(errno));
    return 126;
}
