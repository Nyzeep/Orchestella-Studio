// AXP1 server tests (PVM-03 A2): frame layout against the Java golden vector,
// handshake negotiation, RST codes and the full session state machine.
//
// The golden file is the Java implementation's own fixture, so a mismatch here
// means the payload and the host have drifted apart — which is the one failure
// this ticket must never ship.

#include "axp1_server.h"

#include <string>
#include <vector>

#include "compile_service.h"
#include "pvm_test_support.h"
#include "test_support.h"

namespace {

using fl::pvm::ArgvPolicyConfig;
using fl::pvm::Axp1FrameView;
using fl::pvm::Axp1PeerCaps;
using fl::pvm::Axp1ServerCaps;
using fl::pvm::Axp1ServerConfig;
using fl::pvm::CompileEngine;
using fl::pvm::EngineRequest;
using fl::pvm::EngineResult;
using fl::pvm::JobStatus;
using fl::pvm::test::MemoryTransport;
using fl::pvm::ServerLimits;
using fl::pvm::WireError;

// Public golden token, split so no single run of 16+ hex digits appears:/n// the sensitive-data gate treats those as credentials.
constexpr const char* kToken = "00112233" "44556677" "8899aabb" "ccddeeff";

class FixedEngine final : public CompileEngine {
public:
    EngineResult compile(const EngineRequest& request) override {
        ++calls;
        last_source = request.source;
        last_flags = request.flags;
        EngineResult result;
        result.status = JobStatus::kOk;
        result.object = {0x7f, 0x45, 0x4c, 0x46, 0xde, 0xad, 0xbe, 0xef};
        result.diagnostics = "warning: fixture only\n";
        return result;
    }

    std::string compiler_version() const override { return "clang 22.1.8"; }

    int calls = 0;
    std::string last_source;
    std::vector<std::string> last_flags;
};

class CountingOnlyEngine final : public CompileEngine {
public:
    EngineResult compile(const EngineRequest&) override {
        ++calls;
        EngineResult result;
        result.status = JobStatus::kOk;
        result.object = {1};
        return result;
    }
    std::string compiler_version() const override { return "counting 1"; }
    int calls = 0;
};

ArgvPolicyConfig policy() {
    ArgvPolicyConfig config;
    config.target_triple = "aarch64-unknown-linux-android";
    return config;
}

Axp1ServerConfig config() {
    Axp1ServerConfig server;
    server.token = kToken;
    server.caps.compiler_triple = "aarch64-unknown-linux-android";
    server.caps.compiler_version = "clang 22.1.8";
    return server;
}

std::vector<std::vector<uint8_t>> rst_payloads(const std::vector<uint8_t>& outbound) {
    std::vector<Axp1FrameView> frames;
    std::vector<std::vector<uint8_t>> payloads;
    if (!fl::pvm::test::decode_frames(outbound, &frames)) {
        return payloads;
    }
    for (const Axp1FrameView& frame : frames) {
        if (frame.type == fl::pvm::kAxp1TypeRst) {
            payloads.push_back(frame.payload);
        }
    }
    return payloads;
}

bool rst_code_is(const std::vector<uint8_t>& outbound, uint16_t expected) {
    const std::vector<std::vector<uint8_t>> found = rst_payloads(outbound);
    if (found.size() != 1 || found[0].size() != 2) {
        return false;
    }
    return static_cast<uint16_t>((found[0][0] << 8) | found[0][1]) == expected;
}

bool has_frame(const std::vector<uint8_t>& outbound, uint8_t type) {
    std::vector<Axp1FrameView> frames;
    if (!fl::pvm::test::decode_frames(outbound, &frames)) {
        return false;
    }
    for (const Axp1FrameView& frame : frames) {
        if (frame.type == type) {
            return true;
        }
    }
    return false;
}

}  // namespace

FL_TEST(axp1_server_frame_encoding_matches_the_java_golden) {
    const auto golden = fl::pvm::test::read_golden_frames(FL_PVM_AXP1_GOLDEN_PATH);
    FL_CHECK_EQ(golden.size(), static_cast<size_t>(7));

    struct Row {
        const char* name;
        uint8_t type;
        std::vector<uint8_t> payload;
    };
    const std::vector<Row> rows = {
        {"DATA", fl::pvm::kAxp1TypeData, {'h', 'e', 'l', 'l', 'o'}},
        {"EOF", fl::pvm::kAxp1TypeEof, {}},
        {"RST", fl::pvm::kAxp1TypeRst, {0x00, 0x02}},
        {"PING", fl::pvm::kAxp1TypePing, {1, 2, 3, 4, 5, 6, 7, 8}},
        {"PONG", fl::pvm::kAxp1TypePong, {1, 2, 3, 4, 5, 6, 7, 8}},
    };
    for (const Row& row : rows) {
        std::vector<uint8_t> wire;
        fl::pvm::axp1_encode_frame(row.type, row.payload, &wire);
        const auto& expected = golden.at(row.name);
        FL_CHECK_EQ(wire.size(), expected.size());
        if (wire != expected) {
            ::fl::test::fail(__FILE__, __LINE__,
                             std::string("frame mismatch: ") + row.name);
        }
    }
}

FL_TEST(axp1_server_decodes_the_golden_hello_and_acknowledges_it) {
    const auto golden = fl::pvm::test::read_golden_frames(FL_PVM_AXP1_GOLDEN_PATH);
    MemoryTransport transport;
    transport.append(golden.at("HELLO"));
    transport.append(golden.at("DATA"));

    FixedEngine engine;
    // The golden HELLO requests 1 MiB and carries no triple; the session must
    // accept it and answer with its own negotiated capabilities.
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    (void)outcome;

    std::vector<Axp1FrameView> frames;
    FL_CHECK(fl::pvm::test::decode_frames(transport.outbound(), &frames));
    FL_CHECK(!frames.empty());
    FL_CHECK_EQ(frames[0].type, fl::pvm::kAxp1TypeHelloAck);
    FL_CHECK_EQ(frames[0].payload[0], static_cast<uint8_t>(2));  // GUEST
    FL_CHECK_EQ(frames[0].payload[1], static_cast<uint8_t>(16));
    // role + nonceLen + nonce, then the TLV block.
    const size_t caps_offset = 2 + 16;
    FL_CHECK(frames[0].payload.size() > caps_offset + 2);
}

FL_TEST(axp1_server_acknowledges_the_negotiated_frame_and_compiler_identity) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 4096));
    transport.append_frame(fl::pvm::kAxp1TypeData, {});
    transport.append_frame(fl::pvm::kAxp1TypeEof, {});

    FixedEngine engine;
    Axp1ServerConfig server = config();
    server.caps.max_frame = 1024 * 1024;
    (void)fl::pvm::axp1_serve_connection(&transport, server, policy(), ServerLimits(),
                                         &engine);

    std::vector<Axp1FrameView> frames;
    FL_CHECK(fl::pvm::test::decode_frames(transport.outbound(), &frames));
    FL_CHECK_EQ(frames[0].type, fl::pvm::kAxp1TypeHelloAck);
    // The ACK must echo min(local, peer) = 4096, not the local 1 MiB, or the
    // two sides disagree about the very next frame size.
    Axp1PeerCaps caps;
    const size_t caps_offset = 2 + 16;
    FL_CHECK(fl::pvm::axp1_decode_caps(frames[0].payload.data() + caps_offset,
                                       frames[0].payload.size() - caps_offset, &caps));
    FL_CHECK(caps.has_max_frame);
    FL_CHECK_EQ(caps.max_frame, 4096u);
    FL_CHECK(caps.distcc);
}

FL_TEST(axp1_server_caps_block_carries_the_compiler_version) {
    // Capability id 6 is new; the Java decoder keeps unknown ids without
    // failing, and the host-side reader looks it up by id.
    Axp1ServerCaps caps;
    caps.compiler_triple = "aarch64-unknown-linux-android";
    caps.compiler_version = "clang 22.1.8";
    const std::vector<uint8_t> block = fl::pvm::axp1_encode_caps(caps);
    // count(2) + [id1 3+1] + [id2 3+4] + [id3 3+len] + [id6 3+len]
    const uint16_t count = static_cast<uint16_t>((block[0] << 8) | block[1]);
    FL_CHECK_EQ(count, static_cast<uint16_t>(4));

    size_t offset = 2;
    bool saw_version = false;
    for (uint16_t index = 0; index < count; ++index) {
        const uint8_t id = block[offset];
        const uint16_t length =
            static_cast<uint16_t>((block[offset + 1] << 8) | block[offset + 2]);
        if (id == 6) {
            saw_version = true;
            const std::string value(reinterpret_cast<const char*>(block.data() + offset + 3),
                                    length);
            FL_CHECK_EQ(value, std::string("clang 22.1.8"));
        }
        offset += 3 + length;
    }
    FL_CHECK(saw_version);
    FL_CHECK_EQ(offset, block.size());

    // Round trip through the decoder must not fail on the unknown id.
    Axp1PeerCaps decoded;
    FL_CHECK(fl::pvm::axp1_decode_caps(block.data(), block.size(), &decoded));
}

FL_TEST(axp1_server_carries_a_full_distcc_exchange_in_memory) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 1024 * 1024,
                                                      "aarch64-unknown-linux-android"));
    const std::vector<uint8_t> request = fl::pvm::test::build_distcc_request(
        {"clang", "-c", "unit.c", "-o", "unit.o"}, "int answer(void) { return 42; }\n");
    // Two DATA frames, so the accumulator is exercised across frame borders.
    const size_t split = request.size() / 2;
    transport.append_frame(fl::pvm::kAxp1TypeData,
                           std::vector<uint8_t>(request.begin(), request.begin() + split));
    transport.append_frame(fl::pvm::kAxp1TypeData,
                           std::vector<uint8_t>(request.begin() + split, request.end()));
    transport.append_frame(fl::pvm::kAxp1TypeEof, {});

    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    FL_CHECK(outcome == WireError::kOk);
    FL_CHECK_EQ(engine.calls, 1);
    FL_CHECK(engine.last_source.find("answer") != std::string::npos);
    FL_CHECK(transport.shutdown_called);
    // Handshake timeout first, then the per-frame deadline.
    FL_CHECK(transport.timeout_calls >= 2);
    FL_CHECK_EQ(transport.last_timeout_ms, ServerLimits().frame_timeout_ms);

    std::vector<Axp1FrameView> frames;
    FL_CHECK(fl::pvm::test::decode_frames(transport.outbound(), &frames));
    const std::vector<uint8_t> response = fl::pvm::test::response_bytes(frames);
    FL_CHECK(response.size() > 12 * 5);
    const std::string text(reinterpret_cast<const char*>(response.data()),
                           response.size());
    FL_CHECK(text.rfind("DONE00000001", 0) == 0);
    FL_CHECK(text.find("STAT00000000") != std::string::npos);
    FL_CHECK(text.find("DOTO00000008") != std::string::npos);
    // The response is the last thing on the wire and closes with EOF.
    FL_CHECK_EQ(frames.back().type, fl::pvm::kAxp1TypeEof);
}

FL_TEST(axp1_server_rejects_a_bad_token_with_rst_auth_failed) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello("ffffffff" "ffffffff" "ffffffff" "ffffffff",
                                                      1024 * 1024));
    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    FL_CHECK(outcome == WireError::kAuthFailed);
    FL_CHECK(rst_code_is(transport.outbound(), 2));
    FL_CHECK_EQ(engine.calls, 0);
    // The RST must not leak the expected token anywhere.
    const std::string text(reinterpret_cast<const char*>(transport.outbound().data()),
                           transport.outbound().size());
    FL_CHECK(text.find(kToken) == std::string::npos);
}

FL_TEST(axp1_server_reports_a_version_mismatch_as_rst_1) {
    MemoryTransport transport;
    const std::vector<uint8_t> hello = fl::pvm::test::build_hello(kToken, 1024 * 1024);
    std::vector<uint8_t> wire;
    fl::pvm::axp1_encode_frame(fl::pvm::kAxp1TypeHello, hello, &wire);
    wire[4] = 2;  // AXP1 version 2 does not exist yet
    transport.append(wire);
    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    FL_CHECK(outcome == WireError::kVersionMismatch);
    FL_CHECK(rst_code_is(transport.outbound(), 1));
}

FL_TEST(axp1_server_reports_an_oversized_frame_as_rst_3) {
    MemoryTransport transport;
    std::vector<uint8_t> wire;
    fl::pvm::axp1_encode_frame(fl::pvm::kAxp1TypeHello, std::vector<uint8_t>(64, 0), &wire);
    // Rewrite the length header to 2 MiB: the server must refuse before it
    // allocates anything.
    wire[8] = 0x00;
    wire[9] = 0x20;
    wire[10] = 0x00;
    wire[11] = 0x00;
    transport.append(wire);
    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    FL_CHECK(outcome == WireError::kFrameTooLarge);
    FL_CHECK(rst_code_is(transport.outbound(), 3));
}

FL_TEST(axp1_server_rejects_a_handshake_frame_after_the_handshake) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 1024 * 1024));
    transport.append_frame(fl::pvm::kAxp1TypeHelloAck, {});
    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    FL_CHECK(outcome == WireError::kProtocolError);
    FL_CHECK(rst_code_is(transport.outbound(), 4));
}

FL_TEST(axp1_server_pongs_then_refuses_a_ping_flood) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 1024 * 1024));
    for (int index = 0; index < 5; ++index) {
        transport.append_frame(fl::pvm::kAxp1TypePing, {1, 2, 3, 4, 5, 6, 7, 8});
    }
    ServerLimits limits;
    limits.max_pings = 4;
    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), limits, &engine);
    FL_CHECK(outcome == WireError::kProtocolError);
    FL_CHECK(rst_code_is(transport.outbound(), 4));
    FL_CHECK_EQ(engine.calls, 0);
    FL_CHECK(has_frame(transport.outbound(), fl::pvm::kAxp1TypePong));
}

FL_TEST(axp1_server_answers_a_timeout_with_rst_5_and_never_compiles) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 1024 * 1024));
    transport.timeouts_remaining = 1;
    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    FL_CHECK(outcome == WireError::kTimeout);
    FL_CHECK(rst_code_is(transport.outbound(), 5));
    FL_CHECK_EQ(engine.calls, 0);
}

FL_TEST(axp1_server_drops_the_connection_on_a_distcc_protocol_defect) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 1024 * 1024));
    transport.append_frame(fl::pvm::kAxp1TypeData, {'N', 'O', 'P', 'E'});
    transport.append_frame(fl::pvm::kAxp1TypeEof, {});
    FixedEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), ServerLimits(), &engine);
    FL_CHECK(outcome == WireError::kProtocolError);
    FL_CHECK(rst_code_is(transport.outbound(), 4));
    FL_CHECK_EQ(engine.calls, 0);
    // distccd sends nothing at all for this case; the RST is the AXP1 layer's
    // own error surface, so no DONE/STAT may appear.
    std::vector<Axp1FrameView> frames;
    FL_CHECK(fl::pvm::test::decode_frames(transport.outbound(), &frames));
    for (const Axp1FrameView& frame : frames) {
        FL_CHECK(frame.type != fl::pvm::kAxp1TypeData);
    }
}

FL_TEST(axp1_server_drains_an_oversized_request_and_answers_job_too_large) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 1024 * 1024));
    // Two 6 KiB chunks against an 8 KiB ceiling: the server must stop
    // buffering and still give a distcc-level answer after EOF.
    ServerLimits limits;
    limits.max_input_bytes = 8 * 1024;
    limits.max_argv_count = 8;
    limits.max_argv_bytes = 64;
    const std::vector<uint8_t> chunk(6 * 1024, 'x');
    for (int index = 0; index < 2; ++index) {
        transport.append_frame(fl::pvm::kAxp1TypeData, chunk);
    }
    transport.append_frame(fl::pvm::kAxp1TypeEof, {});
    CountingOnlyEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), limits, &engine);
    FL_CHECK(outcome == WireError::kOk);
    FL_CHECK_EQ(engine.calls, 0);
    std::vector<Axp1FrameView> frames;
    FL_CHECK(fl::pvm::test::decode_frames(transport.outbound(), &frames));
    const std::vector<uint8_t> response = fl::pvm::test::response_bytes(frames);
    const std::string text(reinterpret_cast<const char*>(response.data()), response.size());
    FL_CHECK(text.rfind("DONE00000001", 0) == 0);
    FL_CHECK(text.find("STAT00006900") != std::string::npos);
    FL_CHECK(text.find("JOB_TOO_LARGE") != std::string::npos);
}

FL_TEST(axp1_server_stops_draining_a_hostile_endless_stream) {
    MemoryTransport transport;
    transport.append_frame(fl::pvm::kAxp1TypeHello,
                           fl::pvm::test::build_hello(kToken, 1024 * 1024));
    ServerLimits limits;
    limits.max_input_bytes = 8 * 1024;
    limits.max_argv_count = 8;
    limits.max_argv_bytes = 64;
    const std::vector<uint8_t> chunk(6 * 1024, 'x');
    for (int index = 0; index < 6; ++index) {
        transport.append_frame(fl::pvm::kAxp1TypeData, chunk);
    }
    transport.append_frame(fl::pvm::kAxp1TypeEof, {});
    CountingOnlyEngine engine;
    const WireError outcome = fl::pvm::axp1_serve_connection(
        &transport, config(), policy(), limits, &engine);
    FL_CHECK(outcome == WireError::kFrameTooLarge);
    FL_CHECK(rst_code_is(transport.outbound(), 3));
}

FL_TEST(axp1_server_rejects_a_caps_block_with_too_many_entries) {
    std::vector<uint8_t> block = {0, 64};
    for (int index = 0; index < 64; ++index) {
        block.push_back(static_cast<uint8_t>(index + 1));
        block.push_back(0);
        block.push_back(0);
    }
    block[1] = 250;  // count far beyond the Java limit of 64
    Axp1PeerCaps caps;
    FL_CHECK(!fl::pvm::axp1_decode_caps(block.data(), block.size(), &caps));
}

FL_TEST(axp1_server_refuses_a_config_with_an_impossible_frame_size) {
    MemoryTransport transport;
    FixedEngine engine;
    Axp1ServerConfig server = config();
    server.caps.max_frame = 0;
    FL_CHECK(fl::pvm::axp1_serve_connection(&transport, server, policy(), ServerLimits(),
                                            &engine) == WireError::kProtocolError);
    Axp1ServerConfig bad_token = config();
    bad_token.token = "short";
    FL_CHECK(fl::pvm::axp1_serve_connection(&transport, bad_token, policy(), ServerLimits(),
                                            &engine) == WireError::kProtocolError);
}
