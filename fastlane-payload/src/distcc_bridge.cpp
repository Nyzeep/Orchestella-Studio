#include "fastlane/distcc_bridge.h"

#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <thread>

#include "fastlane/error.h"
#include "fastlane/process.h"

namespace fl {
namespace {

constexpr int kIdleTimeoutMs = 120000;

void close_quietly(int fd) {
    if (fd >= 0) {
        ::close(fd);
    }
}

}  // namespace

DistccBridge::DistccBridge(DistccConfig config) : config_(std::move(config)) {
    if (config_.arguments.empty()) {
        config_.arguments = {"--inetd"};
    }
    if (config_.max_jobs == 0) {
        config_.max_jobs = 1;
    }
    if (config_.distccd_path.empty()) {
        executable_ = resolve_executable(config_.apk_root, "distccd");
    } else {
        executable_ = resolve_executable(config_.apk_root, config_.distccd_path);
    }
}

void DistccBridge::handle(int fd) {
    if (fd < 0) {
        return;
    }
    if (stopping_.load()) {
        close_quietly(fd);
        return;
    }
    if (active_.fetch_add(1) >= config_.max_jobs) {
        active_.fetch_sub(1);
        close_quietly(fd);
        return;
    }
    total_.fetch_add(1);

    SpawnSpec spec;
    spec.argv.push_back(executable_);
    spec.argv.insert(spec.argv.end(), config_.arguments.begin(), config_.arguments.end());
    spec.environment = child_environment(config_.apk_root, config_.storage_root);
    spec.working_directory = config_.storage_root.empty() ? "/" : config_.storage_root + "/tmp";

    PipedChild child;
    try {
        child = spawn_piped(spec);
    } catch (...) {
        active_.fetch_sub(1);
        close_quietly(fd);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        children_.insert(child.pid);
    }

    std::thread uplink([fd, &child]() {
        uint8_t buffer[16384];
        for (;;) {
            const ssize_t read = ::read(fd, buffer, sizeof(buffer));
            if (read > 0) {
                size_t offset = 0;
                while (offset < static_cast<size_t>(read)) {
                    const ssize_t written =
                            ::write(child.input_fd, buffer + offset,
                                    static_cast<size_t>(read) - offset);
                    if (written > 0) {
                        offset += static_cast<size_t>(written);
                        continue;
                    }
                    if (written < 0 && errno == EINTR) {
                        continue;
                    }
                    ::shutdown(fd, SHUT_RDWR);
                    ::close(child.input_fd);
                    return;
                }
                continue;
            }
            if (read == 0) {
                break;
            }
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        ::shutdown(child.input_fd, SHUT_WR);
        ::close(child.input_fd);
    });

    uint8_t buffer[16384];
    for (;;) {
        pollfd descriptor{child.output_fd, POLLIN, 0};
        const int ready = ::poll(&descriptor, 1, kIdleTimeoutMs);
        if (ready <= 0) {
            break;
        }
        const ssize_t read = ::read(child.output_fd, buffer, sizeof(buffer));
        if (read > 0) {
            size_t offset = 0;
            while (offset < static_cast<size_t>(read)) {
                const ssize_t written =
                        ::write(fd, buffer + offset, static_cast<size_t>(read) - offset);
                if (written > 0) {
                    offset += static_cast<size_t>(written);
                    continue;
                }
                if (written < 0 && errno == EINTR) {
                    continue;
                }
                ::shutdown(fd, SHUT_RDWR);
                goto done;
            }
            continue;
        }
        if (read == 0) {
            break;
        }
        if (errno == EINTR) {
            continue;
        }
        break;
    }

done:
    ::shutdown(fd, SHUT_WR);
    uplink.join();
    int status = 0;
    if (!wait_for_child(child.pid, 5000, &status)) {
        terminate_child(child.pid, SIGKILL);
        wait_for_child(child.pid, 1000, &status);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        children_.erase(child.pid);
    }
    close_quietly(child.output_fd);
    close_quietly(fd);
    active_.fetch_sub(1);
}

void DistccBridge::stop() {
    stopping_.store(true);
    std::lock_guard<std::mutex> lock(mutex_);
    for (const pid_t pid : children_) {
        terminate_child(pid, SIGKILL);
    }
}

}  // namespace fl
