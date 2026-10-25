#include "distcc_server.h"

#include <chrono>
#include <cstring>
#include <thread>

namespace fl {
namespace pvm {
namespace {

constexpr size_t kPacketHeaderBytes = 12;  // token(4) + hex(8)

bool read_header(const std::vector<uint8_t>& wire, size_t offset,
                 const char* expect_token, uint32_t* parameter, size_t* next) {
    if (offset + kPacketHeaderBytes > wire.size()) {
        return false;
    }
    if (std::memcmp(wire.data() + offset, expect_token, 4) != 0) {
        return false;
    }
    uint32_t value = 0;
    for (size_t index = 4; index < kPacketHeaderBytes; ++index) {
        const uint8_t digit = wire[offset + index];
        uint32_t nibble;
        if (digit >= '0' && digit <= '9') {
            nibble = static_cast<uint32_t>(digit - '0');
        } else if (digit >= 'a' && digit <= 'f') {
            nibble = static_cast<uint32_t>(digit - 'a' + 10);
        } else if (digit >= 'A' && digit <= 'F') {
            nibble = static_cast<uint32_t>(digit - 'A' + 10);
        } else {
            return false;
        }
        value = (value << 4) | nibble;
    }
    *parameter = value;
    *next = offset + kPacketHeaderBytes;
    return true;
}

DistccParseResult protocol_error() {
    DistccParseResult result;
    result.status = DistccParseStatus::kProtocol;
    return result;
}

DistccParseResult too_large() {
    DistccParseResult result;
    result.status = DistccParseStatus::kTooLarge;
    return result;
}

/**
 * Interruptible wall clock: the engine polls JobCancel, and this thread flips
 * it at the deadline. distccd kills a child process; with no child to kill,
 * the equivalent is a cooperative cancel plus a discarded result.
 */
class WallClockWatchdog {
public:
    WallClockWatchdog(JobCancel* cancel, uint32_t budget_ms) {
        if (cancel == nullptr || budget_ms == 0) {
            return;
        }
        cancel_ = cancel;
        cancel_->cancelled.store(false);
        cancel_->timed_out.store(false);
        thread_ = std::thread([this, budget_ms]() {
            const auto deadline =
                std::chrono::steady_clock::now() + std::chrono::milliseconds(budget_ms);
            while (!done_.load(std::memory_order_relaxed)) {
                if (std::chrono::steady_clock::now() >= deadline) {
                    cancel_->timed_out.store(true);
                    cancel_->cancelled.store(true);
                    return;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
            }
        });
    }

    ~WallClockWatchdog() {
        done_.store(true);
        if (thread_.joinable()) {
            thread_.join();
        }
    }

    WallClockWatchdog(const WallClockWatchdog&) = delete;
    WallClockWatchdog& operator=(const WallClockWatchdog&) = delete;

    bool timed_out() const { return cancel_ != nullptr && cancel_->timed_out.load(); }

private:
    JobCancel* cancel_ = nullptr;
    std::atomic<bool> done_{false};
    std::thread thread_;
};

std::string with_fixed_code(const char* code,
                            const std::string& diagnostics,
                            size_t max_diagnostics_bytes) {
    std::string serr(code);
    serr.push_back('\n');
    if (!diagnostics.empty()) {
        std::string scrubbed = sanitize_diagnostics(diagnostics);
        if (scrubbed.back() != '\n') {
            scrubbed.push_back('\n');
        }
        serr += scrubbed;
    }
    return truncate_utf8(serr, max_diagnostics_bytes);
}

}  // namespace

// The request wire adds ~12 bytes per ARGV plus headers on top of the .i
// text; allow that much slack over max_input_bytes while accumulating.
size_t distcc_request_ceiling(const ServerLimits& limits) {
    return limits.max_input_bytes +
           limits.max_argv_count * (limits.max_argv_bytes + kPacketHeaderBytes) +
           4 * kPacketHeaderBytes;
}

std::vector<uint8_t> encode_token(const char token[5], uint32_t parameter) {
    static const char* kHex = "0123456789" "abcdef";
    std::vector<uint8_t> wire(kPacketHeaderBytes);
    std::memcpy(wire.data(), token, 4);
    for (int shift = 28, index = 4; shift >= 0; shift -= 4, ++index) {
        wire[index] = static_cast<uint8_t>(kHex[(parameter >> shift) & 0xf]);
    }
    return wire;
}

DistccParseResult parse_distcc_request(const std::vector<uint8_t>& wire,
                                       const ServerLimits& limits) {
    if (wire.size() > distcc_request_ceiling(limits)) {
        return too_large();
    }

    size_t offset = 0;
    uint32_t parameter = 0;

    if (!read_header(wire, offset, "DIST", &parameter, &offset) || parameter != 1) {
        // v2/v3 add LZO and server-side cpp; this service speaks v1 only and
        // says so by refusing the stream rather than half-parsing it.
        return protocol_error();
    }
    if (!read_header(wire, offset, "ARGC", &parameter, &offset)) {
        return protocol_error();
    }
    if (parameter == 0) {
        return protocol_error();
    }
    if (parameter > limits.max_argv_count) {
        return too_large();
    }

    const uint32_t argc = parameter;
    DistccParseResult result;
    result.status = DistccParseStatus::kOk;
    result.request.argv.reserve(argc);
    for (uint32_t index = 0; index < argc; ++index) {
        uint32_t length = 0;
        if (!read_header(wire, offset, "ARGV", &length, &offset)) {
            return protocol_error();
        }
        if (length > limits.max_argv_bytes) {
            return too_large();
        }
        if (offset + length > wire.size()) {
            return protocol_error();
        }
        result.request.argv.emplace_back(
            reinterpret_cast<const char*>(wire.data() + offset), length);
        offset += length;
    }

    if (!read_header(wire, offset, "DOTI", &parameter, &offset)) {
        return protocol_error();
    }
    if (parameter > limits.max_input_bytes) {
        return too_large();
    }
    if (offset + parameter != wire.size()) {
        // Trailing bytes or truncation: the request stream ends with DOTI.
        return protocol_error();
    }
    result.request.doti.assign(reinterpret_cast<const char*>(wire.data() + offset),
                               parameter);
    return result;
}

std::vector<uint8_t> encode_distcc_response(const DistccResponse& response) {
    std::vector<uint8_t> wire;
    wire.reserve(5 * kPacketHeaderBytes + response.serr.size() + response.sout.size() +
                 response.doto.size());

    auto append = [&wire](const char token[5], uint32_t parameter,
                          const void* body, size_t size) {
        const std::vector<uint8_t> header = encode_token(token, parameter);
        wire.insert(wire.end(), header.begin(), header.end());
        if (size != 0) {
            const auto* bytes = static_cast<const uint8_t*>(body);
            wire.insert(wire.end(), bytes, bytes + size);
        }
    };

    append("DONE", 1, nullptr, 0);
    append("STAT", response.stat, nullptr, 0);
    append("SERR", static_cast<uint32_t>(response.serr.size()),
           response.serr.data(), response.serr.size());
    append("SOUT", static_cast<uint32_t>(response.sout.size()),
           response.sout.data(), response.sout.size());
    append("DOTO", static_cast<uint32_t>(response.doto.size()),
           response.doto.data(), response.doto.size());
    return wire;
}

uint32_t stat_for_status(JobStatus status, int compiler_exit_code) {
    switch (status) {
        case JobStatus::kOk:
            return 0;
        case JobStatus::kArgvRejected:
            return stat_from_exit_code(DistccExitCode::kBadArguments);
        case JobStatus::kJobTooLarge:
            return stat_from_exit_code(DistccExitCode::kOutOfMemory);
        case JobStatus::kJobTimeout:
            return stat_from_exit_code(DistccExitCode::kTimeout);
        case JobStatus::kCompileError: {
            // Preserve the compiler's own status so the client sees the same
            // number it would have seen locally.
            const int code = compiler_exit_code > 0 && compiler_exit_code < 128
                                 ? compiler_exit_code
                                 : 1;
            return static_cast<uint32_t>(code) << 8;
        }
        case JobStatus::kInternal:
            return stat_from_exit_code(DistccExitCode::kCompilerMissing);
    }
    return stat_from_exit_code(DistccExitCode::kFailed);
}

DistccResponse run_distcc_job(const DistccRequest& request,
                              const ArgvPolicyConfig& policy,
                              const ServerLimits& limits,
                              CompileEngine* engine) {
    DistccResponse response;

    const ArgvDecision decision = filter_argv(request.argv, policy);
    if (!decision.accepted) {
        response.stat = stat_for_status(JobStatus::kArgvRejected);
        response.serr = decision.failure_code + "\n";
        return response;
    }

    if (request.doti.size() > limits.max_input_bytes) {
        response.stat = stat_for_status(JobStatus::kJobTooLarge);
        response.serr = "JOB_TOO_LARGE\n";
        return response;
    }

    if (engine == nullptr) {
        response.stat = stat_for_status(JobStatus::kInternal);
        response.serr = "PAYLOAD_LOAD_FAILED\n";
        return response;
    }

    JobCancel cancel;
    EngineRequest engine_request;
    engine_request.flags = decision.flags;
    engine_request.language = decision.language;
    engine_request.source = request.doti;
    engine_request.cancel = &cancel;

    EngineResult result;
    {
        WallClockWatchdog watchdog(&cancel, limits.wall_budget_ms);
        result = engine->compile(engine_request);
    }
    if (cancel.timed_out.load() && result.status == JobStatus::kOk) {
        // The engine ignored the cancel and still produced an object: the
        // budget is the contract, so the result is dropped, never returned.
        result = EngineResult();
        result.status = JobStatus::kJobTimeout;
    }

    if (result.status == JobStatus::kOk &&
        result.object.size() > limits.max_object_bytes) {
        response.stat = stat_for_status(JobStatus::kJobTooLarge);
        response.serr = "JOB_TOO_LARGE\n";
        return response;
    }

    switch (result.status) {
        case JobStatus::kOk:
            response.stat = 0;
            response.doto = std::move(result.object);
            if (!result.diagnostics.empty()) {
                // A successful compile may still carry warnings: they belong
                // in SERR exactly like distccd forwards them.
                response.serr = truncate_utf8(
                    sanitize_diagnostics([&] {
                        std::string text = result.diagnostics;
                        if (text.back() != '\n') {
                            text.push_back('\n');
                        }
                        return text;
                    }()),
                    limits.max_diagnostics_bytes);
            }
            return response;
        case JobStatus::kArgvRejected:
            response.stat = stat_for_status(JobStatus::kArgvRejected);
            response.serr = "ARGV_REJECTED:CLANG_INTERNAL\n";  // engine-level veto
            return response;
        case JobStatus::kJobTooLarge:
            response.stat = stat_for_status(JobStatus::kJobTooLarge);
            response.serr = "JOB_TOO_LARGE\n";
            return response;
        case JobStatus::kJobTimeout:
            response.stat = stat_for_status(JobStatus::kJobTimeout);
            response.serr = "JOB_TIMEOUT\n";
            return response;
        case JobStatus::kCompileError:
        case JobStatus::kInternal:
            break;
    }

    const char* code = result.status == JobStatus::kCompileError
                           ? job_status_name(JobStatus::kCompileError)
                           : job_status_name(JobStatus::kInternal);
    response.stat = stat_for_status(result.status, result.exit_code);
    response.serr = with_fixed_code(code, result.diagnostics,
                                    limits.max_diagnostics_bytes);
    return response;
}

}  // namespace pvm
}  // namespace fl
