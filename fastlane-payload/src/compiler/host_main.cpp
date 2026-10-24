// Host driver for the in-process compiler prototype (PVM-02).
//
// Compiles a fixed fixture N times and never links it. tests/inprocess_noexec.sh
// runs this binary under strace and asserts the trace shows no execve, fork or
// vfork beyond the initial launch, which is the mechanism proof for "the
// payload can compile in process".

#include "compiler.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>

namespace {

const char* const kFixture =
    "int puts(const char*);\n"
    "int fixture_value(void) { return 6 * 7; }\n"
    "int main(void) { puts(\"pvm-inprocess-ok\"); return fixture_value(); }\n";

}  // namespace

int main(int argc, char** argv) {
    const int iterations = argc > 1 ? std::atoi(argv[1]) : 3;
    if (iterations < 1) {
        std::fprintf(stderr, "usage: pvm_compiler_host [iterations>=1]\n");
        return 2;
    }

    fl::TinyCcCompiler compiler;
    fl::CompileRequest request;
    request.source = kFixture;
    request.argv = {"clang", "-c", "fixture.i"};

    for (int i = 0; i < iterations; ++i) {
        const auto started = std::chrono::steady_clock::now();
        const fl::CompileOutcome outcome = compiler.compile(request);
        const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started);
        if (outcome.status != fl::CompileStatus::kOk) {
            std::fprintf(stderr, "compile %d failed: %s\n%s", i,
                         fl::compile_status_name(outcome.status),
                         outcome.diagnostics.stderr_text.c_str());
            return 1;
        }
        std::printf("compile %d ok: %zu bytes in %lld ms\n", i, outcome.object.size(),
                    static_cast<long long>(elapsed.count()));
    }
    std::printf("INPROCESS_HOST_OK\n");
    return 0;
}
