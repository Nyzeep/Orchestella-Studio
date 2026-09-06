#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TOKEN_HEX_LENGTH 64U

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

int main(int argument_count, char **arguments) {
    char expected_token[TOKEN_HEX_LENGTH + 2U] = {0};
    char provided_token[TOKEN_HEX_LENGTH + 2U] = {0};
    char verb[32] = {0};
    char extra = '\0';
    char request[512] = {0};
    int minimum_version;
    int maximum_version;

    if (argument_count != 3 || strcmp(arguments[1], "--token-file") != 0) {
        fputs("DROIDLAYER/ERROR usage\n", stderr);
        return 64;
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
    if (fgets(request, sizeof(request), stdin) == NULL
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
    if (!constant_time_token_equals(expected_token, provided_token)) {
        fputs("DROIDLAYER/ERROR unauthorized\n", stdout);
        return 3;
    }
    if (minimum_version > 1 || maximum_version < 1) {
        fputs("DROIDLAYER/ERROR unsupported-version\n", stdout);
        return 2;
    }

    fputs("DROIDLAYER/READY 1\n", stdout);
    return 0;
}
