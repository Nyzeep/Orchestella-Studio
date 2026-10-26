// distcc v1 server codec and job orchestration for the PVM-03 compile
// service (layered under axp1_server; see compile_service.h).
//
// Wire (upstream doc/protocol-1.txt + rpc.c, verified against the distcc 3.4
// tree and the repository golden fixtures): every packet is a 4-byte ASCII
// token plus an 8-character lowercase-hex parameter, followed by the body
// when the token carries one. Request: DIST 1, ARGC n, ARGV*n (len = strlen,
// no NUL), DOTI. Response: DONE 1, STAT <wait-status>, SERR, SOUT, DOTO.
// Token order is fixed; a mismatch is a protocol error and, exactly like
// distccd, ends the connection with no response at all.
//
// The request is self-delimiting: DOTI carries its own length and nothing
// follows it. Upstream doc/protocol-1.txt is explicit that closing the channel
// "has no meaning and does not indicate the end of a packet", so this codec
// must never wait for EOF to decide a request is complete (PVM-10).
//
// SOUT exists on the wire because distccd forwards compiler stdout; this
// service never produces any (the engine compiles to memory), so it is always
// empty in production. The field stays so the captured response golden — which
// does carry stdout — can be reproduced byte for byte.

#ifndef FASTLANE_PVM_DISTCC_SERVER_H_
#define FASTLANE_PVM_DISTCC_SERVER_H_

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "argv_policy.h"
#include "compile_service.h"

namespace fl {
namespace pvm {

/** One parsed distcc request. */
struct DistccRequest {
    std::vector<std::string> argv;
    std::string doti;  // preprocessed source, memory only
};

/** One distcc response before encoding. */
struct DistccResponse {
    uint32_t stat = 0;            // wait-status semantics
    std::string serr;             // fixed code + sanitized diagnostics
    std::string sout;             // always empty in production (wire slot)
    std::vector<uint8_t> doto;    // empty unless stat == 0
};

/** distcc-level parse failure. */
enum class DistccParseStatus : int {
    kOk = 0,
    kTooLarge,      // request or DOTI beyond the configured ceilings
    kProtocol,      // token order, hex parameter or trailing bytes
    /**
     * The bytes so far are a valid prefix of a request and more could finish
     * it. Never a failure: the AXP1 data plane simply keeps reading. This is
     * what makes end-of-request the DOTI body rather than a TCP half-close
     * (PVM-10) -- the real distcc client never shuts its write side down.
     */
    kIncomplete,
};

/** Result of parsing one complete request byte stream. */
struct DistccParseResult {
    DistccParseStatus status = DistccParseStatus::kProtocol;
    DistccRequest request;
    /**
     * True once at least one token was recognised, so the stream is known to
     * be a distcc request. A defect after that point can never be repaired by
     * more bytes, so the caller may fail immediately; before it, the bytes may
     * still be an oversized request being drained, and the ceiling decides.
     */
    bool prefix_recognised = false;
};

/**
 * Parses the request stream received so far. Unlike the pre-PVM-10 contract
 * this accepts a prefix: kIncomplete means "not yet", kProtocol means "wrong".
 * Size ceilings are enforced here so an oversized payload is rejected before
 * any argv handling.
 */
DistccParseResult parse_distcc_request(const std::vector<uint8_t>& wire,
                                       const ServerLimits& limits);

/** Encodes one response; deterministic byte-for-byte. */
std::vector<uint8_t> encode_distcc_response(const DistccResponse& response);

/**
 * Upper bound on the bytes one request may occupy on the wire: the .i ceiling
 * plus argv/header slack. Shared with the AXP1 accumulator, which needs the
 * number before it has a parse result.
 */
size_t distcc_request_ceiling(const ServerLimits& limits);

/** Encodes a 12-byte token header (exposed for tests). */
std::vector<uint8_t> encode_token(const char token[5], uint32_t parameter);

/** Maps a job outcome to the STAT word the client will read. */
uint32_t stat_for_status(JobStatus status, int compiler_exit_code = 1);

/**
 * Runs one job: argv triage, size ceilings, engine call under an
 * interruptible wall-clock watchdog, and the fixed STAT/SERR mapping.
 * Never throws; never touches the filesystem; diagnostics are sanitized and
 * UTF-8-truncated before they enter SERR.
 */
DistccResponse run_distcc_job(const DistccRequest& request,
                              const ArgvPolicyConfig& policy,
                              const ServerLimits& limits,
                              CompileEngine* engine);

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_DISTCC_SERVER_H_
