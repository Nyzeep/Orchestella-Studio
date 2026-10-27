#include "cc1_defaults.h"

namespace fl {
namespace pvm {
namespace {

// What the aarch64-android driver passes for a normal compile: synchronous
// tables are implied, asynchronous ones are on. `-fno-asynchronous-unwind-tables`
// makes the driver pass nothing at all, which is why the default is dropped
// instead of being turned into a different value.
constexpr const char* kUnwindTables = "-funwind-tables=2";
constexpr const char* kNoAsyncUnwind = "-fno-asynchronous-unwind-tables";
constexpr const char* kCxxExceptions = "-fcxx-exceptions";
constexpr const char* kExceptions = "-fexceptions";
constexpr const char* kNoExceptions = "-fno-exceptions";
// cc1-form, not the driver's -fno-omit-frame-pointer: -cc1 rejects driver
// spellings outright (PVM-02 burned on -fno-crash-diagnostics; round-3 cxx-v2
// burned on this one), and CreateFromArgs answering "rejected" turns every
// job into an engine-level ARGV_REJECTED.
constexpr const char* kFramePointerAll = "-mframe-pointer=all";

}  // namespace

bool job_requests(const std::vector<std::string>& job_flags, const char* flag) {
    for (const std::string& candidate : job_flags) {
        if (candidate == flag) {
            return true;
        }
    }
    return false;
}

std::vector<std::string> cc1_codegen_defaults(
    const std::string& language, const std::vector<std::string>& job_flags) {
    std::vector<std::string> defaults;
    if (!job_requests(job_flags, kNoAsyncUnwind)) {
        defaults.emplace_back(kUnwindTables);
    }
    // RTTI needs no default: cc1 has it on, and -fno-rtti passes straight
    // through when the job asks for it.
    if (language == "c++" && !job_requests(job_flags, kNoExceptions)) {
        defaults.emplace_back(kCxxExceptions);
        defaults.emplace_back(kExceptions);
        // The aarch64-android driver keeps the frame pointer (verified with
        // the NDK 18 driver path: a non-leaf function carries
        // `stp x29, x30` + `mov x29, sp`, which bare -cc1 drops), and the
        // round-3 cxx round showed the difference against the chroot
        // reference in the guarded function's frame layout. C stays without
        // it on purpose: the on-device C reference matches the omitted form
        // byte for byte (round 3 c regression, 9fd1d91e...), and flipping C
        // would move that anchored hash.
        defaults.emplace_back(kFramePointerAll);
    }
    return defaults;
}

}  // namespace pvm
}  // namespace fl
