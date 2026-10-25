// Untrusted-argv triage for the PVM-03 compile service.
//
// The guest can run anything, so argv arrives as hostile input. Three
// outcomes, and only three:
//
//   ALLOW   forwarded verbatim to the in-process engine
//   ABSORB  parsed for meaning, never forwarded (argv[0], the input file name,
//           and the `-o <file>` pair the real distcc 3.4 client always sends)
//   REJECT  answered with ARGV_REJECTED:<CLASS>; the class is a fixed enum and
//           the offending argument is never echoed back
//
// Why `-o` cannot simply be rejected: distcc's client rewrites the command
// line with `dcc_scan_args` and appends `-o <file>` when it is missing, so
// every real job carries one. Rejecting it would fail 100% of real traffic
// while buying nothing — the output path is never used, the object leaves
// through DOTO. The dangerous substance (writing a guest-chosen path) does not
// exist because the parse never reaches the engine.
//
// Codgen flags are allow-listed by *capability*, not by trust: a flag that
// only changes code generation or diagnostics can be forwarded; a flag that
// opens a file, loads a plugin or re-enters the driver cannot.

#ifndef FASTLANE_PVM_ARGV_POLICY_H_
#define FASTLANE_PVM_ARGV_POLICY_H_

#include <string>
#include <vector>

namespace fl {
namespace pvm {

/** Fixed rejection classes; the name is the wire-visible sub-code. */
enum class ArgvClass : int {
    kResponseFile = 0,
    kClangInternal,
    kPlugin,
    kIncludePath,
    kSysroot,
    kToolchainPath,
    kLinkOpt,
    kDependencyOutput,
    kFileOutput,
    kPathLike,
    kBadLang,
    kBadStd,
    kTargetMismatch,
    kBadTarget,
    kOutputRedirect,
    kPositional,
    kUnknownFlag,
};

const char* argv_class_name(ArgvClass rejection);

struct ArgvPolicyConfig {
    /** Exact triple the payload was built for; `--target` must match it. */
    std::string target_triple;
};

struct ArgvDecision {
    bool accepted = true;
    /** "ARGV_REJECTED:<CLASS>" when rejected, empty otherwise. */
    std::string failure_code;
    /** Allow-listed flags in their original order. */
    std::vector<std::string> flags;
    /** "c" or "c++"; the engine compiles the .i as already preprocessed. */
    std::string language = "c";
};

/**
 * Classifies one argv vector. Deterministic; no allocation on the reject path
 * beyond the fixed code; never inspects the filesystem.
 */
ArgvDecision filter_argv(const std::vector<std::string>& argv,
                         const ArgvPolicyConfig& config);

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_ARGV_POLICY_H_
