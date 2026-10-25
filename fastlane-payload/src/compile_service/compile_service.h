// PVM-03 compile service: distcc v1 over AXP1 inside a protected payload.
//
// Layering (all bytes in memory, zero file writes — the payload domain has
// none, DV-PVM-01 01a):
//
//   Axp1ServerSession   AXP1 frames over a byte transport (handshake, DATA,
//                       EOF, RST, PING/PONG)
//   DistccServer        distcc v1 request/response codec + job orchestration
//   ArgvPolicy          untrusted-argv triage (allow / absorb / reject)
//   CompileEngine       .i -> .o (FakeEngine deterministic; ClangEngine links
//                       libclang and is only built with
//                       FL_PVM_COMPILER_ENGINE=CLANG)
//
// Fixed failure codes follow spec §5.3; the STAT values they map to are the
// distcc `src/exitcode.h` codes, because STAT is a `wait()` status and a
// client reads `>= 128` as "retry elsewhere" versus `< 128` as "this compile
// failed". ARGV_REJECTED classes are a fixed enum and never echo the
// offending argument.
//
// Nothing here may throw: the whole library is compiled with -fno-exceptions
// so an unwind can never cross into the EH=OFF libclang (决策地图 §11).

#ifndef FASTLANE_PVM_COMPILE_SERVICE_H_
#define FASTLANE_PVM_COMPILE_SERVICE_H_

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace fl {
namespace pvm {

/** Job-level status; names are the spec §5.3 fixed codes. */
enum class JobStatus : int {
    kOk = 0,
    kArgvRejected = 1,   // ARGV_REJECTED:<class>
    kJobTooLarge = 2,    // JOB_TOO_LARGE
    kJobTimeout = 3,     // JOB_TIMEOUT
    kCompileError = 4,   // COMPILE_ERROR
    kInternal = 5,       // engine unavailable / implementation failure
};

const char* job_status_name(JobStatus status);

/** distcc's own exit codes (upstream src/exitcode.h), the STAT high byte. */
enum class DistccExitCode : uint32_t {
    kFailed = 100,             // EXIT_DISTCC_FAILED
    kBadArguments = 101,       // EXIT_BAD_ARGUMENTS
    kOutOfMemory = 105,        // EXIT_OUT_OF_MEMORY
    kProtocolError = 109,      // EXIT_PROTOCOL_ERROR
    kCompilerMissing = 110,    // EXIT_COMPILER_MISSING
    kTimeout = 118,            // EXIT_TIMEOUT
};

/** wait()-status form of a distcc exit code: `code << 8`, never a signal. */
uint32_t stat_from_exit_code(DistccExitCode code);

/** AXP1 RST payload code space (mirror of the Java Axp1Error enum). */
enum class WireError : int {
    kOk = 0,
    kVersionMismatch = 1,
    kAuthFailed = 2,
    kFrameTooLarge = 3,
    kProtocolError = 4,
    kTimeout = 5,
    kClosed = 6,
};

/** Resource ceilings for one server (A4). Defaults are the ticket values. */
struct ServerLimits {
    size_t max_input_bytes = 32u * 1024u * 1024u;    // .i upper bound
    size_t max_object_bytes = 64u * 1024u * 1024u;   // .o upper bound
    size_t max_diagnostics_bytes = 64u * 1024u;
    uint32_t wall_budget_ms = 60000;                 // per job, interruptible
    int max_jobs = 1;                                // 1..2 per instance
    size_t max_argv_count = 128;
    size_t max_argv_bytes = 4096;                    // per element
    uint32_t frame_timeout_ms = 60000;               // per blocking AXP1 read
    size_t max_pings = 64;                           // per connection
};

/** Cooperative cancellation handle shared by the server and the engine. */
struct JobCancel {
    std::atomic<bool> cancelled{false};
    std::atomic<bool> timed_out{false};
};

/** One compile job handed to an engine: allow-listed flags plus the .i text. */
struct EngineRequest {
    std::vector<std::string> flags;  // ArgvPolicy allow list, forwarded verbatim
    std::string language;            // "c" or "c++"
    std::string source;              // preprocessed .i, memory only
    /** Never null in server use; engines poll it while they run. */
    JobCancel* cancel = nullptr;
};

struct EngineResult {
    JobStatus status = JobStatus::kInternal;
    std::vector<uint8_t> object;     // empty unless kOk
    std::string diagnostics;         // compiler text, sanitized before SERR
    /** Compiler exit status; only meaningful for kCompileError. */
    int exit_code = 1;
};

/**
 * In-process compile engine. Implementations must not write files, fork or
 * exec; the object stays in memory (DV-PVM-01 01b setOutputStream route).
 */
class CompileEngine {
public:
    virtual ~CompileEngine() = default;
    virtual EngineResult compile(const EngineRequest& request) = 0;
    /** Advertised in HELLO_ACK capability COMPILER_VERSION. */
    virtual std::string compiler_version() const = 0;
};

/** Sanitizes diagnostics: every path-like token becomes "<path>". */
std::string sanitize_diagnostics(const std::string& text);

/** Truncates to at most `max_bytes` without splitting a UTF-8 sequence. */
std::string truncate_utf8(const std::string& text, size_t max_bytes);

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_COMPILE_SERVICE_H_
