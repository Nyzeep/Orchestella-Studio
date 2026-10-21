#include "test_support.h"

#include <fcntl.h>
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

#include "fastlane/protocol.h"
#include "fastlane/runtime.h"
#include "fastlane/storage.h"
#include "fastlane/tar.h"

namespace {

struct TempDir {
    std::string path;

    TempDir() {
        char pattern[] = "/tmp/fastlane-runtime-XXXXXX";
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
        throw std::runtime_error("connect failed");
    }
    timeval timeout{5, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    return fd;
}

void send_frame(int fd, fl::FrameType type, uint32_t session, std::vector<uint8_t> payload) {
    fl::write_frame(fd, fl::Frame{type, session, std::move(payload)});
}

fl::Frame expect(fl::FrameReader& reader, fl::FrameType type) {
    fl::Frame frame;
    if (!reader.next(frame)) {
        throw std::runtime_error("unexpected eof");
    }
    FL_CHECK(frame.type == type);
    return frame;
}


fl::PayloadConfig make_config(const TempDir& temp, const std::string& socket_directory) {
    std::filesystem::create_directories(socket_directory);
    fl::PayloadConfig config;
    config.apk_root = "/usr";
    config.storage_root = temp.path;
    config.unix_transport = true;
    config.socket_directory = socket_directory;
    config.handle_signals = false;
    config.enable_proxy_tcp = false;
    config.max_sessions = 4;
    config.max_connections = 4;
    config.proxy_pair_timeout_ms = 100;
    return config;
}

std::string write_payload_file(const std::string& path, const std::string& content) {
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        throw std::runtime_error("payload file open failed");
    }
    size_t offset = 0;
    while (offset < content.size()) {
        const ssize_t written = ::write(fd, content.data() + offset, content.size() - offset);
        if (written <= 0) {
            ::close(fd);
            throw std::runtime_error("payload file write failed");
        }
        offset += static_cast<size_t>(written);
    }
    ::close(fd);
    return path;
}

std::string read_file(const std::string& path) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        throw std::runtime_error("read failed");
    }
    std::string content;
    char buffer[4096];
    for (;;) {
        const ssize_t read = ::read(fd, buffer, sizeof(buffer));
        if (read <= 0) {
            break;
        }
        content.append(buffer, static_cast<size_t>(read));
    }
    ::close(fd);
    return content;
}

}  // namespace

FL_TEST(runtime_requires_hello_first) {
    TempDir temp;
    const auto config = make_config(temp, temp.path + "/sockets");
    fl::Runtime runtime(config);
    runtime.start();
    const int fd = connect_unix(runtime.socket_path(config.control_port));
    send_frame(fd, fl::FrameType::kPing, 0, {});
    fl::FdSource source(fd);
    fl::FrameReader reader(source);
    const fl::Frame error = expect(reader, fl::FrameType::kError);
    FL_CHECK(error.payload.size() >= 2);
    const uint16_t code = static_cast<uint16_t>((error.payload[0] << 8) | error.payload[1]);
    FL_CHECK(code != 0);
    ::close(fd);
    runtime.stop();
}

FL_TEST(runtime_controls_pty_exports_and_imports) {
    TempDir temp;
    const auto config = make_config(temp, temp.path + "/sockets");
    fl::Runtime runtime(config);
    runtime.start();

    const int fd = connect_unix(runtime.socket_path(config.control_port));
    fl::FdSource source(fd);
    fl::FrameReader reader(source);

    fl::PayloadWriter hello;
    hello.u16(1);
    hello.u16(1);
    hello.u8(0);
    send_frame(fd, fl::FrameType::kHello, 0, hello.take());
    const fl::Frame hello_reply = expect(reader, fl::FrameType::kHelloOk);
    {
        fl::PayloadReader parsed(hello_reply.payload.data(), hello_reply.payload.size());
        uint16_t version = 0;
        uint32_t capabilities = 0;
        uint32_t max_frame = 0;
        uint32_t window = 0;
        FL_CHECK(parsed.u16(&version));
        FL_CHECK_EQ(version, 1);
        FL_CHECK(parsed.u32(&capabilities));
        FL_CHECK((capabilities & 1u) != 0);
        FL_CHECK((capabilities & 4u) != 0);
        FL_CHECK((capabilities & 8u) != 0);
        FL_CHECK(parsed.u32(&max_frame));
        FL_CHECK_EQ(max_frame, 65536u);
        FL_CHECK(parsed.u32(&window));
        FL_CHECK(window > 0);
    }

    send_frame(fd, fl::FrameType::kPing, 0, {0xAA, 0xBB});
    const fl::Frame pong = expect(reader, fl::FrameType::kPong);
    FL_CHECK(pong.payload == std::vector<uint8_t>({0xAA, 0xBB}));

    fl::PayloadWriter open;
    open.u16(80);
    open.u16(24);
    open.u8(3);
    open.string("sh");
    open.string("-c");
    open.string("printf hello");
    send_frame(fd, fl::FrameType::kOpenPty, 0, open.take());
    const fl::Frame opened = expect(reader, fl::FrameType::kPtyOpened);
    uint32_t session = opened.session_id;
    FL_CHECK(session > 0);
    {
        fl::PayloadReader parsed(opened.payload.data(), opened.payload.size());
        uint32_t pid = 0;
        uint32_t window = 0;
        FL_CHECK(parsed.u32(&pid));
        FL_CHECK(pid > 0);
        FL_CHECK(parsed.u32(&window));
    }

    std::string terminal;
    bool saw_exit = false;
    int exit_code = -1;
    for (int frame_count = 0; frame_count < 100 && !saw_exit; ++frame_count) {
        fl::Frame frame;
        FL_CHECK(reader.next(frame));
        if (frame.type == fl::FrameType::kPtyData) {
            FL_CHECK_EQ(frame.session_id, session);
            terminal.append(reinterpret_cast<const char*>(frame.payload.data()),
                            frame.payload.size());
        } else if (frame.type == fl::FrameType::kEof) {
            FL_CHECK_EQ(frame.session_id, session);
        } else if (frame.type == fl::FrameType::kExit) {
            saw_exit = true;
            FL_CHECK_EQ(frame.session_id, session);
            fl::PayloadReader parsed(frame.payload.data(), frame.payload.size());
            uint32_t code = 0;
            uint8_t signal_number = 0;
            FL_CHECK(parsed.u32(&code));
            FL_CHECK(parsed.u8(&signal_number));
            exit_code = static_cast<int>(code);
        } else if (frame.type == fl::FrameType::kError) {
            throw std::runtime_error("unexpected error frame");
        }
    }
    FL_CHECK(saw_exit);
    FL_CHECK_EQ(exit_code, 0);
    FL_CHECK_EQ(terminal, std::string("hello"));

    write_payload_file(runtime.layout().home() + "/a.txt", "exported");
    fl::PayloadWriter export_request;
    export_request.u8(0);
    export_request.u16(1);
    export_request.string("home");
    send_frame(fd, fl::FrameType::kExport, 0, export_request.take());
    expect(reader, fl::FrameType::kExportBegin);
    std::vector<uint8_t> tar_bytes;
    for (;;) {
        fl::Frame frame;
        FL_CHECK(reader.next(frame));
        if (frame.type == fl::FrameType::kExportData) {
            tar_bytes.insert(tar_bytes.end(), frame.payload.begin(), frame.payload.end());
            continue;
        }
        if (frame.type == fl::FrameType::kExportEnd) {
            break;
        }
        throw std::runtime_error("unexpected export frame");
    }
    const std::string tar_path = temp.path + "/export.tar";
    write_payload_file(tar_path, std::string(tar_bytes.begin(), tar_bytes.end()));
    {
        const int tar_fd = ::open(tar_path.c_str(), O_RDONLY | O_CLOEXEC);
        FL_CHECK(tar_fd >= 0);
        fl::TarReader tar(tar_fd);
        fl::TarEntry entry;
        bool found = false;
        while (tar.next(&entry)) {
            if (entry.type == '0' && entry.path == "home/a.txt") {
                std::string content(entry.size, '\0');
                size_t offset = 0;
                while (offset < content.size()) {
                    const size_t read =
                            tar.read_data(&content[offset], content.size() - offset);
                    FL_CHECK(read > 0);
                    offset += read;
                }
                FL_CHECK_EQ(content, std::string("exported"));
                found = true;
            }
        }
        ::close(tar_fd);
        FL_CHECK(found);
    }

    const std::string imported_source = temp.path + "/imported-source";
    write_payload_file(imported_source, "imported-content");
    const std::string import_tar = temp.path + "/import.tar";
    {
        const int out = ::open(import_tar.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
        FL_CHECK(out >= 0);
        const int data = ::open(imported_source.c_str(), O_RDONLY | O_CLOEXEC);
        FL_CHECK(data >= 0);
        fl::TarWriter writer(out);
        writer.add_file("home/imported.txt", data, 16, 0644, 1700000000);
        writer.finish();
        ::close(data);
        ::close(out);
    }
    uint64_t import_size = 0;
    const fl::Sha256Digest import_hash = fl::sha256_file(import_tar, &import_size);

    fl::PayloadWriter begin;
    begin.u8(0);
    begin.u64(0);  // unknown total; IMPORT_END carries the authoritative size
    send_frame(fd, fl::FrameType::kImportBegin, 0, begin.take());
    const int import_fd = ::open(import_tar.c_str(), O_RDONLY | O_CLOEXEC);
    FL_CHECK(import_fd >= 0);
    uint8_t buffer[1000];
    for (;;) {
        const ssize_t read = ::read(import_fd, buffer, sizeof(buffer));
        if (read <= 0) {
            break;
        }
        send_frame(fd, fl::FrameType::kImportData, 0,
                   std::vector<uint8_t>(buffer, buffer + read));
    }
    ::close(import_fd);
    fl::PayloadWriter end;
    end.u64(import_size);
    end.bytes(import_hash.data(), import_hash.size());
    send_frame(fd, fl::FrameType::kImportEnd, 0, end.take());
    const fl::Frame imported = expect(reader, fl::FrameType::kImportDone);
    {
        fl::PayloadReader parsed(imported.payload.data(), imported.payload.size());
        uint64_t files = 0;
        uint64_t bytes = 0;
        FL_CHECK(parsed.u64(&files));
        FL_CHECK(parsed.u64(&bytes));
        FL_CHECK_EQ(files, 1u);
        FL_CHECK_EQ(bytes, 16u);
    }
    FL_CHECK_EQ(read_file(runtime.layout().home() + "/imported.txt"),
                std::string("imported-content"));

    send_frame(fd, fl::FrameType::kMetrics, 0, {});
    const fl::Frame metrics = expect(reader, fl::FrameType::kMetricsOk);
    FL_CHECK(metrics.payload.size() >= 28);

    ::close(fd);
    runtime.stop();
}
