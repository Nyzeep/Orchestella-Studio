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
    /**
     * Triple the payload was built for. The engine always emits for this
     * value; a client's declaration is checked against its *architecture*
     * only, because distcc injects the guest's native triple, which cannot
     * be the Android one. Empty means no declaration is accepted at all.
     */
    std::string target_triple;
};

struct ArgvDecision {
    bool accepted = true;
    /** "ARGV_REJECTED:<CLASS>" when rejected, empty otherwise. */
    std::string failure_code;
    /** Allow-listed flags in their original order. */
    std::vector<std::string> flags;
    /**
     * "c" or "c++"; the engine compiles the content as already preprocessed
     * (job.i / job.ii). Decided by the job's own -x when it carries one --
     * the driver's word outranks the file name -- otherwise inferred from
     * the input file name's suffix (real distcc never sends -x across the
     * wire; the device's round-2 log shows "/root/fixture.cpp" verbatim).
     * An input name with no accepted suffix (.c/.i => c,
     * .C/.cpp/.cc/.cxx/.c++/.ii => c++, .m/.mm and the rest => nothing)
     * rejects the job instead of defaulting to C.
     */
    std::string language = "c";
    /**
     * A triple the client declared that is not the configured one. Advisory
     * only: the engine still emits for ArgvPolicyConfig::target_triple, so
     * this is the record that the two disagreed (a request built for
     * aarch64-alpine-linux-musl is answered with Android code). Empty when
     * the client declared nothing or agreed exactly. Word-shaped by
     * construction, so it is safe to log.
     */
    std::string declared_target;
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
