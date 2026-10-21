#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace fl {

struct PtyOpenResult {
    uint32_t id = 0;
    uint32_t pid = 0;
    uint32_t initial_window = 0;
};

/**
 * Owns PTY-backed children. Session ids are monotonic and never reused. Every
 * session is bound to the connection that opened it; closing that connection
 * must call close_session().
 */
class PtyManager {
public:
    PtyManager(std::string apk_root, std::string storage_root, size_t max_sessions = 8);

    PtyOpenResult open(const std::vector<std::string>& argv, uint16_t columns, uint16_t rows,
                       uint32_t initial_window);

    bool resize(uint32_t id, uint16_t columns, uint16_t rows);
    bool signal(uint32_t id, int signal_number);
    bool write_input(uint32_t id, const void* data, size_t length);

    uint32_t credit(uint32_t id) const;
    bool grant(uint32_t id, uint32_t bytes);
    /** Waits until credit is available or the session is closed/gone. */
    bool wait_for_credit(uint32_t id, int timeout_ms);

    /** Master fd for poll(); -1 when the session is gone. */
    int master_fd(uint32_t id) const;

    /**
     * Duplicates the master under the session lock so a poll() cannot race a
     * close() and accidentally observe a reused descriptor number. The caller
     * owns the returned fd and must close it.
     */
    int duplicate_master_fd(uint32_t id) const;

    /** Returns bytes, 0 for no data/credit, -1 for EOF. */
    ssize_t read_output(uint32_t id, void* buffer, size_t capacity);

    /** Non-blocking wait; closes the master when the child has exited. */
    bool reap(uint32_t id, int* status);

    void erase(uint32_t id);
    void close_session(uint32_t id, bool force);
    void close_all();

    size_t size() const;
    std::vector<uint32_t> ids() const;

private:
    struct Session;

    std::shared_ptr<Session> find(uint32_t id) const;

    mutable std::mutex mutex_;
    std::unordered_map<uint32_t, std::shared_ptr<Session>> sessions_;
    uint32_t next_id_ = 1;
    size_t max_sessions_;
    std::string apk_root_;
    std::string storage_root_;
};

}  // namespace fl
