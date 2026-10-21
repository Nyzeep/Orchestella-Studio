#include "fastlane/runtime.h"

#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <set>
#include <thread>

#include "fastlane/distcc_bridge.h"
#include "fastlane/error.h"
#include "fastlane/paths.h"
#include "fastlane/process.h"
#include "fastlane/protocol.h"
#include "fastlane/proxy_bridge.h"
#include "fastlane/pty.h"
#include "fastlane/transport.h"
#include "fastlane/vm_payload_api.h"

namespace fl {

struct RuntimeState {
    std::atomic<bool> stopping{false};
    std::atomic<size_t> readers{0};
    std::atomic<size_t> connections{0};
    std::atomic<uint64_t> pty_in{0};
    std::atomic<uint64_t> pty_out{0};
    std::mutex mutex;
    std::condition_variable idle;
    std::set<int> connection_fds;
};

struct RuntimeContext {
    explicit RuntimeContext(PayloadConfig configuration)
            : config(std::move(configuration)),
              layout(StorageLayout::initialize(config.storage_root)),
              started_at(std::chrono::steady_clock::now()) {
        pty = std::make_shared<PtyManager>(config.apk_root, config.storage_root,
                                           config.max_sessions);
        state = std::make_shared<RuntimeState>();
        try {
            ProxyBridgeConfig proxy_config;
            proxy_config.socket_directory = layout.tmp();
            proxy_config.socket_name = "proxy.sock";
            proxy_config.enable_tcp = config.enable_proxy_tcp;
            proxy_config.max_connections = config.max_connections;
            proxy_config.pair_timeout_ms = config.proxy_pair_timeout_ms;
            proxy_config.idle_timeout_ms = config.proxy_idle_timeout_ms;
            auto proxy_instance = std::make_shared<ProxyBridge>(proxy_config);
            proxy_instance->start();
            proxy = proxy_instance;
        } catch (const std::exception&) {
            proxy.reset();
        }
        try {
            DistccConfig distcc_config;
            distcc_config.apk_root = config.apk_root;
            distcc_config.storage_root = config.storage_root;
            distcc_config.distccd_path = config.distccd_path;
            distcc = std::make_shared<DistccBridge>(distcc_config);
        } catch (const std::exception&) {
            distcc.reset();
        }
    }

    PayloadConfig config;
    StorageLayout layout;
    std::shared_ptr<PtyManager> pty;
    std::shared_ptr<RuntimeState> state;
    std::shared_ptr<ProxyBridge> proxy;
    std::shared_ptr<DistccBridge> distcc;
    std::chrono::steady_clock::time_point started_at;
};

namespace {

constexpr uint64_t kMaxImportBytes = 16ULL * 1024 * 1024 * 1024;

std::atomic<bool> g_stop_requested{false};

extern "C" void handle_stop_signal(int) {
    g_stop_requested.store(true);
}

std::string environment(const char* name) {
    const char* value = std::getenv(name);
    return value == nullptr ? std::string() : std::string(value);
}

uint16_t parse_port(const char* name, uint16_t fallback) {
    const std::string value = environment(name);
    if (value.empty()) {
        return fallback;
    }
    char* end = nullptr;
    const long parsed = std::strtol(value.c_str(), &end, 10);
    if (end == nullptr || *end != '\0' || parsed < 1 || parsed > 65535) {
        throw Error(Code::kInternal, std::string("invalid port in ") + name);
    }
    return static_cast<uint16_t>(parsed);
}

uint32_t parse_u32(const char* name, uint32_t fallback) {
    const std::string value = environment(name);
    if (value.empty()) {
        return fallback;
    }
    char* end = nullptr;
    const unsigned long parsed = std::strtoul(value.c_str(), &end, 10);
    if (end == nullptr || *end != '\0') {
        throw Error(Code::kInternal, std::string("invalid value in ") + name);
    }
    return static_cast<uint32_t>(parsed);
}

class FrameSink {
public:
    explicit FrameSink(int fd) : fd_(fd) {}

    void send(const Frame& frame) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_) {
            throw Error(Code::kIo, "connection closed");
        }
        try {
            write_frame(fd_, frame);
        } catch (...) {
            closed_ = true;
            throw;
        }
    }

    void close() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (closed_) {
            return;
        }
        closed_ = true;
        ::shutdown(fd_, SHUT_RDWR);
        ::close(fd_);
    }

private:
    int fd_;
    std::mutex mutex_;
    bool closed_ = false;
};

Code wire_code(Code code) {
    return static_cast<int>(code) >= 1 && static_cast<int>(code) <= 14 ? code : Code::kInternal;
}

void send_error(FrameSink* sink, Code code, const char* message) {
    PayloadWriter writer;
    writer.u16(static_cast<uint16_t>(wire_code(code)));
    writer.string(message);
    try {
        sink->send(Frame{FrameType::kError, 0, writer.take()});
    } catch (...) {
        // The peer is gone; the connection is already unusable.
    }
}

struct ImportState {
    bool open = false;
    int fd = -1;
    std::string path;
    uint64_t expected = 0;
    uint64_t received = 0;

    void reset() {
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
        if (!path.empty()) {
            ::unlink(path.c_str());
            path.clear();
        }
        open = false;
        expected = 0;
        received = 0;
    }

    ~ImportState() { reset(); }
};

bool parse_hello(const Frame& frame, uint16_t* minimum, uint16_t* maximum, std::string* token) {
    PayloadReader reader(frame.payload.data(), frame.payload.size());
    uint8_t token_length = 0;
    if (!reader.u16(minimum) || !reader.u16(maximum) || !reader.u8(&token_length)) {
        return false;
    }
    token->assign(token_length, '\0');
    if (token_length > 0 && !reader.bytes(token->data(), token_length)) {
        return false;
    }
    return reader.done();
}

bool parse_open_pty(const Frame& frame, uint16_t* columns, uint16_t* rows,
                    std::vector<std::string>* argv) {
    PayloadReader reader(frame.payload.data(), frame.payload.size());
    uint8_t count = 0;
    if (!reader.u16(columns) || !reader.u16(rows) || !reader.u8(&count) || count == 0 ||
        count > 16) {
        return false;
    }
    for (uint8_t index = 0; index < count; ++index) {
        std::string argument;
        if (!reader.string(&argument) || argument.empty()) {
            return false;
        }
        argv->push_back(std::move(argument));
    }
    return reader.done();
}

void pty_reader(const std::shared_ptr<RuntimeContext>& context,
                const std::shared_ptr<FrameSink>& sink, uint32_t id) {
    uint8_t buffer[kMaxPtyDataFrame];
    try {
        for (;;) {
            if (context->state->stopping.load()) {
                break;
            }
            if (!context->pty->wait_for_credit(id, 200)) {
                if (context->pty->master_fd(id) < 0) {
                    break;
                }
                continue;
            }
            if (context->pty->master_fd(id) < 0) {
                break;
            }
            const int master = context->pty->master_fd(id);
            pollfd descriptor{master, POLLIN, 0};
            const int ready = ::poll(&descriptor, 1, 200);
            if (ready < 0) {
                if (errno == EINTR) {
                    continue;
                }
                break;
            }
            if (ready == 0) {
                continue;
            }
            const ssize_t read = context->pty->read_output(id, buffer, sizeof(buffer));
            if (read > 0) {
                context->state->pty_out.fetch_add(static_cast<uint64_t>(read));
                sink->send(Frame{FrameType::kPtyData, id,
                                 std::vector<uint8_t>(buffer, buffer + read)});
                continue;
            }
            if (read == 0) {
                continue;
            }
            sink->send(Frame{FrameType::kEof, id, {}});
            int status = 0;
            bool reaped = false;
            for (int attempt = 0; attempt < 100 && !reaped; ++attempt) {
                reaped = context->pty->reap(id, &status);
                if (!reaped) {
                    ::usleep(10000);
                }
            }
            if (reaped) {
                PayloadWriter writer;
                const int exit_code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
                const int term_signal = WIFSIGNALED(status) ? WTERMSIG(status) : 0;
                writer.u32(static_cast<uint32_t>(exit_code));
                writer.u8(static_cast<uint8_t>(term_signal));
                sink->send(Frame{FrameType::kExit, id, writer.take()});
            }
            context->pty->erase(id);
            break;
        }
    } catch (const std::exception&) {
        context->pty->close_session(id, true);
    }
    context->state->readers.fetch_sub(1);
}

void start_pty_reader(const std::shared_ptr<RuntimeContext>& context,
                      const std::shared_ptr<FrameSink>& sink, uint32_t id) {
    context->state->readers.fetch_add(1);
    try {
        std::thread(pty_reader, context, sink, id).detach();
    } catch (...) {
        context->state->readers.fetch_sub(1);
        throw;
    }
}

void handle_export(const std::shared_ptr<RuntimeContext>& context, FrameSink* sink,
                   const Frame& frame) {
    PayloadReader reader(frame.payload.data(), frame.payload.size());
    uint8_t format = 0;
    uint16_t count = 0;
    if (!reader.u8(&format) || !reader.u16(&count) || count == 0 || count > 4) {
        send_error(sink, Code::kProtocolMalformed, "malformed export request");
        return;
    }
    std::vector<std::string> roots;
    for (uint16_t index = 0; index < count; ++index) {
        std::string root;
        if (!reader.string(&root) || root.empty()) {
            send_error(sink, Code::kProtocolMalformed, "malformed export root");
            return;
        }
        roots.push_back(std::move(root));
    }
    if (!reader.done() || format != 0) {
        send_error(sink, Code::kUnsupported, "only tar export is supported");
        return;
    }

    int pipe_fds[2] = {-1, -1};
    if (::pipe2(pipe_fds, O_CLOEXEC) != 0) {
        send_error(sink, Code::kExportIo, "cannot start export");
        return;
    }
    std::atomic<bool> failed{false};
    std::thread writer([context, roots, &pipe_fds, &failed] {
        try {
            export_tar(context->layout, roots, pipe_fds[1]);
        } catch (const std::exception&) {
            failed.store(true);
        }
        ::close(pipe_fds[1]);
    });

    PayloadWriter begin;
    begin.u8(0);
    begin.u16(0);
    bool sent_all = false;
    try {
        sink->send(Frame{FrameType::kExportBegin, 0, begin.take()});
        uint64_t total = 0;
        uint8_t buffer[65536];
        for (;;) {
            const ssize_t read = ::read(pipe_fds[0], buffer, sizeof(buffer));
            if (read > 0) {
                sink->send(Frame{FrameType::kExportData, 0,
                                 std::vector<uint8_t>(buffer, buffer + read)});
                total += static_cast<uint64_t>(read);
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
        ::close(pipe_fds[0]);
        pipe_fds[0] = -1;
        writer.join();
        if (failed.load()) {
            send_error(sink, Code::kExportIo, "export failed");
            return;
        }
        PayloadWriter end;
        end.u64(total);
        sink->send(Frame{FrameType::kExportEnd, 0, end.take()});
        sent_all = true;
    } catch (...) {
        if (pipe_fds[0] >= 0) {
            ::close(pipe_fds[0]);
        }
        if (writer.joinable()) {
            writer.join();
        }
        if (!sent_all) {
            throw;
        }
    }
}

void handle_import(const std::shared_ptr<RuntimeContext>& context, FrameSink* sink,
                   const Frame& frame, ImportState* state) {
    PayloadReader reader(frame.payload.data(), frame.payload.size());
    if (frame.type == FrameType::kImportBegin) {
        if (state->open) {
            send_error(sink, Code::kPayloadBusy, "import already in progress");
            return;
        }
        uint8_t format = 0;
        uint64_t total = 0;
        if (!reader.u8(&format) || !reader.u64(&total) || !reader.done() || format != 0 ||
            total > kMaxImportBytes) {
            send_error(sink, Code::kUnsupported, "invalid import header");
            return;
        }
        std::string pattern = context->layout.tmp() + "/.import-XXXXXX";
        std::vector<char> buffer(pattern.begin(), pattern.end());
        buffer.push_back('\0');
        const int fd = ::mkstemp(buffer.data());
        if (fd < 0) {
            send_error(sink, Code::kExportIo, "cannot create staging file");
            return;
        }
        ::fcntl(fd, F_SETFD, FD_CLOEXEC);
        state->open = true;
        state->fd = fd;
        state->path = buffer.data();
        state->expected = total;
        state->received = 0;
        return;
    }
    if (frame.type == FrameType::kImportData) {
        if (!state->open || state->fd < 0) {
            send_error(sink, Code::kUnknownCommand, "import is not open");
            return;
        }
        if (frame.payload.empty()) {
            return;
        }
        if (state->expected > 0 &&
            state->received + frame.payload.size() > state->expected) {
            state->reset();
            send_error(sink, Code::kLimitExceeded, "import exceeds declared size");
            return;
        }
        try {
            write_all(state->fd, frame.payload.data(), frame.payload.size());
        } catch (const Error&) {
            state->reset();
            send_error(sink, Code::kExportIo, "import write failed");
            return;
        }
        state->received += frame.payload.size();
        return;
    }
    // kImportEnd
    uint64_t declared = 0;
    Sha256Digest digest{};
    if (!state->open || !reader.u64(&declared) || !reader.bytes(digest.data(), digest.size()) ||
        !reader.done()) {
        state->reset();
        send_error(sink, Code::kProtocolMalformed, "malformed import end");
        return;
    }
    if (state->received != declared ||
        (state->expected != 0 && declared != state->expected)) {
        state->reset();
        send_error(sink, Code::kExportIo, "import size mismatch");
        return;
    }
    ::fsync(state->fd);
    ::close(state->fd);
    state->fd = -1;
    try {
        const ImportResult result =
                import_tar(context->layout, state->path, declared, digest);
        ::unlink(state->path.c_str());
        state->path.clear();
        state->open = false;
        state->expected = 0;
        state->received = 0;
        PayloadWriter done;
        done.u64(result.files);
        done.u64(result.bytes);
        sink->send(Frame{FrameType::kImportDone, 0, done.take()});
    } catch (const Error& error) {
        state->reset();
        send_error(sink, error.code() == Code::kPathRejected ? Code::kPathRejected
                                                             : Code::kExportIo,
                   "import rejected");
    }
}

void serve_framed(const std::shared_ptr<RuntimeContext>& context, int fd) {
    timeval timeout{5, 0};
    ::setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    context->state->connections.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(context->state->mutex);
        context->state->connection_fds.insert(fd);
    }

    auto sink = std::make_shared<FrameSink>(fd);
    FdSource source(fd);
    FrameReader reader(source);
    std::set<uint32_t> owned;
    ImportState import;

    bool hello_done = false;
    try {
        Frame hello;
        if (!reader.next(hello)) {
            throw Error(Code::kProtocolMalformed, "missing hello");
        }
        if (hello.type != FrameType::kHello) {
            throw Error(Code::kUnknownCommand, "hello must be the first frame");
        }
        uint16_t minimum = 0;
        uint16_t maximum = 0;
        std::string token;
        if (!parse_hello(hello, &minimum, &maximum, &token) || minimum > kProtocolVersion ||
            maximum < kProtocolVersion) {
            throw Error(Code::kUnsupportedVersion, "unsupported protocol version");
        }
        if (!context->config.auth_token.empty() &&
            !constant_time_equal(token, context->config.auth_token)) {
            throw Error(Code::kBadToken, "bad token");
        }
        PayloadWriter ok;
        ok.u16(kProtocolVersion);
        uint32_t capabilities = 1 | 4 | 8 | 32;
        if (context->proxy) {
            capabilities |= 2;
        }
        if (context->distcc) {
            capabilities |= 16;
        }
        ok.u32(capabilities);
        ok.u32(kMaxFramePayload);
        ok.u32(context->config.initial_window);
        sink->send(Frame{FrameType::kHelloOk, 0, ok.take()});
        hello_done = true;

        for (;;) {
            Frame frame;
            if (!reader.next(frame)) {
                break;
            }
            switch (frame.type) {
                case FrameType::kPing: {
                    if (frame.payload.size() > 32) {
                        throw Error(Code::kProtocolMalformed, "ping payload too large");
                    }
                    sink->send(Frame{FrameType::kPong, frame.session_id, frame.payload});
                    break;
                }
                case FrameType::kOpenPty: {
                    uint16_t columns = 0;
                    uint16_t rows = 0;
                    std::vector<std::string> argv;
                    if (!parse_open_pty(frame, &columns, &rows, &argv)) {
                        throw Error(Code::kProtocolMalformed, "malformed open_pty");
                    }
                    const PtyOpenResult opened = context->pty->open(
                            argv, columns, rows, context->config.initial_window);
                    owned.insert(opened.id);
                    PayloadWriter reply;
                    reply.u32(opened.pid);
                    reply.u32(opened.initial_window);
                    sink->send(Frame{FrameType::kPtyOpened, opened.id, reply.take()});
                    start_pty_reader(context, sink, opened.id);
                    break;
                }
                case FrameType::kPtyData: {
                    if (owned.count(frame.session_id) == 0) {
                        throw Error(Code::kSessionNotFound, "unknown session");
                    }
                    if (!context->pty->write_input(frame.session_id, frame.payload.data(),
                                                   frame.payload.size())) {
                        throw Error(Code::kSessionNotFound, "session is closed");
                    }
                    context->state->pty_in.fetch_add(frame.payload.size());
                    break;
                }
                case FrameType::kResize: {
                    PayloadReader resize(frame.payload.data(), frame.payload.size());
                    uint16_t columns = 0;
                    uint16_t rows = 0;
                    if (!resize.u16(&columns) || !resize.u16(&rows) || !resize.done() ||
                        owned.count(frame.session_id) == 0) {
                        throw Error(Code::kProtocolMalformed, "malformed resize");
                    }
                    if (!context->pty->resize(frame.session_id, columns, rows)) {
                        throw Error(Code::kSessionNotFound, "session is closed");
                    }
                    sink->send(Frame{FrameType::kResized, frame.session_id, {}});
                    break;
                }
                case FrameType::kSignal: {
                    PayloadReader signal_reader(frame.payload.data(), frame.payload.size());
                    uint8_t signal_number = 0;
                    if (!signal_reader.u8(&signal_number) || !signal_reader.done() ||
                        owned.count(frame.session_id) == 0 ||
                        !context->pty->signal(frame.session_id, signal_number)) {
                        throw Error(Code::kProtocolMalformed, "malformed signal");
                    }
                    break;
                }
                case FrameType::kClose: {
                    PayloadReader close_reader(frame.payload.data(), frame.payload.size());
                    uint8_t force = 0;
                    if (!close_reader.u8(&force) || !close_reader.done()) {
                        throw Error(Code::kProtocolMalformed, "malformed close");
                    }
                    context->pty->close_session(frame.session_id, force != 0);
                    owned.erase(frame.session_id);
                    PayloadWriter reply;
                    reply.u8(force != 0 ? 2 : 0);
                    sink->send(Frame{FrameType::kClosed, frame.session_id, reply.take()});
                    break;
                }
                case FrameType::kWindow: {
                    PayloadReader window(frame.payload.data(), frame.payload.size());
                    uint32_t bytes = 0;
                    if (!window.u32(&bytes) || !window.done() ||
                        owned.count(frame.session_id) == 0) {
                        throw Error(Code::kProtocolMalformed, "malformed window");
                    }
                    context->pty->grant(frame.session_id, bytes);
                    break;
                }
                case FrameType::kExport:
                    handle_export(context, sink.get(), frame);
                    break;
                case FrameType::kImportBegin:
                case FrameType::kImportData:
                case FrameType::kImportEnd:
                    handle_import(context, sink.get(), frame, &import);
                    break;
                case FrameType::kMetrics: {
                    PayloadWriter metrics;
                    metrics.u32(static_cast<uint32_t>(context->pty->size()));
                    metrics.u64(context->state->pty_in.load());
                    metrics.u64(context->state->pty_out.load());
                    metrics.u64(context->proxy ? context->proxy->bytes() : 0);
                    metrics.u32(context->distcc
                                        ? static_cast<uint32_t>(context->distcc->total_jobs())
                                        : 0);
                    const auto uptime = std::chrono::duration_cast<std::chrono::seconds>(
                            std::chrono::steady_clock::now() - context->started_at);
                    metrics.u32(static_cast<uint32_t>(uptime.count()));
                    sink->send(Frame{FrameType::kMetricsOk, 0, metrics.take()});
                    break;
                }
                case FrameType::kHello:
                    throw Error(Code::kUnknownCommand, "duplicate hello");
                default:
                    throw Error(Code::kUnknownCommand, "unknown command");
            }
        }
    } catch (const Error& error) {
        if (hello_done || error.code() != Code::kIo) {
            send_error(sink.get(), error.code(), code_name(error.code()));
        }
    } catch (const std::exception&) {
        send_error(sink.get(), Code::kInternal, "internal error");
    }

    for (const uint32_t id : owned) {
        context->pty->close_session(id, true);
    }
    import.reset();
    sink->close();
    {
        std::lock_guard<std::mutex> lock(context->state->mutex);
        context->state->connection_fds.erase(fd);
    }
    context->state->connections.fetch_sub(1);
    context->state->idle.notify_all();
}

}  // namespace

PayloadConfig PayloadConfig::from_environment() {
    PayloadConfig config;
    config.apk_root = environment("FASTLANE_APK_ROOT");
    if (config.apk_root.empty()) {
        const char* path = AVmPayload_getApkContentsPath();
        if (path != nullptr) {
            config.apk_root = path;
        }
    }
    config.storage_root = environment("FASTLANE_STORAGE_ROOT");
    if (config.storage_root.empty()) {
        const char* path = AVmPayload_getEncryptedStoragePath();
        if (path != nullptr) {
            config.storage_root = path;
        }
    }
    if (config.apk_root.empty()) {
        throw Error(Code::kInternal, "APK contents path is unavailable");
    }
    if (config.storage_root.empty()) {
        throw Error(Code::kStorageFailed, "encrypted storage is unavailable");
    }
    config.control_port = parse_port("FASTLANE_CONTROL_PORT", config.control_port);
    config.terminal_port = parse_port("FASTLANE_TERMINAL_PORT", config.terminal_port);
    config.proxy_port = parse_port("FASTLANE_PROXY_PORT", config.proxy_port);
    config.distcc_port = parse_port("FASTLANE_DISTCC_PORT", config.distcc_port);
    config.initial_window = parse_u32("FASTLANE_INITIAL_WINDOW", config.initial_window);
    config.max_sessions = parse_u32("FASTLANE_MAX_SESSIONS", 8);
    config.max_connections = parse_u32("FASTLANE_MAX_CONNECTIONS", 8);
    config.auth_token = environment("FASTLANE_AUTH_TOKEN");
    config.unix_transport = environment("FASTLANE_TRANSPORT") == "unix";
    config.socket_directory = environment("FASTLANE_SOCKET_DIR");
    config.distccd_path = environment("FASTLANE_DISTCCD");
    config.enable_proxy_tcp = environment("FASTLANE_PROXY_TCP") != "0";
    if (config.unix_transport && config.socket_directory.empty()) {
        throw Error(Code::kInternal, "FASTLANE_SOCKET_DIR is required for unix transport");
    }
    return config;
}

Runtime::Runtime(PayloadConfig config) : config_(std::move(config)) {}

Runtime::~Runtime() {
    stop();
}

std::string Runtime::socket_path(uint16_t port) const {
    const char* name = "control.sock";
    if (port == config_.terminal_port) {
        name = "terminal.sock";
    } else if (port == config_.proxy_port) {
        name = "proxy.sock";
    } else if (port == config_.distcc_port) {
        name = "distcc.sock";
    }
    return config_.socket_directory + "/" + name;
}

namespace {

std::unique_ptr<Listener> make_listener(const PayloadConfig& config, uint16_t port,
                                        const std::string& unix_path) {
    return config.unix_transport ? make_unix_listener(unix_path)
                                 : make_vsock_listener(port);
}

}  // namespace

const StorageLayout& Runtime::layout() const {
    if (!context_) {
        throw Error(Code::kInternal, "runtime is not started");
    }
    return context_->layout;
}

uint32_t Runtime::capabilities() const {
    if (!context_) {
        return 0;
    }
    uint32_t capabilities = 1 | 4 | 8 | 32;
    if (context_->proxy) {
        capabilities |= 2;
    }
    if (context_->distcc) {
        capabilities |= 16;
    }
    return capabilities;
}

void Runtime::start() {
    if (started_) {
        return;
    }
    context_ = std::make_shared<RuntimeContext>(config_);
    control_listener_ = make_listener(config_, config_.control_port,
                                      socket_path(config_.control_port));
    terminal_listener_ = make_listener(config_, config_.terminal_port,
                                       socket_path(config_.terminal_port));
    if (context_->proxy) {
        proxy_listener_ = make_listener(config_, config_.proxy_port,
                                        socket_path(config_.proxy_port));
    }
    if (context_->distcc) {
        distcc_listener_ = make_listener(config_, config_.distcc_port,
                                         socket_path(config_.distcc_port));
    }
    started_ = true;
    control_thread_ = std::thread(&Runtime::accept_framed, this, control_listener_.get());
    terminal_thread_ = std::thread(&Runtime::accept_framed, this, terminal_listener_.get());
    if (proxy_listener_) {
        proxy_thread_ = std::thread([context = context_, listener = proxy_listener_.get()] {
            while (!context->state->stopping.load()) {
                const int fd = listener->accept();
                if (fd < 0) {
                    return;
                }
                context->proxy->add_channel(fd);
            }
        });
    }
    if (distcc_listener_) {
        distcc_thread_ = std::thread([context = context_, listener = distcc_listener_.get()] {
            while (!context->state->stopping.load()) {
                const int fd = listener->accept();
                if (fd < 0) {
                    return;
                }
                std::thread([context, fd] { context->distcc->handle(fd); }).detach();
            }
        });
    }
}

void Runtime::accept_framed(Listener* listener) {
    while (!context_->state->stopping.load()) {
        const int fd = listener->accept();
        if (fd < 0) {
            return;
        }
        if (context_->state->connections.load() >= config_.max_connections) {
            ::close(fd);
            continue;
        }
        std::thread(serve_framed, context_, fd).detach();
    }
}

void Runtime::stop() {
    if (!started_ || stopped_) {
        return;
    }
    stopped_ = true;
    g_stop_requested.store(true);
    context_->state->stopping.store(true);
    context_->state->idle.notify_all();

    control_listener_->close();
    terminal_listener_->close();
    if (proxy_listener_) {
        proxy_listener_->close();
    }
    if (distcc_listener_) {
        distcc_listener_->close();
    }
    if (control_thread_.joinable()) {
        control_thread_.join();
    }
    if (terminal_thread_.joinable()) {
        terminal_thread_.join();
    }
    if (proxy_thread_.joinable()) {
        proxy_thread_.join();
    }
    if (distcc_thread_.joinable()) {
        distcc_thread_.join();
    }

    std::set<int> connections;
    {
        std::lock_guard<std::mutex> lock(context_->state->mutex);
        connections = context_->state->connection_fds;
    }
    for (const int fd : connections) {
        ::shutdown(fd, SHUT_RDWR);
    }

    if (context_->proxy) {
        context_->proxy->stop();
    }
    if (context_->distcc) {
        context_->distcc->stop();
    }
    context_->pty->close_all();

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (std::chrono::steady_clock::now() < deadline) {
        if (context_->state->readers.load() == 0 &&
            context_->state->connections.load() == 0) {
            break;
        }
        ::usleep(10000);
    }

    if (config_.unix_transport) {
        ::unlink(socket_path(config_.control_port).c_str());
        ::unlink(socket_path(config_.terminal_port).c_str());
        if (proxy_listener_) {
            ::unlink(socket_path(config_.proxy_port).c_str());
        }
        if (distcc_listener_) {
            ::unlink(socket_path(config_.distcc_port).c_str());
        }
    }
}

int Runtime::run() {
    std::signal(SIGPIPE, SIG_IGN);
    if (config_.handle_signals) {
        std::signal(SIGTERM, handle_stop_signal);
        std::signal(SIGINT, handle_stop_signal);
    }
    start();
    AVmPayload_notifyPayloadReady();
    while (!g_stop_requested.load() && !context_->state->stopping.load()) {
        ::usleep(100000);
    }
    stop();
    return 0;
}

}  // namespace fl
