// The driver defaults the payload has to restore (DV-PVM-02b round 2).
//
// The payload builds a CompilerInvocation from -cc1 arguments, so it inherits
// cc1's defaults instead of the driver's. Two of those defaults change the
// emitted object, not the diagnostics, and both were missing on the device:
// the remote object had no .eh_frame (88 KB smaller than the guest's own
// object) and C++ `throw` did not compile at all.

#include "cc1_defaults.h"

#include <string>
#include <vector>

#include "test_support.h"

namespace {

using fl::pvm::cc1_codegen_defaults;
using fl::pvm::job_requests;

std::vector<std::string> defaults_for(const char* language,
                                      std::vector<std::string> flags = {}) {
    return cc1_codegen_defaults(language, flags);
}

bool contains(const std::vector<std::string>& values, const std::string& wanted) {
    for (const std::string& value : values) {
        if (value == wanted) {
            return true;
        }
    }
    return false;
}

}  // namespace

// A C job still needs unwind tables: -funwind-tables=2 is what puts .eh_frame
// in the object, and without it the remote object cannot be unwound.
FL_TEST(cc1_defaults_c_restores_unwind_tables) {
    const std::vector<std::string> defaults = defaults_for("c");
    FL_CHECK_EQ(defaults.size(), std::size_t{1});
    FL_CHECK_EQ(defaults[0], std::string("-funwind-tables=2"));
}

// A C++ job needs the same unwind tables plus the two flags that make `throw`
// legal, plus the frame pointer the aarch64-android driver keeps: the driver
// compiles with it, bare -cc1 drops it, and the round-3 cxx round showed the
// remote object's guarded function losing `mov x29, sp` against both the
// chroot reference and the NDK driver path.
FL_TEST(cc1_defaults_cxx_restores_exceptions) {
    const std::vector<std::string> defaults = defaults_for("c++");
    FL_CHECK_EQ(defaults.size(), std::size_t{4});
    FL_CHECK_EQ(defaults[0], std::string("-funwind-tables=2"));
    FL_CHECK_EQ(defaults[1], std::string("-fcxx-exceptions"));
    FL_CHECK_EQ(defaults[2], std::string("-fexceptions"));
    FL_CHECK_EQ(defaults[3], std::string("-mframe-pointer=all"));
}

// The job's own opt-outs are on the argv allow list and must survive: the
// driver drops -funwind-tables=2 when the job says no to async unwind tables.
FL_TEST(cc1_defaults_job_can_drop_unwind_tables) {
    const std::vector<std::string> defaults =
        defaults_for("c", {"-O1", "-fno-asynchronous-unwind-tables"});
    FL_CHECK(defaults.empty());
}

FL_TEST(cc1_defaults_job_can_drop_exceptions) {
    const std::vector<std::string> defaults =
        defaults_for("c++", {"-O2", "-fno-exceptions"});
    FL_CHECK_EQ(defaults.size(), std::size_t{1});
    FL_CHECK_EQ(defaults[0], std::string("-funwind-tables=2"));
}

// Unrelated flags must not disturb the defaults; the composition is a function
// of language plus these two opt-outs only.
FL_TEST(cc1_defaults_other_flags_are_ignored) {
    const std::vector<std::string> defaults =
        defaults_for("c++", {"-O1", "-fPIC", "-ffunction-sections", "-std=c++17",
                             "-fno-rtti", "-Werror"});
    FL_CHECK_EQ(defaults.size(), std::size_t{4});
    FL_CHECK(contains(defaults, "-fcxx-exceptions"));
    FL_CHECK(contains(defaults, "-mframe-pointer=all"));
}

FL_TEST(cc1_defaults_every_default_precedes_the_job_flags) {
    // Callers append the job's flags after the returned block, so a job flag
    // such as -fno-exceptions wins by being later on the command line. The
    // composition itself must therefore never reorder or deduplicate.
    const std::vector<std::string> defaults = defaults_for("c++");
    for (const std::string& value : defaults) {
        FL_CHECK(!value.empty());
    }
    // Guarded: a failing run must print FAIL, not read past an empty vector
    // and take the whole suite down with it.
    FL_CHECK(!defaults.empty());
    FL_CHECK_EQ(defaults.front(), std::string("-funwind-tables=2"));
}

FL_TEST(cc1_defaults_job_requests_is_exact) {
    const std::vector<std::string> flags = {"-O1", "-fno-exceptions"};
    FL_CHECK(job_requests(flags, "-fno-exceptions"));
    FL_CHECK(!job_requests(flags, "-fno-exception"));
    FL_CHECK(!job_requests(flags, "-fexceptions"));
    FL_CHECK(!job_requests(std::vector<std::string>{}, "-fno-exceptions"));
}
