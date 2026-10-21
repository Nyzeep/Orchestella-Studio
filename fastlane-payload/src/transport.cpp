#include "fastlane/transport.h"

#include <fcntl.h>
#include <linux/vm_sockets.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>

#include "fastlane/error.h"

namespace fl {
namespace {

class FdListener : public Listener {
public:
    explicit FdListener(int fd) : fd_(fd) {}
    ~FdListener() override { close(); }

    int accept() override {
        for (;;) {
            const int listening = fd_.load();
            if (listening < 0) {
                return -1;
            }
            const int client = ::accept4(listening, nullptr, nullptr, SOCK_CLOEXEC);
            if (client >= 0) {
                return client;
            }
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
    }

    void close() override {
        const int fd = fd_.exchange(-1);
        if (fd < 0) {
            return;
        }
        ::shutdown(fd, SHUT_RDWR);
        ::close(fd);
    }

private:
    std::atomic<int> fd_;
};

void listen_or_throw(int fd) {
    if (::listen(fd, 8) != 0) {
        const int saved = errno;
        ::close(fd);
        throw Error(Code::kIo, std::string("listen failed: ") + std::strerror(saved));
    }
}

}  // namespace

std::unique_ptr<Listener> make_vsock_listener(uint16_t port) {
    const int fd = ::socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        throw Error(Code::kUnsupported, std::string("vsock socket failed: ") + std::strerror(errno));
    }
    sockaddr_vm address{};
    address.svm_family = AF_VSOCK;
    address.svm_cid = VMADDR_CID_ANY;
    address.svm_port = port;
    if (::bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        const int saved = errno;
        ::close(fd);
        throw Error(Code::kIo, std::string("vsock bind failed: ") + std::strerror(saved));
    }
    listen_or_throw(fd);
    return std::make_unique<FdListener>(fd);
}

std::unique_ptr<Listener> make_unix_listener(const std::string& path) {
    if (path.empty() || path.size() >= sizeof(sockaddr_un::sun_path)) {
        throw Error(Code::kPathRejected, "unix socket path is invalid");
    }
    struct stat info {};
    if (::lstat(path.c_str(), &info) == 0) {
        if (!S_ISSOCK(info.st_mode)) {
            throw Error(Code::kPathRejected, "socket path exists and is not a socket");
        }
        ::unlink(path.c_str());
    }
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        throw Error(Code::kIo, std::string("unix socket failed: ") + std::strerror(errno));
    }
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    std::strncpy(address.sun_path, path.c_str(), sizeof(address.sun_path) - 1);
    if (::bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        const int saved = errno;
        ::close(fd);
        throw Error(Code::kIo, std::string("unix bind failed: ") + std::strerror(saved));
    }
    ::chmod(path.c_str(), 0600);
    listen_or_throw(fd);
    return std::make_unique<FdListener>(fd);
}

}  // namespace fl
