// DV-PVM-01a mechanism gate: TinyCC in-process compile inside the payload.
//
// QA-only payload entry (name: libpvm_probe.so). Compiles the PVM-02 host
// fixture N times with fl::TinyCcCompiler — no fork/exec anywhere — and logs
// one PVM_PROBE line per iteration. Exit code 0 means every iteration
// produced an ELF object; anything else is a fixed-code failure.
//
// The TinyCC build for this target bakes its builtin predefs in
// (CONFIG_TCC_PREDEFS), so no filesystem include path is consulted at run
// time: the payload has no headers to resolve and FL_TINYCC_INCLUDE is a
// build-host path that cannot exist on the device.

#include <string>
#include <vector>

#include "probe_common.h"

#include "fastlane/vm_payload_api.h"

#include "compiler.h"

namespace {

constexpr int kIterations = 10;

// Equivalent of the PVM-02 host fixture: valid as C source and as a
// preprocessed .i unit — no includes, one call, fixed value.
const char* const kFixture =
    "int puts(const char*);\n"
    "int fixture_value(void) { return 6 * 7; }\n"
    "int main(void) { puts(\"pvm-inprocess-ok\"); return fixture_value(); }\n";

constexpr const char* kFallbackVersion = "tcc (in-process prototype)";

}  // namespace

int run_probe() {
    // Open the vsock result channel before notifying readiness: the host
    // dials as soon as the VM runs, and the payload must not finish (the
    // tcc fixture compiles in milliseconds) before the channel is live.
    pvmprobe::start_result_listener();
    pvmprobe::g_first_failure_exit.store(1);
    AVmPayload_notifyPayloadReady();
    const long long started = pvmprobe::steady_now_ms();
    const int topology = pvmprobe::cpu_topology_online();
#if defined(__ANDROID__)
    pvmprobe::emit("caps", "OK", 0, 0, pvmprobe::ru_maxrss_kb(), 0,
                   pvmprobe::capability_facts(), topology);
#endif

    fl::CompileRequest request;
    request.source = kFixture;
    request.argv = {"clang", "-c", "probe.i"};

    int failures = 0;
    for (int iter = 1; iter <= kIterations; ++iter) {
        const long long iter_started = pvmprobe::steady_now_ms();
        const fl::CompileOutcome outcome =
            fl::TinyCcCompiler().compile(request);
        const long long elapsed = pvmprobe::steady_ms_since(iter_started);
        const bool ok = outcome.status == fl::CompileStatus::kOk &&
                        pvmprobe::looks_like_elf(outcome.object);
        if (!ok) {
            ++failures;
            if (outcome.status != fl::CompileStatus::kOk && failures == 1) {
                pvmprobe::g_first_failure_exit.store(12);
            }
            if (outcome.status == fl::CompileStatus::kOk &&
                !pvmprobe::looks_like_elf(outcome.object) && failures == 1) {
                pvmprobe::g_first_failure_exit.store(13);
            }
        }
        std::string version = outcome.diagnostics.compiler_id.empty()
                                  ? std::string(kFallbackVersion)
                                  : outcome.diagnostics.compiler_id;
        if (!ok && !outcome.diagnostics.stderr_text.empty()) {
            // The failure diagnostic is the evidence the gate needs; the
            // compiler id is already known from the host smoke run.
            version = pvmprobe::scrub_paths(outcome.diagnostics.stderr_text.substr(
                0, outcome.diagnostics.stderr_text.find_first_of("\r\n")));
        }
        pvmprobe::emit("compile", ok ? "OK" : fl::compile_status_name(outcome.status),
                       iter, elapsed, pvmprobe::ru_maxrss_kb(),
                       ok ? static_cast<long long>(outcome.object.size()) : 0,
                       version, topology);
    }

    pvmprobe::emit("done", failures == 0 ? "OK" : "COMPILE_FAILED", 0,
                   pvmprobe::steady_ms_since(started), pvmprobe::ru_maxrss_kb(), 0,
                   kFallbackVersion, topology);
    pvmprobe::finish_result_sink();
    return failures == 0 ? 0 : pvmprobe::g_first_failure_exit.load();
}

#if defined(__ANDROID__)
extern "C" __attribute__((visibility("default"))) int AVmPayload_main(void) {
    return run_probe();
}
#else
int main() { return run_probe(); }
#endif
