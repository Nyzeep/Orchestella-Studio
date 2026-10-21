#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace fl {

struct ProxyBridgeConfig {
    std::string socket_directory;   // empty disables the Unix listener
    std::string socket_name = "proxy.sock";
    bool enable_tcp = true;         // 127.0.0.1 only; failure degrades to UDS
    size_t max_connections = 8;
    int pair_timeout_ms = 10000;
    int idle_timeout_ms = 300000;
};

/**
 * Guest side of the proxy path: a byte-transparent bridge between a local tool
 * endpoint and host-opened vsock channels. The host speaks SOCKS5/HTTP CONNECT
 * over the channel; this class never parses the proxy protocol.
 *
 * The guest cannot connect vsock (SELinux neverallow), so the host opens the
 * channels and queues them here with add_channel(). Each local connection takes
 * one channel FIFO.
 */
class ProxyBridge {
public:
    struct Shared;

    explicit ProxyBridge(ProxyBridgeConfig config);
    ~ProxyBridge();

    void start();
    void add_channel(int fd);

    int tcp_port() const { return tcp_port_; }
    std::string unix_socket_path() const { return unix_socket_path_; }
    uint64_t bytes() const;
    size_t active_tunnels() const;

    void stop();

private:
    static void tunnel_main(const std::shared_ptr<Shared>& shared, int client, int channel,
                            int idle_timeout_ms);
    static int take_channel(const std::shared_ptr<Shared>& shared, int timeout_ms);
    void accept_loop(int listen_fd);

    ProxyBridgeConfig config_;
    std::shared_ptr<Shared> shared_;
    int tcp_listen_fd_ = -1;
    int unix_listen_fd_ = -1;
    int tcp_port_ = 0;
    std::string unix_socket_path_;
    std::thread tcp_thread_;
    std::thread unix_thread_;
    bool started_ = false;
};

}  // namespace fl
