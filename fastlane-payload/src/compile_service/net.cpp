#include "net.h"

#include <cerrno>
#include <cstring>

#if defined(_WIN32)
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <winsock2.h>
#  include <ws2tcpip.h>
#else
#  include <netinet/in.h>
#  include <sys/socket.h>
#  include <sys/time.h>
#  include <sys/types.h>
#  include <sys/un.h>
#  include <unistd.h>
#  if defined(__linux__)
#    include <linux/vm_sockets.h>
#  endif
#endif

namespace fl {
namespace pvm {
namespace {

#if defined(_WIN32)

bool ensure_winsock() {
    static const bool ready = [] {
        WSADATA data;
        return WSAStartup(MAKEWORD(2, 2), &data) == 0;
    }();
    return ready;
}

using NativeSocket = SOCKET;

NativeSocket to_native(SocketHandle handle) {
    return static_cast<NativeSocket>(handle);
}

bool is_would_block(int error) {
    return error == WSAEWOULDBLOCK || error == WSAETIMEDOUT;
}

int last_socket_error() { return WSAGetLastError(); }

constexpr int kInterrupted = WSAEINTR;

#else

using NativeSocket = int;

NativeSocket to_native(SocketHandle handle) {
    return static_cast<NativeSocket>(handle);
}

bool is_would_block(int error) {
    return error == EAGAIN || error == EWOULDBLOCK;
}

int last_socket_error() { return errno; }

constexpr int kInterrupted = EINTR;

#endif

constexpr int kSendFlags =
#if defined(MSG_NOSIGNAL)
    MSG_NOSIGNAL;
#else
    0;
#endif

class TcpListener : public Listener {
public:
    explicit TcpListener(NativeSocket handle) : handle_(handle) {}
    ~TcpListener() override { close(); }

    SocketHandle accept() override {
        for (;;) {
            const NativeSocket peer = ::accept(handle_, nullptr, nullptr);
            if (peer != static_cast<NativeSocket>(-1)) {
                return static_cast<SocketHandle>(peer);
            }
            const int error = last_socket_error();
            if (error == kInterrupted) {
                continue;
            }
            return kInvalidSocket;
        }
    }

    void close() override {
        if (handle_ == static_cast<NativeSocket>(-1)) {
            return;
        }
#if defined(_WIN32)
        ::closesocket(handle_);
#else
        ::close(handle_);
#endif
        handle_ = static_cast<NativeSocket>(-1);
    }

    uint16_t port() const override { return port_; }

    void set_port(uint16_t port) { port_ = port; }

private:
    NativeSocket handle_;
    uint16_t port_ = 0;
};

#if !defined(__ANDROID__) && !defined(_WIN32)
class UnixListener final : public Listener {
public:
    explicit UnixListener(NativeSocket handle) : handle_(handle) {}
    ~UnixListener() override { close(); }

    SocketHandle accept() override {
        for (;;) {
            const NativeSocket peer = ::accept(handle_, nullptr, nullptr);
            if (peer != static_cast<NativeSocket>(-1)) {
                return static_cast<SocketHandle>(peer);
            }
            if (last_socket_error() == kInterrupted) {
                continue;
            }
            return kInvalidSocket;
        }
    }

    void close() override {
        if (handle_ == static_cast<NativeSocket>(-1)) {
            return;
        }
        ::close(handle_);
        handle_ = static_cast<NativeSocket>(-1);
    }

    uint16_t port() const override { return 0; }

private:
    NativeSocket handle_;
};
#endif

#if defined(__linux__)
class VsockListener final : public TcpListener {
public:
    using TcpListener::TcpListener;
};
#endif

}  // namespace

void close_socket(SocketHandle handle) {
    if (handle == kInvalidSocket) {
        return;
    }
#if defined(_WIN32)
    ::closesocket(to_native(handle));
#else
    ::close(to_native(handle));
#endif
}

std::unique_ptr<Listener> make_loopback_tcp_listener(uint16_t port) {
#if defined(_WIN32)
    if (!ensure_winsock()) {
        return nullptr;
    }
#endif
    const NativeSocket handle = ::socket(AF_INET, SOCK_STREAM, 0);
    if (handle == static_cast<NativeSocket>(-1)) {
        return nullptr;
    }
    const int enable = 1;
    ::setsockopt(handle, SOL_SOCKET, SO_REUSEADDR,
                 reinterpret_cast<const char*>(&enable), sizeof(enable));

    sockaddr_in address;
    std::memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    if (::bind(handle, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
        ::listen(handle, 16) != 0) {
        close_socket(static_cast<SocketHandle>(handle));
        return nullptr;
    }

    uint16_t bound = port;
    if (bound == 0) {
        sockaddr_in actual;
        std::memset(&actual, 0, sizeof(actual));
#if defined(_WIN32)
        int length = sizeof(actual);
#else
        socklen_t length = sizeof(actual);
#endif
        if (::getsockname(handle, reinterpret_cast<sockaddr*>(&actual), &length) == 0) {
            bound = ntohs(actual.sin_port);
        }
    }
    auto listener = std::unique_ptr<TcpListener>(
        new TcpListener(static_cast<SocketHandle>(handle)));
    listener->set_port(bound);
    return listener;
}

std::unique_ptr<Listener> make_vsock_listener(uint16_t port) {
#if defined(__linux__)
    const NativeSocket handle = ::socket(AF_VSOCK, SOCK_STREAM, 0);
    if (handle == static_cast<NativeSocket>(-1)) {
        return nullptr;
    }
    sockaddr_vm address;
    std::memset(&address, 0, sizeof(address));
    address.svm_family = AF_VSOCK;
    address.svm_cid = VMADDR_CID_ANY;
    address.svm_port = port;
    if (::bind(handle, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
        ::listen(handle, 16) != 0) {
        close_socket(static_cast<SocketHandle>(handle));
        return nullptr;
    }
    auto listener =
        std::unique_ptr<VsockListener>(new VsockListener(static_cast<SocketHandle>(handle)));
    listener->set_port(port);
    return listener;
#else
    (void)port;
    return nullptr;
#endif
}

#if !defined(__ANDROID__) && !defined(_WIN32)
std::unique_ptr<Listener> make_unix_listener(const std::string& path) {
    const NativeSocket handle = ::socket(AF_UNIX, SOCK_STREAM, 0);
    if (handle == static_cast<NativeSocket>(-1)) {
        return nullptr;
    }
    ::unlink(path.c_str());
    sockaddr_un address;
    std::memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (path.size() >= sizeof(address.sun_path)) {
        ::close(handle);
        return nullptr;
    }
    std::memcpy(address.sun_path, path.c_str(), path.size());
    if (::bind(handle, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
        ::listen(handle, 16) != 0) {
        ::close(handle);
        return nullptr;
    }
    return std::unique_ptr<Listener>(
        new UnixListener(static_cast<SocketHandle>(handle)));
}
#endif

SocketHandle connect_loopback_tcp(uint16_t port) {
#if defined(_WIN32)
    if (!ensure_winsock()) {
        return kInvalidSocket;
    }
#endif
    const NativeSocket handle = ::socket(AF_INET, SOCK_STREAM, 0);
    if (handle == static_cast<NativeSocket>(-1)) {
        return kInvalidSocket;
    }
    sockaddr_in address;
    std::memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons(port);
    if (::connect(handle, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        close_socket(static_cast<SocketHandle>(handle));
        return kInvalidSocket;
    }
    return static_cast<SocketHandle>(handle);
}

SocketTransport::~SocketTransport() { close_socket(handle_); }

long SocketTransport::read(uint8_t* buffer, size_t size) {
    for (;;) {
        const size_t wanted = size > 0x7fffffffu ? 0x7fffffffu : size;
        const int chunk = static_cast<int>(
            ::recv(to_native(handle_), reinterpret_cast<char*>(buffer),
                   static_cast<int>(wanted), 0));
        if (chunk > 0) {
            return chunk;
        }
        if (chunk == 0) {
            return 0;  // orderly shutdown
        }
        const int error = last_socket_error();
        if (error == kInterrupted) {
            continue;
        }
        if (is_would_block(error)) {
            return -2;  // deadline expired, not a transport failure
        }
        return -1;
    }
}

bool SocketTransport::write(const uint8_t* buffer, size_t size) {
    size_t total = 0;
    while (total < size) {
        const size_t remaining = size - total;
        const size_t wanted = remaining > 0x7fffffffu ? 0x7fffffffu : remaining;
        const int chunk = static_cast<int>(
            ::send(to_native(handle_), reinterpret_cast<const char*>(buffer + total),
                   static_cast<int>(wanted), kSendFlags));
        if (chunk > 0) {
            total += static_cast<size_t>(chunk);
            continue;
        }
        const int error = last_socket_error();
        if (error == kInterrupted) {
            continue;
        }
        return false;
    }
    return true;
}

void SocketTransport::shutdown() {
    if (handle_ == kInvalidSocket) {
        return;
    }
#if defined(_WIN32)
    ::shutdown(to_native(handle_), SD_SEND);
#else
    ::shutdown(to_native(handle_), SHUT_WR);
#endif
}

void SocketTransport::set_read_timeout_ms(uint32_t timeout_ms) {
    if (handle_ == kInvalidSocket) {
        return;
    }
#if defined(_WIN32)
    const DWORD timeout = timeout_ms;
    ::setsockopt(to_native(handle_), SOL_SOCKET, SO_RCVTIMEO,
                 reinterpret_cast<const char*>(&timeout), sizeof(timeout));
#else
    timeval timeout;
    timeout.tv_sec = static_cast<long>(timeout_ms / 1000u);
    timeout.tv_usec = static_cast<long>((timeout_ms % 1000u) * 1000u);
    ::setsockopt(to_native(handle_), SOL_SOCKET, SO_RCVTIMEO, &timeout,
                 sizeof(timeout));
#endif
}

}  // namespace pvm
}  // namespace fl
