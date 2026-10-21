#pragma once

#include <cstdint>
#include <memory>
#include <string>

namespace fl {

class Listener {
public:
    virtual ~Listener() = default;
    /** Returns a connected fd, or -1 after close/stop. */
    virtual int accept() = 0;
    virtual void close() = 0;
};

std::unique_ptr<Listener> make_vsock_listener(uint16_t port);
std::unique_ptr<Listener> make_unix_listener(const std::string& path);

}  // namespace fl
