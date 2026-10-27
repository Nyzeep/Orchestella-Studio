#include "argv_policy.h"

#include <array>
#include <cstring>

namespace fl {
namespace pvm {
namespace {

// -std= whitelist (spec §8.2: protocol carries C and C++, product claims C).
const std::array<const char*, 9> kAllowedStd = {
    "c99", "c11", "c17", "c23", "c++11", "c++14", "c++17", "c++20", "c++23"};

// -O variants clang understands; -Osubstring tricks are not accepted.
const std::array<const char*, 8> kAllowedOpt = {"0", "1",     "2", "3",
                                                "s", "z", "g", "fast"};

// Codegen-only flags: no filesystem, no plugin loading, no driver re-entry.
// Every entry is a full literal so a prefix collision cannot smuggle a value.
const std::array<const char*, 27> kAllowedCodegen = {
    "-fPIC", "-fpic", "-fPIE", "-fpie", "-fno-pic", "-fno-pie",
    "-ffunction-sections", "-fdata-sections",
    "-fomit-frame-pointer", "-fno-omit-frame-pointer",
    "-fstrict-aliasing", "-fno-strict-aliasing",
    "-fno-common", "-fcommon",
    "-funwind-tables", "-fasynchronous-unwind-tables", "-fno-asynchronous-unwind-tables",
    "-fstack-protector", "-fstack-protector-strong", "-fstack-protector-all",
    "-fno-exceptions", "-fno-rtti", "-fno-builtin", "-fbuiltin",
    "-fmerge-constants", "-ffreestanding", "-pthread"};

// Prefix families that need a value; the value must be word-shaped (letters,
// digits, '_', '-', '+', '=', ',', '.') and is additionally refused if it
// carries a path separator.
const std::array<const char*, 3> kValuePrefixes = {"-W", "-fvisibility=", "-m"};

bool starts_with(const std::string& value, const char* prefix) {
    const size_t length = std::strlen(prefix);
    return value.size() >= length && value.compare(0, length, prefix) == 0;
}

template <size_t N>
bool equals_any(const std::string& value, const std::array<const char*, N>& options) {
    for (const char* option : options) {
        if (value == option) {
            return true;
        }
    }
    return false;
}

bool contains_path_separator(const std::string& value) {
    return value.find('/') != std::string::npos ||
           value.find('\\') != std::string::npos;
}

// distcc's dcc_add_clang_target() appends the guest's *native* triple
// (aarch64-alpine-linux-musl on an Alpine chroot) and does so even when a
// --target= is already on the command line, so an exact comparison against
// the configured Android triple can never hold for real traffic. Only the
// architecture has to agree: it decides the instruction set the engine emits.
// The libc/ABI suffix does not, because the payload never links anything.
bool is_aarch64_triple(const std::string& value) {
    return value == "aarch64" || starts_with(value, "aarch64-");
}

// `native` is word-shaped, so the -m family below would forward it; the
// driver would then probe the CPU it happens to run on and emit for that,
// silently and differently from the configured triple. Refused as
// UNKNOWN_FLAG rather than a new class: the class list is wire-visible and
// part of the protocol contract, and the client's action (fail the job) is
// the same as for any other unaccepted spelling.
bool is_native_machine_flag(const std::string& argument) {
    return argument == "-march=native" || argument == "-mtune=native" ||
           argument == "-mcpu=native";
}

// Word-shaped: everything a compiler flag value can be without being a path
// or a shell fragment. Rejects '%', '$', '@', ':', whitespace, quotes.
bool is_word_shaped(const std::string& value) {
    if (value.empty()) {
        return false;
    }
    for (const char character : value) {
        const bool ok = (character >= 'a' && character <= 'z') ||
                        (character >= 'A' && character <= 'Z') ||
                        (character >= '0' && character <= '9') || character == '_' ||
                        character == '-' || character == '+' || character == '=' ||
                        character == ',' || character == '.';
        if (!ok) {
            return false;
        }
    }
    return true;
}

ArgvDecision reject(ArgvClass rejection) {
    ArgvDecision decision;
    decision.accepted = false;
    decision.failure_code = std::string("ARGV_REJECTED:") + argv_class_name(rejection);
    return decision;
}

// Anything that would make the driver open a file for writing.
bool is_file_output(const std::string& argument) {
    return starts_with(argument, "-save-temps") || starts_with(argument, "-ftime-trace") ||
           starts_with(argument, "-fcrash-diagnostics") ||
           starts_with(argument, "-fprofile") || starts_with(argument, "-fcoverage") ||
           starts_with(argument, "--serialize-diagnostics") ||
           argument == "-MJ";
}

// Anything that produces a dependency file next to the output.
bool is_dependency_output(const std::string& argument) {
    return argument == "-MD" || argument == "-MMD" || argument == "-MG" ||
           argument == "-MP" || argument == "-MF" || argument == "-MT" ||
           argument == "-MQ" || argument == "-M" || argument == "-MM" ||
           starts_with(argument, "-fdep-file") || starts_with(argument, "-dependency-file");
}

// -g with an optional level or dialect; bounded so it cannot carry a path.
bool is_debug_flag(const std::string& argument) {
    if (argument == "-g") {
        return true;
    }
    if (!starts_with(argument, "-g") || argument.size() > 24) {
        return false;
    }
    const std::string suffix = argument.substr(2);
    static const std::array<const char*, 8> kDialects = {
        "gdb", "stabs", "none", "line-tables-only", "dwarf-2", "dwarf-3", "dwarf-4",
        "dwarf-5"};
    if (suffix.size() == 1 && suffix[0] >= '0' && suffix[0] <= '3') {
        return true;
    }
    return equals_any(suffix, kDialects);
}

}  // namespace

const char* argv_class_name(ArgvClass rejection) {
    switch (rejection) {
        case ArgvClass::kResponseFile: return "RESPONSE_FILE";
        case ArgvClass::kClangInternal: return "CLANG_INTERNAL";
        case ArgvClass::kPlugin: return "PLUGIN";
        case ArgvClass::kIncludePath: return "INCLUDE_PATH";
        case ArgvClass::kSysroot: return "SYSROOT";
        case ArgvClass::kToolchainPath: return "TOOLCHAIN_PATH";
        case ArgvClass::kLinkOpt: return "LINK_OPT";
        case ArgvClass::kDependencyOutput: return "DEPENDENCY_OUTPUT";
        case ArgvClass::kFileOutput: return "FILE_OUTPUT";
        case ArgvClass::kPathLike: return "PATH_LIKE";
        case ArgvClass::kBadLang: return "BAD_LANG";
        case ArgvClass::kBadStd: return "BAD_STD";
        case ArgvClass::kTargetMismatch: return "TARGET_MISMATCH";
        case ArgvClass::kBadTarget: return "BAD_TARGET";
        case ArgvClass::kOutputRedirect: return "OUTPUT_REDIRECT";
        case ArgvClass::kPositional: return "POSITIONAL";
        case ArgvClass::kUnknownFlag: return "UNKNOWN_FLAG";
    }
    return "UNKNOWN_FLAG";
}

ArgvDecision filter_argv(const std::vector<std::string>& argv,
                         const ArgvPolicyConfig& config) {
    if (argv.empty()) {
        return reject(ArgvClass::kPositional);
    }

    ArgvDecision decision;
    decision.accepted = true;
    decision.language = "c";

    bool seen_output = false;      // one "-o <file>" pair is absorbed
    bool seen_positional = false;  // one input file name is absorbed
    bool await_output_value = false;

    // Adds a flag after the path-separator guard, so the guard stays
    // reachable whatever the allow list grows to contain.
    auto allow = [&decision](const std::string& argument) -> bool {
        if (contains_path_separator(argument)) {
            return false;
        }
        decision.flags.push_back(argument);
        return true;
    };

    // argv[0] is the compiler name from the guest command line; absorbed in
    // full (it may be an absolute path and is never forwarded).
    for (size_t index = 1; index < argv.size(); ++index) {
        const std::string& argument = argv[index];

        if (await_output_value) {
            await_output_value = false;
            continue;  // the -o value is absorbed along with the flag
        }

        if (starts_with(argument, "@")) {
            return reject(ArgvClass::kResponseFile);
        }
        if (starts_with(argument, "-Xclang") || argument == "-cc1" ||
            argument == "-cc1as" || starts_with(argument, "-mllvm") ||
            starts_with(argument, "-Xclang-")) {
            return reject(ArgvClass::kClangInternal);
        }
        if (starts_with(argument, "-fplugin") || starts_with(argument, "-load") ||
            starts_with(argument, "-plugin")) {
            return reject(ArgvClass::kPlugin);
        }
        if (starts_with(argument, "-I") || starts_with(argument, "-include") ||
            starts_with(argument, "-isystem") || starts_with(argument, "-iquote") ||
            starts_with(argument, "-imacros") || starts_with(argument, "-iprefix") ||
            starts_with(argument, "-iwithprefix") || starts_with(argument, "-idirafter") ||
            starts_with(argument, "-F")) {
            return reject(ArgvClass::kIncludePath);
        }
        if (starts_with(argument, "--sysroot") || starts_with(argument, "-isysroot")) {
            return reject(ArgvClass::kSysroot);
        }
        if (starts_with(argument, "-B") || starts_with(argument, "-resource-dir") ||
            starts_with(argument, "-ccc-")) {
            return reject(ArgvClass::kToolchainPath);
        }
        if (starts_with(argument, "-L") || starts_with(argument, "-l")) {
            return reject(ArgvClass::kLinkOpt);
        }
        if (is_dependency_output(argument)) {
            return reject(ArgvClass::kDependencyOutput);
        }
        if (is_file_output(argument)) {
            return reject(ArgvClass::kFileOutput);
        }
        // Refusals come before every allowance, so a later allow-list entry
        // cannot quietly re-open `native`.
        if (is_native_machine_flag(argument)) {
            return reject(ArgvClass::kUnknownFlag);
        }

        if (argument == "-o") {
            if (seen_output || index + 1 >= argv.size()) {
                return reject(ArgvClass::kOutputRedirect);
            }
            seen_output = true;
            await_output_value = true;
            continue;
        }
        if (starts_with(argument, "-o") && argument.size() > 2) {
            // joined form "-ofile": never produced by distcc, always suspect
            return reject(ArgvClass::kOutputRedirect);
        }

        if (argument == "-c") {
            if (!allow(argument)) {
                return reject(ArgvClass::kPathLike);
            }
            continue;
        }
        if (argument == "-x" || argument == "-xcpp-output" ||
            argument == "-xc++-cpp-output") {
            std::string language;
            if (argument == "-x") {
                if (index + 1 >= argv.size()) {
                    return reject(ArgvClass::kBadLang);
                }
                language = argv[++index];
            } else {
                language = argument.substr(2);
            }
            if (language == "cpp-output") {
                decision.language = "c";
            } else if (language == "c++-cpp-output") {
                decision.language = "c++";
            } else {
                return reject(ArgvClass::kBadLang);
            }
            continue;
        }
        if (starts_with(argument, "-O")) {
            const std::string level = argument.substr(2);
            if (!level.empty() && !equals_any(level, kAllowedOpt)) {
                return reject(ArgvClass::kUnknownFlag);
            }
            if (!allow(argument)) {
                return reject(ArgvClass::kPathLike);
            }
            continue;
        }
        if (is_debug_flag(argument)) {
            if (!allow(argument)) {
                return reject(ArgvClass::kPathLike);
            }
            continue;
        }
        if (starts_with(argument, "-std=") || argument == "-std") {
            std::string value;
            if (argument == "-std") {
                if (index + 1 >= argv.size()) {
                    return reject(ArgvClass::kBadStd);
                }
                value = argv[++index];
            } else {
                value = argument.substr(5);
            }
            if (contains_path_separator(value)) {
                return reject(ArgvClass::kPathLike);
            }
            if (!equals_any(value, kAllowedStd)) {
                return reject(ArgvClass::kBadStd);
            }
            if (!allow(std::string("-std=") + value)) {
                return reject(ArgvClass::kPathLike);
            }
            continue;
        }
        // Target declarations. dcc_add_clang_target() in distcc 3.4 appends
        // the two-token `-target <triple>` spelling, and it does so even when
        // the build file already carries a --target=, so a real job delivers
        // both. Accepting only the long form is what made every real job
        // answer ARGV_REJECTED:UNKNOWN_FLAG (PVM-11). All four spellings are
        // one declaration to the driver, so all four are accepted here.
        if (argument == "--target" || argument == "-target" ||
            starts_with(argument, "--target=") || starts_with(argument, "-target=")) {
            std::string value;
            if (argument == "--target" || argument == "-target") {
                if (index + 1 >= argv.size()) {
                    return reject(ArgvClass::kBadTarget);
                }
                value = argv[++index];
            } else {
                value = argument.substr(argument.find('=') + 1);
            }
            if (value.empty()) {
                return reject(ArgvClass::kBadTarget);
            }
            if (contains_path_separator(value)) {
                return reject(ArgvClass::kPathLike);
            }
            if (!is_word_shaped(value)) {
                return reject(ArgvClass::kBadTarget);
            }
            if (config.target_triple.empty()) {
                // No triple configured: the payload cannot honour any claim.
                return reject(ArgvClass::kTargetMismatch);
            }
            if (!is_aarch64_triple(value)) {
                return reject(ArgvClass::kTargetMismatch);
            }
            if (value != config.target_triple) {
                // Accepted, but never silently: the engine emits for the
                // configured triple either way, so the disagreement has to
                // survive as data instead of vanishing here.
                decision.declared_target = value;
            }
            continue;  // the engine applies the configured triple itself
        }
        if (equals_any(argument, kAllowedCodegen)) {
            if (!allow(argument)) {
                return reject(ArgvClass::kPathLike);
            }
            continue;
        }
        bool value_family = false;
        for (const char* prefix : kValuePrefixes) {
            if (!starts_with(argument, prefix)) {
                continue;
            }
            value_family = true;
            const std::string value = argument.substr(std::strlen(prefix));
            if (contains_path_separator(value)) {
                return reject(ArgvClass::kPathLike);
            }
            // -Wp,/-Wl,/-Wa, reach the preprocessor, linker or assembler;
            // -mllvm reaches raw LLVM options. None may pass.
            if (starts_with(argument, "-Wp,") || starts_with(argument, "-Wl,") ||
                starts_with(argument, "-Wa,") || starts_with(argument, "-mllvm")) {
                return reject(ArgvClass::kClangInternal);
            }
            if (!is_word_shaped(value)) {
                return reject(ArgvClass::kUnknownFlag);
            }
            break;
        }
        if (value_family) {
            if (!allow(argument)) {
                return reject(ArgvClass::kPathLike);
            }
            continue;
        }

        if (!argument.empty() && argument[0] == '-') {
            return reject(ArgvClass::kUnknownFlag);
        }
        if (seen_positional) {
            return reject(ArgvClass::kPositional);
        }
        seen_positional = true;  // input file name, absorbed (path or not)
    }

    if (!seen_output && !seen_positional) {
        // Neither an explicit output nor an input name: the real client always
        // sends both, so this is a hand-rolled or broken request.
        return reject(ArgvClass::kPositional);
    }
    return decision;
}

}  // namespace pvm
}  // namespace fl
