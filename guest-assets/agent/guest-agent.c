#define _POSIX_C_SOURCE 200809L

#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#if !defined(_WIN32)
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

/*
 * The vsock serving mode (Ticket 37: the guest-side half of the AVF data and
 * control planes). Production compiles against AF_VSOCK; a test build with
 * -DDROIDLAYER_TEST_AF_INET swaps the socket family to loopback TCP so the
 * Linux process tests can drive the exact same serving logic on hosts
 * without a vsock namespace. Only the socket setup differs — every serving
 * path below is family-agnostic.
 */
#if defined(DROIDLAYER_TEST_AF_INET)
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#else
#include <sys/socket.h> /* sockaddr/sa_family_t must precede the vsock header */
#include <linux/vm_sockets.h>
#endif
#endif

#define TOKEN_HEX_LENGTH 64U
#define MAX_TERMINAL_COLUMNS 1000
#define MAX_TERMINAL_ROWS 1000
#define MAX_CONTROL_LINE 512U

static int is_lower_hex_token(const char *token) {
    size_t index;
    if (strlen(token) != TOKEN_HEX_LENGTH) {
        return 0;
    }
    for (index = 0; index < TOKEN_HEX_LENGTH; ++index) {
        unsigned char character = (unsigned char)token[index];
        if (!isdigit(character) && !(character >= 'a' && character <= 'f')) {
            return 0;
        }
    }
    return 1;
}

static int constant_time_token_equals(const char *expected, const char *provided) {
    size_t index;
    unsigned int difference = 0U;
    for (index = 0; index < TOKEN_HEX_LENGTH; ++index) {
        difference |= (unsigned int)((unsigned char)expected[index]
                                     ^ (unsigned char)provided[index]);
    }
    return difference == 0U;
}

static int read_token(const char *path, char token[TOKEN_HEX_LENGTH + 2U]) {
    FILE *token_file = fopen(path, "rb");
    size_t length;
    if (token_file == NULL) {
        return 0;
    }
    if (fgets(token, (int)TOKEN_HEX_LENGTH + 2, token_file) == NULL) {
        fclose(token_file);
        return 0;
    }
    fclose(token_file);
    length = strcspn(token, "\r\n");
    token[length] = '\0';
    return is_lower_hex_token(token) ? 1 : -1;
}

/* Reads one control line without consuming the bytes that follow its newline. */
static int read_line_fd(int stream_fd, char *request, size_t request_size) {
    size_t length = 0U;
    while (length + 1U < request_size) {
        ssize_t bytes_read = read(stream_fd, &request[length], 1U);
        if (bytes_read == 0) {
            return length == 0U ? 0 : -1;
        }
        if (bytes_read < 0) {
            return -1;
        }
        ++length;
        if (request[length - 1U] == '\n') {
            request[length - 1U] = '\0';
            if (length > 1U && request[length - 2U] == '\r') {
                request[length - 2U] = '\0';
            }
            return 1;
        }
    }
    return -1;
}

static int write_control_line_fd(int stream_fd, const char *line) {
    size_t length = strlen(line);
    ssize_t written = write(stream_fd, line, length);
    if (written != (ssize_t)length) {
        return 0;
    }
    return 1;
}

static int parse_resize(const char *request, int *terminal_columns, int *terminal_rows) {
    char extra = '\0';
    int columns;
    int rows;

    if (sscanf(request, "DROIDLAYER/RESIZE %d %d %c", &columns, &rows, &extra) != 2
        || columns < 1 || columns > MAX_TERMINAL_COLUMNS
        || rows < 1 || rows > MAX_TERMINAL_ROWS) {
        return 0;
    }
    *terminal_columns = columns;
    *terminal_rows = rows;
    return 1;
}

static int resize_terminal(const char *terminal_device, int terminal_columns, int terminal_rows) {
#if !defined(_WIN32)
    struct winsize terminal_size;
    int terminal_descriptor;
    int result;

    terminal_descriptor = open(terminal_device, O_RDWR | O_NOCTTY);
    if (terminal_descriptor < 0) {
        return 0;
    }
    memset(&terminal_size, 0, sizeof(terminal_size));
    terminal_size.ws_col = (unsigned short)terminal_columns;
    terminal_size.ws_row = (unsigned short)terminal_rows;
    result = ioctl(terminal_descriptor, TIOCSWINSZ, &terminal_size) == 0;
    close(terminal_descriptor);
    return result;
#else
    (void)terminal_device;
    (void)terminal_columns;
    (void)terminal_rows;
    return 0;
#endif
}

static int serve_control_channel_fd(int read_fd, int write_fd, const char *terminal_device) {
    char request[MAX_CONTROL_LINE] = {0};
    int terminal_columns;
    int terminal_rows;
    int line_status;

    while (1) {
        line_status = read_line_fd(read_fd, request, sizeof(request));
        if (line_status == 0) {
            return 0;
        }
        if (line_status < 0) {
            write_control_line_fd(write_fd, "DROIDLAYER/ERROR malformed-command\n");
            return 4;
        }
        if (strcmp(request, "DROIDLAYER/PING") == 0) {
            if (!write_control_line_fd(write_fd, "DROIDLAYER/PONG 1\n")) {
                return 70;
            }
            continue;
        }
        if (parse_resize(request, &terminal_columns, &terminal_rows)) {
            if (!resize_terminal(terminal_device, terminal_columns, terminal_rows)) {
                write_control_line_fd(write_fd, "DROIDLAYER/ERROR resize-unavailable\n");
                return 4;
            }
            if (!write_control_line_fd(write_fd, "DROIDLAYER/OK resize\n")) {
                return 70;
            }
            continue;
        }
        write_control_line_fd(write_fd, "DROIDLAYER/ERROR malformed-command\n");
        return 4;
    }
}

/*
 * One authenticated Bridge v1 session over an already-connected descriptor:
 * the host initiates HELLO, the agent answers READY, then PING/RESIZE are
 * served until the peer closes. Same wire behavior on every transport.
 * authenticate_control returns 0 once READY went out (the caller then either
 * serves control commands or hands the stream to the guest shell); any other
 * value is the handshake exit code.
 */
static int authenticate_control(int read_fd, int write_fd, const char *expected_token) {
    char verb[32] = {0};
    char extra = '\0';
    char request[MAX_CONTROL_LINE] = {0};
    char provided_token[TOKEN_HEX_LENGTH + 2U] = {0};
    int minimum_version;
    int maximum_version;
    int handshake_status;

    handshake_status = read_line_fd(read_fd, request, sizeof(request));
    if (handshake_status != 1
        || sscanf(request,
                  "%31s %d %d %65s %c",
                  verb,
                  &minimum_version,
                  &maximum_version,
                  provided_token,
                  &extra)
               != 4
        || strcmp(verb, "DROIDLAYER/HELLO") != 0
        || !is_lower_hex_token(provided_token)) {
        write_control_line_fd(write_fd, "DROIDLAYER/ERROR malformed-handshake\n");
        return 1;
    }
    if (minimum_version < 0 || maximum_version < minimum_version) {
        write_control_line_fd(write_fd, "DROIDLAYER/ERROR malformed-handshake\n");
        return 1;
    }
    if (!constant_time_token_equals(expected_token, provided_token)) {
        write_control_line_fd(write_fd, "DROIDLAYER/ERROR unauthorized\n");
        return 3;
    }
    if (minimum_version > 1 || maximum_version < 1) {
        write_control_line_fd(write_fd, "DROIDLAYER/ERROR unsupported-version\n");
        return 2;
    }

    if (!write_control_line_fd(write_fd, "DROIDLAYER/READY 1\n")) {
        return 70;
    }
    return 0;
}

#if !defined(_WIN32)

/*
 * vsock (or test-loopback) serving mode: the VM may only LISTEN, the owner
 * app connects — so the agent listens on the announced ports and serves each
 * accepted channel. One forked child per connection keeps the listeners alive
 * for reconnects (guest agent restarts, host retry windows).
 */
static int listen_on_port(int port) {
    int listener;
    int reuse = 1;
#if defined(DROIDLAYER_TEST_AF_INET)
    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons((unsigned short)port);
    listener = socket(AF_INET, SOCK_STREAM, 0);
#else
    struct sockaddr_vm address;
    memset(&address, 0, sizeof(address));
    address.svm_family = AF_VSOCK;
    address.svm_cid = VMADDR_CID_ANY;
    address.svm_port = (unsigned int)port;
    listener = socket(AF_VSOCK, SOCK_STREAM, 0);
#endif
    if (listener < 0) {
        return -1;
    }
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    if (bind(listener, (struct sockaddr *)&address, sizeof(address)) != 0
        || listen(listener, 4) != 0) {
        close(listener);
        return -1;
    }
    return listener;
}

/* The 9p data plane: the accepted socket fd becomes the trans=fd transport. */
static void mount_share_with_connection(int connection_fd, const char *tag, const char *mount_point) {
    if (connection_fd != 3 && dup2(connection_fd, 3) < 0) {
        close(connection_fd);
        _exit(71);
    }
    execlp("mount", "mount", "-t", "9p",
           "-o", "trans=fd,rfdno=3,wfdno=3,version=9p2000.L,ro",
           tag, mount_point, (char *)NULL);
    _exit(71);
}

/* Terminal relay: raw bytes between the vsock connection and the console. */
static void relay_terminal_channel(int connection_fd, const char *terminal_device) {
    struct pollfd watched[2];
    char buffer[4096];
    int terminal_descriptor;

    terminal_descriptor = open(terminal_device, O_RDWR | O_NOCTTY);
    if (terminal_descriptor < 0) {
        _exit(72);
    }
#if !defined(DROIDLAYER_TEST_AF_INET)
    {
        struct termios raw_settings;
        if (tcgetattr(terminal_descriptor, &raw_settings) == 0) {
            /* cfmakeraw() expanded: it is a BSD extension, not POSIX. */
            raw_settings.c_iflag &=
                    (tcflag_t)~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON);
            raw_settings.c_oflag &= (tcflag_t)~OPOST;
            raw_settings.c_lflag &= (tcflag_t)~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
            raw_settings.c_cflag &= (tcflag_t)~(CSIZE | PARENB);
            raw_settings.c_cflag |= CS8;
            tcsetattr(terminal_descriptor, TCSANOW, &raw_settings);
        }
    }
#endif

    watched[0].fd = connection_fd;
    watched[0].events = POLLIN;
    watched[1].fd = terminal_descriptor;
    watched[1].events = POLLIN;
    while (1) {
        ssize_t moved;
        int ready = poll(watched, 2, -1);
        if (ready < 0) {
            continue;
        }
        if (watched[0].revents & (POLLIN | POLLHUP)) {
            moved = read(connection_fd, buffer, sizeof(buffer));
            if (moved <= 0) {
                _exit(0);
            }
            if (write(terminal_descriptor, buffer, (size_t)moved) != moved) {
                _exit(72);
            }
        }
        if (watched[1].revents & POLLIN) {
            moved = read(terminal_descriptor, buffer, sizeof(buffer));
            if (moved > 0) {
                if (write(connection_fd, buffer, (size_t)moved) != moved) {
                    _exit(0);
                }
            } else if (moved < 0 && errno != EINTR && errno != EAGAIN) {
                _exit(72);
            } else if (moved == 0) {
                /* Character devices never hit EOF here; a zero read means a
                 * non-blocking-style source (test fixtures use plain files).
                 * Yield instead of spinning. */
                struct timespec pause = {0, 10 * 1000 * 1000};
                nanosleep(&pause, NULL);
            }
        }
    }
}

static int serve_vsock_mode(
        const char *expected_token,
        const char *terminal_device,
        int control_port,
        int terminal_port,
        int share_port,
        const char *share_tag,
        const char *share_mount) {
    int control_listener;
    int terminal_listener;
    int share_listener;

    if (terminal_port > 0 && terminal_device == NULL) {
        return 64; /* a relayed terminal channel needs its device */
    }
    control_listener = listen_on_port(control_port);
    terminal_listener = terminal_port > 0 ? listen_on_port(terminal_port) : -1;
    share_listener = share_port > 0 ? listen_on_port(share_port) : -1;

    if (control_listener < 0 || (terminal_port > 0 && terminal_listener < 0)
        || (share_port > 0 && share_listener < 0)) {
        return 71;
    }
    signal(SIGCHLD, SIG_IGN); /* fire-and-forget children: no zombie army */

    while (1) {
        struct pollfd watched[3];
        int watched_count = 0;
        int control_slot = -1;
        int terminal_slot = -1;
        int share_slot = -1;
        int ready;
        int connection;

        watched[watched_count].fd = control_listener;
        watched[watched_count].events = POLLIN;
        control_slot = watched_count;
        watched_count++;
        if (terminal_listener >= 0) {
            watched[watched_count].fd = terminal_listener;
            watched[watched_count].events = POLLIN;
            terminal_slot = watched_count;
            watched_count++;
        }
        if (share_listener >= 0) {
            watched[watched_count].fd = share_listener;
            watched[watched_count].events = POLLIN;
            share_slot = watched_count;
            watched_count++;
        }
        ready = poll(watched, (nfds_t)watched_count, -1);
        if (ready < 0) {
            continue;
        }
        if (watched[control_slot].revents & POLLIN) {
            connection = accept(control_listener, NULL, NULL);
            if (connection >= 0) {
                pid_t worker = fork();
                if (worker == 0) {
                    int authenticated;
                    close(control_listener);
                    if (terminal_listener >= 0) {
                        close(terminal_listener);
                    }
                    if (share_listener >= 0) {
                        close(share_listener);
                    }
                    authenticated = authenticate_control(connection, connection, expected_token);
                    if (authenticated != 0) {
                        _exit(authenticated);
                    }
                    _exit(serve_control_channel_fd(connection, connection, terminal_device));
                }
                close(connection);
            }
        }
        if (terminal_slot >= 0 && (watched[terminal_slot].revents & POLLIN)) {
            connection = accept(terminal_listener, NULL, NULL);
            if (connection >= 0) {
                pid_t worker = fork();
                if (worker == 0) {
                    close(control_listener);
                    close(terminal_listener);
                    if (share_listener >= 0) {
                        close(share_listener);
                    }
                    relay_terminal_channel(connection, terminal_device);
                    _exit(0);
                }
                close(connection);
            }
        }
        if (share_slot >= 0 && (watched[share_slot].revents & POLLIN)) {
            connection = accept(share_listener, NULL, NULL);
            if (connection >= 0) {
                pid_t worker = fork();
                if (worker == 0) {
                    close(control_listener);
                    if (terminal_listener >= 0) {
                        close(terminal_listener);
                    }
                    close(share_listener);
                    mount_share_with_connection(connection, share_tag, share_mount);
                }
                close(connection);
                /* Exactly one mount per boot: stop advertising the port. */
                share_listener = -1;
                share_slot = -1;
            }
        }
    }
}

#endif /* !defined(_WIN32) */

int main(int argument_count, char **arguments) {
    char expected_token[TOKEN_HEX_LENGTH + 2U] = {0};
    const char *terminal_device = NULL;
    const char *token_path = NULL;
    /* vsock mode parameters (all zero/NULL = char-device mode, unchanged). */
    int vsock_mode = 0;
    int control_port = 0;
    int terminal_port = 0;
    int share_port = 0;
    const char *share_tag = "downloads";
    const char *share_mount = "/mnt/downloads";
    int index;
    int token_status;

    for (index = 1; index < argument_count; ++index) {
        const char *argument = arguments[index];
        if (strcmp(argument, "--token-file") == 0 && index + 1 < argument_count) {
            token_path = arguments[++index];
        } else if (strcmp(argument, "--terminal-device") == 0
                   && index + 1 < argument_count && arguments[index + 1][0] != '\0') {
            terminal_device = arguments[++index];
        } else if (strcmp(argument, "--vsock-control-port") == 0
                   && index + 1 < argument_count) {
            control_port = atoi(arguments[++index]);
            vsock_mode = 1;
        } else if (strcmp(argument, "--vsock-terminal-port") == 0
                   && index + 1 < argument_count) {
            terminal_port = atoi(arguments[++index]);
        } else if (strcmp(argument, "--vsock-share-port") == 0
                   && index + 1 < argument_count) {
            share_port = atoi(arguments[++index]);
        } else if (strcmp(argument, "--share-tag") == 0
                   && index + 1 < argument_count && arguments[index + 1][0] != '\0') {
            share_tag = arguments[++index];
        } else if (strcmp(argument, "--share-mount") == 0
                   && index + 1 < argument_count && arguments[index + 1][0] != '\0') {
            share_mount = arguments[++index];
        } else {
            fputs("DROIDLAYER/ERROR usage\n", stderr);
            return 64;
        }
    }
    if (token_path == NULL) {
        fputs("DROIDLAYER/ERROR usage\n", stderr);
        return 64;
    }

    token_status = read_token(token_path, expected_token);
    if (token_status == 0) {
        fputs("DROIDLAYER/ERROR token-unavailable\n", stderr);
        return 65;
    }
    if (token_status < 0) {
        fputs("DROIDLAYER/ERROR token-invalid\n", stderr);
        return 65;
    }

    if (vsock_mode) {
#if !defined(_WIN32)
        if (control_port <= 0) {
            fputs("DROIDLAYER/ERROR usage\n", stderr);
            return 64;
        }
        return serve_vsock_mode(
                expected_token,
                terminal_device,
                control_port,
                terminal_port,
                share_port,
                share_tag,
                share_mount);
#else
        /* The vsock serving surface is POSIX-only; on Windows the request is
         * refused honestly and the parsed parameters stay unused. */
        (void)control_port;
        (void)terminal_port;
        (void)share_port;
        (void)share_tag;
        (void)share_mount;
        fputs("DROIDLAYER/ERROR vsock-unavailable\n", stderr);
        return 70;
#endif
    }

    if (terminal_device != NULL) {
        int authenticated = authenticate_control(STDIN_FILENO, STDOUT_FILENO, expected_token);
        if (authenticated != 0) {
            return authenticated;
        }
        return serve_control_channel_fd(STDIN_FILENO, STDOUT_FILENO, terminal_device);
    }
    {
        int authenticated = authenticate_control(STDIN_FILENO, STDOUT_FILENO, expected_token);
        if (authenticated != 0) {
            return authenticated;
        }
    }
    execl("/bin/sh", "sh", (char *)NULL);
    fputs("DROIDLAYER/ERROR terminal-unavailable\n", stderr);
    return 70;
}
