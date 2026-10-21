#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <set>
#include <string>
#include <vector>

namespace fl {

struct DistccConfig {
    std::string apk_root;
    std::string storage_root;
    std::string distccd_path;                 // resolved in the constructor
    std::vector<std::string> arguments;       // default: {"--inetd"}
    size_t max_jobs = 4;
};

/**
 * One raw vsock connection per distcc job. The payload never parses the distcc
 * protocol: it starts `distccd --inetd` and splices bytes to its stdin/stdout.
 * stderr is discarded so it can never corrupt the protocol.
 */
class DistccBridge {
public:
    explicit DistccBridge(DistccConfig config);

    /** Takes ownership of fd and blocks until the job is finished. */
    void handle(int fd);

    size_t active_jobs() const { return active_.load(); }
    uint64_t total_jobs() const { return total_.load(); }
    void stop();

private:
    DistccConfig config_;
    std::string executable_;
    std::atomic<size_t> active_{0};
    std::atomic<uint64_t> total_{0};
    std::atomic<bool> stopping_{false};
    std::mutex mutex_;
    std::set<pid_t> children_;
};

}  // namespace fl
