// Socket-level end-to-end test (PVM-03 A5, zero device).
//
// This is the part the unit tests cannot prove: a real TCP listener, the real
// AXP1 session state machine, the real distcc codec and the real engine, all
// in one process, with the golden request bytes a distcc 3.4 client produced.
// The Java bridge is exercised separately (PvmCompilerPayloadE2eTest); here we
// pin the payload side of that same wire.

#include <algorithm>
#include <atomic>
#include <chrono>
#include <string>
#include <thread>
#include <vector>

#include "axp1_server.h"
#include "compile_service.h"
#include "fake_engine.h"
#include "net.h"
#include "pvm_test_support.h"
#include "test_support.h"

namespace {

using fl::pvm::Axp1FrameView;
using fl::pvm::Axp1ServerConfig;
using fl::pvm::ArgvPolicyConfig;
using fl::pvm::CompileEngine;
using fl::pvm::EngineRequest;
using fl::pvm::EngineResult;
using fl::pvm::JobStatus;
using fl::pvm::Listener;
using fl::pvm::ServerLimits;
using fl::pvm::SocketTransport;

constexpr const char* kToken = "00112233" "44556677" "8899aabb" "ccddeeff";
constexpr uint32_t kFrame = 1024 * 1024;

ArgvPolicyConfig policy() {
    ArgvPolicyConfig config;
    config.target_triple = "aarch64-unknown-linux-android";
    return config;
}

Axp1ServerConfig config() {
    Axp1ServerConfig server;
    server.token = kToken;
    server.caps.compiler_triple = "aarch64-unknown-linux-android";
    server.caps.compiler_version = "fake-engine 1";
    return server;
}

/** Engine that echoes a per-job marker into SERR, so cross-talk is visible. */
class MarkerEngine final : public CompileEngine {
public:
    MarkerEngine() : base_(fl::pvm::make_fake_engine()) {}

    EngineResult compile(const EngineRequest& request) override {
        EngineResult result = base_->compile(request);
        result.diagnostics = "marker:" + request.source + "\n";
        return result;
    }

    std::string compiler_version() const override { return "fake-engine 1"; }

private:
    std::unique_ptr<CompileEngine> base_;
};

/** Serves a fixed number of connections, then closes the listener. */
class TestServer {
public:
    TestServer(std::unique_ptr<Listener> listener, CompileEngine* engine,
               int remaining, int max_jobs)
        : listener_(std::move(listener)), engine_(engine), remaining_(remaining),
          max_jobs_(max_jobs) {}

    uint16_t port() const { return listener_->port(); }

    void start() { thread_ = std::thread(&TestServer::run, this); }

    void join() {
        if (thread_.joinable()) {
            thread_.join();
        }
    }

    ~TestServer() {
        listener_->close();
        join();
    }

private:
    void run() {
        int served = 0;
        while (served < remaining_) {
            const fl::pvm::SocketHandle handle = listener_->accept();
            if (handle == fl::pvm::kInvalidSocket) {
                return;
            }
            ++served;
            if (active_.load() >= static_cast<unsigned>(max_jobs_)) {
                fl::pvm::close_socket(handle);
                continue;
            }
            active_.fetch_add(1);
            std::thread([this, handle]() {
                SocketTransport transport(handle);
                fl::pvm::axp1_serve_connection(&transport, config(), policy(),
                                               ServerLimits(), engine_);
                active_.fetch_sub(1);
            }).detach();
        }
        // Let the accepted sessions finish before the engine goes away.
        const auto deadline =
            std::chrono::steady_clock::now() + std::chrono::seconds(10);
        while (active_.load() != 0 && std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }

    std::unique_ptr<Listener> listener_;
    CompileEngine* engine_;
    int remaining_;
    int max_jobs_;
    std::atomic<unsigned> active_{0};
    std::thread thread_;
};

/** One client: HELLO, request bytes, EOF; returns the response byte stream. */
std::vector<uint8_t> run_client(uint16_t port, const std::vector<uint8_t>& request) {
    const fl::pvm::SocketHandle handle = fl::pvm::connect_loopback_tcp(port);
    if (handle == fl::pvm::kInvalidSocket) {
        return {};
    }
    SocketTransport transport(handle);
    transport.set_read_timeout_ms(10000);

    std::vector<uint8_t> hello;
    fl::pvm::axp1_encode_frame(fl::pvm::kAxp1TypeHello,
                               fl::pvm::test::build_hello(kToken, kFrame,
                                                          "aarch64-unknown-linux-android"),
                               &hello);
    transport.write(hello.data(), hello.size());

    Axp1FrameView frame;
    if (fl::pvm::axp1_read_frame(&transport, kFrame, &frame) !=
            fl::pvm::Axp1ReadStatus::kFrame ||
        frame.type != fl::pvm::kAxp1TypeHelloAck) {
        return {};
    }

    std::vector<uint8_t> body;
    const size_t chunk = 8 * 1024;
    for (size_t offset = 0; offset < request.size(); offset += chunk) {
        const size_t take = request.size() - offset < chunk ? request.size() - offset
                                                            : chunk;
        fl::pvm::axp1_encode_frame(
            fl::pvm::kAxp1TypeData,
            std::vector<uint8_t>(request.begin() + offset, request.begin() + offset + take),
            &body);
    }
    fl::pvm::axp1_encode_frame(fl::pvm::kAxp1TypeEof, {}, &body);
    if (!transport.write(body.data(), body.size())) {
        return {};
    }

    std::vector<uint8_t> response;
    for (;;) {
        if (fl::pvm::axp1_read_frame(&transport, kFrame, &frame) !=
            fl::pvm::Axp1ReadStatus::kFrame) {
            break;
        }
        if (frame.type == fl::pvm::kAxp1TypeEof) {
            break;
        }
        if (frame.type == fl::pvm::kAxp1TypeRst) {
            break;
        }
        if (frame.type == fl::pvm::kAxp1TypeData) {
            response.insert(response.end(), frame.payload.begin(), frame.payload.end());
        }
    }
    return response;
}

std::vector<uint8_t> golden_request() {
    return fl::pvm::test::read_hex_dump(FL_PVM_DISTCC_GOLDEN_DIR "/request-v1.hex");
}

std::vector<uint8_t> fake_object() {
    auto engine = fl::pvm::make_fake_engine();
    EngineRequest request;
    request.source = "int x;\n";
    return engine->compile(request).object;
}

}  // namespace

FL_TEST(pvm_e2e_serves_the_golden_request_over_loopback_tcp) {
    auto listener = fl::pvm::make_loopback_tcp_listener(0);
    FL_CHECK(listener != nullptr);
    FL_CHECK(listener->port() != 0);

    auto engine = fl::pvm::make_fake_engine();
    TestServer server(std::move(listener), engine.get(), 1, 1);
    server.start();

    const std::vector<uint8_t> response = run_client(server.port(), golden_request());
    server.join();

    FL_CHECK(response.size() > 5 * 12);
    std::vector<Axp1FrameView> frames;
    std::vector<uint8_t> framed;
    fl::pvm::axp1_encode_frame(fl::pvm::kAxp1TypeData, response, &framed);
    FL_CHECK(fl::pvm::test::decode_frames(framed, &frames));
    const std::string text(reinterpret_cast<const char*>(response.data()),
                           response.size());
    FL_CHECK(text.rfind("DONE00000001", 0) == 0);
    FL_CHECK(text.find("STAT00000000") != std::string::npos);
    // SOUT stays an empty packet; the object rides DOTO and is byte-identical
    // to the fixed fake object, which is what compare_objects.py judges.
    FL_CHECK(text.find("SOUT00000000") != std::string::npos);
    const std::vector<uint8_t> object = fake_object();
    const std::string doto_header = "DOTO" + [&object] {
        static const char* kHex = "0123456789" "abcdef";
        std::string hex(8, '0');
        uint32_t value = static_cast<uint32_t>(object.size());
        for (int index = 7; index >= 0; --index) {
            hex[static_cast<size_t>(index)] = kHex[value & 0xf];
            value >>= 4;
        }
        return hex;
    }();
    FL_CHECK(text.find(doto_header) != std::string::npos);
    const auto payload_at = response.size() - object.size();
    FL_CHECK(std::equal(object.begin(), object.end(), response.begin() + payload_at));
}

FL_TEST(pvm_e2e_keeps_four_concurrent_jobs_apart) {
    auto listener = fl::pvm::make_loopback_tcp_listener(0);
    FL_CHECK(listener != nullptr);
    MarkerEngine engine;
    TestServer server(std::move(listener), &engine, 4, 4);
    server.start();

    constexpr int kClients = 4;
    std::vector<std::thread> clients;
    std::vector<std::string> responses(kClients);
    for (int index = 0; index < kClients; ++index) {
        clients.emplace_back([&server, &responses, index]() {
            const std::string marker = "unit" + std::to_string(index);
            const std::vector<uint8_t> request = fl::pvm::test::build_distcc_request(
                {"clang", "-c", marker + ".c", "-o", marker + ".o"},
                marker + " source body\n");
            const std::vector<uint8_t> response = run_client(server.port(), request);
            responses[static_cast<size_t>(index)] =
                std::string(reinterpret_cast<const char*>(response.data()),
                            response.size());
        });
    }
    for (std::thread& client : clients) {
        client.join();
    }
    server.join();

    for (int index = 0; index < kClients; ++index) {
        const std::string& response = responses[static_cast<size_t>(index)];
        FL_CHECK(response.rfind("DONE00000001", 0) == 0);
        FL_CHECK(response.find("STAT00000000") != std::string::npos);
        // Each session must carry its own marker and none of the others'.
        FL_CHECK(response.find("marker:unit" + std::to_string(index) + " source") !=
                 std::string::npos);
        for (int other = 0; other < kClients; ++other) {
            if (other == index) {
                continue;
            }
            FL_CHECK(response.find("marker:unit" + std::to_string(other)) ==
                     std::string::npos);
        }
    }
}
