// Table-driven argv triage tests (PVM-03 A3).
//
// Two tables: everything that must pass through, and one representative per
// rejection class. The class names are wire-visible, so a rename here is a
// protocol change and the table is the contract.

#include "argv_policy.h"

#include <string>
#include <vector>

#include "test_support.h"

namespace {

using fl::pvm::ArgvDecision;
using fl::pvm::ArgvPolicyConfig;
using fl::pvm::filter_argv;

ArgvPolicyConfig config() {
    ArgvPolicyConfig policy;
    policy.target_triple = "aarch64-unknown-linux-android";
    return policy;
}

bool rejected_with(const std::vector<std::string>& argv, const char* expected_class) {
    const ArgvDecision decision = filter_argv(argv, config());
    return !decision.accepted &&
           decision.failure_code == std::string("ARGV_REJECTED:") + expected_class;
}

// Loop-friendly wrappers: a table row that fails must name itself, because
// every row reports the same source line.
void expect_rejected(const std::vector<std::string>& argv, const char* expected_class,
                     const char* label) {
    const ArgvDecision decision = filter_argv(argv, config());
    if (decision.accepted ||
        decision.failure_code != std::string("ARGV_REJECTED:") + expected_class) {
        ::fl::test::fail(__FILE__, __LINE__,
                         std::string(label) + ": expected " + expected_class + " got " +
                             (decision.accepted ? std::string("accepted")
                                                : decision.failure_code));
    }
}

void expect_accepted(const std::vector<std::string>& argv, const char* label) {
    const ArgvDecision decision = filter_argv(argv, config());
    if (!decision.accepted) {
        ::fl::test::fail(__FILE__, __LINE__,
                         std::string(label) + ": expected accepted got " +
                             decision.failure_code);
    }
}

// A minimal client shape with one flag appended, so an accepted flag can be
// counted and a rejected one lands in the class the policy picks.
std::vector<std::string> with_flag(const std::string& flag) {
    return {"cc", "-c", "a.c", "-o", "a.o", flag};
}

}  // namespace

FL_TEST(argv_policy_allows_the_distcc_client_shape) {
    // Exactly what distcc 3.4 sends after dcc_scan_args: compiler name, -c,
    // the original input name, and an explicit -o pair.
    const ArgvDecision decision =
        filter_argv({"cc", "-c", "foo.c", "-o", "foo.o"}, config());
    FL_CHECK(decision.accepted);
    FL_CHECK_EQ(decision.failure_code, std::string());
    FL_CHECK_EQ(decision.flags.size(), static_cast<size_t>(1));
    FL_CHECK_EQ(decision.flags[0], std::string("-c"));
    FL_CHECK_EQ(decision.language, std::string("c"));
}

FL_TEST(argv_policy_allows_codegen_and_diagnostic_flags) {
    const std::vector<std::string> argv = {
        "clang", "-c", "unit.c", "-o", "unit.o",
        "-O2", "-g", "-std=c11", "-Wall", "-Wextra", "-Werror",
        "-fPIC", "-fno-omit-frame-pointer", "-ffunction-sections",
        "-fdata-sections", "-fvisibility=hidden", "-pthread",
        "-march=armv8-a", "-mtune=cortex-a76",
        "--target=aarch64-unknown-linux-android",
    };
    const ArgvDecision decision = filter_argv(argv, config());
    FL_CHECK(decision.accepted);
    // 15 flag-like entries survive; -o and its value, argv[0] and the input
    // name are absorbed, --target is consumed by the engine config.
    FL_CHECK_EQ(decision.flags.size(), static_cast<size_t>(15));
}

FL_TEST(argv_policy_accepts_every_optimisation_and_debug_level) {
    for (const char* level : {"-O0", "-O1", "-O2", "-O3", "-Os", "-Oz", "-Og",
                              "-Ofast", "-O"}) {
        const ArgvDecision decision =
            filter_argv({"cc", "-c", level, "a.c", "-o", "a.o"}, config());
        FL_CHECK(decision.accepted);
        FL_CHECK_EQ(decision.flags.size(), static_cast<size_t>(2));
    }
    for (const char* level : {"-g", "-g0", "-g1", "-g2", "-g3", "-ggdb", "-gdwarf-4",
                              "-gline-tables-only"}) {
        const ArgvDecision decision =
            filter_argv({"cc", "-c", level, "a.c", "-o", "a.o"}, config());
        FL_CHECK(decision.accepted);
    }
}

FL_TEST(argv_policy_reads_the_language_from_x) {
    const ArgvDecision c =
        filter_argv({"cc", "-c", "-x", "cpp-output", "a.i", "-o", "a.o"}, config());
    FL_CHECK(c.accepted);
    FL_CHECK_EQ(c.language, std::string("c"));

    const ArgvDecision cxx = filter_argv(
        {"cc", "-c", "-x", "c++-cpp-output", "a.ii", "-o", "a.o"}, config());
    FL_CHECK(cxx.accepted);
    FL_CHECK_EQ(cxx.language, std::string("c++"));

    const ArgvDecision joined =
        filter_argv({"cc", "-c", "-xc++-cpp-output", "a.ii", "-o", "a.o"}, config());
    FL_CHECK(joined.accepted);
    FL_CHECK_EQ(joined.language, std::string("c++"));
}

FL_TEST(argv_policy_accepted_std_values) {
    for (const char* std_value : {"c99", "c11", "c17", "c23", "c++11", "c++14",
                                  "c++17", "c++20", "c++23"}) {
        const std::vector<std::string> argv = {"cc", "-c", "a.c", "-o", "a.o",
                                               std::string("-std=") + std_value};
        FL_CHECK(filter_argv(argv, config()).accepted);
    }
    // The separated form is not what distcc produces but is a legal driver
    // spelling; it is normalised before it reaches the engine.
    const ArgvDecision separated =
        filter_argv({"cc", "-c", "-std", "c17", "a.c", "-o", "a.o"}, config());
    FL_CHECK(separated.accepted);
    FL_CHECK_EQ(separated.flags.back(), std::string("-std=c17"));
}

FL_TEST(argv_policy_target_accepts_the_configured_triple_and_refuses_x86) {
    FL_CHECK(filter_argv({"cc",
                          "-c",
                          "a.c",
                          "-o",
                          "a.o",
                          "--target=aarch64-unknown-linux-android"},
                         config())
                 .accepted);
    FL_CHECK(rejected_with(
        {"cc", "-c", "a.c", "-o", "a.o", "--target=x86_64-unknown-linux-gnu"},
        "TARGET_MISMATCH"));
    // An empty configured triple means "no target accepted at all".
    ArgvPolicyConfig unset;
    FL_CHECK(!filter_argv({"cc", "-c", "a.c", "-o", "a.o", "--target=whatever"}, unset)
                  .accepted);
}

FL_TEST(argv_policy_accepts_all_four_target_spellings) {
    // PVM-11. distcc's dcc_add_clang_target() appends the two-token short
    // form; a build file may carry either short or long. To the driver these
    // are one declaration, so they must not be four different answers.
    const std::vector<std::vector<std::string>> spellings = {
        {"cc", "-c", "a.c", "-o", "a.o", "--target=aarch64-unknown-linux-android"},
        {"cc", "-c", "a.c", "-o", "a.o", "--target", "aarch64-unknown-linux-android"},
        {"cc", "-c", "a.c", "-o", "a.o", "-target", "aarch64-unknown-linux-android"},
        {"cc", "-c", "a.c", "-o", "a.o", "-target=aarch64-unknown-linux-android"},
    };
    size_t index = 0;
    for (const std::vector<std::string>& argv : spellings) {
        const ArgvDecision decision = filter_argv(argv, config());
        if (!decision.accepted) {
            ::fl::test::fail(__FILE__, __LINE__,
                             "spelling " + std::to_string(index) + " rejected as " +
                                 decision.failure_code);
        }
        // Consumed, never forwarded: the engine emits for the configured
        // triple, so -c is the only flag left.
        FL_CHECK_EQ(decision.flags.size(), static_cast<size_t>(1));
        FL_CHECK_EQ(decision.flags[0], std::string("-c"));
        FL_CHECK(decision.declared_target.empty());
        ++index;
    }
}

FL_TEST(argv_policy_records_a_declared_triple_it_did_not_use) {
    // The guest's own triple arrives on every real job. It is accepted but
    // never adopted -- this is a compile service, not a cross compiler -- so
    // the disagreement is recorded rather than dropped, or "compiled for
    // musl" would be indistinguishable from "compiled for Android".
    const ArgvDecision decision = filter_argv(
        {"cc", "-c", "a.c", "-o", "a.o", "-target", "aarch64-alpine-linux-musl"},
        config());
    FL_CHECK(decision.accepted);
    FL_CHECK_EQ(decision.declared_target, std::string("aarch64-alpine-linux-musl"));

    const ArgvDecision agreeing = filter_argv(
        {"cc", "-c", "a.c", "-o", "a.o", "-target", "aarch64-unknown-linux-android"},
        config());
    FL_CHECK(agreeing.accepted);
    FL_CHECK(agreeing.declared_target.empty());
}

FL_TEST(argv_policy_target_architecture_must_be_aarch64) {
    for (const char* triple : {"x86_64-unknown-linux-gnu", "armv7a-linux-androideabi21",
                               "riscv64-linux-android", "aarch64x-linux"}) {
        if (!rejected_with({"cc", "-c", "a.c", "-o", "a.o", "-target", triple},
                           "TARGET_MISMATCH")) {
            const ArgvDecision decision =
                filter_argv({"cc", "-c", "a.c", "-o", "a.o", "-target", triple}, config());
            ::fl::test::fail(__FILE__, __LINE__,
                             std::string(triple) + " was not refused: " +
                                 (decision.accepted ? std::string("accepted")
                                                    : decision.failure_code));
        }
    }
    // Any libc/ABI dressing is fine as long as the architecture matches.
    for (const char* triple : {"aarch64", "aarch64-alpine-linux-musl",
                               "aarch64-linux-android21", "aarch64-unknown-linux-gnu"}) {
        FL_CHECK(filter_argv({"cc", "-c", "a.c", "-o", "a.o", "-target", triple}, config())
                     .accepted);
    }
}

FL_TEST(argv_policy_target_guards_survive_the_new_spellings) {
    // The new spellings must not become a way around the old guards.
    FL_CHECK(rejected_with(
        {"cc", "-c", "a.c", "-o", "a.o", "-target", "aarch64-../../etc/passwd"},
        "PATH_LIKE"));
    FL_CHECK(rejected_with({"cc", "-c", "a.c", "-o", "a.o", "-target"}, "BAD_TARGET"));
    FL_CHECK(rejected_with({"cc", "-c", "a.c", "-o", "a.o", "-target="}, "BAD_TARGET"));
    // Word-shaped only: whitespace would both confuse the driver and forge a
    // log line.
    FL_CHECK(rejected_with({"cc", "-c", "a.c", "-o", "a.o", "-target", "aarch64 x"},
                           "BAD_TARGET"));
}

FL_TEST(argv_policy_refuses_native_machine_tuning) {
    // PVM-11 T3. `native` is word-shaped, so the -m family forwards it; the
    // driver would then emit for whatever CPU it happens to probe instead of
    // the configured triple. That is a silent codegen change, so it is
    // refused. Concrete values stay allowed.
    for (const char* flag : {"-march=native", "-mtune=native", "-mcpu=native"}) {
        if (!rejected_with({"cc", "-c", flag, "a.c", "-o", "a.o"}, "UNKNOWN_FLAG")) {
            const ArgvDecision decision =
                filter_argv({"cc", "-c", flag, "a.c", "-o", "a.o"}, config());
            ::fl::test::fail(__FILE__, __LINE__,
                             std::string(flag) + " was not refused: " +
                                 (decision.accepted ? std::string("accepted")
                                                    : decision.failure_code));
        }
    }
    for (const char* flag : {"-march=armv8-a", "-march=armv8-a+crypto",
                             "-mtune=cortex-a76", "-mcpu=cortex-a76"}) {
        FL_CHECK(filter_argv({"cc", "-c", flag, "a.c", "-o", "a.o"}, config()).accepted);
    }
}

FL_TEST(argv_policy_rejects_one_case_per_class) {
    struct Row {
        std::vector<std::string> argv;
        const char* expected_class;
    };
    const std::vector<Row> rows = {
        {{"cc", "@rsp.txt", "a.c"}, "RESPONSE_FILE"},
        {{"cc", "-Xclang", "-load", "evil.so", "a.c"}, "CLANG_INTERNAL"},
        {{"cc", "-mllvm", "-print-after-all", "a.c"}, "CLANG_INTERNAL"},
        {{"cc", "-cc1", "a.c"}, "CLANG_INTERNAL"},
        {{"cc", "-fplugin=evil.so", "a.c"}, "PLUGIN"},
        {{"cc", "-I/usr/include", "a.c"}, "INCLUDE_PATH"},
        {{"cc", "-include", "/etc/passwd", "a.c"}, "INCLUDE_PATH"},
        {{"cc", "--sysroot=/", "a.c"}, "SYSROOT"},
        {{"cc", "-B/tmp/toolchain", "a.c"}, "TOOLCHAIN_PATH"},
        {{"cc", "-L/tmp", "a.c"}, "LINK_OPT"},
        {{"cc", "-lssl", "a.c"}, "LINK_OPT"},
        {{"cc", "-MD", "-MF", "a.d", "a.c"}, "DEPENDENCY_OUTPUT"},
        {{"cc", "-save-temps", "a.c"}, "FILE_OUTPUT"},
        {{"cc", "-fprofile-instr-generate", "a.c"}, "FILE_OUTPUT"},
        {{"cc", "-std=c11/../../etc/passwd", "a.c"}, "PATH_LIKE"},
        {{"cc", "-Wl,--gc-sections", "a.c"}, "CLANG_INTERNAL"},
        {{"cc", "-x", "c", "a.c"}, "BAD_LANG"},
        {{"cc", "-std=c++03", "a.c"}, "BAD_STD"},
        {{"cc", "--target=", "a.c"}, "BAD_TARGET"},
        {{"cc", "-ofoo.o", "a.c"}, "OUTPUT_REDIRECT"},
        {{"cc", "-o", "a.o", "-o", "b.o", "a.c"}, "OUTPUT_REDIRECT"},
        {{"cc", "-o"}, "OUTPUT_REDIRECT"},
        {{"cc", "a.c", "b.c"}, "POSITIONAL"},
        {{}, "POSITIONAL"},
        {{"cc", "-fno-such-flag", "a.c"}, "UNKNOWN_FLAG"},
        {{"cc", "-O9", "a.c"}, "UNKNOWN_FLAG"},
    };
    size_t index = 0;
    for (const Row& row : rows) {
        if (!rejected_with(row.argv, row.expected_class)) {
            const ArgvDecision decision = filter_argv(row.argv, config());
            ::fl::test::fail(__FILE__, __LINE__,
                             "row " + std::to_string(index) + " expected " +
                                 row.expected_class + " got " +
                                 (decision.accepted ? std::string("accepted")
                                                    : decision.failure_code));
        }
        ++index;
    }
}

FL_TEST(argv_policy_never_echoes_the_offending_argument) {
    const ArgvDecision decision =
        filter_argv({"cc", "-I/tmp/secret-host-path", "a.c"}, config());
    FL_CHECK(!decision.accepted);
    FL_CHECK(decision.failure_code.find("secret") == std::string::npos);
    FL_CHECK(decision.failure_code.find('/') == std::string::npos);
}

FL_TEST(argv_policy_absorbs_the_output_pair_without_forwarding_it) {
    // A dangerous token hidden where -o's value goes is dropped with the pair:
    // it never reaches the engine, and the following positional is still
    // classified on its own.
    const ArgvDecision decision =
        filter_argv({"cc", "-c", "a.c", "-o", "-Xclang", "-load", "evil.so"}, config());
    FL_CHECK(!decision.accepted);
    FL_CHECK_EQ(decision.failure_code, std::string("ARGV_REJECTED:PLUGIN"));
    for (const std::string& flag : decision.flags) {
        FL_CHECK(flag.find("-Xclang") == std::string::npos);
    }
}

FL_TEST(argv_policy_requires_an_input_or_output) {
    // "cc -c" alone is a broken client: no input name, no output pair.
    FL_CHECK(rejected_with({"cc", "-c"}, "POSITIONAL"));
}

// --- target spellings --------------------------------------------------------
// The single-dash aliases (-target <triple> / -target=<triple>) belong to the
// PVM-11 branch: distcc injects that two-token form and the policy there accepts
// it. This table deliberately does not assert either behaviour, so merging
// PVM-11 cannot turn a green test red.
FL_TEST(argv_policy_target_value_forms_and_missing_values) {
    expect_accepted({"cc", "-c", "a.c", "-o", "a.o", "--target",
                     "aarch64-unknown-linux-android"},
                    "--target separated");
    expect_rejected({"cc", "-c", "a.c", "-o", "a.o", "--target", "x86_64-unknown-linux-gnu"},
                    "TARGET_MISMATCH", "--target separated mismatch");
    expect_rejected({"cc", "-c", "a.c", "-o", "a.o", "--target"}, "BAD_TARGET",
                    "--target without value");
    expect_rejected({"cc", "-c", "a.c", "-o", "a.o", "--target",
                     "aarch64-unknown-linux-android/../../etc"},
                    "PATH_LIKE", "--target path-shaped");
}

// --- file output and dependency output families ------------------------------
FL_TEST(argv_policy_rejects_every_dependency_and_file_output_flag) {
    struct Row {
        const char* flag;
        const char* expected_class;
    };
    const Row rows[] = {
        {"-MMD", "DEPENDENCY_OUTPUT"},
        {"-MG", "DEPENDENCY_OUTPUT"},
        {"-MP", "DEPENDENCY_OUTPUT"},
        {"-MT", "DEPENDENCY_OUTPUT"},
        {"-MQ", "DEPENDENCY_OUTPUT"},
        {"-M", "DEPENDENCY_OUTPUT"},
        {"-MM", "DEPENDENCY_OUTPUT"},
        {"-fdep-file", "DEPENDENCY_OUTPUT"},
        {"-fdep-file=a.json", "DEPENDENCY_OUTPUT"},
        {"-ftime-trace", "FILE_OUTPUT"},
        {"-ftime-trace=a.json", "FILE_OUTPUT"},
        {"-fcrash-diagnostics", "FILE_OUTPUT"},
        {"-fcrash-diagnostics-dir=.d", "FILE_OUTPUT"},
        {"-fcoverage-mapping", "FILE_OUTPUT"},
        {"-fcoverage-notes", "FILE_OUTPUT"},
        {"--serialize-diagnostics", "FILE_OUTPUT"},
        {"--serialize-diagnostics=a.dia", "FILE_OUTPUT"},
        {"-MJ", "FILE_OUTPUT"},
    };
    for (const Row& row : rows) {
        expect_rejected(with_flag(row.flag), row.expected_class, row.flag);
    }
    // The joined spellings clang also accepts are not in the family: they are
    // rejected as unknown rather than silently forwarded.
    expect_rejected(with_flag("-MTa.d"), "UNKNOWN_FLAG", "-MT joined");
    expect_rejected(with_flag("-MJbuild"), "UNKNOWN_FLAG", "-MJ joined");
}

// --- include search path prefixes -------------------------------------------
FL_TEST(argv_policy_rejects_every_include_prefix_with_or_without_a_value) {
    for (const char* prefix : {"-isystem", "-iquote", "-imacros", "-iprefix",
                               "-iwithprefix", "-idirafter"}) {
        // Empty value, joined value and separated value all hit the same
        // reject before the value is ever considered.
        expect_rejected(with_flag(prefix), "INCLUDE_PATH", prefix);
        expect_rejected(with_flag(std::string(prefix) + "/tmp/inc"), "INCLUDE_PATH", prefix);
        expect_rejected({"cc", "-c", "a.c", "-o", "a.o", prefix, "/tmp/inc"},
                        "INCLUDE_PATH", prefix);
    }
}

// --- driver escape hatches ---------------------------------------------------
FL_TEST(argv_policy_rejects_driver_escape_hatches) {
    struct Row {
        std::vector<std::string> argv;
        const char* expected_class;
        const char* label;
    };
    const Row rows[] = {
        {{"cc", "-cc1as", "a.s"}, "CLANG_INTERNAL", "-cc1as"},
        {{"cc", "-isysroot=/"}, "SYSROOT", "-isysroot= joined"},
        {{"cc", "-isysroot", "/"}, "SYSROOT", "-isysroot separated"},
        {{"cc", "-resource-dir=/tmp/r"}, "TOOLCHAIN_PATH", "-resource-dir"},
        {{"cc", "-ccc-gcc-name=clang"}, "TOOLCHAIN_PATH", "-ccc-*"},
        {{"cc", "-F"}, "INCLUDE_PATH", "-F bare"},
        {{"cc", "-F/tmp/Frameworks"}, "INCLUDE_PATH", "-F joined"},
        {{"cc", "-plugin", "/tmp/x.so"}, "PLUGIN", "-plugin"},
        {{"cc", "-plugin-arg-x=y"}, "PLUGIN", "-plugin-arg"},
        {{"cc", "-load", "a.c", "-o", "a.o"}, "PLUGIN", "-load bare"},
        {{"cc", "-Wp,-DFOO=1"}, "CLANG_INTERNAL", "-Wp,"},
        {{"cc", "-Wa,--noexecstack"}, "CLANG_INTERNAL", "-Wa,"},
        {{"cc", "-Wp,-I/tmp/inc"}, "PATH_LIKE", "-Wp, with a path"},
    };
    for (const Row& row : rows) {
        expect_rejected(row.argv, row.expected_class, row.label);
    }
}

// --- codegen whitelist -------------------------------------------------------
FL_TEST(argv_policy_accepts_the_whole_codegen_whitelist) {
    // The 27 literals in argv_policy.cpp, enumerated one by one: a silent
    // shrink of that table would otherwise only show up as a mysterious
    // UNKNOWN_FLAG at the client.
    for (const char* flag :
         {"-fPIC", "-fpic", "-fPIE", "-fpie", "-fno-pic", "-fno-pie",
          "-ffunction-sections", "-fdata-sections", "-fomit-frame-pointer",
          "-fno-omit-frame-pointer", "-fstrict-aliasing", "-fno-strict-aliasing",
          "-fno-common", "-fcommon", "-funwind-tables", "-fasynchronous-unwind-tables",
          "-fno-asynchronous-unwind-tables", "-fstack-protector",
          "-fstack-protector-strong", "-fstack-protector-all", "-fno-exceptions",
          "-fno-rtti", "-fno-builtin", "-fbuiltin", "-fmerge-constants",
          "-ffreestanding", "-pthread"}) {
        const ArgvDecision decision = filter_argv(with_flag(flag), config());
        expect_accepted(with_flag(flag), flag);
        FL_CHECK_EQ(decision.failure_code, std::string());
        FL_CHECK_EQ(decision.flags.size(), static_cast<size_t>(2));
        FL_CHECK_EQ(decision.flags[1], std::string(flag));
    }
    // Codegen flags outside the table stay rejected: the table is a literal
    // allow list, not a "-f*" family.
    expect_rejected(with_flag("-fno-plt"), "UNKNOWN_FLAG", "-fno-plt");
    expect_rejected(with_flag("-fshort-enums"), "UNKNOWN_FLAG", "-fshort-enums");
    expect_rejected(with_flag("-fvisibility-inlines-hidden"), "UNKNOWN_FLAG",
                    "-fvisibility-inlines-hidden");
}

// --- value families and is_word_shaped --------------------------------------
FL_TEST(argv_policy_value_families_are_word_shaped) {
    for (const char* flag : {"-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                             "-march=armv8-a", "-mtune=cortex-a76", "-mabi=lp64",
                             "-fvisibility=hidden"}) {
        expect_accepted(with_flag(flag), flag);
    }
    // '%', '$', '@' and ':' are not word characters: the value would be a shell
    // fragment or a path on a foreign host.
    struct Row {
        const char* flag;
        const char* expected_class;
    };
    const Row rows[] = {
        {"-Wall%", "UNKNOWN_FLAG"},
        {"-Werror=$HOME", "UNKNOWN_FLAG"},
        {"-march=armv8-a@2", "UNKNOWN_FLAG"},
        {"-mabi=lp64:x", "UNKNOWN_FLAG"},
        {"-fvisibility=hid%den", "UNKNOWN_FLAG"},
        {"-march=armv8/a", "PATH_LIKE"},
        {"-Werror=/tmp/x", "PATH_LIKE"},
    };
    for (const Row& row : rows) {
        expect_rejected(with_flag(row.flag), row.expected_class, row.flag);
    }
}

// --- debug flags -------------------------------------------------------------
FL_TEST(argv_policy_bounds_the_debug_suffix) {
    for (const char* flag : {"-gstabs", "-gnone", "-gdwarf-2", "-gdwarf-3", "-gdwarf-5"}) {
        expect_accepted(with_flag(flag), flag);
    }
    expect_rejected(with_flag("-gdwarf-6"), "UNKNOWN_FLAG", "-gdwarf-6");
    expect_rejected(with_flag("-g4"), "UNKNOWN_FLAG", "-g4");
    // Longer than 24 bytes: the suffix can no longer be a bounded dialect name,
    // so the whole token is refused rather than forwarded.
    expect_rejected(with_flag(std::string("-g") + "line-tables-only-and-then-some"),
                    "UNKNOWN_FLAG", "-g over-long");
}

// --- missing values for -x and -std -----------------------------------------
FL_TEST(argv_policy_rejects_missing_language_and_std_values) {
    expect_rejected({"cc", "-c", "a.c", "-o", "a.o", "-x"}, "BAD_LANG",
                    "-x without value");
    expect_rejected({"cc", "-c", "a.c", "-o", "a.o", "-std"}, "BAD_STD",
                    "-std without value");
    expect_rejected({"cc", "-c", "a.c", "-o", "a.o", "-std="}, "BAD_STD",
                    "-std= empty");
}
