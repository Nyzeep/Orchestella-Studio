#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace fl {

constexpr uint32_t kFrameMagic = 0x464C4E31u;  // "FLN1"
constexpr uint8_t kProtocolVersion = 1;
constexpr size_t kFrameHeaderSize = 14;
constexpr uint32_t kMaxFramePayload = 65536;
constexpr uint32_t kDefaultInitialWindow = 262144;
constexpr uint32_t kMaxPtyDataFrame = 32768;

enum class FrameType : uint8_t {
    kHello = 0x01,
    kHelloOk = 0x81,
    kPing = 0x02,
    kPong = 0x82,
    kOpenPty = 0x03,
    kPtyOpened = 0x83,
    kPtyData = 0x04,
    kResize = 0x05,
    kResized = 0x85,
    kSignal = 0x06,
    kClose = 0x07,
    kClosed = 0x87,
    kEof = 0x08,
    kExit = 0x09,
    kWindow = 0x0A,
    kExport = 0x0B,
    kExportBegin = 0x8B,
    kExportData = 0x0C,
    kExportEnd = 0x0D,
    kImportBegin = 0x0E,
    kImportData = 0x0F,
    kImportEnd = 0x10,
    kImportDone = 0x90,
    kMetrics = 0x11,
    kMetricsOk = 0x91,
    kError = 0xFF,
};

struct Frame {
    FrameType type = FrameType::kPing;
    uint32_t session_id = 0;
    std::vector<uint8_t> payload;
};

bool is_reply(FrameType type);
bool is_known_frame_type(FrameType type);
const char* frame_type_name(FrameType type);

/** Serialises one complete frame including the 14-byte header. */
std::vector<uint8_t> encode_frame(const Frame& frame);

/** Reads bytes from a transport; returns 0 only on clean EOF. */
class ByteSource {
public:
    virtual ~ByteSource() = default;
    virtual size_t read(void* destination, size_t length) = 0;
};

class MemorySource : public ByteSource {
public:
    MemorySource(std::vector<uint8_t> bytes, size_t max_chunk = 0);
    size_t read(void* destination, size_t length) override;

private:
    std::vector<uint8_t> bytes_;
    size_t offset_ = 0;
    size_t max_chunk_;
};

class FdSource : public ByteSource {
public:
    explicit FdSource(int fd) : fd_(fd) {}
    size_t read(void* destination, size_t length) override;

private:
    int fd_;
};

/**
 * Reads complete frames. A short read is normal; a truncated header or payload is
 * PROTOCOL_MALFORMED, and the stream must not be resynchronised afterwards.
 */
class FrameReader {
public:
    explicit FrameReader(ByteSource& source) : source_(source) {}

    /** Returns false on clean EOF before a header starts. */
    bool next(Frame& frame);

private:
    ByteSource& source_;
};

void write_all(int fd, const void* data, size_t length);
void write_frame(int fd, const Frame& frame);

class PayloadWriter {
public:
    void u8(uint8_t value);
    void u16(uint16_t value);
    void u32(uint32_t value);
    void u64(uint64_t value);
    void bytes(const void* data, size_t length);
    void string(const std::string& value);
    std::vector<uint8_t> take();

private:
    std::vector<uint8_t> buffer_;
};

class PayloadReader {
public:
    PayloadReader(const uint8_t* data, size_t length) : data_(data), length_(length) {}

    bool u8(uint8_t* value);
    bool u16(uint16_t* value);
    bool u32(uint32_t* value);
    bool u64(uint64_t* value);
    bool string(std::string* value);
    bool bytes(void* destination, size_t length);
    bool skip(size_t length);
    bool done() const { return offset_ == length_; }
    size_t remaining() const { return length_ - offset_; }

private:
    bool require(size_t length);
    const uint8_t* data_;
    size_t length_;
    size_t offset_ = 0;
};

/** Compares without an early exit so a token check does not leak its length. */
bool constant_time_equal(const std::string& left, const std::string& right);

}  // namespace fl
