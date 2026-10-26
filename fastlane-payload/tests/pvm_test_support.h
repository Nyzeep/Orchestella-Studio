// Shared helpers for the PVM-03 compile-service tests.
//
// Everything here is deliberately small and header-only: the same sources are
// compiled into the FLN1 test binary (Linux/CI) and into the standalone
// pvm_compile_service_tests target (any host), so nothing may depend on
// fastlane_payload_core.

#pragma once

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <iterator>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "axp1_server.h"
#include "distcc_server.h"

namespace fl {
namespace pvm {
namespace test {

/** Parses "41 58 50 31 ..." (whitespace separated hex) into bytes. */
inline std::vector<uint8_t> parse_hex_bytes(const std::string& text) {
    std::vector<uint8_t> bytes;
    std::istringstream stream(text);
    std::string token;
    while (stream >> token) {
        bytes.push_back(static_cast<uint8_t>(std::stoul(token, nullptr, 16)));
    }
    return bytes;
}

/** Reads a raw hex dump file (all whitespace ignored). */
inline std::vector<uint8_t> read_hex_dump(const char* path) {
    std::ifstream file(path, std::ios::binary);
    std::string text((std::istreambuf_iterator<char>(file)),
                     std::istreambuf_iterator<char>());
    std::string compact;
    compact.reserve(text.size());
    for (const char character : text) {
        if (character != ' ' && character != '\n' && character != '\r' &&
            character != '\t') {
            compact.push_back(character);
        }
    }
    std::vector<uint8_t> bytes;
    bytes.reserve(compact.size() / 2);
    for (size_t index = 0; index + 1 < compact.size(); index += 2) {
        bytes.push_back(static_cast<uint8_t>(
            std::stoul(compact.substr(index, 2), nullptr, 16)));
    }
    return bytes;
}

/** Reads golden-v1.hex: one "<TYPE> <hex bytes>" record per line. */
inline std::map<std::string, std::vector<uint8_t>> read_golden_frames(const char* path) {
    std::map<std::string, std::vector<uint8_t>> frames;
    std::ifstream file(path);
    std::string line;
    while (std::getline(file, line)) {
        std::istringstream stream(line);
        std::string name;
        if (!(stream >> name)) {
            continue;
        }
        std::string rest;
        std::getline(stream, rest);
        frames[name] = parse_hex_bytes(rest);
    }
    return frames;
}

/** Builds a distcc v1 request byte stream from argv + preprocessed source. */
inline std::vector<uint8_t> build_distcc_request(const std::vector<std::string>& argv,
                                                 const std::string& doti) {
    std::vector<uint8_t> wire = encode_token("DIST", 1);
    auto append = [&wire](const char token[5], uint32_t parameter, const void* body,
                          size_t size) {
        const std::vector<uint8_t> header = encode_token(token, parameter);
        wire.insert(wire.end(), header.begin(), header.end());
        if (size != 0) {
            const auto* bytes = static_cast<const uint8_t*>(body);
            wire.insert(wire.end(), bytes, bytes + size);
        }
    };
    append("ARGC", static_cast<uint32_t>(argv.size()), nullptr, 0);
    for (const std::string& argument : argv) {
        append("ARGV", static_cast<uint32_t>(argument.size()), argument.data(),
               argument.size());
    }
    append("DOTI", static_cast<uint32_t>(doti.size()), doti.data(), doti.size());
    return wire;
}

/** In-memory AXP1 transport with a scripted inbound queue. */
class MemoryTransport final : public Axp1Transport {
public:
    void append(const std::vector<uint8_t>& bytes) {
        inbound_.insert(inbound_.end(), bytes.begin(), bytes.end());
    }

    void append_frame(uint8_t type, const std::vector<uint8_t>& payload) {
        std::vector<uint8_t> wire;
        axp1_encode_frame(type, payload, &wire);
        append(wire);
    }

    /** Number of reads that report a deadline instead of EOF. */
    int timeouts_remaining = 0;

    /**
     * When true, an exhausted queue behaves like a live socket whose peer has
     * gone quiet: it blocks for `quiet_ms` and then reports the read deadline
     * (-2) instead of end-of-stream. The real distcc client does exactly this
     * -- it writes the request, never calls shutdown(SHUT_WR), and waits for
     * the response on the same socket.
     */
    bool quiet_instead_of_eof = false;
    uint32_t quiet_ms = 0;

    long read(uint8_t* buffer, size_t size) override {
        if (position_ >= inbound_.size()) {
            if (timeouts_remaining > 0) {
                --timeouts_remaining;
                return -2;
            }
            if (quiet_instead_of_eof) {
                std::this_thread::sleep_for(std::chrono::milliseconds(quiet_ms));
                return -2;
            }
            return 0;
        }
        const size_t remaining = inbound_.size() - position_;
        const size_t take = remaining < size ? remaining : size;
        for (size_t index = 0; index < take; ++index) {
            buffer[index] = inbound_[position_ + index];
        }
        position_ += take;
        return static_cast<long>(take);
    }

    bool write(const uint8_t* buffer, size_t size) override {
        outbound_.insert(outbound_.end(), buffer, buffer + size);
        return true;
    }

    void shutdown() override { shutdown_called = true; }

    void set_read_timeout_ms(uint32_t timeout_ms) override {
        last_timeout_ms = timeout_ms;
        ++timeout_calls;
    }

    const std::vector<uint8_t>& outbound() const { return outbound_; }

    bool shutdown_called = false;
    uint32_t last_timeout_ms = 0;
    int timeout_calls = 0;

private:
    std::vector<uint8_t> inbound_;
    std::vector<uint8_t> outbound_;
    size_t position_ = 0;
};

/** Splits an AXP1 byte stream into frames; returns false on a short stream. */
inline bool decode_frames(const std::vector<uint8_t>& wire,
                          std::vector<Axp1FrameView>* frames) {
    size_t offset = 0;
    while (offset < wire.size()) {
        if (offset + 12 > wire.size()) {
            return false;
        }
        if (std::memcmp(wire.data() + offset, "AXP1", 4) != 0) {
            return false;
        }
        const uint32_t length = (static_cast<uint32_t>(wire[offset + 8]) << 24) |
                                (static_cast<uint32_t>(wire[offset + 9]) << 16) |
                                (static_cast<uint32_t>(wire[offset + 10]) << 8) |
                                static_cast<uint32_t>(wire[offset + 11]);
        if (offset + 12 + length > wire.size()) {
            return false;
        }
        Axp1FrameView frame;
        frame.type = wire[offset + 5];
        frame.payload.assign(wire.begin() + offset + 12,
                             wire.begin() + offset + 12 + length);
        frames->push_back(frame);
        offset += 12 + length;
    }
    return true;
}

/** Concatenates the DATA payloads of a decoded response, ignoring EOF/RST. */
inline std::vector<uint8_t> response_bytes(const std::vector<Axp1FrameView>& frames) {
    std::vector<uint8_t> bytes;
    for (const Axp1FrameView& frame : frames) {
        if (frame.type == kAxp1TypeData) {
            bytes.insert(bytes.end(), frame.payload.begin(), frame.payload.end());
        }
    }
    return bytes;
}

/** Builds a HELLO payload in the Java Axp1Connection layout. */
inline std::vector<uint8_t> build_hello(const std::string& token,
                                        uint32_t max_frame,
                                        const std::string& triple = std::string()) {
    std::vector<uint8_t> payload;
    payload.push_back(1);  // HOST
    payload.push_back(16);
    for (int index = 0; index < 16; ++index) {
        payload.push_back(static_cast<uint8_t>(index));
    }
    payload.push_back(static_cast<uint8_t>(token.size()));
    payload.insert(payload.end(), token.begin(), token.end());
    Axp1ServerCaps caps;
    caps.max_frame = max_frame;
    caps.compiler_triple = triple;
    const std::vector<uint8_t> block = axp1_encode_caps(caps);
    payload.insert(payload.end(), block.begin(), block.end());
    return payload;
}

}  // namespace test
}  // namespace pvm
}  // namespace fl
