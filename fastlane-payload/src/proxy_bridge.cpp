#include "fastlane/proxy_bridge.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>

#include "fastlane/error.h"

namespace fl {

struct ProxyBridge::Shared {
    std::atomic<bool> stopping{false};
    std::atomic<uint64_t> bytes{0};
    std::atomic<size_t> active{0};
    std::mutex mutex;
    std::condition_variable queue_ready;
    std::deque<int> channels;
    std::set<int> live_fds;
};

namespace {

constexpr size_t kQueueLimit = 16;
constexpr int kBufferBytes = 16384;

void close_quietly(int fd) {
    if (fd >= 0) {
        ::close(fd);
    }
}

void set_nonblocking(int fd) {
    const int flags = ::fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        ::fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
}

bool write_all_socket(int fd, const uint8_t* data, size_t length, int timeout_ms) {
    size_t offset = 0;
    while (offset < length) {
        const ssize_t written = ::write(fd, data + offset, length - offset);
        if (written > 0) {
            offset += static_cast<size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            pollfd descriptor{fd, POLLOUT, 0};
            const int ready = ::poll(&descriptor, 1, timeout_ms);
            if (ready > 0 && (descriptor.revents & POLLOUT)) {
                continue;
            }
            return false;
        }
        return false;
    }
    return true;
}

void register_fd(const std::shared_ptr<ProxyBridge::Shared>& shared, int fd) {
    std::lock_guard<std::mutex> lock(shared->mutex);
    shared->live_fds.insert(fd);
}

void unregister_fd(const std::shared_ptr<ProxyBridge::Shared>& shared, int fd) {
    std::lock_guard<std::mutex> lock(shared->mutex);
    shared->live_fds.erase(fd);
}

}  // namespace

void ProxyBridge::tunnel_main(const std::shared_ptr<ProxyBridge::Shared>& shared, int client,
                              int channel, int idle_timeout_ms) {
    set_nonblocking(client);
    set_nonblocking(channel);
    register_fd(shared, client);
    register_fd(shared, channel);

    bool client_open = true;
    bool channel_open = true;
    auto last_activity = std::chrono::steady_clock::now();
    uint8_t buffer[kBufferBytes];

    while (!shared->stopping.load() && (client_open || channel_open)) {
        pollfd descriptors[2] = {
                {client, static_cast<short>(client_open ? POLLIN : 0), 0},
                {channel, static_cast<short>(channel_open ? POLLIN : 0), 0},
        };
        const auto idle_elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - last_activity);
        if (idle_timeout_ms > 0 && idle_elapsed.count() >= idle_timeout_ms) {
            break;
        }
        int wait_ms = 500;
        if (idle_timeout_ms > 0) {
            wait_ms = static_cast<int>(std::min<int64_t>(
                    wait_ms, idle_timeout_ms - idle_elapsed.count()));
        }
        const int ready = ::poll(descriptors, 2, wait_ms);
        if (ready < 0 && errno != EINTR) {
            break;
        }
        if (ready <= 0) {
            continue;
        }

        for (int index = 0; index < 2; ++index) {
            if (descriptors[index].revents == 0) {
                continue;
            }
            const int source = index == 0 ? client : channel;
            const int destination = index == 0 ? channel : client;
            bool& source_open = index == 0 ? client_open : channel_open;
            bool& destination_open = index == 0 ? channel_open : client_open;
            if (!source_open) {
                continue;
            }
            if (descriptors[index].revents & (POLLERR | POLLNVAL)) {
                source_open = false;
                continue;
            }
            const ssize_t read = ::read(source, buffer, sizeof(buffer));
            if (read > 0) {
                last_activity = std::chrono::steady_clock::now();
                if (!write_all_socket(destination, buffer, static_cast<size_t>(read), 5000)) {
                    source_open = false;
                    destination_open = false;
                    break;
                }
                shared->bytes.fetch_add(static_cast<uint64_t>(read));
                continue;
            }
            if (read == 0) {
                ::shutdown(destination, SHUT_WR);
                source_open = false;
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                continue;
            }
            source_open = false;
            destination_open = false;
            break;
        }
    }

    ::shutdown(client, SHUT_RDWR);
    ::shutdown(channel, SHUT_RDWR);
    unregister_fd(shared, client);
    unregister_fd(shared, channel);
    close_quietly(client);
    close_quietly(channel);
    shared->active.fetch_sub(1);
}

int ProxyBridge::take_channel(const std::shared_ptr<ProxyBridge::Shared>& shared, int timeout_ms) {
    std::unique_lock<std::mutex> lock(shared->mutex);
    if (!shared->queue_ready.wait_for(lock, std::chrono::milliseconds(timeout_ms),
                                      [shared] {
                                          return shared->stopping.load() ||
                                                 !shared->channels.empty();
                                      })) {
        return -1;
    }
    if (shared->stopping.load() || shared->channels.empty()) {
        return -1;
    }
    const int fd = shared->channels.front();
    shared->channels.pop_front();
    return fd;
}

ProxyBridge::ProxyBridge(ProxyBridgeConfig config)
        : config_(std::move(config)), shared_(std::make_shared<Shared>()) {
    if (config_.max_connections == 0) {
        config_.max_connections = 1;
    }
}

ProxyBridge::~ProxyBridge() {
    stop();
}

void ProxyBridge::start() {
    if (started_) {
        return;
    }

    if (!config_.socket_directory.empty()) {
        const std::string path = config_.socket_directory + "/" + config_.socket_name;
        struct stat info {};
        if (::lstat(path.c_str(), &info) == 0) {
            if (!S_ISSOCK(info.st_mode)) {
                throw Error(Code::kPathRejected, "proxy socket path exists and is not a socket");
            }
            ::unlink(path.c_str());
        }
        const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            sockaddr_un address{};
            address.sun_family = AF_UNIX;
            if (path.size() < sizeof(address.sun_path)) {
                std::strncpy(address.sun_path, path.c_str(), sizeof(address.sun_path) - 1);
                if (::bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0 &&
                    ::listen(fd, 8) == 0) {
                    ::chmod(path.c_str(), 0600);
                    unix_listen_fd_ = fd;
                    unix_socket_path_ = path;
                }
            }
            if (unix_listen_fd_ < 0) {
                close_quietly(fd);
            }
        }
    }

    if (config_.enable_tcp) {
        const int fd = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd >= 0) {
            int reuse = 1;
            ::setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
            sockaddr_in address{};
            address.sin_family = AF_INET;
            address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
            address.sin_port = 0;
            if (::bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) == 0 &&
                ::listen(fd, 16) == 0) {
                socklen_t length = sizeof(address);
                if (::getsockname(fd, reinterpret_cast<sockaddr*>(&address), &length) == 0) {
                    tcp_port_ = ntohs(address.sin_port);
                    tcp_listen_fd_ = fd;
                }
            }
            if (tcp_listen_fd_ < 0) {
                close_quietly(fd);
            }
        }
    }

    if (tcp_listen_fd_ < 0 && unix_listen_fd_ < 0) {
        throw Error(Code::kUnsupported, "no local proxy endpoint could be created");
    }
    started_ = true;
    if (tcp_listen_fd_ >= 0) {
        tcp_thread_ = std::thread(&ProxyBridge::accept_loop, this, tcp_listen_fd_);
    }
    if (unix_listen_fd_ >= 0) {
        unix_thread_ = std::thread(&ProxyBridge::accept_loop, this, unix_listen_fd_);
    }
}

void ProxyBridge::accept_loop(int listen_fd) {
    while (!shared_->stopping.load()) {
        const int client = ::accept4(listen_fd, nullptr, nullptr, SOCK_CLOEXEC);
        if (client < 0) {
            if (shared_->stopping.load()) {
                return;
            }
            if (errno == EINTR) {
                continue;
            }
            return;
        }
        if (shared_->active.fetch_add(1) >= config_.max_connections) {
            shared_->active.fetch_sub(1);
            close_quietly(client);
            continue;
        }
        const int channel = take_channel(shared_, config_.pair_timeout_ms);
        if (channel < 0) {
            shared_->active.fetch_sub(1);
            close_quietly(client);
            continue;
        }
        try {
            std::thread(ProxyBridge::tunnel_main, shared_, client, channel,
                        config_.idle_timeout_ms)
                    .detach();
        } catch (...) {
            shared_->active.fetch_sub(1);
            ::shutdown(client, SHUT_RDWR);
            ::shutdown(channel, SHUT_RDWR);
            close_quietly(client);
            close_quietly(channel);
        }
    }
}

void ProxyBridge::add_channel(int fd) {
    if (fd < 0) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(shared_->mutex);
        if (shared_->stopping.load() || shared_->channels.size() >= kQueueLimit) {
            close_quietly(fd);
            return;
        }
        shared_->channels.push_back(fd);
    }
    shared_->queue_ready.notify_one();
}

uint64_t ProxyBridge::bytes() const {
    return shared_->bytes.load();
}

size_t ProxyBridge::active_tunnels() const {
    return shared_->active.load();
}

void ProxyBridge::stop() {
    if (!started_) {
        return;
    }
    started_ = false;
    shared_->stopping.store(true);
    shared_->queue_ready.notify_all();
    if (tcp_listen_fd_ >= 0) {
        ::shutdown(tcp_listen_fd_, SHUT_RDWR);
    }
    if (unix_listen_fd_ >= 0) {
        ::shutdown(unix_listen_fd_, SHUT_RDWR);
    }
    close_quietly(tcp_listen_fd_);
    close_quietly(unix_listen_fd_);
    tcp_listen_fd_ = -1;
    unix_listen_fd_ = -1;
    if (tcp_thread_.joinable()) {
        tcp_thread_.join();
    }
    if (unix_thread_.joinable()) {
        unix_thread_.join();
    }

    {
        std::lock_guard<std::mutex> lock(shared_->mutex);
        for (const int fd : shared_->channels) {
            close_quietly(fd);
        }
        shared_->channels.clear();
        // Shut down while still holding the registry lock: the tunnel thread
        // removes a descriptor before closing it, so this cannot touch a
        // recycled descriptor number.
        for (const int fd : shared_->live_fds) {
            ::shutdown(fd, SHUT_RDWR);
        }
    }
    for (int attempt = 0; attempt < 50 && shared_->active.load() > 0; ++attempt) {
        ::usleep(10000);
    }
    if (!unix_socket_path_.empty()) {
        ::unlink(unix_socket_path_.c_str());
    }
}

}  // namespace fl
