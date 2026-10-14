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
#include <sys/stat.h>
#include <sys/un.h>
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
            if (errno == EINTR) continue;
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
    size_t offset = 0U;
    while (offset < length) {
        ssize_t written = write(stream_fd, line + offset, length - offset);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return 0;
        offset += (size_t)written;
    }
    return 1;
}

static int parse_resize(const char *request, int *terminal_columns, int *terminal_rows) {
    char extra = '\0';
    int columns;
    int rows;

    if ((sscanf(request, "ORCHESTELLA/RESIZE %d %d %c", &columns, &rows, &extra) != 2 && sscanf(request, "DROIDLAYER/RESIZE %d %d %c", &columns, &rows, &extra) != 2)
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

#if !defined(_WIN32)
#define ORCHESTELLA_BRIDGE_SOCK "/run/orchestella/bridge.sock"
#define DROIDLAYER_BRIDGE_SOCK "/run/droidlayer/bridge.sock"
#define MAX_IPC_CLIENTS 8

static int setup_bridge_ipc_socket(void) {
    int server_fd;
    struct sockaddr_un address;

    mkdir("/run", 0755);
    mkdir("/run/orchestella", 0755);
    mkdir("/run/droidlayer", 0755);
    unlink(ORCHESTELLA_BRIDGE_SOCK);
    unlink(DROIDLAYER_BRIDGE_SOCK);

    server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        return -1;
    }

    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    strncpy(address.sun_path, ORCHESTELLA_BRIDGE_SOCK, sizeof(address.sun_path) - 1);

    if (bind(server_fd, (struct sockaddr *)&address, sizeof(address)) != 0) {
        close(server_fd);
        return -1;
    }

    chmod(ORCHESTELLA_BRIDGE_SOCK, 0666);
    symlink(ORCHESTELLA_BRIDGE_SOCK, DROIDLAYER_BRIDGE_SOCK);

    if (listen(server_fd, MAX_IPC_CLIENTS) != 0) {
        close(server_fd);
        return -1;
    }

    int flags = fcntl(server_fd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(server_fd, F_SETFL, flags | O_NONBLOCK);
    }

    return server_fd;
}
#endif

#define MAX_PAYLOAD_LINE (384 * 1024U)
#define MAX_FEATURE_RESULT 4096U
#ifndef ORCHESTELLA_PROFILECTL_PATH
#define ORCHESTELLA_PROFILECTL_PATH "/usr/local/lib/orchestella/profilectl"
#endif

static int feature_profile_allowed(const char *profile) {
    return strcmp(profile, "desktop-labwc") == 0 || strcmp(profile, "podman") == 0
        || strcmp(profile, "code-server") == 0 || strcmp(profile, "docker-cli") == 0;
}

static int feature_action_allowed(const char *action) {
    return strcmp(action, "probe") == 0 || strcmp(action, "install") == 0
        || strcmp(action, "status") == 0 || strcmp(action, "start") == 0
        || strcmp(action, "stop") == 0 || strcmp(action, "cancel") == 0
        || strcmp(action, "resize") == 0 || strcmp(action, "expose") == 0
        || strcmp(action, "unexpose") == 0 || strcmp(action, "confirm-frame") == 0;
}

/* The helper only dispatches short jobs; package installation runs outside the control reader. */
static int run_feature_helper(const char *action, const char *profile,
                              const char *width, const char *height, const char *scale,
                              char result[MAX_FEATURE_RESULT + 1U]) {
#if !defined(_WIN32)
    int descriptors[2];
    pid_t child;
    size_t length = 0U;
    int valid = 1;
    int child_status = 0;
    struct timespec started, now;
    if (!feature_action_allowed(action) || !feature_profile_allowed(profile)
        || pipe(descriptors) != 0) return 0;
    child = fork();
    if (child < 0) { close(descriptors[0]); close(descriptors[1]); return 0; }
    if (child == 0) {
        int null_fd = open("/dev/null", O_RDWR);
        close(descriptors[0]);
        if (dup2(descriptors[1], STDOUT_FILENO) < 0) _exit(70);
        close(descriptors[1]);
        if (null_fd >= 0) {
            dup2(null_fd, STDIN_FILENO);
            dup2(null_fd, STDERR_FILENO);
            if (null_fd > STDERR_FILENO) close(null_fd);
        }
        /* No inherited VM/control/IPC descriptors or untrusted shell evaluation. */
        long bound = sysconf(_SC_OPEN_MAX);
        if (bound < 0 || bound > 65536) bound = 65536;
        for (int descriptor = 3; descriptor < bound; ++descriptor) close(descriptor);
        if (strcmp(action, "confirm-frame") == 0) {
            execl(ORCHESTELLA_PROFILECTL_PATH, "profilectl", action, profile, width, (char *)NULL);
        } else if (width != NULL) {
            execl(ORCHESTELLA_PROFILECTL_PATH, "profilectl", action, profile,
                  width, height, scale, (char *)NULL);
        } else {
            execl(ORCHESTELLA_PROFILECTL_PATH, "profilectl", action, profile, (char *)NULL);
        }
        _exit(69);
    }
    close(descriptors[1]);
    if (clock_gettime(CLOCK_MONOTONIC, &started) != 0) valid = 0;
    while (valid) {
        struct pollfd pending = { descriptors[0], POLLIN, 0 };
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0
            || now.tv_sec - started.tv_sec >= 3) { valid = 0; break; }
        int polled = poll(&pending, 1, 100);
        if (polled < 0 && errno == EINTR) continue;
        if (polled < 0) { valid = 0; break; }
        if (polled == 0) continue;
        char buffer[512];
        ssize_t count = read(descriptors[0], buffer, sizeof(buffer));
        if (count < 0 && errno == EINTR) continue;
        if (count < 0 || length + (size_t)count > MAX_FEATURE_RESULT) { valid = 0; break; }
        if (count == 0) break;
        memcpy(result + length, buffer, (size_t)count);
        length += (size_t)count;
    }
    close(descriptors[0]);
    while (valid) {
        pid_t completed = waitpid(child, &child_status, WNOHANG);
        if (completed == child) break;
        if (completed < 0 && errno != EINTR) { valid = 0; break; }
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0
            || now.tv_sec - started.tv_sec >= 3) { valid = 0; break; }
        poll(NULL, 0, 10);
    }
    if (!valid) {
        kill(child, SIGKILL);
        while (waitpid(child, &child_status, 0) < 0 && errno == EINTR) { }
    }
    if (!valid || !WIFEXITED(child_status) || WEXITSTATUS(child_status) != 0) return 0;
    while (length > 0 && (result[length - 1] == '\n' || result[length - 1] == '\r')) --length;
    if (length < 2 || result[0] != '{' || result[length - 1] != '}') return 0;
    for (size_t index = 0; index < length; ++index) {
        if ((unsigned char)result[index] < 0x20U) return 0;
    }
    result[length] = '\0';
    return 1;
#else
    (void)action; (void)profile; (void)width; (void)height; (void)scale; (void)result;
    return 0;
#endif
}

static int handle_features(int write_fd, const char *command) {
    int version = 0;
    char action[16] = {0};
    char profile[32] = {0};
    char extra = '\0';
    char result[MAX_FEATURE_RESULT + 1U];
    char generation[33] = {0};
    if (sscanf(command, "%d %15s %31s %32s %c", &version, action, profile, generation, &extra) == 4
        && version == 1 && strcmp(action, "CONFIRM-FRAME") == 0 && strcmp(profile, "desktop-labwc") == 0) {
        int valid = strlen(generation) == 32U;
        for (size_t index = 0; valid && index < 32U; ++index)
            valid = (generation[index] >= '0' && generation[index] <= '9')
                || (generation[index] >= 'a' && generation[index] <= 'f');
        if (!valid) return write_control_line_fd(write_fd, "ORCHESTELLA/ERROR malformed-command\n");
        if (!run_feature_helper("confirm-frame", "desktop-labwc", generation, NULL, NULL, result))
            return write_control_line_fd(write_fd, "ORCHESTELLA/ERROR display-confirm-unavailable\n");
        return write_control_line_fd(write_fd, "ORCHESTELLA/OK features ")
            && write_control_line_fd(write_fd, result) && write_control_line_fd(write_fd, "\n");
    }
    if (strlen(command) > 128U || sscanf(command, "%d %15s %31s %c", &version, action, profile, &extra) != 3)
        return write_control_line_fd(write_fd, "ORCHESTELLA/ERROR malformed-command\n");
    if (version != 1) return write_control_line_fd(write_fd, "ORCHESTELLA/ERROR unsupported-feature-version\n");
    for (char *value = action; *value != '\0'; ++value) *value = (char)tolower((unsigned char)*value);
    if (!feature_action_allowed(action) || strcmp(action, "resize") == 0 || strcmp(action, "confirm-frame") == 0 || !feature_profile_allowed(profile)
        || ((strcmp(action, "expose") == 0 || strcmp(action, "unexpose") == 0) && strcmp(profile, "code-server") != 0))
        return write_control_line_fd(write_fd, "ORCHESTELLA/ERROR unsupported-feature-command\n");
    if (!run_feature_helper(action, profile, NULL, NULL, NULL, result))
        return write_control_line_fd(write_fd, "ORCHESTELLA/ERROR features-unavailable\n");
    return write_control_line_fd(write_fd, "ORCHESTELLA/OK features ")
        && write_control_line_fd(write_fd, result) && write_control_line_fd(write_fd, "\n");
}

#if !defined(_WIN32) && !defined(ORCHESTELLA_TEST_POWER)
static int executable_power_program(const char *path) {
    struct stat info;
    unsigned char magic[4];
    FILE *program;
    size_t count;
    if (stat(path, &info) != 0 || !S_ISREG(info.st_mode) || access(path, X_OK) != 0) {
        return 0;
    }
    program = fopen(path, "rb");
    if (program == NULL) {
        return 0;
    }
    count = fread(magic, 1U, sizeof(magic), program);
    fclose(program);
    return count == sizeof(magic) && magic[0] == 0x7fU
            && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
}
#endif

/* Returns 0 for an acknowledged dispatch, 1 when unavailable, or 70 on reply failure.
 * Both existing v1 actions shut down the guest. No request bytes reach argv or a shell. */
static int dispatch_power_request(int write_fd) {
#if defined(ORCHESTELLA_TEST_POWER)
    /* Compile-time test seam: never executes power utilities, including on Windows. */
    const char *fault = getenv("ORCHESTELLA_TEST_POWER_FAULT");
    const char *record_path = getenv("ORCHESTELLA_TEST_POWER_RECORD");
    const char *mode = getenv("ORCHESTELLA_TEST_POWER_MODE");
    FILE *record;
    if (fault != NULL && (strcmp(fault, "access") == 0 || strcmp(fault, "fork") == 0
                          || strcmp(fault, "pipe") == 0)) {
        return 1;
    }
    if (record_path == NULL || (record = fopen(record_path, "ab")) == NULL) {
        return 1;
    }
    if ((fault != NULL && strcmp(fault, "reply") == 0)
        || !write_control_line_fd(write_fd, "ORCHESTELLA/OK power\n")) {
        fclose(record);
        return 70;
    }
    fputs(mode != NULL && strcmp(mode, "recovery") == 0
                  ? "/usr/lib/orchestella/busybox poweroff -f\n"
                  : "/sbin/poweroff\n", record);
    fclose(record);
    return 0;
#elif !defined(_WIN32)
    const char *program;
    char init_name[64] = {0};
    FILE *init_comm;
    int recovery;
    int gate[2];
    pid_t worker;
    char release = 'G';
    if (geteuid() != 0 || (init_comm = fopen("/proc/1/comm", "rb")) == NULL) {
        return 1;
    }
    if (fgets(init_name, sizeof(init_name), init_comm) == NULL) {
        fclose(init_comm);
        return 1;
    }
    fclose(init_comm);
    init_name[strcspn(init_name, "\r\n")] = '\0';
    recovery = strcmp(init_name, "systemd") != 0 && strcmp(init_name, "init") != 0;
    program = recovery ? "/usr/lib/orchestella/busybox" : "/sbin/poweroff";
    if (!executable_power_program(program) || pipe(gate) != 0) {
        return 1;
    }
    worker = fork();
    if (worker < 0) {
        close(gate[0]);
        close(gate[1]);
        return 1;
    }
    if (worker == 0) {
        ssize_t received;
        struct timespec pause = {0, 250000000L};
        close(gate[1]);
        do {
            received = read(gate[0], &release, 1U);
        } while (received < 0 && errno == EINTR);
        close(gate[0]);
        if (received != 1 || release != 'G') {
            _exit(0); /* No successful parent acknowledgement: do not shut down. */
        }
        while (nanosleep(&pause, &pause) != 0 && errno == EINTR) {}
        close(write_fd); /* Utility output must not pollute the bridge protocol. */
        if (recovery) {
            execl(program, "busybox", "poweroff", "-f", (char *)NULL);
        } else {
            execl(program, "poweroff", (char *)NULL);
        }
        fputs("ORCHESTELLA_GUEST_POWER_ERROR stage=exec\n", stderr);
        _exit(71);
    }
    close(gate[0]);
    if (!write_control_line_fd(write_fd, "ORCHESTELLA/OK power\n")) {
        close(gate[1]); /* EOF cancels the child before exec. */
        while (waitpid(worker, NULL, 0) < 0 && errno == EINTR) {}
        return 70;
    }
    /* The child cannot execute before the reply has been written. Allow it to drain. */
    while (write(gate[1], &release, 1U) < 0 && errno == EINTR) {}
    close(gate[1]);
    return 0;
#else
    (void)write_fd;
    return 1; /* Production Windows never invokes host power commands. */
#endif
}

/* The same v1 authentication rules apply to startup retries on an already-open channel. */
static int answer_control_hello(int write_fd, const char *request, const char *expected_token) {
    char verb[32] = {0};
    char extra = '\0';
    char provided_token[TOKEN_HEX_LENGTH + 2U] = {0};
    int minimum_version;
    int maximum_version;

    if (strlen(request) >= MAX_CONTROL_LINE
        || sscanf(request, "%31s %d %d %65s %c", verb, &minimum_version,
                  &maximum_version, provided_token, &extra) != 4
        || (strcmp(verb, "ORCHESTELLA/HELLO") != 0 && strcmp(verb, "DROIDLAYER/HELLO") != 0)
        || !is_lower_hex_token(provided_token)
        || minimum_version < 0 || maximum_version < minimum_version) {
        write_control_line_fd(write_fd, "ORCHESTELLA/ERROR malformed-handshake\n");
        return 1;
    }
    if (!constant_time_token_equals(expected_token, provided_token)) {
        write_control_line_fd(write_fd, "ORCHESTELLA/ERROR unauthorized\n");
        return 3;
    }
    if (minimum_version > 1 || maximum_version < 1) {
        write_control_line_fd(write_fd, "ORCHESTELLA/ERROR unsupported-version\n");
        return 2;
    }
    return write_control_line_fd(write_fd, "ORCHESTELLA/READY 1\n") ? 0 : 70;
}

static int serve_control_channel_fd(int read_fd, int write_fd, const char *terminal_device,
                                    const char *expected_token) {
    char *request = (char *)malloc(MAX_PAYLOAD_LINE);
    if (request == NULL) {
        return 71;
    }
    int terminal_columns;
    int terminal_rows;
    int line_status;

#if !defined(_WIN32)
    int ipc_server_fd = setup_bridge_ipc_socket();
    int ipc_clients[MAX_IPC_CLIENTS];
    int i;
    for (i = 0; i < MAX_IPC_CLIENTS; ++i) {
        ipc_clients[i] = -1;
    }
#endif

    while (1) {
#if !defined(_WIN32)
        struct pollfd poll_fds[2 + MAX_IPC_CLIENTS];
        int num_poll_fds = 0;
        int host_idx = -1;
        int ipc_server_idx = -1;
        int client_map[2 + MAX_IPC_CLIENTS];

        for (i = 0; i < 2 + MAX_IPC_CLIENTS; ++i) {
            client_map[i] = -1;
        }

        /* 1. Host bridge descriptor (read_fd) */
        poll_fds[num_poll_fds].fd = read_fd;
        poll_fds[num_poll_fds].events = POLLIN;
        host_idx = num_poll_fds++;

        /* 2. IPC server listener */
        if (ipc_server_fd >= 0) {
            poll_fds[num_poll_fds].fd = ipc_server_fd;
            poll_fds[num_poll_fds].events = POLLIN;
            ipc_server_idx = num_poll_fds++;
        }

        /* 3. Connected IPC clients */
        for (i = 0; i < MAX_IPC_CLIENTS; ++i) {
            if (ipc_clients[i] >= 0) {
                client_map[num_poll_fds] = i;
                poll_fds[num_poll_fds].fd = ipc_clients[i];
                poll_fds[num_poll_fds].events = POLLIN;
                num_poll_fds++;
            }
        }

        int poll_res = poll(poll_fds, (nfds_t)num_poll_fds, -1);
        if (poll_res < 0) {
            if (errno == EINTR) {
                continue;
            }
            free(request);
            if (ipc_server_fd >= 0) {
                close(ipc_server_fd);
            }
            return 70;
        }

        /* Accept new IPC connections */
        if (ipc_server_idx >= 0 && (poll_fds[ipc_server_idx].revents & POLLIN)) {
            int new_client = accept(ipc_server_fd, NULL, NULL);
            if (new_client >= 0) {
                int added = 0;
                for (i = 0; i < MAX_IPC_CLIENTS; ++i) {
                    if (ipc_clients[i] < 0) {
                        ipc_clients[i] = new_client;
                        added = 1;
                        break;
                    }
                }
                if (!added) {
                    close(new_client);
                }
            }
        }

        /* Check connected IPC clients for outbound commands to host */
        for (i = 0; i < num_poll_fds; ++i) {
            int client_slot = client_map[i];
            if (client_slot >= 0 && (poll_fds[i].revents & (POLLIN | POLLHUP | POLLERR))) {
                char client_buffer[4096];
                ssize_t bytes = read(ipc_clients[client_slot], client_buffer, sizeof(client_buffer));
                if (bytes > 0) {
                    /* Forward outbound bytes directly to host bridge write_fd */
                    ssize_t written = 0;
                    while (written < bytes) {
                        ssize_t w = write(write_fd, client_buffer + written, (size_t)(bytes - written));
                        if (w <= 0) {
                            break;
                        }
                        written += w;
                    }
                } else if (bytes == 0 || (bytes < 0 && errno != EAGAIN && errno != EINTR)) {
                    /* Client closed connection */
                    close(ipc_clients[client_slot]);
                    ipc_clients[client_slot] = -1;
                }
            }
        }

        /* Check if host bridge (read_fd) has incoming data */
        if (host_idx < 0 || !(poll_fds[host_idx].revents & (POLLIN | POLLHUP))) {
            continue;
        }
#endif

        line_status = read_line_fd(read_fd, request, MAX_PAYLOAD_LINE);
        if (line_status == 0) {
            free(request);
#if !defined(_WIN32)
            if (ipc_server_fd >= 0) {
                close(ipc_server_fd);
            }
#endif
            return 0;
        }
        if (line_status < 0) {
            write_control_line_fd(write_fd, "ORCHESTELLA/ERROR malformed-command\n");
            free(request);
#if !defined(_WIN32)
            if (ipc_server_fd >= 0) {
                close(ipc_server_fd);
            }
#endif
            return 4;
        }
        if (strncmp(request, "ORCHESTELLA/HELLO", 16U) == 0
            || strncmp(request, "DROIDLAYER/HELLO", 15U) == 0) {
            int repeated_hello = answer_control_hello(write_fd, request, expected_token);
            if (repeated_hello != 0) {
                free(request);
#if !defined(_WIN32)
                if (ipc_server_fd >= 0) {
                    close(ipc_server_fd);
                }
#endif
                return repeated_hello;
            }
            continue;
        }
        if (strcmp(request, "ORCHESTELLA/PING") == 0 || strcmp(request, "DROIDLAYER/PING") == 0) {
            if (!write_control_line_fd(write_fd, "ORCHESTELLA/PONG 1\n")) {
                free(request);
                return 70;
            }
            continue;
        }
        if (strncmp(request, "ORCHESTELLA/POWER", sizeof("ORCHESTELLA/POWER") - 1U) == 0
            || strncmp(request, "DROIDLAYER/POWER", sizeof("DROIDLAYER/POWER") - 1U) == 0) {
            int power_status;
            if (strcmp(request, "ORCHESTELLA/POWER shutdown") != 0
                && strcmp(request, "ORCHESTELLA/POWER poweroff") != 0
                && strcmp(request, "DROIDLAYER/POWER shutdown") != 0
                && strcmp(request, "DROIDLAYER/POWER poweroff") != 0) {
                write_control_line_fd(write_fd, "ORCHESTELLA/ERROR malformed-command\n");
                free(request);
                return 4;
            }
            power_status = dispatch_power_request(write_fd);
            if (power_status == 70) {
                free(request);
                return 70;
            }
            if (power_status != 0
                && !write_control_line_fd(write_fd, "ORCHESTELLA/ERROR power-unavailable\n")) {
                free(request);
                return 70;
            }
            continue;
        }
        if (parse_resize(request, &terminal_columns, &terminal_rows)) {
            if (!resize_terminal(terminal_device, terminal_columns, terminal_rows)) {
                write_control_line_fd(write_fd, "ORCHESTELLA/ERROR resize-unavailable\n");
                free(request);
                return 4;
            }
            if (!write_control_line_fd(write_fd, "ORCHESTELLA/OK resize\n")) {
                free(request);
                return 70;
            }
            continue;
        }
        const char *feature_cmd = NULL;
        if (strncmp(request, "ORCHESTELLA/FEATURES ", sizeof("ORCHESTELLA/FEATURES ") - 1U) == 0)
            feature_cmd = request + sizeof("ORCHESTELLA/FEATURES ") - 1U;
        if (feature_cmd != NULL) {
            if (!handle_features(write_fd, feature_cmd)) { free(request); return 70; }
            continue;
        }
        const char *display_cmd = NULL;
        if (strncmp(request, "ORCHESTELLA/DISPLAY ", sizeof("ORCHESTELLA/DISPLAY ") - 1U) == 0)
            display_cmd = request + sizeof("ORCHESTELLA/DISPLAY ") - 1U;
        else if (strncmp(request, "DROIDLAYER/DISPLAY ", sizeof("DROIDLAYER/DISPLAY ") - 1U) == 0)
            display_cmd = request + sizeof("DROIDLAYER/DISPLAY ") - 1U;
        if (display_cmd != NULL) {
            char result[MAX_FEATURE_RESULT + 1U];
            const char *response = "ORCHESTELLA/ERROR malformed-command\n";
            if (strcmp(display_cmd, "LAUNCH") == 0) {
                response = run_feature_helper("start", "desktop-labwc", NULL, NULL, NULL, result)
                    ? "ORCHESTELLA/OK display\n" : "ORCHESTELLA/ERROR display-launch-unavailable\n";
            } else {
                int width = 0, height = 0;
                double scale = 1.0;
                char extra = '\0';
                int mode = sscanf(display_cmd, "MODE %d %d %lf %c", &width, &height, &scale, &extra) == 3;
                int resize = !mode && sscanf(display_cmd, "RESIZE %d %d %c", &width, &height, &extra) == 2;
                if ((mode || resize) && width >= 100 && width <= 4096 && height >= 100 && height <= 4096
                    && (long long)width * height <= 4194304LL && scale >= 0.1 && scale <= 5.0) {
                    char width_arg[16], height_arg[16], scale_arg[32];
                    snprintf(width_arg, sizeof(width_arg), "%d", width);
                    snprintf(height_arg, sizeof(height_arg), "%d", height);
                    snprintf(scale_arg, sizeof(scale_arg), "%.3f", scale);
                    response = run_feature_helper("resize", "desktop-labwc", width_arg, height_arg, scale_arg, result)
                        ? "ORCHESTELLA/OK display\n" : "ORCHESTELLA/ERROR display-mode-unavailable\n";
                } else {
                    int fps = 0;
                    if (sscanf(display_cmd, "FPS %d %c", &fps, &extra) == 1 && fps >= 1 && fps <= 240)
                        response = "ORCHESTELLA/ERROR display-fps-unavailable\n";
                }
            }
            if (!write_control_line_fd(write_fd, response)) { free(request); return 70; }
            continue;
        }

        /* Inbound clipboard command from host to guest */
        if (strncmp(request, "ORCHESTELLA/CLIPBOARD", 21) == 0
            || strncmp(request, "DROIDLAYER/CLIPBOARD", 20) == 0) {
#if !defined(_WIN32)
            /* 1. Forward to any persistent connected IPC clients */
            for (i = 0; i < MAX_IPC_CLIENTS; ++i) {
                if (ipc_clients[i] >= 0) {
                    write_control_line_fd(ipc_clients[i], request);
                    write_control_line_fd(ipc_clients[i], "\n");
                }
            }
            /* 2. Write to clipboard inbound FIFO */
            int fifo_fd = open("/run/orchestella/clipboard-inbound.fifo", O_WRONLY | O_NONBLOCK);
            if (fifo_fd < 0) {
                fifo_fd = open("/tmp/orchestella-clipboard/inbound.fifo", O_WRONLY | O_NONBLOCK);
            }
            if (fifo_fd >= 0) {
                size_t req_len = strlen(request);
                write(fifo_fd, request, req_len);
                write(fifo_fd, "\n", 1);
                close(fifo_fd);
            }
#endif
            if (!write_control_line_fd(write_fd, "ORCHESTELLA/OK clipboard\n")) {
                free(request);
                return 70;
            }
            continue;
        }

        if (!write_control_line_fd(write_fd, "ORCHESTELLA/ERROR unsupported-command\n")) {
            free(request);
            return 70;
        }
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
    char request[MAX_CONTROL_LINE] = {0};
    if (read_line_fd(read_fd, request, sizeof(request)) != 1) {
        write_control_line_fd(write_fd, "ORCHESTELLA/ERROR malformed-handshake\n");
        return 1;
    }
    return answer_control_hello(write_fd, request, expected_token);
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
                    /* This worker must wait for its own feature dispatch children. */
                    signal(SIGCHLD, SIG_DFL);
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
                    _exit(serve_control_channel_fd(connection, connection, terminal_device, expected_token));
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
        } else if (strcmp(argument, "--vsock-display-port") == 0
                   && index + 1 < argument_count) {
            /* Handled / recognized for display channel */
            (void)atoi(arguments[++index]);
        } else if (strcmp(argument, "--share-tag") == 0
                   && index + 1 < argument_count && arguments[index + 1][0] != '\0') {
            share_tag = arguments[++index];
        } else if (strcmp(argument, "--share-mount") == 0
                   && index + 1 < argument_count && arguments[index + 1][0] != '\0') {
            share_mount = arguments[++index];
        } else {
            fputs("ORCHESTELLA/ERROR usage\n", stderr);
            return 64;
        }
    }
    if (token_path == NULL) {
        fputs("ORCHESTELLA/ERROR usage\n", stderr);
        return 64;
    }

    token_status = read_token(token_path, expected_token);
    if (token_status == 0) {
        fputs("ORCHESTELLA/ERROR token-unavailable\n", stderr);
        return 65;
    }
    if (token_status < 0) {
        fputs("ORCHESTELLA/ERROR token-invalid\n", stderr);
        return 65;
    }

    if (vsock_mode) {
#if !defined(_WIN32)
        if (control_port <= 0) {
            fputs("ORCHESTELLA/ERROR usage\n", stderr);
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
        fputs("ORCHESTELLA/ERROR vsock-unavailable\n", stderr);
        return 70;
#endif
    }

    if (terminal_device != NULL) {
        int authenticated = authenticate_control(STDIN_FILENO, STDOUT_FILENO, expected_token);
        if (authenticated != 0) {
            return authenticated;
        }
        return serve_control_channel_fd(STDIN_FILENO, STDOUT_FILENO, terminal_device, expected_token);
    }
    {
        int authenticated = authenticate_control(STDIN_FILENO, STDOUT_FILENO, expected_token);
        if (authenticated != 0) {
            return authenticated;
        }
    }
    execl("/bin/sh", "sh", (char *)NULL);
    fputs("ORCHESTELLA/ERROR terminal-unavailable\n", stderr);
    return 70;
}
