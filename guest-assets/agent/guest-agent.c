#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#if !defined(_WIN32)
#include <fcntl.h>
#include <sys/ioctl.h>
#include <termios.h>
#endif

#define TOKEN_HEX_LENGTH 64U
#define MAX_TERMINAL_COLUMNS 1000
#define MAX_TERMINAL_ROWS 1000

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
static int read_line(char *request, size_t request_size) {
    size_t length = 0U;
    while (length + 1U < request_size) {
        ssize_t bytes_read = read(STDIN_FILENO, &request[length], 1U);
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

static int write_control_line(const char *line) {
    return fputs(line, stdout) >= 0 && fflush(stdout) == 0;
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

static int serve_control_channel(const char *terminal_device) {
    char request[512] = {0};
    int terminal_columns;
    int terminal_rows;
    int line_status;

    while (1) {
        line_status = read_line(request, sizeof(request));
        if (line_status == 0) {
            return 0;
        }
        if (line_status < 0) {
            fputs("DROIDLAYER/ERROR malformed-command\n", stdout);
            return 4;
        }
        if (strcmp(request, "DROIDLAYER/PING") == 0) {
            if (!write_control_line("DROIDLAYER/PONG 1\n")) {
                return 70;
            }
            continue;
        }
        if (parse_resize(request, &terminal_columns, &terminal_rows)) {
            if (!resize_terminal(terminal_device, terminal_columns, terminal_rows)) {
                fputs("DROIDLAYER/ERROR resize-unavailable\n", stdout);
                return 4;
            }
            if (!write_control_line("DROIDLAYER/OK resize\n")) {
                return 70;
            }
            continue;
        }
        fputs("DROIDLAYER/ERROR malformed-command\n", stdout);
        return 4;
    }
}

int main(int argument_count, char **arguments) {
    char expected_token[TOKEN_HEX_LENGTH + 2U] = {0};
    char provided_token[TOKEN_HEX_LENGTH + 2U] = {0};
    char verb[32] = {0};
    char extra = '\0';
    char request[512] = {0};
    const char *terminal_device = NULL;
    int minimum_version;
    int maximum_version;
    int handshake_status;

    if ((argument_count != 3 && argument_count != 5)
        || strcmp(arguments[1], "--token-file") != 0
        || (argument_count == 5
            && (strcmp(arguments[3], "--terminal-device") != 0 || arguments[4][0] == '\0'))) {
        fputs("DROIDLAYER/ERROR usage\n", stderr);
        return 64;
    }
    if (argument_count == 5) {
        terminal_device = arguments[4];
    }
    int token_status;

    token_status = read_token(arguments[2], expected_token);
    if (token_status == 0) {
        fputs("DROIDLAYER/ERROR token-unavailable\n", stderr);
        return 65;
    }
    if (token_status < 0) {
        fputs("DROIDLAYER/ERROR token-invalid\n", stderr);
        return 65;
    }
    handshake_status = read_line(request, sizeof(request));
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
        fputs("DROIDLAYER/ERROR malformed-handshake\n", stdout);
        return 1;
    }
    if (minimum_version < 0 || maximum_version < minimum_version) {
        fputs("DROIDLAYER/ERROR malformed-handshake\n", stdout);
        return 1;
    }
    if (!constant_time_token_equals(expected_token, provided_token)) {
        fputs("DROIDLAYER/ERROR unauthorized\n", stdout);
        return 3;
    }
    if (minimum_version > 1 || maximum_version < 1) {
        fputs("DROIDLAYER/ERROR unsupported-version\n", stdout);
        return 2;
    }

    if (!write_control_line("DROIDLAYER/READY 1\n")) {
        return 70;
    }
    if (terminal_device != NULL) {
        return serve_control_channel(terminal_device);
    }
    execl("/bin/sh", "sh", (char *)NULL);
    fputs("DROIDLAYER/ERROR terminal-unavailable\n", stderr);
    return 70;
}
