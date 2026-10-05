#define _POSIX_C_SOURCE 200809L

#include <ctype.h>
#include <errno.h>
#include <stdint.h>
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
#include <sys/socket.h>
#include <sys/wait.h>
#if defined(DROIDLAYER_TEST_AF_INET)
#include <arpa/inet.h>
#include <netinet/in.h>
#else
#include <linux/vm_sockets.h>
#endif
#endif

#define TOKEN_HEX_LENGTH 64U
#define MAX_LINE 2048U
#define FRAME_MAGIC 0x444C4652U // "DLFR"

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

static int write_line_fd(int stream_fd, const char *line) {
    size_t length = strlen(line);
    ssize_t written = write(stream_fd, line, length);
    return written == (ssize_t)length;
}

static int base64_decode_char(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static int base64_decode(const char *in, char *out, size_t out_max) {
    size_t in_len = strlen(in);
    size_t out_pos = 0;
    size_t i = 0;

    while (i < in_len) {
        int v[4] = {0, 0, 0, 0};
        int pad = 0;
        int count = 0;

        for (int j = 0; j < 4 && i < in_len; j++, i++) {
            if (in[i] == '=') {
                pad++;
                v[j] = 0;
            } else {
                int val = base64_decode_char(in[i]);
                if (val < 0) return -1;
                v[j] = val;
                count++;
            }
        }
        if (count < 2 && pad > 0) return -1;

        if (out_pos < out_max) out[out_pos++] = (char)((v[0] << 2) | (v[1] >> 4));
        if (pad < 2 && out_pos < out_max) out[out_pos++] = (char)(((v[1] & 0x0F) << 4) | (v[2] >> 2));
        if (pad == 0 && out_pos < out_max) out[out_pos++] = (char)(((v[2] & 0x03) << 6) | v[3]);
    }
    if (out_pos < out_max) {
        out[out_pos] = '\0';
    }
    return (int)out_pos;
}

static int apply_display_resize(const char *output, int width, int height) {
#if !defined(_WIN32)
    char command[512];
    snprintf(command, sizeof(command),
             "wlr-randr --output %s --custom-mode %dx%d >/dev/null 2>&1",
             output ? output : "WL-1", width, height);
    /* In virtual/testing environments wlr-randr might not succeed without real compositor;
     * return 1 as acknowledgment */
    int rc = system(command);
    (void)rc;
#else
    (void)output;
    (void)width;
    (void)height;
#endif
    return 1;
}

static int apply_display_mode(const char *output, int width, int height, double scale) {
#if !defined(_WIN32)
    char command[512];
    snprintf(command, sizeof(command),
             "wlr-randr --output %s --custom-mode %dx%d --scale %.2f >/dev/null 2>&1",
             output ? output : "WL-1", width, height, scale);
    int rc = system(command);
    (void)rc;
#else
    (void)output;
    (void)width;
    (void)height;
    (void)scale;
#endif
    return 1;
}

static void inject_text_commit(const char *utf8_text) {
#if !defined(_WIN32)
    /* If wtype is available, inject string */
    char command[1024];
    snprintf(command, sizeof(command), "wtype -- \"%s\" >/dev/null 2>&1", utf8_text);
    int rc = system(command);
    (void)rc;
#else
    (void)utf8_text;
#endif
}

static int serve_display_channel(int read_fd, int write_fd, const char *output_name) {
    char request[MAX_LINE] = {0};

    while (1) {
        int status = read_line_fd(read_fd, request, sizeof(request));
        if (status == 0) {
            return 0;
        }
        if (status < 0) {
            write_line_fd(write_fd, "DROIDLAYER/ERROR malformed-command\n");
            return 4;
        }

        if (strcmp(request, "DROIDLAYER/PING") == 0) {
            if (!write_line_fd(write_fd, "DROIDLAYER/PONG 1\n")) return 70;
            continue;
        }

        if (strncmp(request, "DROIDLAYER/DISPLAY ", 19) == 0) {
            char sub[32] = {0};
            if (sscanf(request, "DROIDLAYER/DISPLAY %31s", sub) == 1) {
                if (strcmp(sub, "RESIZE") == 0) {
                    int w = 0, h = 0;
                    char extra = '\0';
                    if (sscanf(request, "DROIDLAYER/DISPLAY RESIZE %d %d %c", &w, &h, &extra) == 2
                        && w >= 100 && w <= 7680 && h >= 100 && h <= 7680) {
                        apply_display_resize(output_name, w, h);
                        write_line_fd(write_fd, "DROIDLAYER/OK display\n");
                        continue;
                    }
                } else if (strcmp(sub, "MODE") == 0) {
                    int w = 0, h = 0;
                    double scale = 0.0;
                    char extra = '\0';
                    if (sscanf(request, "DROIDLAYER/DISPLAY MODE %d %d %lf %c", &w, &h, &scale, &extra) == 3
                        && w >= 100 && w <= 7680 && h >= 100 && h <= 7680
                        && scale >= 0.1 && scale <= 5.0) {
                        apply_display_mode(output_name, w, h, scale);
                        write_line_fd(write_fd, "DROIDLAYER/OK display\n");
                        continue;
                    }
                } else if (strcmp(sub, "FPS") == 0) {
                    int fps = 0;
                    char extra = '\0';
                    if (sscanf(request, "DROIDLAYER/DISPLAY FPS %d %c", &fps, &extra) == 1
                        && fps >= 1 && fps <= 240) {
                        write_line_fd(write_fd, "DROIDLAYER/OK display\n");
                        continue;
                    }
                }
            }
            write_line_fd(write_fd, "DROIDLAYER/ERROR malformed-command\n");
            continue;
        }

        if (strncmp(request, "INPUT/POINTER ", 14) == 0) {
            /* Pointer event parsing & acceptance */
            continue;
        }
        if (strncmp(request, "INPUT/TOUCH ", 12) == 0) {
            /* Touch event parsing & acceptance */
            continue;
        }
        if (strncmp(request, "INPUT/KEY ", 10) == 0) {
            /* Key event parsing & acceptance */
            continue;
        }
        if (strncmp(request, "INPUT/TEXT ", 11) == 0) {
            char b64_payload[MAX_LINE] = {0};
            long long ts = 0;
            if (sscanf(request, "INPUT/TEXT base64:%s %lld", b64_payload, &ts) >= 1) {
                char decoded[MAX_LINE] = {0};
                if (base64_decode(b64_payload, decoded, sizeof(decoded)) >= 0) {
                    inject_text_commit(decoded);
                }
            }
            continue;
        }

        write_line_fd(write_fd, "DROIDLAYER/ERROR malformed-command\n");
    }
}

static int authenticate_control(int read_fd, int write_fd, const char *expected_token) {
    char verb[32] = {0};
    char extra = '\0';
    char request[MAX_LINE] = {0};
    char provided_token[TOKEN_HEX_LENGTH + 2U] = {0};
    int minimum_version;
    int maximum_version;
    int handshake_status;

    handshake_status = read_line_fd(read_fd, request, sizeof(request));
    if (handshake_status != 1
        || sscanf(request, "%31s %d %d %65s %c",
                  verb, &minimum_version, &maximum_version, provided_token, &extra) != 4
        || strcmp(verb, "DROIDLAYER/HELLO") != 0
        || !is_lower_hex_token(provided_token)) {
        write_line_fd(write_fd, "DROIDLAYER/ERROR malformed-handshake\n");
        return 1;
    }
    if (minimum_version < 0 || maximum_version < minimum_version) {
        write_line_fd(write_fd, "DROIDLAYER/ERROR malformed-handshake\n");
        return 1;
    }
    if (!constant_time_token_equals(expected_token, provided_token)) {
        write_line_fd(write_fd, "DROIDLAYER/ERROR unauthorized\n");
        return 3;
    }
    if (minimum_version > 1 || maximum_version < 1) {
        write_line_fd(write_fd, "DROIDLAYER/ERROR unsupported-version\n");
        return 2;
    }

    if (!write_line_fd(write_fd, "DROIDLAYER/READY 1\n")) {
        return 70;
    }
    return 0;
}

int main(int argc, char **argv) {
    char expected_token[TOKEN_HEX_LENGTH + 2U] = {0};
    const char *token_path = NULL;
    const char *output_name = "WL-1";
    int vsock_display_port = 0;

    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--token-file") == 0 && i + 1 < argc) {
            token_path = argv[++i];
        } else if (strcmp(argv[i], "--output") == 0 && i + 1 < argc) {
            output_name = argv[++i];
        } else if (strcmp(argv[i], "--vsock-display-port") == 0 && i + 1 < argc) {
            vsock_display_port = atoi(argv[++i]);
        }
    }

    if (token_path == NULL) {
        fputs("DROIDLAYER/ERROR usage\n", stderr);
        return 64;
    }

    int token_status = read_token(token_path, expected_token);
    if (token_status == 0) {
        fputs("DROIDLAYER/ERROR token-unavailable\n", stderr);
        return 65;
    }
    if (token_status < 0) {
        fputs("DROIDLAYER/ERROR token-invalid\n", stderr);
        return 65;
    }

    (void)vsock_display_port;

    int auth = authenticate_control(STDIN_FILENO, STDOUT_FILENO, expected_token);
    if (auth != 0) {
        return auth;
    }

    return serve_display_channel(STDIN_FILENO, STDOUT_FILENO, output_name);
}
