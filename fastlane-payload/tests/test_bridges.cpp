#include "test_support.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <filesystem>
#include <string>
#include <thread>
#include <vector>

#include "fastlane/distcc_bridge.h"
#include "fastlane/proxy_bridge.h"
#include "fastlane/storage.h"

namespace {

struct TempDir {
    std::string path;

    TempDir() {
        char pattern[] = "/tmp/fastlane-bridge-XXXXXX";
        char* created = ::mkdtemp(pattern);
        if (created == nullptr) {
            throw std::runtime_error("mkdtemp failed");
        }
        path = created;
    }

    ~TempDir() {
        std::error_code ignored;
        std::filesystem::remove_all(path, ignored);
    }
};

void set_timeout(int fd, int seconds) {
    timeval timeout{seconds, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}

int connect_loopback(int port) {
    const int fd = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        throw std::runtime_error("client socket failed");
    }
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(static_cast<uint16_t>(port));
    if (::connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        ::close(fd);
        throw std::runtime_error("connect failed");
    }
    set_timeout(fd, 3);
    return fd;
}

int connect_unix(const std::string& path) {
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        throw std::runtime_error("client socket failed");
    }
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::strncpy(address.sun_path, path.c_str(), sizeof(address.sun_path) - 1);
    if (::connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        ::close(fd);
        throw std::runtime_error("unix connect failed");
    }
    set_timeout(fd, 3);
    return fd;
}

std::string read_exactly(int fd, size_t length) {
    std::string data;
    char buffer[256];
    while (data.size() < length) {
        const ssize_t read = ::read(fd, buffer, std::min(sizeof(buffer), length - data.size()));
        if (read <= 0) {
            throw std::runtime_error("unexpected eof while reading");
        }
        data.append(buffer, static_cast<size_t>(read));
    }
    return data;
}

std::string read_to_eof(int fd) {
    std::string data;
    char buffer[256];
    for (;;) {
        const ssize_t read = ::read(fd, buffer, sizeof(buffer));
        if (read == 0) {
            return data;
        }
        if (read < 0) {
            throw std::runtime_error("read failed while waiting for eof");
        }
        data.append(buffer, static_cast<size_t>(read));
    }
}

void write_all_fd(int fd, const std::string& data) {
    size_t offset = 0;
    while (offset < data.size()) {
        const ssize_t written = ::write(fd, data.data() + offset, data.size() - offset);
        if (written <= 0) {
            throw std::runtime_error("write failed");
        }
        offset += static_cast<size_t>(written);
    }
}

bool wait_for_active(const fl::ProxyBridge& bridge, size_t expected, int timeout_ms) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    while (std::chrono::steady_clock::now() < deadline) {
        if (bridge.active_tunnels() == expected) {
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return false;
}

}  // namespace

FL_TEST(distcc_bridge_pipes_one_job) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::DistccConfig config;
    config.apk_root = "/";
    config.storage_root = layout.root();
    config.distccd_path = "/bin/sh";
    config.arguments = {"-c", "cat"};
    fl::DistccBridge bridge(config);

    int pair[2] = {-1, -1};
    FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair), 0);
    std::thread worker([&bridge, &pair] { bridge.handle(pair[1]); });
    const std::string request = "DIST 00012\nARGC 00002\n";
    write_all_fd(pair[0], request);
    ::shutdown(pair[0], SHUT_WR);
    FL_CHECK_EQ(read_to_eof(pair[0]), request);
    worker.join();
    ::close(pair[0]);
    FL_CHECK_EQ(bridge.total_jobs(), 1u);
    FL_CHECK_EQ(bridge.active_jobs(), 0u);
}

FL_TEST(distcc_bridge_runs_concurrent_jobs) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::DistccConfig config;
    config.apk_root = "/";
    config.storage_root = layout.root();
    config.distccd_path = "/bin/sh";
    config.arguments = {"-c", "cat"};
    config.max_jobs = 4;
    fl::DistccBridge bridge(config);

    std::vector<std::thread> workers;
    std::vector<int> clients;
    for (int index = 0; index < 4; ++index) {
        int pair[2] = {-1, -1};
        FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, pair), 0);
        clients.push_back(pair[0]);
        workers.emplace_back([&bridge, fd = pair[1]] { bridge.handle(fd); });
    }
    for (int index = 0; index < 4; ++index) {
        const std::string payload = "job-" + std::to_string(index);
        write_all_fd(clients[index], payload);
        ::shutdown(clients[index], SHUT_WR);
    }
    for (int index = 0; index < 4; ++index) {
        FL_CHECK_EQ(read_to_eof(clients[index]), "job-" + std::to_string(index));
        ::close(clients[index]);
    }
    for (auto& worker : workers) {
        worker.join();
    }
    FL_CHECK_EQ(bridge.total_jobs(), 4u);
    FL_CHECK_EQ(bridge.active_jobs(), 0u);
}

FL_TEST(distcc_bridge_rejects_over_limit) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::DistccConfig config;
    config.apk_root = "/";
    config.storage_root = layout.root();
    config.distccd_path = "/bin/sh";
    config.arguments = {"-c", "cat"};
    config.max_jobs = 1;
    fl::DistccBridge bridge(config);

    int first[2] = {-1, -1};
    FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, first), 0);
    std::thread worker([&bridge, &first] { bridge.handle(first[1]); });
    write_all_fd(first[0], "hold");
    for (int attempt = 0; attempt < 100 && bridge.active_jobs() != 1; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    FL_CHECK_EQ(bridge.active_jobs(), 1u);

    int second[2] = {-1, -1};
    FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, second), 0);
    bridge.handle(second[1]);
    set_timeout(second[0], 1);
    FL_CHECK_EQ(read_to_eof(second[0]), "");
    ::close(second[0]);

    ::shutdown(first[0], SHUT_WR);
    read_to_eof(first[0]);
    ::close(first[0]);
    worker.join();
    FL_CHECK_EQ(bridge.active_jobs(), 0u);
}

FL_TEST(distcc_bridge_rejects_missing_tool) {
    fl::DistccConfig config;
    config.apk_root = "/";
    config.distccd_path = "/bin/not-a-distccd";
    bool threw = false;
    try {
        fl::DistccBridge bridge(config);
    } catch (const fl::Error& error) {
        threw = error.code() == fl::Code::kExecFailed;
    }
    FL_CHECK(threw);
}

FL_TEST(proxy_bridge_tcp_pairs_one_channel) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::ProxyBridgeConfig config;
    config.socket_directory = layout.tmp();
    config.enable_tcp = true;
    config.pair_timeout_ms = 500;
    config.idle_timeout_ms = 5000;
    fl::ProxyBridge bridge(config);
    bridge.start();
    FL_CHECK(bridge.tcp_port() > 0);

    int channel[2] = {-1, -1};
    FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, channel), 0);
    bridge.add_channel(channel[1]);
    const int client = connect_loopback(bridge.tcp_port());
    write_all_fd(client, "ping");
    FL_CHECK_EQ(read_exactly(channel[0], 4), std::string("ping"));
    write_all_fd(channel[0], "pong");
    FL_CHECK_EQ(read_exactly(client, 4), std::string("pong"));
    ::shutdown(client, SHUT_WR);
    FL_CHECK_EQ(read_to_eof(channel[0]), "");
    ::close(channel[0]);
    ::close(client);
    FL_CHECK(wait_for_active(bridge, 0, 2000));
    FL_CHECK(bridge.bytes() >= 8u);
    bridge.stop();
}

FL_TEST(proxy_bridge_unix_socket_is_0600) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::ProxyBridgeConfig config;
    config.socket_directory = layout.tmp();
    config.enable_tcp = false;
    config.pair_timeout_ms = 500;
    fl::ProxyBridge bridge(config);
    bridge.start();
    FL_CHECK(!bridge.unix_socket_path().empty());
    struct stat info {};
    FL_CHECK_EQ(::stat(bridge.unix_socket_path().c_str(), &info), 0);
    FL_CHECK_EQ(info.st_mode & 0777, static_cast<unsigned int>(0600));

    int channel[2] = {-1, -1};
    FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, channel), 0);
    bridge.add_channel(channel[1]);
    const int client = connect_unix(bridge.unix_socket_path());
    write_all_fd(client, "abc");
    FL_CHECK_EQ(read_exactly(channel[0], 3), std::string("abc"));
    write_all_fd(channel[0], "xyz");
    FL_CHECK_EQ(read_exactly(client, 3), std::string("xyz"));
    ::close(client);
    ::close(channel[0]);
    FL_CHECK(wait_for_active(bridge, 0, 2000));
    bridge.stop();
}

FL_TEST(proxy_bridge_pair_timeout_closes_client) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::ProxyBridgeConfig config;
    config.socket_directory = layout.tmp();
    config.enable_tcp = true;
    config.pair_timeout_ms = 100;
    fl::ProxyBridge bridge(config);
    bridge.start();
    const int client = connect_loopback(bridge.tcp_port());
    FL_CHECK_EQ(read_to_eof(client), "");
    ::close(client);
    bridge.stop();
}

FL_TEST(proxy_bridge_idle_timeout_closes_tunnel) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::ProxyBridgeConfig config;
    config.socket_directory = layout.tmp();
    config.enable_tcp = true;
    config.pair_timeout_ms = 500;
    config.idle_timeout_ms = 200;
    fl::ProxyBridge bridge(config);
    bridge.start();
    int channel[2] = {-1, -1};
    FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, channel), 0);
    bridge.add_channel(channel[1]);
    const int client = connect_loopback(bridge.tcp_port());
    FL_CHECK(wait_for_active(bridge, 1, 1000));
    FL_CHECK_EQ(read_to_eof(channel[0]), "");
    FL_CHECK(wait_for_active(bridge, 0, 2000));
    ::close(channel[0]);
    ::close(client);
    bridge.stop();
}

FL_TEST(proxy_bridge_stop_closes_tunnels) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::ProxyBridgeConfig config;
    config.socket_directory = layout.tmp();
    config.enable_tcp = true;
    config.pair_timeout_ms = 500;
    fl::ProxyBridge bridge(config);
    bridge.start();
    int channel[2] = {-1, -1};
    FL_CHECK_EQ(::socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, channel), 0);
    bridge.add_channel(channel[1]);
    const int client = connect_loopback(bridge.tcp_port());
    FL_CHECK(wait_for_active(bridge, 1, 1000));
    bridge.stop();
    FL_CHECK(wait_for_active(bridge, 0, 2000));
    set_timeout(client, 1);
    set_timeout(channel[0], 1);
    (void)read_to_eof(client);
    (void)read_to_eof(channel[0]);
    ::close(client);
    ::close(channel[0]);
}
