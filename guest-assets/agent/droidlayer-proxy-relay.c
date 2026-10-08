#define _POSIX_C_SOURCE 200809L

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#define PROXY_PORT 8080
#define MAX_CHANNELS 128
#define BUFFER_SIZE (16 * 1024)

#define CMD_DATA 1
#define CMD_CLOSE 2
#define CMD_RESET 3

static const char *DEVICE_CANDIDATES[] = {
    "/dev/virtio-ports/droidlayer.proxy",
    "/dev/vport0p2",
    "/dev/droidlayer-proxy",
    NULL
};

typedef struct {
    int channel_id;
    int client_fd;
    volatile int active;
} Channel;

static Channel g_channels[MAX_CHANNELS];
static pthread_mutex_t g_channels_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_serial_write_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_serial_fd = -1;
static volatile int g_running = 1;
static uint32_t g_next_channel_id = 1;

static int open_proxy_device(void) {
    for (int retry = 0; retry < 30; ++retry) {
        for (int i = 0; DEVICE_CANDIDATES[i] != NULL; ++i) {
            int fd = open(DEVICE_CANDIDATES[i], O_RDWR);
            if (fd >= 0) {
                printf("[droidlayer-proxy-relay] Connected to proxy port: %s\n", DEVICE_CANDIDATES[i]);
                return fd;
            }
        }
        usleep(200000); // 200ms
    }
    return -1;
}

static int write_exact(int fd, const void *buf, size_t len) {
    const uint8_t *p = (const uint8_t *)buf;
    size_t remaining = len;
    while (remaining > 0) {
        ssize_t n = write(fd, p, remaining);
        if (n <= 0) {
            if (n < 0 && (errno == EINTR || errno == EAGAIN)) {
                continue;
            }
            return -1;
        }
        p += n;
        remaining -= (size_t)n;
    }
    return 0;
}

static int read_exact(int fd, void *buf, size_t len) {
    uint8_t *p = (uint8_t *)buf;
    size_t remaining = len;
    while (remaining > 0) {
        ssize_t n = read(fd, p, remaining);
        if (n <= 0) {
            if (n < 0 && (errno == EINTR || errno == EAGAIN)) {
                continue;
            }
            return -1;
        }
        p += n;
        remaining -= (size_t)n;
    }
    return 0;
}

static int send_frame(int channel_id, uint8_t cmd, const void *payload, uint32_t len) {
    uint8_t header[9];
    uint32_t net_cid = htonl((uint32_t)channel_id);
    uint32_t net_len = htonl(len);

    memcpy(&header[0], &net_cid, 4);
    header[4] = cmd;
    memcpy(&header[5], &net_len, 4);

    pthread_mutex_lock(&g_serial_write_lock);
    int res = write_exact(g_serial_fd, header, 9);
    if (res == 0 && len > 0 && payload != NULL) {
        res = write_exact(g_serial_fd, payload, len);
    }
    pthread_mutex_unlock(&g_serial_write_lock);
    return res;
}

static Channel *alloc_channel(int client_fd) {
    pthread_mutex_lock(&g_channels_lock);
    for (int i = 0; i < MAX_CHANNELS; ++i) {
        if (!g_channels[i].active) {
            g_channels[i].channel_id = (int)g_next_channel_id++;
            if (g_next_channel_id == 0) g_next_channel_id = 1;
            g_channels[i].client_fd = client_fd;
            g_channels[i].active = 1;
            pthread_mutex_unlock(&g_channels_lock);
            return &g_channels[i];
        }
    }
    pthread_mutex_unlock(&g_channels_lock);
    return NULL;
}

static Channel *find_channel(int channel_id) {
    pthread_mutex_lock(&g_channels_lock);
    for (int i = 0; i < MAX_CHANNELS; ++i) {
        if (g_channels[i].active && g_channels[i].channel_id == channel_id) {
            pthread_mutex_unlock(&g_channels_lock);
            return &g_channels[i];
        }
    }
    pthread_mutex_unlock(&g_channels_lock);
    return NULL;
}

static void release_channel(int channel_id) {
    pthread_mutex_lock(&g_channels_lock);
    for (int i = 0; i < MAX_CHANNELS; ++i) {
        if (g_channels[i].channel_id == channel_id) {
            if (g_channels[i].active) {
                close(g_channels[i].client_fd);
                g_channels[i].active = 0;
            }
            break;
        }
    }
    pthread_mutex_unlock(&g_channels_lock);
}

typedef struct {
    int channel_id;
    int client_fd;
} WorkerArgs;

static void *client_reader_thread(void *arg) {
    WorkerArgs *args = (WorkerArgs *)arg;
    int ch_id = args->channel_id;
    int client_fd = args->client_fd;
    free(args);

    uint8_t buf[BUFFER_SIZE];
    while (g_running) {
        ssize_t n = read(client_fd, buf, sizeof(buf));
        if (n > 0) {
            if (send_frame(ch_id, CMD_DATA, buf, (uint32_t)n) != 0) {
                break;
            }
        } else if (n == 0) {
            // EOF from local client
            send_frame(ch_id, CMD_CLOSE, NULL, 0);
            break;
        } else {
            if (errno == EINTR || errno == EAGAIN) continue;
            send_frame(ch_id, CMD_RESET, NULL, 0);
            break;
        }
    }

    release_channel(ch_id);
    return NULL;
}

static void *serial_reader_thread(void *arg) {
    (void)arg;
    uint8_t header[9];
    uint8_t buf[BUFFER_SIZE];

    while (g_running) {
        if (read_exact(g_serial_fd, header, 9) != 0) {
            fprintf(stderr, "[droidlayer-proxy-relay] Serial read header EOF or error\n");
            break;
        }

        uint32_t net_cid, net_len;
        memcpy(&net_cid, &header[0], 4);
        uint8_t cmd = header[4];
        memcpy(&net_len, &header[5], 4);

        int channel_id = (int)ntohl(net_cid);
        uint32_t len = ntohl(net_len);

        if (len > 0) {
            uint32_t to_read = len;
            while (to_read > 0) {
                uint32_t chunk_size = (to_read < sizeof(buf)) ? to_read : (uint32_t)sizeof(buf);
                if (read_exact(g_serial_fd, buf, chunk_size) != 0) {
                    fprintf(stderr, "[droidlayer-proxy-relay] Serial read payload error\n");
                    return NULL;
                }
                if (cmd == CMD_DATA) {
                    Channel *ch = find_channel(channel_id);
                    if (ch != NULL && ch->active) {
                        write_exact(ch->client_fd, buf, chunk_size);
                    }
                }
                to_read -= chunk_size;
            }
        }

        if (cmd == CMD_CLOSE || cmd == CMD_RESET) {
            release_channel(channel_id);
        }
    }
    return NULL;
}

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;

    g_serial_fd = open_proxy_device();
    if (g_serial_fd < 0) {
        fprintf(stderr, "[droidlayer-proxy-relay] Could not open proxy virtio device\n");
        return 1;
    }

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) {
        perror("socket");
        return 1;
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(PROXY_PORT);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(server_fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        perror("bind 127.0.0.1:8080");
        return 1;
    }

    if (listen(server_fd, 64) < 0) {
        perror("listen");
        return 1;
    }

    printf("[droidlayer-proxy-relay] Listening on 127.0.0.1:%d -> Virtio-Serial proxy\n", PROXY_PORT);

    pthread_t serial_tid;
    if (pthread_create(&serial_tid, NULL, serial_reader_thread, NULL) != 0) {
        perror("pthread_create serial_reader");
        return 1;
    }
    pthread_detach(serial_tid);

    while (g_running) {
        struct sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (struct sockaddr *)&client_addr, &client_len);
        if (client_fd < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            perror("accept");
            break;
        }

        int nodelay = 1;
        setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

        Channel *ch = alloc_channel(client_fd);
        if (ch == NULL) {
            fprintf(stderr, "[droidlayer-proxy-relay] Max channels reached, rejecting\n");
            close(client_fd);
            continue;
        }

        WorkerArgs *wargs = (WorkerArgs *)malloc(sizeof(WorkerArgs));
        if (wargs == NULL) {
            release_channel(ch->channel_id);
            continue;
        }
        wargs->channel_id = ch->channel_id;
        wargs->client_fd = client_fd;

        pthread_t client_tid;
        if (pthread_create(&client_tid, NULL, client_reader_thread, wargs) != 0) {
            perror("pthread_create client_reader");
            free(wargs);
            release_channel(ch->channel_id);
            continue;
        }
        pthread_detach(client_tid);
    }

    close(server_fd);
    close(g_serial_fd);
    return 0;
}
