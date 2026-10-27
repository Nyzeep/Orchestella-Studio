#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>
#include <string>
#include <vector>

#include "fastlane/storage.h"

namespace fl {

struct PayloadConfig {
    std::string apk_root;
    std::string storage_root;
    uint16_t control_port = 5100;
    uint16_t terminal_port = 5101;
    uint16_t proxy_port = 5102;
    uint16_t distcc_port = 5103;
    uint32_t initial_window = 262144;
    size_t max_sessions = 8;
    size_t max_connections = 8;
    int proxy_pair_timeout_ms = 10000;
    int proxy_idle_timeout_ms = 300000;
    bool enable_proxy_tcp = true;
    std::string auth_token;
    bool unix_transport = false;
    std::string socket_directory;
    std::string distccd_path;
    bool handle_signals = true;

    static PayloadConfig from_environment();
};

class Listener;
class ProxyBridge;
class DistccBridge;
class PtyManager;
struct RuntimeContext;
struct RuntimeState;

/**
 * Guest payload runtime. start() binds the enabled vsock (or host-test Unix)
 * listeners and returns; run() additionally waits for SIGTERM/SIGINT, notifies
 * the host that the payload is ready, and stops. stop() is idempotent.
 */
class Runtime {
public:
    explicit Runtime(PayloadConfig config);
    ~Runtime();

    void start();
    void stop();
    int run();

    const StorageLayout& layout() const;
    uint32_t capabilities() const;
    std::string socket_path(uint16_t port) const;
    bool started() const { return started_; }

private:
    void accept_framed(Listener* listener);

    PayloadConfig config_;
    std::shared_ptr<RuntimeContext> context_;
    std::unique_ptr<Listener> control_listener_;
    std::unique_ptr<Listener> terminal_listener_;
    std::unique_ptr<Listener> proxy_listener_;
    std::unique_ptr<Listener> distcc_listener_;
    std::thread control_thread_;
    std::thread terminal_thread_;
    std::thread proxy_thread_;
    std::thread distcc_thread_;
    bool started_ = false;
    // stop() is public and hosts call it from a control thread while run() is
    // blocked in the main loop, so the latch is read and written on two
    // threads.
    std::atomic<bool> stopped_{false};
};

}  // namespace fl
