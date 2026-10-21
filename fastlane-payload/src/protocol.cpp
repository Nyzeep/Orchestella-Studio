#include "fastlane/protocol.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <unistd.h>

#include "fastlane/error.h"

namespace fl {
namespace {

uint16_t load_u16(const uint8_t* data) {
    return static_cast<uint16_t>((static_cast<uint16_t>(data[0]) << 8) | data[1]);
}

uint32_t load_u32(const uint8_t* data) {
    return (static_cast<uint32_t>(data[0]) << 24) | (static_cast<uint32_t>(data[1]) << 16) |
           (static_cast<uint32_t>(data[2]) << 8) | static_cast<uint32_t>(data[3]);
}

void store_u16(uint8_t* data, uint16_t value) {
    data[0] = static_cast<uint8_t>(value >> 8);
    data[1] = static_cast<uint8_t>(value);
}

void store_u32(uint8_t* data, uint32_t value) {
    data[0] = static_cast<uint8_t>(value >> 24);
    data[1] = static_cast<uint8_t>(value >> 16);
    data[2] = static_cast<uint8_t>(value >> 8);
    data[3] = static_cast<uint8_t>(value);
}

void read_exactly(ByteSource& source, uint8_t* destination, size_t length, bool allow_clean_eof,
                  bool* clean_eof) {
    size_t offset = 0;
    while (offset < length) {
        const size_t remaining = length - offset;
        const size_t read = source.read(destination + offset, remaining);
        if (read == 0) {
            if (offset == 0 && allow_clean_eof) {
                *clean_eof = true;
                return;
            }
            throw Error(Code::kProtocolMalformed, "truncated frame");
        }
        if (read > remaining) {
            throw Error(Code::kProtocolMalformed, "transport returned an invalid read size");
        }
        offset += read;
    }
}

}  // namespace

bool is_reply(FrameType type) {
    const auto value = static_cast<uint8_t>(type);
    return value == 0xFF || (value > 0x80);
}

bool is_known_frame_type(FrameType type) {
    switch (type) {
        case FrameType::kHello:
        case FrameType::kHelloOk:
        case FrameType::kPing:
        case FrameType::kPong:
        case FrameType::kOpenPty:
        case FrameType::kPtyOpened:
        case FrameType::kPtyData:
        case FrameType::kResize:
        case FrameType::kResized:
        case FrameType::kSignal:
        case FrameType::kClose:
        case FrameType::kClosed:
        case FrameType::kEof:
        case FrameType::kExit:
        case FrameType::kWindow:
        case FrameType::kExport:
        case FrameType::kExportBegin:
        case FrameType::kExportData:
        case FrameType::kExportEnd:
        case FrameType::kImportBegin:
        case FrameType::kImportData:
        case FrameType::kImportEnd:
        case FrameType::kImportDone:
        case FrameType::kMetrics:
        case FrameType::kMetricsOk:
        case FrameType::kError:
            return true;
    }
    return false;
}

const char* frame_type_name(FrameType type) {
    switch (type) {
        case FrameType::kHello:
            return "HELLO";
        case FrameType::kHelloOk:
            return "HELLO_OK";
        case FrameType::kPing:
            return "PING";
        case FrameType::kPong:
            return "PONG";
        case FrameType::kOpenPty:
            return "OPEN_PTY";
        case FrameType::kPtyOpened:
            return "PTY_OPENED";
        case FrameType::kPtyData:
            return "PTY_DATA";
        case FrameType::kResize:
            return "RESIZE";
        case FrameType::kResized:
            return "RESIZED";
        case FrameType::kSignal:
            return "SIGNAL";
        case FrameType::kClose:
            return "CLOSE";
        case FrameType::kClosed:
            return "CLOSED";
        case FrameType::kEof:
            return "EOF";
        case FrameType::kExit:
            return "EXIT";
        case FrameType::kWindow:
            return "WINDOW";
        case FrameType::kExport:
            return "EXPORT";
        case FrameType::kExportBegin:
            return "EXPORT_BEGIN";
        case FrameType::kExportData:
            return "EXPORT_DATA";
        case FrameType::kExportEnd:
            return "EXPORT_END";
        case FrameType::kImportBegin:
            return "IMPORT_BEGIN";
        case FrameType::kImportData:
            return "IMPORT_DATA";
        case FrameType::kImportEnd:
            return "IMPORT_END";
        case FrameType::kImportDone:
            return "IMPORT_DONE";
        case FrameType::kMetrics:
            return "METRICS";
        case FrameType::kMetricsOk:
            return "METRICS_OK";
        case FrameType::kError:
            return "ERROR";
    }
    return "UNKNOWN";
}

std::vector<uint8_t> encode_frame(const Frame& frame) {
    if (frame.payload.size() > kMaxFramePayload) {
        throw Error(Code::kProtocolMalformed, "frame payload exceeds limit");
    }
    std::vector<uint8_t> bytes(kFrameHeaderSize + frame.payload.size());
    store_u32(bytes.data(), kFrameMagic);
    bytes[4] = kProtocolVersion;
    bytes[5] = static_cast<uint8_t>(frame.type);
    store_u32(bytes.data() + 6, frame.session_id);
    store_u32(bytes.data() + 10, static_cast<uint32_t>(frame.payload.size()));
    if (!frame.payload.empty()) {
        std::copy(frame.payload.begin(), frame.payload.end(), bytes.begin() + kFrameHeaderSize);
    }
    return bytes;
}

MemorySource::MemorySource(std::vector<uint8_t> bytes, size_t max_chunk)
        : bytes_(std::move(bytes)), max_chunk_(max_chunk) {}

size_t MemorySource::read(void* destination, size_t length) {
    if (offset_ >= bytes_.size() || length == 0) {
        return 0;
    }
    size_t available = bytes_.size() - offset_;
    size_t chunk = std::min(available, length);
    if (max_chunk_ > 0) {
        chunk = std::min(chunk, max_chunk_);
    }
    std::memcpy(destination, bytes_.data() + offset_, chunk);
    offset_ += chunk;
    return chunk;
}

size_t FdSource::read(void* destination, size_t length) {
    for (;;) {
        const ssize_t result = ::read(fd_, destination, length);
        if (result > 0) {
            return static_cast<size_t>(result);
        }
        if (result == 0) {
            return 0;
        }
        if (errno == EINTR) {
            continue;
        }
        throw Error(Code::kIo, std::string("read failed: ") + std::strerror(errno));
    }
}

bool FrameReader::next(Frame& frame) {
    uint8_t header[kFrameHeaderSize];
    bool clean_eof = false;
    read_exactly(source_, header, sizeof(header), true, &clean_eof);
    if (clean_eof) {
        return false;
    }
    if (load_u32(header) != kFrameMagic) {
        throw Error(Code::kProtocolMalformed, "bad frame magic");
    }
    if (header[4] != kProtocolVersion) {
        throw Error(Code::kUnsupportedVersion, "unsupported protocol version");
    }
    const uint32_t length = load_u32(header + 10);
    if (length > kMaxFramePayload) {
        throw Error(Code::kProtocolMalformed, "frame payload exceeds limit");
    }
    const FrameType type = static_cast<FrameType>(header[5]);
    if (!is_known_frame_type(type)) {
        throw Error(Code::kUnknownCommand, "unknown frame type");
    }
    frame.type = type;
    frame.session_id = load_u32(header + 6);
    frame.payload.assign(length, 0);
    if (length > 0) {
        bool ignored_eof = false;
        read_exactly(source_, frame.payload.data(), length, false, &ignored_eof);
    }
    return true;
}

void write_all(int fd, const void* data, size_t length) {
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t offset = 0;
    while (offset < length) {
        const ssize_t written = ::write(fd, bytes + offset, length - offset);
        if (written > 0) {
            offset += static_cast<size_t>(written);
            continue;
        }
        if (written < 0 && errno == EINTR) {
            continue;
        }
        throw Error(Code::kIo, std::string("write failed: ") + std::strerror(errno));
    }
}

void write_frame(int fd, const Frame& frame) {
    const auto bytes = encode_frame(frame);
    write_all(fd, bytes.data(), bytes.size());
}

void PayloadWriter::u8(uint8_t value) {
    buffer_.push_back(value);
}

void PayloadWriter::u16(uint16_t value) {
    const size_t offset = buffer_.size();
    buffer_.resize(offset + 2);
    store_u16(buffer_.data() + offset, value);
}

void PayloadWriter::u32(uint32_t value) {
    const size_t offset = buffer_.size();
    buffer_.resize(offset + 4);
    store_u32(buffer_.data() + offset, value);
}

void PayloadWriter::u64(uint64_t value) {
    u32(static_cast<uint32_t>(value >> 32));
    u32(static_cast<uint32_t>(value));
}

void PayloadWriter::bytes(const void* data, size_t length) {
    const auto* source = static_cast<const uint8_t*>(data);
    buffer_.insert(buffer_.end(), source, source + length);
}

void PayloadWriter::string(const std::string& value) {
    if (value.size() > 0xFFFF) {
        throw Error(Code::kProtocolMalformed, "string too long");
    }
    u16(static_cast<uint16_t>(value.size()));
    bytes(value.data(), value.size());
}

std::vector<uint8_t> PayloadWriter::take() {
    return std::move(buffer_);
}

bool PayloadReader::require(size_t length) {
    // Written as a subtraction so a hostile length cannot wrap offset_ + length.
    return offset_ <= length_ && length <= length_ - offset_;
}

bool PayloadReader::u8(uint8_t* value) {
    if (!require(1)) {
        return false;
    }
    *value = data_[offset_++];
    return true;
}

bool PayloadReader::u16(uint16_t* value) {
    if (!require(2)) {
        return false;
    }
    *value = load_u16(data_ + offset_);
    offset_ += 2;
    return true;
}

bool PayloadReader::u32(uint32_t* value) {
    if (!require(4)) {
        return false;
    }
    *value = load_u32(data_ + offset_);
    offset_ += 4;
    return true;
}

bool PayloadReader::u64(uint64_t* value) {
    uint32_t high = 0;
    uint32_t low = 0;
    if (!u32(&high) || !u32(&low)) {
        return false;
    }
    *value = (static_cast<uint64_t>(high) << 32) | low;
    return true;
}

bool PayloadReader::string(std::string* value) {
    uint16_t length = 0;
    if (!u16(&length) || !require(length)) {
        return false;
    }
    value->assign(reinterpret_cast<const char*>(data_ + offset_), length);
    offset_ += length;
    return true;
}

bool PayloadReader::bytes(void* destination, size_t length) {
    if (!require(length)) {
        return false;
    }
    std::memcpy(destination, data_ + offset_, length);
    offset_ += length;
    return true;
}

bool PayloadReader::skip(size_t length) {
    if (!require(length)) {
        return false;
    }
    offset_ += length;
    return true;
}

bool constant_time_equal(const std::string& left, const std::string& right) {
    const size_t length = std::max(left.size(), right.size());
    unsigned int difference = left.size() == right.size() ? 0u : 1u;
    for (size_t index = 0; index < length; ++index) {
        const unsigned char a = index < left.size() ? static_cast<unsigned char>(left[index]) : 0;
        const unsigned char b = index < right.size() ? static_cast<unsigned char>(right[index]) : 0;
        difference |= static_cast<unsigned int>(a ^ b);
    }
    return difference == 0;
}

}  // namespace fl
