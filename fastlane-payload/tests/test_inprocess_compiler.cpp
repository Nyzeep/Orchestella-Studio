#include "compiler.h"

#include "test_support.h"

#include <sys/types.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <system_error>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <psapi.h>
#else
#include <sys/wait.h>
#include <unistd.h>
#endif

namespace fl {
namespace test {
namespace {

#if defined(__SANITIZE_ADDRESS__)
#define FL_TEST_UNDER_ASAN 1
#elif defined(__clang__)
#if __has_feature(address_sanitizer)
#define FL_TEST_UNDER_ASAN 1
#endif
#endif

// Deterministic fixture that stays valid both as C source and as a
// preprocessed .i unit: no includes, one print, fixed exit code.
const char* const kProgram =
    "int puts(const char*);\n"
    "int fixture_value(void) { return 6 * 7; }\n"
    "int main(void) { puts(\"pvm-inprocess-ok\"); return fixture_value(); }\n";

CompileRequest make_request(std::string_view source) {
    CompileRequest request;
    request.source = source;
    request.argv = {"clang", "-c", "fixture.i"};
    return request;
}

// Scratch files land in the working directory (ctest's build dir): /tmp may
// be mounted noexec, and the linked fixture executable must be runnable.
std::filesystem::path scratch_path(const char* suffix) {
    static std::atomic<unsigned> counter{0};
#if defined(_WIN32)
    const unsigned pid = ::GetCurrentProcessId();
#else
    const unsigned pid = static_cast<unsigned>(::getpid());
#endif
    std::ostringstream name;
    name << "fl_inprocess_" << pid << "_" << counter++ << suffix;
    return std::filesystem::current_path() / name.str();
}

bool write_bytes(const std::filesystem::path& path, const std::vector<uint8_t>& bytes) {
    std::ofstream stream(path, std::ios::binary | std::ios::trunc);
    if (!stream.is_open()) {
        return false;
    }
    stream.write(reinterpret_cast<const char*>(bytes.data()),
                 static_cast<std::streamsize>(bytes.size()));
    return static_cast<bool>(stream);
}

struct RunResult {
    int exit_code = -1;
    std::string output;
};

// The test harness may spawn compilers/linkers: the no-exec assertion covers
// the compile() call itself, which the dedicated inprocess_noexec ctest
// verifies under strace for the whole pvm_compiler_host process.
RunResult run_command(const std::string& command) {
    RunResult result;
#if defined(_WIN32)
    FILE* pipe = ::_popen(command.c_str(), "r");
#else
    FILE* pipe = ::popen(command.c_str(), "r");
#endif
    if (pipe == nullptr) {
        fail(__FILE__, __LINE__, "popen failed for: " + command);
    }
    char buffer[4096];
    while (std::fgets(buffer, sizeof buffer, pipe) != nullptr) {
        result.output.append(buffer);
    }
#if defined(_WIN32)
    result.exit_code = ::_pclose(pipe);
#else
    const int raw = ::pclose(pipe);
    result.exit_code = raw == -1 ? -1 : WEXITSTATUS(raw);
#endif
    return result;
}

// Unused under AddressSanitizer builds, where the RSS test body is compiled
// out (the quarantine distorts RSS); -Werror would flag it there.
[[maybe_unused]] size_t rss_bytes() {
#if defined(_WIN32)
    PROCESS_MEMORY_COUNTERS counters{};
    counters.cb = sizeof counters;
    if (::GetProcessMemoryInfo(::GetCurrentProcess(), &counters, sizeof counters) == 0) {
        fail(__FILE__, __LINE__, "GetProcessMemoryInfo failed");
    }
    return static_cast<size_t>(counters.WorkingSetSize);
#else
    std::ifstream statm("/proc/self/statm");
    size_t total_pages = 0;
    size_t resident_pages = 0;
    statm >> total_pages >> resident_pages;
    if (!statm) {
        fail(__FILE__, __LINE__, "cannot read /proc/self/statm");
    }
    return resident_pages * static_cast<size_t>(::sysconf(_SC_PAGESIZE));
#endif
}

}  // namespace

FL_TEST(inprocess_compiles_links_and_runs) {
    TinyCcCompiler compiler;
    const CompileOutcome outcome = compiler.compile(make_request(kProgram));
    if (outcome.status != CompileStatus::kOk) {
        std::cout << "diagnostics: " << outcome.diagnostics.stderr_text;
        fail(__FILE__, __LINE__, "expected kOk, got compile status");
    }
    FL_CHECK(!outcome.object.empty());
#if !defined(_WIN32)
    FL_CHECK(outcome.object[0] == 0x7f);
    FL_CHECK(outcome.object[1] == 'E');
    FL_CHECK(outcome.object[2] == 'L');
    FL_CHECK(outcome.object[3] == 'F');
    const uint16_t elf_type = static_cast<uint16_t>(outcome.object[16]) |
                              static_cast<uint16_t>(outcome.object[17]) << 8;
    FL_CHECK_EQ(elf_type, 1);  // ET_REL: relocatable object
#endif

    const auto object_path = scratch_path(".o");
    FL_CHECK(write_bytes(object_path, outcome.object));

#if defined(_WIN32)
    // TinyCC's win64 objects carry SEH metadata (.pdata fixups against its
    // internal .uw_base symbol) that GNU ld cannot resolve; only TinyCC's own
    // linker understands them. The link-and-run proof runs on Linux and on
    // the arm64 payload; on Windows the object is verified to exist only.
    std::cout << "SKIP link-and-run on Windows: TinyCC win64 objects are not "
                 "GNU-ld compatible (SEH .uw_base fixups); object produced and "
                 "written for inspection\n";
#else
    const auto exe_path = scratch_path("");
    const RunResult linked = run_command("gcc \"" + object_path.string() + "\" -o \"" +
                                         exe_path.string() + "\"");
    if (linked.exit_code != 0) {
        std::cout << "link output: " << linked.output;
        fail(__FILE__, __LINE__, "gcc link of the compiled object failed");
    }

    const RunResult executed = run_command("\"" + exe_path.string() + "\"");
    FL_CHECK_EQ(executed.exit_code, 42);
    FL_CHECK(executed.output.find("pvm-inprocess-ok") != std::string::npos);

    std::error_code ignored;
    std::filesystem::remove(object_path, ignored);
    std::filesystem::remove(exe_path, ignored);
#endif
}

FL_TEST(inprocess_reports_syntax_errors) {
    TinyCcCompiler compiler;
    const CompileOutcome outcome =
        compiler.compile(make_request("int main(void) { this is not C }\n"));
    FL_CHECK(outcome.status == CompileStatus::kCompileError);
    FL_CHECK(!outcome.diagnostics.stderr_text.empty());
    FL_CHECK(outcome.object.empty());
}

FL_TEST(inprocess_rejects_oversized_input) {
    TinyCcCompiler compiler;
    CompileRequest request = make_request(kProgram);
    request.limits.max_source_bytes = 8;
    const CompileOutcome outcome = compiler.compile(request);
    FL_CHECK(outcome.status == CompileStatus::kJobTooLarge);
    FL_CHECK(outcome.object.empty());
}

FL_TEST(inprocess_rejects_unsafe_argv) {
    TinyCcCompiler compiler;
    CompileRequest request = make_request(kProgram);

    request.argv = {"clang", "-o", "evil.o"};
    FL_CHECK(compiler.compile(request).status == CompileStatus::kArgvRejected);

    request.argv = {"clang", "@response.rsp"};
    FL_CHECK(compiler.compile(request).status == CompileStatus::kArgvRejected);

    request.argv = {"clang", "-c"};
    FL_CHECK(compiler.compile(request).status == CompileStatus::kOk);
}

FL_TEST(inprocess_rss_stays_bounded_over_ten_compiles) {
#if defined(FL_TEST_UNDER_ASAN)
    std::cout << "SKIP: RSS assertion disabled under AddressSanitizer; the "
                 "quarantine distorts RSS and LeakSanitizer enforces leak safety\n";
#else
    // Amplify per-job state so a per-compile leak rises above allocator noise.
    std::ostringstream stream;
    for (int i = 0; i < 200; ++i) {
        stream << "int fn" << i << "(int x) { return x + " << i << "; }\n";
    }
    stream << "int main(void) { return fn199(0); }\n";
    const std::string source = stream.str();

    TinyCcCompiler compiler;
    std::vector<size_t> samples;
    samples.push_back(rss_bytes());
    for (int i = 0; i < 10; ++i) {
        const CompileOutcome outcome = compiler.compile(make_request(source));
        if (outcome.status != CompileStatus::kOk) {
            std::cout << "diagnostics: " << outcome.diagnostics.stderr_text;
            fail(__FILE__, __LINE__, "expected kOk on every iteration");
        }
        samples.push_back(rss_bytes());
    }

    bool strictly_increasing = true;
    for (size_t i = 1; i < samples.size(); ++i) {
        if (samples[i] <= samples[i - 1]) {
            strictly_increasing = false;
        }
    }
    FL_CHECK(!strictly_increasing);

    const size_t first = samples[1];
    const size_t last = samples.back();
    // The working set can shrink between samples (especially on Windows), so
    // the growth allowance derives from a positive delta only.
    const size_t growth_room = first > samples[0] ? 2 * (first - samples[0]) : 0;
    const size_t allowed = std::max<size_t>(2u * 1024u * 1024u, growth_room);
    if (last > first + allowed) {
        std::ostringstream detail;
        detail << "RSS grew beyond the threshold: baseline=" << samples[0]
               << " after_first=" << first << " after_last=" << last
               << " allowed_growth=" << allowed;
        fail(__FILE__, __LINE__, detail.str());
    }
#endif
}

}  // namespace test
}  // namespace fl
