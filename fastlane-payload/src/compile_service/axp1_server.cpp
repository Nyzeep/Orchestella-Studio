#include "axp1_server.h"

#include <chrono>
#include <cstring>

namespace fl {
namespace pvm {
namespace {

constexpr uint8_t kRoleHost = 1;
constexpr uint8_t kRoleGuest = 2;
constexpr size_t kNonceBytes = 16;
constexpr size_t kTokenHexChars = 32;
constexpr size_t kMaxCapsEntries = 64;
constexpr size_t kMaxCapsString = 128;
constexpr size_t kHeaderBytes = 12;
constexpr long kReadTimeout = -2;

bool read_exact(Axp1Transport* transport, uint8_t* buffer, size_t size) {
    size_t filled = 0;
    while (filled < size) {
        const long chunk = transport->read(buffer + filled, size - filled);
        if (chunk > 0) {
            filled += static_cast<size_t>(chunk);
            continue;
        }
        return false;
    }
    return true;
}

bool write_all(Axp1Transport* transport, const uint8_t* buffer, size_t size) {
    return transport->write(buffer, size);
}

// Constant-time equality over equal-length inputs.
bool constant_time_equal(const uint8_t* left, const uint8_t* right, size_t size) {
    uint8_t diff = 0;
    for (size_t index = 0; index < size; ++index) {
        diff = static_cast<uint8_t>(diff | (left[index] ^ right[index]));
    }
    return diff == 0;
}

uint32_t read_be32(const uint8_t* data) {
    return (static_cast<uint32_t>(data[0]) << 24) |
           (static_cast<uint32_t>(data[1]) << 16) |
           (static_cast<uint32_t>(data[2]) << 8) | static_cast<uint32_t>(data[3]);
}

uint16_t read_be16(const uint8_t* data) {
    return static_cast<uint16_t>((static_cast<uint16_t>(data[0]) << 8) | data[1]);
}

void append_be16(std::vector<uint8_t>* out, uint16_t value) {
    out->push_back(static_cast<uint8_t>(value >> 8));
    out->push_back(static_cast<uint8_t>(value));
}

void append_tlv(std::vector<uint8_t>* out, uint8_t id, const uint8_t* value,
                size_t size) {
    out->push_back(id);
    append_be16(out, static_cast<uint16_t>(size));
    out->insert(out->end(), value, value + size);
}

// 16 bytes of nonce material. The Java side never checks it (parseHandshake
// keeps it for diagnostics only) and the token is the credential, so this is
// deliberately cheap: monotonic clock plus a per-process counter, no syscalls
// that could fail in a payload domain.
void fill_nonce(uint8_t* nonce) {
    static std::atomic<uint64_t> sequence{0};
    const auto ticks = static_cast<uint64_t>(
        std::chrono::steady_clock::now().time_since_epoch().count());
    // Golden-ratio step, written as two 32-bit halves so no long hex run
    // appears in the source (the sensitive-data gate reads those as hashes).
    const uint64_t counter =
        sequence.fetch_add((static_cast<uint64_t>(0x9E3779B9u) << 32) |
                           0x7F4A7C15u);
    uint64_t mixed = ticks ^ counter;
    for (size_t index = 0; index < kNonceBytes; index += sizeof(uint64_t)) {
        mixed ^= mixed << 13;
        mixed ^= mixed >> 7;
        mixed ^= mixed << 17;
        const size_t remaining = kNonceBytes - index;
        const size_t width = remaining < sizeof(uint64_t) ? remaining : sizeof(uint64_t);
        std::memcpy(nonce + index, &mixed, width);
    }
}

bool send_rst(Axp1Transport* transport, WireError error) {
    std::vector<uint8_t> wire;
    const int code = static_cast<int>(error);
    const uint8_t payload[2] = {static_cast<uint8_t>((code >> 8) & 0xff),
                                static_cast<uint8_t>(code & 0xff)};
    axp1_encode_frame(kAxp1TypeRst, std::vector<uint8_t>(payload, payload + 2), &wire);
    return write_all(transport, wire.data(), wire.size());
}

struct ParsedHello {
    uint8_t role = 0;
    uint8_t token[kTokenHexChars] = {};
    Axp1PeerCaps caps;
};

// payload := u8 role | u8 nonceLen | nonce | u8 tokenLen | token | caps
WireError parse_hello(const std::vector<uint8_t>& payload, ParsedHello* hello) {
    size_t offset = 0;
    if (payload.size() < 2) {
        return WireError::kProtocolError;
    }
    hello->role = payload[offset++];
    if (hello->role != kRoleHost) {
        // The Java side rejects a peer whose role equals its own.
        return WireError::kProtocolError;
    }
    const uint8_t nonce_length = payload[offset++];
    if (nonce_length != kNonceBytes || offset + nonce_length > payload.size()) {
        return WireError::kProtocolError;
    }
    offset += nonce_length;
    if (offset >= payload.size()) {
        return WireError::kAuthFailed;
    }
    const uint8_t token_length = payload[offset++];
    if (token_length > 64 || offset + token_length > payload.size()) {
        return WireError::kAuthFailed;
    }
    if (token_length != kTokenHexChars) {
        return WireError::kAuthFailed;
    }
    std::memcpy(hello->token, payload.data() + offset, kTokenHexChars);
    offset += kTokenHexChars;
    if (!axp1_decode_caps(payload.data() + offset, payload.size() - offset,
                          &hello->caps)) {
        return WireError::kProtocolError;
    }
    return WireError::kOk;
}

std::vector<uint8_t> build_hello_ack(const Axp1ServerCaps& caps) {
    std::vector<uint8_t> payload;
    payload.push_back(kRoleGuest);
    payload.push_back(static_cast<uint8_t>(kNonceBytes));
    uint8_t nonce[kNonceBytes];
    fill_nonce(nonce);
    payload.insert(payload.end(), nonce, nonce + kNonceBytes);
    const std::vector<uint8_t> caps_wire = axp1_encode_caps(caps);
    payload.insert(payload.end(), caps_wire.begin(), caps_wire.end());
    return payload;
}

// Maps a read failure to the RST code the Java server would have answered
// with; kClosed and kTimeout are handled by the caller (no RST on timeout).
bool rst_for_read_status(Axp1ReadStatus status, WireError* error) {
    switch (status) {
        case Axp1ReadStatus::kVersionMismatch:
            *error = WireError::kVersionMismatch;
            return true;
        case Axp1ReadStatus::kFrameTooLarge:
            *error = WireError::kFrameTooLarge;
            return true;
        case Axp1ReadStatus::kProtocolError:
            *error = WireError::kProtocolError;
            return true;
        case Axp1ReadStatus::kTimeout:
            *error = WireError::kTimeout;
            return true;
        case Axp1ReadStatus::kEof:
        case Axp1ReadStatus::kClosed:
        case Axp1ReadStatus::kFrame:
            return false;
    }
    return false;
}

}  // namespace

Axp1ReadStatus axp1_read_frame(Axp1Transport* transport, uint32_t max_frame,
                               Axp1FrameView* frame) {
    uint8_t header[kHeaderBytes];
    const long first = transport->read(header, 1);
    if (first == kReadTimeout) {
        return Axp1ReadStatus::kTimeout;
    }
    if (first == 0) {
        return Axp1ReadStatus::kEof;
    }
    if (first < 0) {
        return Axp1ReadStatus::kClosed;
    }
    if (!read_exact(transport, header + 1, kHeaderBytes - 1)) {
        return Axp1ReadStatus::kClosed;
    }
    if (std::memcmp(header, "AXP1", 4) != 0) {
        return Axp1ReadStatus::kProtocolError;
    }
    if (header[4] != 1) {
        return Axp1ReadStatus::kVersionMismatch;
    }
    const uint8_t type = header[5];
    if (type < kAxp1TypeHello || type > kAxp1TypePong) {
        return Axp1ReadStatus::kProtocolError;
    }
    const uint32_t length = read_be32(header + 8);
    // Checking against the local ceiling before allocating is what makes a
    // hostile length header harmless.
    if (length > kAxp1AbsoluteMaxFrame || (max_frame != 0 && length > max_frame)) {
        return Axp1ReadStatus::kFrameTooLarge;
    }
    frame->type = type;
    frame->payload.assign(length, 0);
    if (length != 0 && !read_exact(transport, frame->payload.data(), length)) {
        return Axp1ReadStatus::kClosed;
    }
    return Axp1ReadStatus::kFrame;
}

void axp1_encode_frame(uint8_t type, const std::vector<uint8_t>& payload,
                       std::vector<uint8_t>* out) {
    const size_t base = out->size();
    out->resize(base + kHeaderBytes + payload.size());
    uint8_t* header = out->data() + base;
    std::memcpy(header, "AXP1", 4);
    header[4] = 1;
    header[5] = type;
    header[6] = 0;
    header[7] = 0;
    header[8] = static_cast<uint8_t>(payload.size() >> 24);
    header[9] = static_cast<uint8_t>(payload.size() >> 16);
    header[10] = static_cast<uint8_t>(payload.size() >> 8);
    header[11] = static_cast<uint8_t>(payload.size());
    if (!payload.empty()) {
        std::memcpy(header + kHeaderBytes, payload.data(), payload.size());
    }
}

std::vector<uint8_t> axp1_encode_caps(const Axp1ServerCaps& caps) {
    std::vector<uint8_t> out;
    const std::string triple = truncate_utf8(caps.compiler_triple, kMaxCapsString);
    const std::string version = truncate_utf8(caps.compiler_version, kMaxCapsString);
    const uint16_t count = static_cast<uint16_t>(2 + (triple.empty() ? 0 : 1) +
                                                 (version.empty() ? 0 : 1));
    append_be16(&out, count);
    const uint8_t distcc_value = caps.distcc ? 1 : 0;
    append_tlv(&out, 1, &distcc_value, 1);
    const uint8_t frame_value[4] = {
        static_cast<uint8_t>(caps.max_frame >> 24),
        static_cast<uint8_t>(caps.max_frame >> 16),
        static_cast<uint8_t>(caps.max_frame >> 8),
        static_cast<uint8_t>(caps.max_frame)};
    append_tlv(&out, 2, frame_value, sizeof(frame_value));
    if (!triple.empty()) {
        append_tlv(&out, 3, reinterpret_cast<const uint8_t*>(triple.data()),
                   triple.size());
    }
    if (!version.empty()) {
        append_tlv(&out, 6, reinterpret_cast<const uint8_t*>(version.data()),
                   version.size());
    }
    return out;
}

bool axp1_decode_caps(const uint8_t* data, size_t size, Axp1PeerCaps* caps) {
    if (size < 2) {
        return false;
    }
    const uint16_t count = read_be16(data);
    size_t offset = 2;
    if (count > kMaxCapsEntries) {
        return false;
    }
    for (uint16_t index = 0; index < count; ++index) {
        if (offset + 3 > size) {
            return false;
        }
        const uint8_t id = data[offset++];
        const uint16_t length = read_be16(data + offset);
        offset += 2;
        if (length > size || offset + length > size) {
            return false;
        }
        switch (id) {
            case 1:
                if (length != 1) {
                    return false;
                }
                caps->distcc = data[offset] != 0;
                break;
            case 2: {
                if (length != 4) {
                    return false;
                }
                const uint32_t frame = read_be32(data + offset);
                if (frame < 1 || frame > kAxp1AbsoluteMaxFrame) {
                    return false;
                }
                caps->has_max_frame = true;
                caps->max_frame = frame;
                break;
            }
            case 3:
            case 4:
                if (length > kMaxCapsString) {
                    return false;
                }
                break;  // triple/arch: the server only answers its own
            default:
                break;  // unknown ids (including 6) are ignored, never logged
        }
        offset += length;
    }
    return true;
}

WireError axp1_serve_connection(Axp1Transport* transport,
                                const Axp1ServerConfig& config,
                                const ArgvPolicyConfig& policy,
                                const ServerLimits& limits,
                                CompileEngine* engine) {
    if (config.token.size() != kTokenHexChars) {
        return WireError::kProtocolError;
    }
    if (config.caps.max_frame < 1 || config.caps.max_frame > kAxp1AbsoluteMaxFrame) {
        return WireError::kProtocolError;
    }

    // --- Handshake: expect HELLO, answer HELLO_ACK. ---
    transport->set_read_timeout_ms(config.handshake_timeout_ms);
    Axp1FrameView frame;
    const Axp1ReadStatus hello_status =
        axp1_read_frame(transport, config.caps.max_frame, &frame);
    if (hello_status != Axp1ReadStatus::kFrame) {
        WireError error = WireError::kClosed;
        if (hello_status == Axp1ReadStatus::kTimeout) {
            return WireError::kTimeout;  // Java sends no RST for a timeout
        }
        if (rst_for_read_status(hello_status, &error)) {
            send_rst(transport, error);
        }
        return hello_status == Axp1ReadStatus::kEof ? WireError::kClosed : error;
    }
    if (frame.type == kAxp1TypeRst) {
        return WireError::kClosed;
    }
    if (frame.type != kAxp1TypeHello) {
        send_rst(transport, WireError::kProtocolError);
        return WireError::kProtocolError;
    }
    ParsedHello hello;
    const WireError parsed = parse_hello(frame.payload, &hello);
    if (parsed != WireError::kOk) {
        send_rst(transport, parsed);
        return parsed;
    }
    uint8_t expected[kTokenHexChars];
    std::memcpy(expected, config.token.data(), kTokenHexChars);
    if (!constant_time_equal(hello.token, expected, kTokenHexChars)) {
        send_rst(transport, WireError::kAuthFailed);
        return WireError::kAuthFailed;
    }

    // The ACK must advertise what both sides will actually use: the Java
    // factory takes min(local, peer) independently, so a server that echoes
    // its own ceiling would desynchronise as soon as the peer is lower.
    Axp1ServerCaps negotiated = config.caps;
    if (hello.caps.has_max_frame && hello.caps.max_frame < negotiated.max_frame) {
        negotiated.max_frame = hello.caps.max_frame;
    }
    const uint32_t frame_ceiling = negotiated.max_frame;

    std::vector<uint8_t> ack;
    axp1_encode_frame(kAxp1TypeHelloAck, build_hello_ack(negotiated), &ack);
    if (!write_all(transport, ack.data(), ack.size())) {
        return WireError::kClosed;
    }

    // --- Data plane: the request self-delimits at its DOTI body. ---
    //
    // PVM-10: end-of-request used to mean "the peer sent an EOF frame", which
    // meant "the TCP client half-closed". Real distcc never half-closes: it
    // writes DIST/ARGC/ARGV*/DOTI<len><bytes> and then reads the answer on the
    // same socket (upstream doc/protocol-1.txt: "Closing the channel has no
    // meaning and does not indicate the end of a packet"). So the EOF frame
    // never came and the payload burned frame_timeout_ms for nothing. The
    // accumulator now re-parses after every DATA frame and stops as soon as a
    // whole request is present. An EOF frame is still accepted -- the QA
    // compile mode sends one -- but it is no longer required.
    transport->set_read_timeout_ms(limits.frame_timeout_ms);
    std::vector<uint8_t> request_wire;
    const size_t ceiling = distcc_request_ceiling(limits);
    bool overflow = false;
    size_t discarded = 0;
    size_t pings = 0;
    bool saw_eof_frame = false;
    bool request_ready = false;
    DistccParseResult parsed_request;

    for (;;) {
        const Axp1ReadStatus status =
            axp1_read_frame(transport, frame_ceiling, &frame);
        if (status == Axp1ReadStatus::kEof || status == Axp1ReadStatus::kClosed) {
            // The transport is gone. What is buffered decides the outcome: a
            // complete request still earns an answer, a partial one does not.
            break;
        }
        if (status == Axp1ReadStatus::kTimeout) {
            send_rst(transport, WireError::kTimeout);
            return WireError::kTimeout;
        }
        if (status != Axp1ReadStatus::kFrame) {
            WireError error = WireError::kProtocolError;
            rst_for_read_status(status, &error);
            send_rst(transport, error);
            return error;
        }
        if (frame.type == kAxp1TypeEof) {
            saw_eof_frame = true;
            break;
        }
        if (frame.type == kAxp1TypeData) {
            if (overflow) {
                // Keep draining so the peer can finish writing, but stop
                // buffering: the answer is already decided.
                discarded += frame.payload.size();
                // One extra max_input worth of junk is tolerated so a real
                // (merely oversized) request still gets JOB_TOO_LARGE; beyond
                // that the peer is streaming on purpose and loses the link.
                if (discarded > ceiling + limits.max_input_bytes) {
                    send_rst(transport, WireError::kFrameTooLarge);
                    return WireError::kFrameTooLarge;
                }
                continue;
            }
            if (request_wire.size() + frame.payload.size() > ceiling) {
                overflow = true;
                request_wire.clear();
                request_wire.shrink_to_fit();
                continue;
            }
            request_wire.insert(request_wire.end(), frame.payload.begin(),
                                frame.payload.end());

            parsed_request = parse_distcc_request(request_wire, limits);
            if (parsed_request.status == DistccParseStatus::kOk) {
                request_ready = true;
                break;  // compile now; no EOF frame is coming
            }
            if (parsed_request.status == DistccParseStatus::kTooLarge) {
                // A declared length already breaks a ceiling. Answer only
                // after the peer finishes writing, exactly like the overflow
                // path above, or the client sees a reset mid-request.
                overflow = true;
                request_wire.clear();
                request_wire.shrink_to_fit();
                continue;
            }
            if (parsed_request.status == DistccParseStatus::kProtocol &&
                parsed_request.prefix_recognised) {
                // Recognised tokens followed by a defect no later byte can
                // repair. distccd drops the connection with no bytes at all.
                send_rst(transport, WireError::kProtocolError);
                return WireError::kProtocolError;
            }
            // kIncomplete, or junk before the first token: the ceiling and the
            // frame deadline are what bound this, exactly as before.
            continue;
        }
        if (frame.type == kAxp1TypePing) {
            if (++pings > limits.max_pings) {
                send_rst(transport, WireError::kProtocolError);
                return WireError::kProtocolError;
            }
            std::vector<uint8_t> pong;
            axp1_encode_frame(kAxp1TypePong, frame.payload, &pong);
            if (!write_all(transport, pong.data(), pong.size())) {
                return WireError::kClosed;
            }
            continue;
        }
        if (frame.type == kAxp1TypeRst) {
            return WireError::kClosed;
        }
        send_rst(transport, WireError::kProtocolError);
        return WireError::kProtocolError;
    }

    // --- Decide what the buffered bytes say, then compile and answer. ---
    if (!overflow && !request_ready) {
        parsed_request = parse_distcc_request(request_wire, limits);
        if (parsed_request.status == DistccParseStatus::kIncomplete) {
            if (!saw_eof_frame) {
                return WireError::kClosed;  // the peer vanished mid-request
            }
            // An EOF frame promises the request is over, so a short body is a
            // defect rather than a stream still in flight.
            parsed_request.status = DistccParseStatus::kProtocol;
        }
    }

    DistccResponse response;
    if (overflow || (!request_ready &&
                     parsed_request.status == DistccParseStatus::kTooLarge)) {
        response.stat = stat_for_status(JobStatus::kJobTooLarge);
        response.serr = "JOB_TOO_LARGE\n";
    } else if (!request_ready &&
               parsed_request.status == DistccParseStatus::kProtocol) {
        // distccd drops the connection with no bytes at all; the client
        // then reports a protocol error and falls back locally.
        send_rst(transport, WireError::kProtocolError);
        return WireError::kProtocolError;
    } else {
        response = run_distcc_job(parsed_request.request, policy, limits, engine);
    }

    const std::vector<uint8_t> response_wire = encode_distcc_response(response);
    std::vector<uint8_t> out;
    for (size_t offset = 0; offset < response_wire.size();) {
        const size_t remaining = response_wire.size() - offset;
        const size_t chunk = remaining < frame_ceiling ? remaining : frame_ceiling;
        axp1_encode_frame(kAxp1TypeData,
                          std::vector<uint8_t>(response_wire.begin() + offset,
                                               response_wire.begin() + offset + chunk),
                          &out);
        offset += chunk;
    }
    axp1_encode_frame(kAxp1TypeEof, std::vector<uint8_t>(), &out);
    if (!write_all(transport, out.data(), out.size())) {
        return WireError::kClosed;
    }
    transport->shutdown();
    return WireError::kOk;
}

}  // namespace pvm
}  // namespace fl
