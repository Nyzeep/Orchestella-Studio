// AXP1 server side for the PVM-03 compile service.
//
// Byte-for-byte mirror of the Java implementation in
// runtime/fastlane-distcc/.../axp1/ (the wire is fixed there; this side must
// interoperate, never extend): 12-byte big-endian frame header "AXP1" |
// u8 version | u8 type | u16 flags(0) | u32 payload length. HELLO carries
// role(1=HOST/2=GUEST) + 16-byte nonce + 32-hex token + capability TLVs;
// HELLO_ACK mirrors it without the token. DATA frames are the transparent
// distcc v1 byte stream, EOF is the half-close, RST carries a 2-byte error
// code, PING echoes back as PONG.
//
// New capability id 6 (COMPILER_VERSION, UTF-8) rides the existing "unknown
// ids are retained but ignored" rule, so the host wire needs no change.
//
// Failure behaviour follows Axp1Connection: a handshake defect answers with
// the matching RST code (VERSION_MISMATCH / FRAME_TOO_LARGE / PROTOCOL_ERROR),
// a timeout closes without RST, and a distcc-level protocol defect ends the
// connection with no response at all — exactly what distccd does.

#ifndef FASTLANE_PVM_AXP1_SERVER_H_
#define FASTLANE_PVM_AXP1_SERVER_H_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "argv_policy.h"
#include "compile_service.h"
#include "distcc_server.h"

namespace fl {
namespace pvm {

constexpr uint8_t kAxp1TypeHello = 1;
constexpr uint8_t kAxp1TypeHelloAck = 2;
constexpr uint8_t kAxp1TypeData = 3;
constexpr uint8_t kAxp1TypeEof = 4;
constexpr uint8_t kAxp1TypeRst = 5;
constexpr uint8_t kAxp1TypePing = 6;
constexpr uint8_t kAxp1TypePong = 7;

constexpr uint32_t kAxp1AbsoluteMaxFrame = 16u * 1024u * 1024u;
constexpr uint32_t kAxp1DefaultMaxFrame = 1024u * 1024u;

/** Byte transport one AXP1 connection runs over (fd or in-memory test). */
class Axp1Transport {
public:
    virtual ~Axp1Transport() = default;
    /** Returns bytes read, 0 on clean EOF, -1 on failure, -2 on timeout. */
    virtual long read(uint8_t* buffer, size_t size) = 0;
    /** Returns true when all bytes were written. */
    virtual bool write(const uint8_t* buffer, size_t size) = 0;
    virtual void shutdown() = 0;
    /**
     * Bounds the next blocking read. Implementations that cannot express a
     * timeout leave it as a no-op; the session then relies on the peer.
     */
    virtual void set_read_timeout_ms(uint32_t /*timeout_ms*/) {}
};

/** One decoded AXP1 frame. */
struct Axp1FrameView {
    uint8_t type = 0;
    std::vector<uint8_t> payload;
};

enum class Axp1ReadStatus : int {
    kFrame = 0,
    kEof,             // clean transport EOF before any header byte
    kVersionMismatch,
    kFrameTooLarge,
    kProtocolError,
    kClosed,          // transport failure mid-frame
    kTimeout,         // read deadline expired
};

/** Reads one frame; validates magic, version, type and the length ceiling. */
Axp1ReadStatus axp1_read_frame(Axp1Transport* transport, uint32_t max_frame,
                               Axp1FrameView* frame);

/** Appends the wire encoding of one frame to `out`. */
void axp1_encode_frame(uint8_t type, const std::vector<uint8_t>& payload,
                       std::vector<uint8_t>* out);

/** Capabilities this server advertises in HELLO_ACK. */
struct Axp1ServerCaps {
    bool distcc = true;
    uint32_t max_frame = kAxp1DefaultMaxFrame;
    std::string compiler_triple;
    std::string compiler_version;  // capability id 6
};

/** The subset of peer capabilities the server acts on. */
struct Axp1PeerCaps {
    bool distcc = false;
    bool has_max_frame = false;
    uint32_t max_frame = 0;
};

/** Encodes the TLV capability block (Java caps strings are <= 128 bytes). */
std::vector<uint8_t> axp1_encode_caps(const Axp1ServerCaps& caps);

/** Decodes a TLV block; unknown ids are ignored per the Java rules. */
bool axp1_decode_caps(const uint8_t* data, size_t size, Axp1PeerCaps* caps);

/** Server-side session configuration. */
struct Axp1ServerConfig {
    std::string token;  // exactly 32 lowercase hex characters
    Axp1ServerCaps caps;
    uint32_t handshake_timeout_ms = 3000;  // Java Axp1Limits default
};

/**
 * Serves exactly one connection = one distcc job: HELLO/HELLO_ACK handshake
 * (constant-time token compare), DATA accumulation up to the request
 * ceiling, PING->PONG, EOF triggers the compile job, the response is
 * written back as DATA frames plus EOF. Returns the terminal wire error
 * (kOk when the exchange completed).
 *
 * Never touches the filesystem; diagnostics stay in memory.
 */
WireError axp1_serve_connection(Axp1Transport* transport,
                                const Axp1ServerConfig& config,
                                const ArgvPolicyConfig& policy,
                                const ServerLimits& limits,
                                CompileEngine* engine);

}  // namespace pvm
}  // namespace fl

#endif  // FASTLANE_PVM_AXP1_SERVER_H_
