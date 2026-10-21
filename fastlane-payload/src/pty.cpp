#include "fastlane/pty.h"

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cerrno>
#include <cstdint>
#include <cstring>

#include "fastlane/error.h"
#include "fastlane/process.h"
#include "fastlane/protocol.h"

namespace fl {

struct PtyManager::Session {
    uint32_t id = 0;
    pid_t pid = -1;
    int master_fd = -1;
    uint32_t max_credit = 0;
    std::atomic<uint32_t> credit{0};
    mutable std::mutex mutex;
    std::condition_variable credit_ready;
    bool closed = false;
};

PtyManager::PtyManager(std::string apk_root, std::string storage_root, size_t max_sessions)
        : max_sessions_(max_sessions), apk_root_(std::move(apk_root)),
          storage_root_(std::move(storage_root)) {}

std::shared_ptr<PtyManager::Session> PtyManager::find(uint32_t id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    const auto iterator = sessions_.find(id);
    return iterator == sessions_.end() ? nullptr : iterator->second;
}

PtyOpenResult PtyManager::open(const std::vector<std::string>& argv, uint16_t columns,
                               uint16_t rows, uint32_t initial_window) {
    if (argv.empty() || argv.size() > 16) {
        throw Error(Code::kExecFailed, "argv must have 1..16 entries");
    }
    size_t total = 0;
    for (const std::string& argument : argv) {
        if (argument.size() > 4096) {
            throw Error(Code::kExecFailed, "argv entry too long");
        }
        total += argument.size();
    }
    if (total > 4096) {
        throw Error(Code::kExecFailed, "argv too long");
    }
    if (initial_window == 0) {
        initial_window = kDefaultInitialWindow;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (sessions_.size() >= max_sessions_) {
        throw Error(Code::kSessionLimit, "PTY session limit reached");
    }

    SpawnSpec spec;
    spec.argv.push_back(resolve_executable(apk_root_, argv.front()));
    spec.argv.insert(spec.argv.end(), argv.begin() + 1, argv.end());
    spec.environment = child_environment(apk_root_, storage_root_);
    spec.working_directory = storage_root_ + "/home";

    const PtyChild child = spawn_pty(spec, columns, rows);
    auto session = std::make_shared<Session>();
    while (next_id_ == 0 || sessions_.count(next_id_) != 0) {
        ++next_id_;
    }
    session->id = next_id_++;
    session->pid = child.pid;
    session->master_fd = child.master_fd;
    const uint64_t doubled = static_cast<uint64_t>(initial_window) * 2;
    session->max_credit = static_cast<uint32_t>(std::min<uint64_t>(doubled, UINT32_MAX));
    session->credit.store(initial_window);
    sessions_.emplace(session->id, session);

    PtyOpenResult result;
    result.id = session->id;
    result.pid = static_cast<uint32_t>(child.pid);
    result.initial_window = initial_window;
    return result;
}

bool PtyManager::resize(uint32_t id, uint16_t columns, uint16_t rows) {
    const auto session = find(id);
    if (!session) {
        return false;
    }
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed) {
        return false;
    }
    struct winsize window {};
    window.ws_col = columns;
    window.ws_row = rows;
    return ::ioctl(session->master_fd, TIOCSWINSZ, &window) == 0;
}

bool PtyManager::signal(uint32_t id, int signal_number) {
    const auto session = find(id);
    if (!session || signal_number <= 0 || signal_number > 64) {
        return false;
    }
    terminate_child(session->pid, signal_number);
    return true;
}

bool PtyManager::write_input(uint32_t id, const void* data, size_t length) {
    const auto session = find(id);
    if (!session) {
        return false;
    }
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t offset = 0;
    while (offset < length) {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->closed) {
            return false;
        }
        const ssize_t written =
                ::write(session->master_fd, bytes + offset, length - offset);
        if (written > 0) {
            offset += static_cast<size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) {
            continue;
        }
        if (written < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            pollfd descriptor{session->master_fd, POLLOUT, 0};
            ::poll(&descriptor, 1, 200);
            continue;
        }
        return false;
    }
    return true;
}

uint32_t PtyManager::credit(uint32_t id) const {
    const auto session = find(id);
    return session ? session->credit.load() : 0;
}

bool PtyManager::grant(uint32_t id, uint32_t bytes) {
    const auto session = find(id);
    if (!session || bytes == 0) {
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->closed) {
            return false;
        }
    }
    uint32_t current = session->credit.load();
    for (;;) {
        const uint32_t updated = std::min(session->max_credit, current + bytes);
        if (session->credit.compare_exchange_weak(current, updated)) {
            session->credit_ready.notify_all();
            return true;
        }
    }
}

bool PtyManager::wait_for_credit(uint32_t id, int timeout_ms) {
    const auto session = find(id);
    if (!session) {
        return false;
    }
    std::unique_lock<std::mutex> lock(session->mutex);
    return session->credit_ready.wait_for(lock, std::chrono::milliseconds(timeout_ms), [&session] {
        return session->closed || session->credit.load() > 0;
    });
}

int PtyManager::master_fd(uint32_t id) const {
    const auto session = find(id);
    if (!session) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(session->mutex);
    return session->closed ? -1 : session->master_fd;
}

int PtyManager::duplicate_master_fd(uint32_t id) const {
    const auto session = find(id);
    if (!session) {
        return -1;
    }
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed || session->master_fd < 0) {
        return -1;
    }
    return ::fcntl(session->master_fd, F_DUPFD_CLOEXEC, 0);
}

ssize_t PtyManager::read_output(uint32_t id, void* buffer, size_t capacity) {    const auto session = find(id);
    if (!session) {
        return -1;
    }
    const uint32_t available = session->credit.load();
    if (available == 0) {
        return 0;
    }
    const size_t limit = std::min<size_t>(capacity, available);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed) {
        return -1;
    }
    const ssize_t read = ::read(session->master_fd, buffer, limit);
    if (read > 0) {
        session->credit.fetch_sub(static_cast<uint32_t>(read));
        return read;
    }
    if (read == 0) {
        return -1;
    }
    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
        return 0;
    }
    return -1;
}

bool PtyManager::reap(uint32_t id, int* status) {
    const auto session = find(id);
    if (!session) {
        return false;
    }
    int local_status = 0;
    const pid_t result = ::waitpid(session->pid, &local_status, WNOHANG);
    if (result != session->pid) {
        return false;
    }
    if (status != nullptr) {
        *status = local_status;
    }
    std::lock_guard<std::mutex> lock(session->mutex);
    if (!session->closed) {
        session->closed = true;
        ::close(session->master_fd);
        session->master_fd = -1;
        session->credit_ready.notify_all();
    }
    return true;
}

void PtyManager::erase(uint32_t id) {
    std::lock_guard<std::mutex> lock(mutex_);
    sessions_.erase(id);
}

void PtyManager::close_session(uint32_t id, bool force) {
    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto iterator = sessions_.find(id);
        if (iterator == sessions_.end()) {
            return;
        }
        session = iterator->second;
    }

    bool needs_kill = false;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!session->closed) {
            session->closed = true;
            session->credit_ready.notify_all();
            needs_kill = true;
        }
    }
    if (!needs_kill) {
        erase(id);
        return;
    }

    if (force) {
        terminate_child(session->pid, SIGKILL);
    } else {
        terminate_child(session->pid, SIGHUP);
    }
    int status = 0;
    if (!wait_for_child(session->pid, force ? 500 : 700, &status)) {
        terminate_child(session->pid, SIGKILL);
        wait_for_child(session->pid, 500, &status);
    }
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->master_fd >= 0) {
            ::close(session->master_fd);
            session->master_fd = -1;
        }
    }
    erase(id);
}

void PtyManager::close_all() {
    for (const uint32_t id : ids()) {
        close_session(id, false);
    }
}

size_t PtyManager::size() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return sessions_.size();
}

std::vector<uint32_t> PtyManager::ids() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<uint32_t> result;
    result.reserve(sessions_.size());
    for (const auto& entry : sessions_) {
        result.push_back(entry.first);
    }
    return result;
}

}  // namespace fl
