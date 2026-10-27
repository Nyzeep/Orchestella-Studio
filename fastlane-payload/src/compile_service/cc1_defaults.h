// The -cc1 arguments the clang driver would have injected for this job.
//
// The payload builds a CompilerInvocation from -cc1 arguments directly, so it
// inherits cc1's own defaults rather than the driver's. Three driver defaults
// change the emitted object rather than the diagnostics; the first two were
// missing on the device (DV-PVM-02b round 2), the third showed up in the
// round-3 cxx round:
//
//   * unwind tables — the aarch64-android driver passes -funwind-tables=2,
//     which is what emits .eh_frame/.rela.eh_frame. Without it the remote
//     object came back 88 KB smaller than the guest's own object and could not
//     be unwound: harmless for C, fatal for C++ exceptions.
//   * C++ exceptions — the driver passes -fcxx-exceptions -fexceptions, without
//     which `throw` is a hard compile error in an otherwise valid C++ job.
//   * C++ frame pointer — the driver keeps it (-fno-omit-frame-pointer; the
//     NDK 18 driver path puts `mov x29, sp` in every non-leaf function), and
//     bare -cc1 omits it, so the guarded function's frame layout differed
//     from both the chroot reference and the NDK driver path. C stays on the
//     omitted form on purpose: the on-device C reference matches it byte for
//     byte (round 3 c regression, .text 9fd1d91e...), and flipping C would
//     move that anchored hash.
//
// The composition lives in its own TU with no LLVM includes so it can be
// tested on any host, in the same build that cannot carry libclang.

#ifndef FASTLANE_PVM_CC1_DEFAULTS_H_
#define FASTLANE_PVM_CC1_DEFAULTS_H_

#include <string>
#include <vector>

namespace fl {
namespace pvm {

// True when the job asks for exactly this flag. Exact comparison is the point:
// a prefix match would let "-fno-exceptions" answer for "-fno-exception".
bool job_requests(const std::vector<std::string>& job_flags, const char* flag);

// The -cc1 flags to place BEFORE the job's own flags, so that anything the job
// says explicitly wins by coming later on the command line. Returns an empty
// vector when the job opted out of everything.
std::vector<std::string> cc1_codegen_defaults(
    const std::string& language, const std::vector<std::string>& job_flags);

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_CC1_DEFAULTS_H_
