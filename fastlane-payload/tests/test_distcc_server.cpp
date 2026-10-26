// distcc v1 codec + job orchestration tests (PVM-03 A2/A4).
//
// The two golden fixtures are byte dumps captured from a real distcc 3.4
// client and distccd, so they are the authority for token order, hex width and
// body lengths. Nothing here may relax them: a "works but differs by a byte"
// codec is a broken codec.

#include "distcc_server.h"

#include <chrono>
#include <string>
#include <thread>
#include <vector>

#include "compile_service.h"
#include "pvm_test_support.h"
#include "test_support.h"

namespace {

using fl::pvm::ArgvPolicyConfig;
using fl::pvm::CompileEngine;
using fl::pvm::DistccExitCode;
using fl::pvm::DistccParseStatus;
using fl::pvm::EngineRequest;
using fl::pvm::EngineResult;
using fl::pvm::JobStatus;
using fl::pvm::ServerLimits;
using fl::pvm::encode_distcc_response;
using fl::pvm::encode_token;
using fl::pvm::filter_argv;
using fl::pvm::parse_distcc_request;
using fl::pvm::run_distcc_job;
using fl::pvm::stat_for_status;

ArgvPolicyConfig policy() {
    ArgvPolicyConfig config;
    config.target_triple = "aarch64-unknown-linux-android";
    return config;
}

std::vector<uint8_t> golden_request() {
    return fl::pvm::test::read_hex_dump(FL_PVM_DISTCC_GOLDEN_DIR "/request-v1.hex");
}

std::vector<uint8_t> golden_response() {
    return fl::pvm::test::read_hex_dump(FL_PVM_DISTCC_GOLDEN_DIR "/response-v1.hex");
}

class CountingEngine final : public CompileEngine {
public:
    EngineResult compile(const EngineRequest& request) override {
        ++calls;
        last_flags = request.flags;
        last_language = request.language;
        EngineResult result;
        result.status = JobStatus::kOk;
        result.object = {0x7f, 0x45, 0x4c, 0x46};
        result.diagnostics = warnings;
        return result;
    }

    std::string compiler_version() const override { return "counting-engine 1"; }

    int calls = 0;
    std::vector<std::string> last_flags;
    std::string last_language;
    std::string warnings;
};

class FixedStatusEngine final : public CompileEngine {
public:
    explicit FixedStatusEngine(JobStatus status, int exit_code = 1)
        : status_(status), exit_code_(exit_code) {}

    EngineResult compile(const EngineRequest&) override {
        EngineResult result;
        result.status = status_;
        result.exit_code = exit_code_;
        result.diagnostics = "diag\n";
        return result;
    }

    std::string compiler_version() const override { return "fixed 1"; }

private:
    JobStatus status_;
    int exit_code_;
};

// Ignores cancellation and finishes only once the deadline fired: this is the
// case the server must win, because a payload cannot kill its own thread.
class SleepyEngine final : public CompileEngine {
public:
    explicit SleepyEngine(int linger_ms) : linger_ms_(linger_ms) {}

    EngineResult compile(const EngineRequest& request) override {
        const auto start = std::chrono::steady_clock::now();
        while (std::chrono::steady_clock::now() - start <
               std::chrono::milliseconds(linger_ms_)) {
            if (request.cancel != nullptr && request.cancel->cancelled.load()) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        EngineResult result;
        result.status = JobStatus::kOk;
        result.object = {1, 2, 3, 4};
        return result;
    }

    std::string compiler_version() const override { return "sleepy 1"; }

private:
    int linger_ms_;
};

std::string serr_of(const fl::pvm::DistccResponse& response) { return response.serr; }

}  // namespace

FL_TEST(distcc_server_parses_the_golden_request) {
    const std::vector<uint8_t> wire = golden_request();
    FL_CHECK_EQ(wire.size(), static_cast<size_t>(162));
    const auto parsed = parse_distcc_request(wire, ServerLimits());
    FL_CHECK(parsed.status == DistccParseStatus::kOk);
    FL_CHECK_EQ(parsed.request.argv.size(), static_cast<size_t>(3));
    FL_CHECK_EQ(parsed.request.argv[0], std::string("cc"));
    FL_CHECK_EQ(parsed.request.argv[1], std::string("-c"));
    FL_CHECK_EQ(parsed.request.argv[2], std::string("fixture.c"));
    FL_CHECK_EQ(parsed.request.doti.size(), static_cast<size_t>(77));
    FL_CHECK(parsed.request.doti.find("fastlane_answer") != std::string::npos);
}

FL_TEST(distcc_server_encodes_the_golden_response_byte_for_byte) {
    fl::pvm::DistccResponse response;
    response.stat = 0;
    response.serr = "warning: fixture only\n";
    response.sout = "fixture stdout\n";
    response.doto = {0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00,
                     0x00, 0x00, 0x00, 0xff, 0x0a, 0x00, 0x42};
    const std::vector<uint8_t> encoded = encode_distcc_response(response);
    const std::vector<uint8_t> expected = golden_response();
    FL_CHECK_EQ(encoded.size(), expected.size());
    FL_CHECK(encoded == expected);
}

FL_TEST(distcc_server_hex_parameter_is_eight_lowercase_digits) {
    const std::vector<uint8_t> header = encode_token("STAT", 0x1a2b3c4d);
    FL_CHECK_EQ(header.size(), static_cast<size_t>(12));
    const std::string text(reinterpret_cast<const char*>(header.data()), header.size());
    FL_CHECK_EQ(text, std::string("STAT1a2b3c4d"));
    const std::vector<uint8_t> zero = encode_token("DOTO", 0);
    FL_CHECK_EQ(std::string(reinterpret_cast<const char*>(zero.data()), zero.size()),
                std::string("DOTO00000000"));
}

FL_TEST(distcc_server_stat_values_follow_distcc_exit_codes) {
    // STAT is a wait() status: (exit << 8). 100/101/102 would be signals.
    FL_CHECK_EQ(stat_for_status(JobStatus::kOk), 0u);
    FL_CHECK_EQ(stat_for_status(JobStatus::kArgvRejected),
                static_cast<uint32_t>(DistccExitCode::kBadArguments) << 8);
    FL_CHECK_EQ(stat_for_status(JobStatus::kArgvRejected), 0x6500u);
    FL_CHECK_EQ(stat_for_status(JobStatus::kJobTooLarge), 0x6900u);
    FL_CHECK_EQ(stat_for_status(JobStatus::kJobTimeout), 0x7600u);
    FL_CHECK_EQ(stat_for_status(JobStatus::kInternal), 0x6e00u);
    FL_CHECK_EQ(stat_for_status(JobStatus::kCompileError, 1), 0x0100u);
    FL_CHECK_EQ(stat_for_status(JobStatus::kCompileError, 42), 0x2a00u);
    // Out-of-range compiler statuses clamp rather than masquerade as a signal.
    FL_CHECK_EQ(stat_for_status(JobStatus::kCompileError, 200), 0x0100u);
    FL_CHECK_EQ(stat_for_status(JobStatus::kCompileError, 0), 0x0100u);
}

FL_TEST(distcc_server_rejects_protocol_defects) {
    ServerLimits limits;
    // Bad DIST version: v2/v3 bring LZO and server-side cpp.
    {
        std::vector<uint8_t> wire = golden_request();
        wire[11] = '2';
        FL_CHECK(parse_distcc_request(wire, limits).status ==
                 DistccParseStatus::kProtocol);
    }
    // Wrong token order.
    {
        std::vector<uint8_t> wire = golden_request();
        wire[0] = 'X';
        FL_CHECK(parse_distcc_request(wire, limits).status ==
                 DistccParseStatus::kProtocol);
    }
    // Truncated header: before PVM-10 this was classified as a protocol
    // defect, which is exactly the conflation that made the payload wait for a
    // client half-close. "Not enough bytes yet" is not "wrong bytes".
    {
        std::vector<uint8_t> wire = golden_request();
        wire.resize(10);
        FL_CHECK(parse_distcc_request(wire, limits).status ==
                 DistccParseStatus::kIncomplete);
    }
    // Trailing byte after DOTI: the request ends with the DOTI body.
    {
        std::vector<uint8_t> wire = golden_request();
        wire.push_back(0x00);
        FL_CHECK(parse_distcc_request(wire, limits).status ==
                 DistccParseStatus::kProtocol);
    }
    // ARGC 0 would make the upstream server dereference a null argv[0].
    {
        std::vector<uint8_t> wire = golden_request();
        std::memcpy(wire.data() + 12 + 4, "00000000", 8);
        FL_CHECK(parse_distcc_request(wire, limits).status ==
                 DistccParseStatus::kProtocol);
    }
}

FL_TEST(distcc_server_rejects_oversized_requests) {
    ServerLimits limits;
    // ARGC beyond the configured count.
    {
        std::vector<uint8_t> wire = golden_request();
        std::memcpy(wire.data() + 12 + 4, "000000ff", 8);
        FL_CHECK(parse_distcc_request(wire, limits).status ==
                 DistccParseStatus::kTooLarge);
    }
    // A single ARGV beyond max_argv_bytes.
    {
        limits.max_argv_bytes = 4;
        FL_CHECK(parse_distcc_request(golden_request(), limits).status ==
                 DistccParseStatus::kTooLarge);
        limits.max_argv_bytes = 4096;
    }
    // DOTI beyond max_input_bytes.
    {
        limits.max_input_bytes = 16;
        FL_CHECK(parse_distcc_request(golden_request(), limits).status ==
                 DistccParseStatus::kTooLarge);
        limits.max_input_bytes = 32u * 1024u * 1024u;
    }
}

FL_TEST(distcc_server_reports_every_proper_prefix_as_incomplete) {
    // PVM-10: the request stream is fed to this codec one DATA frame at a
    // time, so every proper prefix must read as "not yet". Walking the cut one
    // byte at a time is what catches a length-difference heuristic: the header,
    // the ARGV bodies and the DOTI body all fail at different offsets.
    const std::vector<uint8_t> wire = golden_request();
    const ServerLimits limits;
    for (size_t cut = 0; cut < wire.size(); ++cut) {
        const std::vector<uint8_t> prefix(wire.begin(), wire.begin() + cut);
        const fl::pvm::DistccParseResult parsed = parse_distcc_request(prefix, limits);
        if (parsed.status != DistccParseStatus::kIncomplete) {
            ::fl::test::fail(__FILE__, __LINE__,
                             "prefix length " + std::to_string(cut) +
                                 " is not kIncomplete (status=" +
                                 std::to_string(static_cast<int>(parsed.status)) + ")");
        }
    }
    FL_CHECK(parse_distcc_request(wire, limits).status == DistccParseStatus::kOk);
}

FL_TEST(distcc_server_keeps_trailing_bytes_and_bad_tokens_as_protocol_errors) {
    const ServerLimits limits;
    // One byte past the DOTI body can never become a valid request.
    {
        std::vector<uint8_t> wire = golden_request();
        wire.push_back(0x00);
        FL_CHECK(parse_distcc_request(wire, limits).status == DistccParseStatus::kProtocol);
    }
    // DIST is whole but its version is not 1: no later byte repairs that, so
    // the caller is allowed to answer with a reset immediately.
    {
        std::vector<uint8_t> wire = golden_request();
        wire[11] = '2';
        const fl::pvm::DistccParseResult parsed = parse_distcc_request(wire, limits);
        FL_CHECK(parsed.status == DistccParseStatus::kProtocol);
        FL_CHECK(parsed.prefix_recognised);
    }
    // A token order defect after ARGC was read is equally final.
    {
        std::vector<uint8_t> wire = golden_request();
        std::memcpy(wire.data() + 12 + 12, "ARGX00000003", 12);
        const fl::pvm::DistccParseResult parsed = parse_distcc_request(wire, limits);
        FL_CHECK(parsed.status == DistccParseStatus::kProtocol);
        FL_CHECK(parsed.prefix_recognised);
    }
    // Junk before any token: still a protocol error, but not committed -- the
    // AXP1 layer keeps draining so an oversized request still earns
    // JOB_TOO_LARGE instead of a reset.
    {
        const std::vector<uint8_t> wire(24, 'x');
        const fl::pvm::DistccParseResult parsed = parse_distcc_request(wire, limits);
        FL_CHECK(parsed.status == DistccParseStatus::kProtocol);
        FL_CHECK(!parsed.prefix_recognised);
    }
}

FL_TEST(distcc_server_maps_argv_rejection_to_a_fixed_stat) {
    CountingEngine engine;
    const auto parsed = parse_distcc_request(golden_request(), ServerLimits());
    fl::pvm::DistccRequest request = parsed.request;
    request.argv = {"cc", "-Xclang", "-load", "evil.so", "fixture.c"};
    const auto response = run_distcc_job(request, policy(), ServerLimits(), &engine);
    FL_CHECK_EQ(response.stat, 0x6500u);
    FL_CHECK_EQ(serr_of(response), std::string("ARGV_REJECTED:CLANG_INTERNAL\n"));
    FL_CHECK_EQ(engine.calls, 0);
    FL_CHECK(response.doto.empty());
}

FL_TEST(distcc_server_maps_oversized_object_and_input_to_job_too_large) {
    CountingEngine engine;
    ServerLimits limits;
    limits.max_object_bytes = 2;
    const auto parsed = parse_distcc_request(golden_request(), limits);
    const auto response = run_distcc_job(parsed.request, policy(), limits, &engine);
    FL_CHECK_EQ(response.stat, 0x6900u);
    FL_CHECK_EQ(serr_of(response), std::string("JOB_TOO_LARGE\n"));
    FL_CHECK(response.doto.empty());

    ServerLimits input_limits;
    input_limits.max_input_bytes = 4;
    CountingEngine second;
    const auto response2 =
        run_distcc_job(parsed.request, policy(), input_limits, &second);
    FL_CHECK_EQ(response2.stat, 0x6900u);
    FL_CHECK_EQ(second.calls, 0);
}

FL_TEST(distcc_server_cancels_a_job_that_overruns_its_wall_clock) {
    SleepyEngine engine(200);
    ServerLimits limits;
    limits.wall_budget_ms = 20;
    const auto parsed = parse_distcc_request(golden_request(), limits);
    const auto started = std::chrono::steady_clock::now();
    const auto response = run_distcc_job(parsed.request, policy(), limits, &engine);
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                             std::chrono::steady_clock::now() - started)
                             .count();
    FL_CHECK_EQ(response.stat, 0x7600u);
    FL_CHECK_EQ(serr_of(response), std::string("JOB_TIMEOUT\n"));
    FL_CHECK(response.doto.empty());
    // The watchdog must actually interrupt, not just report afterwards.
    FL_CHECK(elapsed < 150);
}

FL_TEST(distcc_server_preserves_the_compiler_exit_status) {
    FixedStatusEngine engine(JobStatus::kCompileError, 1);
    const auto parsed = parse_distcc_request(golden_request(), ServerLimits());
    const auto response = run_distcc_job(parsed.request, policy(), ServerLimits(), &engine);
    FL_CHECK_EQ(response.stat, 0x0100u);
    FL_CHECK(response.serr.rfind("COMPILE_ERROR\n", 0) == 0);
    FL_CHECK(response.doto.empty());
}

FL_TEST(distcc_server_maps_a_missing_engine_to_payload_load_failed) {
    const auto parsed = parse_distcc_request(golden_request(), ServerLimits());
    const auto response = run_distcc_job(parsed.request, policy(), ServerLimits(), nullptr);
    FL_CHECK_EQ(response.stat, 0x6e00u);
    FL_CHECK_EQ(serr_of(response), std::string("PAYLOAD_LOAD_FAILED\n"));
}

FL_TEST(distcc_server_scrubs_paths_out_of_diagnostics) {
    CountingEngine engine;
    engine.warnings = "job.c:1:1: warning: /host/secret/path/header.h is odd\n";
    const auto parsed = parse_distcc_request(golden_request(), ServerLimits());
    const auto response = run_distcc_job(parsed.request, policy(), ServerLimits(), &engine);
    FL_CHECK_EQ(response.stat, 0u);
    FL_CHECK(response.serr.find("/host/secret") == std::string::npos);
    FL_CHECK(response.serr.find("<path>") != std::string::npos);
    // The message text itself is preserved; only path tokens are replaced.
    FL_CHECK(response.serr.find("is odd") != std::string::npos);
    // Idempotent: scrubbing twice changes nothing.
    FL_CHECK_EQ(fl::pvm::sanitize_diagnostics(response.serr), response.serr);
}

FL_TEST(distcc_server_truncates_diagnostics_without_splitting_utf8) {
    ServerLimits limits;
    limits.max_diagnostics_bytes = 8;
    const std::string text = "abc\u00e9\u00e9\n";  // 3 + 2*2 + 1 bytes
    const std::string truncated = fl::pvm::truncate_utf8(text, 6);
    FL_CHECK_EQ(truncated, std::string("abc\u00e9"));

    CountingEngine engine;
    engine.warnings = std::string(200, 'x');
    const auto parsed = parse_distcc_request(golden_request(), limits);
    const auto response = run_distcc_job(parsed.request, policy(), limits, &engine);
    FL_CHECK(response.serr.size() <= limits.max_diagnostics_bytes);
}

FL_TEST(distcc_server_accepts_the_argv_a_real_distcc_client_sends) {
    // PVM-11. This is the argv the guest actually produced, recovered by
    // reconciling the device-side `bytes_in = 2672` against the known command:
    //
    //   12 + 12 + 9*12 + 110 + 12 + 2418 = 2672
    //
    // Entry [2] came from the user's build file; [7] and [8] were injected by
    // distcc itself (dcc_add_clang_target appends the two-token spelling, and
    // it does so even when a --target= is already present). The payload used
    // to answer this with ARGV_REJECTED:UNKNOWN_FLAG, so the first remote
    // compile in the whole crossfire chain never happened.
    const std::vector<std::string> argv = {
        "clang",
        "-c",
        "--target=aarch64-unknown-linux-android",
        "-O1",
        "/root/fixture.c",
        "-o",
        "/root/cross.o",
        "-target",
        "aarch64-alpine-linux-musl",
    };

    // Pin the argv block to the evidence: 9 entries, 12-byte headers each,
    // 110 bytes of body. If this drifts the test is no longer about PVM-11.
    size_t argv_body_bytes = 0;
    for (const std::string& argument : argv) {
        argv_body_bytes += argument.size();
    }
    FL_CHECK_EQ(argv_body_bytes, static_cast<size_t>(110));

    const std::string doti = "int fastlane_answer;\n";
    const std::vector<uint8_t> wire = fl::pvm::test::build_distcc_request(argv, doti);
    FL_CHECK_EQ(wire.size(), static_cast<size_t>(12 + 12 + 9 * 12 + 110 + 12) +
                                 doti.size());

    const auto parsed = parse_distcc_request(wire, ServerLimits());
    FL_CHECK(parsed.status == DistccParseStatus::kOk);

    CountingEngine engine;
    const auto response = run_distcc_job(parsed.request, policy(), ServerLimits(), &engine);
    // Asserted before stat so a regression names the class instead of a number.
    FL_CHECK_EQ(response.serr, std::string());
    FL_CHECK_EQ(response.stat, 0u);
    FL_CHECK_EQ(engine.calls, 1);
    // Both target declarations are consumed by the policy: the engine always
    // emits for the configured triple, so forwarding either would only invite
    // the driver to disagree. -c and -O1 are the only survivors.
    FL_CHECK_EQ(engine.last_flags.size(), static_cast<size_t>(2));
    FL_CHECK_EQ(engine.last_flags[0], std::string("-c"));
    FL_CHECK_EQ(engine.last_flags[1], std::string("-O1"));
    FL_CHECK_EQ(response.doto.size(), static_cast<size_t>(4));
}

FL_TEST(distcc_server_forwards_only_allow_listed_flags_and_the_language) {
    CountingEngine engine;
    const std::vector<uint8_t> wire = fl::pvm::test::build_distcc_request(
        {"clang", "-c", "-x", "c++-cpp-output", "-O2", "unit.ii", "-o", "unit.o"},
        "int x;\n");
    const auto parsed = parse_distcc_request(wire, ServerLimits());
    FL_CHECK(parsed.status == DistccParseStatus::kOk);
    const auto response = run_distcc_job(parsed.request, policy(), ServerLimits(), &engine);
    FL_CHECK_EQ(response.stat, 0u);
    FL_CHECK_EQ(engine.calls, 1);
    FL_CHECK_EQ(engine.last_language, std::string("c++"));
    FL_CHECK_EQ(engine.last_flags.size(), static_cast<size_t>(2));
    FL_CHECK_EQ(engine.last_flags[0], std::string("-c"));
    FL_CHECK_EQ(engine.last_flags[1], std::string("-O2"));
}
