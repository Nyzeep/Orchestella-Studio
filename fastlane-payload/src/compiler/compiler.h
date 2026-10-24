#pragma once

// In-process compiler kernel interface (PVM-02 prototype).
//
// Contract: source and object bytes live in memory only. A compile must never
// fork/exec/spawn and must not write any visible file — the implementation
// captures the compiler's file-oriented object output through an anonymous
// memfd on Linux/Android. The mechanism proof is the strace-based
// inprocess_noexec ctest, which asserts zero execve/fork/vfork for the whole
// process while it compiles.

#include <chrono>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace fl {

/** Status of one in-process compile job; names follow spec §5.3 fixed codes. */
enum class CompileStatus : int {
    kOk = 0,
    kArgvRejected = 1,   // ARGV_REJECTED: argv hit the prototype deny list
    kJobTooLarge = 2,    // JOB_TOO_LARGE: source or object beyond the limits
    kJobTimeout = 3,     // JOB_TIMEOUT: wall budget exceeded
    kCompileError = 4,   // compiler-level diagnostics, no object produced
    kInternal = 5,       // unexpected implementation failure
};

const char* compile_status_name(CompileStatus status);

struct CompileLimits {
    size_t max_source_bytes = 16u * 1024u * 1024u;
    size_t max_object_bytes = 64u * 1024u * 1024u;
    size_t max_diagnostics_bytes = 64u * 1024u;
    std::chrono::milliseconds wall_budget{60000};
};

struct CompileRequest {
    // Preprocessed .i text (C for this prototype). Must not contain #include:
    // the payload has no guest filesystem to resolve headers against.
    std::string_view source;
    // Guest argv, treated as untrusted. Prototype policy is fail-closed:
    // "-c" and non-flag arguments are accepted, everything else (including
    // "@response-file" and "-o") is rejected. The full distcc argv whitelist
    // belongs to PVM-03.
    std::vector<std::string> argv;
    CompileLimits limits;
};

struct CompileDiagnostics {
    // Compiler messages only; may quote the offending token, never the whole
    // source. Capped by CompileLimits::max_diagnostics_bytes.
    std::string stderr_text;
    std::string compiler_id;
};

struct CompileOutcome {
    CompileStatus status = CompileStatus::kInternal;
    // Relocatable object bytes (ELF on Linux/Android); empty unless kOk.
    std::vector<uint8_t> object;
    CompileDiagnostics diagnostics;
    std::chrono::milliseconds elapsed{0};
};

/** One .i -> .o compile inside the current process, no subprocesses. */
class InProcessCompiler {
public:
    virtual ~InProcessCompiler() = default;
    virtual CompileOutcome compile(const CompileRequest& request) = 0;
};

/**
 * TinyCC-backed prototype kernel.
 *
 * License conclusion (PVM-02): TinyCC is LGPL-2.1. The vendored code is
 * fetched at build time from a hash-pinned tarball and must never become a
 * product dependency or enter any distributed artifact; PVM-03/PVM-04 replace
 * it with an Apache-2.0 libclang build.
 */
class TinyCcCompiler final : public InProcessCompiler {
public:
    CompileOutcome compile(const CompileRequest& request) override;
};

}  // namespace fl
