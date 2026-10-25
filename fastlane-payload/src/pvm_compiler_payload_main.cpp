// PVM-03 compile-service payload entry.
//
// Android (libpvm_compiler_payload.so): AVmPayload_main listens on the Fast
// Lane distcc vsock port (default 5103), reads the auth token from the guest
// environment (ORCHESTELLA_ACCEL_TOKEN, the channel AcceleratorUplink uses)
// and serves one distcc job per accepted connection through the AXP1
// handshake. Engine selection is compile-time: FL_PVM_COMPILER_ENGINE_CLANG
// selects the libclang engine, everything else builds the deterministic fake
// engine so CI never needs an Android libclang.
//
// Host (pvm_compiler_payload_host): same session loop over a loopback TCP or
// Unix socket, so the zero-device e2e chain exercises the exact wire path a
// device would.
//
// Zero file writes, zero exec, and no exceptions: the whole translation unit
// is built with -fno-exceptions, so there is nothing to catch — every failure
// is a status code (决策地图 §11: the payload ABI is EH=OFF).

#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "compile_service/axp1_server.h"
#include "compile_service/compile_service.h"
#include "compile_service/net.h"

#if defined(FL_PVM_COMPILER_ENGINE_CLANG)
#include "compile_service/clang_engine.h"
#else
#include "compile_service/fake_engine.h"
#endif

#if defined(__ANDROID__)
#include <fcntl.h>
#include <unistd.h>

#include "fastlane/vm_payload_api.h"
#endif

namespace {

using fl::pvm::Axp1ServerConfig;
using fl::pvm::ArgvPolicyConfig;
using fl::pvm::CompileEngine;
using fl::pvm::Listener;
using fl::pvm::ServerLimits;
using fl::pvm::SocketTransport;
using fl::pvm::WireError;

constexpr uint16_t kDefaultVsockPort = 5103;
constexpr int kMaxJobsCeiling = 2;  // one protected VM has one vCPU

// Small, dependency-free integer reader: std::stoi throws, and this build has
// exceptions disabled.
bool parse_u32(const char* text, uint32_t* value) {
    if (text == nullptr || *text == '\0') {
        return false;
    }
    uint64_t result = 0;
    for (const char* cursor = text; *cursor != '\0'; ++cursor) {
        if (*cursor < '0' || *cursor > '9') {
            return false;
        }
        result = result * 10u + static_cast<uint64_t>(*cursor - '0');
        if (result > 0xffffffffull) {
            return false;
        }
    }
    *value = static_cast<uint32_t>(result);
    return true;
}

uint32_t env_u32(const char* name, uint32_t fallback) {
    uint32_t value = 0;
    return parse_u32(::getenv(name), &value) ? value : fallback;
}

const char* env_string(const char* name) { return ::getenv(name); }

// The token is the AXP1 credential: the payload must know it before the
// handshake, and the host must hold the same value. Environment first, because
// that is the channel the host twin and any shell-driven launch use.
//
// A protected Microdroid payload has no environment channel: there is no
// setEnv on VirtualMachineConfig, and setPayloadConfigPath — the only
// runtime-config surface — is exclusive with setPayloadBinaryName. The APK
// contents are therefore the only host->payload channel that exists before the
// handshake, so device/QA builds drop the token at assets/accel_token (DV-PVM-02).
// Neither source is ever logged; only the size is reported on failure.
std::string read_token_from_apk_contents() {
#if defined(__ANDROID__)
    const char* root = AVmPayload_getApkContentsPath();
    if (root == nullptr || root[0] == '\0') {
        return std::string();
    }
    const std::string path = std::string(root) + "/assets/accel_token";
    const int descriptor = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) {
        return std::string();
    }
    char buffer[64] = {};
    const ssize_t read_bytes = ::read(descriptor, buffer, sizeof(buffer) - 1);
    ::close(descriptor);
    if (read_bytes <= 0) {
        return std::string();
    }
    std::string token(buffer, static_cast<size_t>(read_bytes));
    // A trailing newline written by an editor is not part of the token.
    while (!token.empty() && (token.back() == '\n' || token.back() == '\r' ||
                              token.back() == ' ' || token.back() == '\t')) {
        token.pop_back();
    }
    return token;
#else
    return std::string();
#endif
}

std::string read_token() {
    const char* from_environment = env_string("ORCHESTELLA_ACCEL_TOKEN");
    if (from_environment != nullptr && from_environment[0] != '\0') {
        return std::string(from_environment);
    }
    return read_token_from_apk_contents();
}

class CompileServiceServer {
public:
    CompileServiceServer(std::unique_ptr<Listener> listener, uint16_t port)
        : listener_(std::move(listener)), port_(port) {
        config_.token = read_token();
        config_.caps.compiler_triple = read_triple();
        config_.handshake_timeout_ms = env_u32("ORCHESTELLA_PVM_HANDSHAKE_MS", 3000);
#if defined(FL_PVM_COMPILER_ENGINE_CLANG)
        fl::pvm::ClangEngineConfig engine_config;
        engine_config.target_triple = config_.caps.compiler_triple;
        engine_ = fl::pvm::make_clang_engine(engine_config);
#else
        engine_ = fl::pvm::make_fake_engine();
#endif
        if (engine_ != nullptr) {
            config_.caps.compiler_version = engine_->compiler_version();
        }
        policy_.target_triple = config_.caps.compiler_triple;

        const uint32_t jobs = env_u32("ORCHESTELLA_PVM_COMPILER_JOBS",
                                      static_cast<uint32_t>(limits_.max_jobs));
        // Zero would reject every peer (the counter test `active >= 0` is
        // always true) and more than two violates the one-vCPU budget.
        limits_.max_jobs = static_cast<int>(jobs == 0 ? 1 : (jobs > kMaxJobsCeiling
                                                                 ? kMaxJobsCeiling
                                                                 : jobs));
        limits_.wall_budget_ms = env_u32("ORCHESTELLA_PVM_JOB_WALL_MS",
                                        limits_.wall_budget_ms);
        limits_.max_input_bytes = env_u32("ORCHESTELLA_PVM_INPUT_MAX",
                                         static_cast<uint32_t>(limits_.max_input_bytes));
        limits_.max_object_bytes =
            env_u32("ORCHESTELLA_PVM_OUTPUT_MAX",
                    static_cast<uint32_t>(limits_.max_object_bytes));
    }

    bool ready() const { return listener_ != nullptr && config_.token.size() == 32; }

    uint16_t port() const { return port_; }

    /** Serves connections until the listener closes, then drains in-flight jobs. */
    void serve_forever() {
        if (!ready()) {
            std::fprintf(stderr, "PVM_CSDL token missing or malformed\n");
            return;
        }
        std::fprintf(stderr, "PVM_CSDL listening port=%u jobs=%d engine=%s\n", port_,
                     limits_.max_jobs, config_.caps.compiler_version.c_str());
        for (;;) {
            const fl::pvm::SocketHandle handle = listener_->accept();
            if (handle == fl::pvm::kInvalidSocket) {
                break;
            }
            if (stopping_.load()) {
                fl::pvm::close_socket(handle);
                break;
            }
            if (active_.load() >= static_cast<unsigned>(limits_.max_jobs)) {
                // distccd keeps excess peers in the listen backlog; there is
                // no in-band "busy" packet in v1, so closing is the only
                // signal available without inventing protocol.
                fl::pvm::close_socket(handle);
                continue;
            }
            active_.fetch_add(1);
            total_.fetch_add(1);
            std::thread(&CompileServiceServer::serve_one, this, handle).detach();
        }
        // Detached workers capture `this`: wait for them before the members
        // they read go away.
        std::unique_lock<std::mutex> lock(mutex_);
        drained_.wait(lock, [this]() { return active_.load() == 0; });
    }

    void stop() {
        stopping_.store(true);
        if (listener_ != nullptr) {
            listener_->close();
        }
    }

private:
    void serve_one(fl::pvm::SocketHandle handle) {
        SocketTransport transport(handle);
        const WireError outcome = fl::pvm::axp1_serve_connection(
            &transport, config_, policy_, limits_, engine_.get());
        std::fprintf(stderr, "PVM_CSDL job done code=%d total=%u\n",
                     static_cast<int>(outcome), total_.load());
        active_.fetch_sub(1);
        {
            std::lock_guard<std::mutex> lock(mutex_);
        }
        drained_.notify_all();
    }

    static std::string read_triple() {
        const char* override_triple = env_string("ORCHESTELLA_PVM_COMPILER_TRIPLE");
        if (override_triple != nullptr && override_triple[0] != '\0') {
            return override_triple;
        }
#if defined(__aarch64__) && defined(__ANDROID__)
        return "aarch64-unknown-linux-android";
#elif defined(__x86_64__) || defined(_M_X64)
        return "x86_64-unknown-linux-gnu";
#else
        return "aarch64-unknown-linux-android";
#endif
    }

    std::unique_ptr<Listener> listener_;
    uint16_t port_;
    Axp1ServerConfig config_;
    ArgvPolicyConfig policy_;
    ServerLimits limits_;
    std::unique_ptr<CompileEngine> engine_;
    std::atomic<unsigned> active_{0};
    std::atomic<unsigned> total_{0};
    std::atomic<bool> stopping_{false};
    std::mutex mutex_;
    std::condition_variable drained_;
};

}  // namespace

#if defined(__ANDROID__)
extern "C" __attribute__((visibility("default"))) int AVmPayload_main(void) {
    uint32_t port = kDefaultVsockPort;
    if (const char* override_port = env_string("ORCHESTELLA_PVM_COMPILER_PORT")) {
        uint32_t parsed = 0;
        if (parse_u32(override_port, &parsed) && parsed > 0 && parsed <= 65535) {
            port = parsed;
        }
    }
    auto listener = fl::pvm::make_vsock_listener(static_cast<uint16_t>(port));
    CompileServiceServer server(std::move(listener), static_cast<uint16_t>(port));
    if (!server.ready()) {
        std::fprintf(stderr, "PVM_CSDL not ready (token/listener)\n");
        return 1;
    }
    // Ready means "the port is bound and the token is usable" — never before.
    AVmPayload_notifyPayloadReady();
    server.serve_forever();
    return 0;
}
#else
int main(int argc, char** argv) {
    if (argc != 3) {
        std::fprintf(stderr,
                     "usage: pvm_compiler_payload_host --tcp <port> | --unix <path>\n");
        return 2;
    }
#if !defined(_WIN32)
    ::signal(SIGPIPE, SIG_IGN);
#endif
    const std::string mode = argv[1];
    std::unique_ptr<Listener> listener;
    uint16_t port = 0;
    if (mode == "--tcp") {
        uint32_t requested = 0;
        if (!parse_u32(argv[2], &requested) || requested > 65535) {
            std::fprintf(stderr, "bad port: %s\n", argv[2]);
            return 2;
        }
        listener = fl::pvm::make_loopback_tcp_listener(static_cast<uint16_t>(requested));
    }
#if !defined(_WIN32)
    else if (mode == "--unix") {
        listener = fl::pvm::make_unix_listener(argv[2]);
    }
#endif
    else {
        std::fprintf(stderr, "unknown mode: %s\n", mode.c_str());
        return 2;
    }
    if (listener == nullptr) {
        std::fprintf(stderr, "PVM_CSDL listen failed\n");
        return 1;
    }
    port = listener->port();
    CompileServiceServer server(std::move(listener), port);
    if (!server.ready()) {
        std::fprintf(stderr, "PVM_CSDL token missing (ORCHESTELLA_ACCEL_TOKEN)\n");
        return 3;
    }
    // The port is printed first so a test harness can read it: the child binds
    // an ephemeral port and the parent needs the number, not a guess.
    std::printf("PVM_CSDL_PORT %u\n", static_cast<unsigned>(port));
    std::fflush(stdout);
    server.serve_forever();
    return 0;
}
#endif
