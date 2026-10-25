// Listening sockets and the fd-backed AXP1 transport for the compile
// service.
//
// The service deliberately carries its own thin socket layer instead of
// reusing fastlane_payload_core: the payload .so must not pull in the FLN1
// transport (it links the vsock listener only), and the FLN1 core includes
// <linux/vm_sockets.h> unconditionally, which keeps it from building on a
// non-Linux host. Treating a socket handle as int64_t also avoids the Win64
// SOCKET truncation trap in the host twin.
//
// No file writes anywhere: the Unix-socket listener is host-test only and is
// compiled out of the payload build.

#ifndef FASTLANE_PVM_NET_H_
#define FASTLANE_PVM_NET_H_

#include <cstdint>
#include <memory>
#include <string>

#include "axp1_server.h"

namespace fl {
namespace pvm {

/** Native socket handle widened so Win64 SOCKET survives the trip. */
using SocketHandle = int64_t;
constexpr SocketHandle kInvalidSocket = -1;

void close_socket(SocketHandle handle);

/** Listening socket; accept() blocks until a peer arrives or close() runs. */
class Listener {
public:
    virtual ~Listener() = default;
    /** Returns a connected handle, or kInvalidSocket after close(). */
    virtual SocketHandle accept() = 0;
    virtual void close() = 0;
    /** Bound port, or 0 for vsock / Unix sockets. */
    virtual uint16_t port() const = 0;
};

/** Loopback TCP listener (port 0 picks an ephemeral one). */
std::unique_ptr<Listener> make_loopback_tcp_listener(uint16_t port);

/** AF_VSOCK listener; nullptr on platforms without vsock. */
std::unique_ptr<Listener> make_vsock_listener(uint16_t port);

#if !defined(__ANDROID__) && !defined(_WIN32)
/** Unix-domain listener for local e2e on a POSIX host (not the payload). */
std::unique_ptr<Listener> make_unix_listener(const std::string& path);
#endif

/** Connects to 127.0.0.1:port; returns kInvalidSocket on failure. */
SocketHandle connect_loopback_tcp(uint16_t port);

/** AXP1 transport over a connected socket; owns the handle. */
class SocketTransport final : public Axp1Transport {
public:
    explicit SocketTransport(SocketHandle handle) : handle_(handle) {}
    ~SocketTransport() override;

    SocketTransport(const SocketTransport&) = delete;
    SocketTransport& operator=(const SocketTransport&) = delete;

    long read(uint8_t* buffer, size_t size) override;
    bool write(const uint8_t* buffer, size_t size) override;
    void shutdown() override;
    void set_read_timeout_ms(uint32_t timeout_ms) override;

private:
    SocketHandle handle_;
};

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_NET_H_
