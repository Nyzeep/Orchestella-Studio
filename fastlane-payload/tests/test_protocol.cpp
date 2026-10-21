#include "test_support.h"

#include <cstdint>
#include <string>
#include <vector>

#include "fastlane/error.h"
#include "fastlane/protocol.h"

namespace {

fl::Frame frame(fl::FrameType type, uint32_t session_id, std::vector<uint8_t> payload) {
    fl::Frame value;
    value.type = type;
    value.session_id = session_id;
    value.payload = std::move(payload);
    return value;
}

}  // namespace

FL_TEST(header_has_fixed_layout) {
    const auto bytes = fl::encode_frame(frame(fl::FrameType::kPing, 0x01020304, {0xAA, 0xBB}));
    FL_CHECK_EQ(bytes.size(), fl::kFrameHeaderSize + 2);
    FL_CHECK_EQ(bytes[0], 0x46);
    FL_CHECK_EQ(bytes[1], 0x4C);
    FL_CHECK_EQ(bytes[2], 0x4E);
    FL_CHECK_EQ(bytes[3], 0x31);
    FL_CHECK_EQ(bytes[4], 1);
    FL_CHECK_EQ(bytes[5], 0x02);
    FL_CHECK_EQ(bytes[6], 0x01);
    FL_CHECK_EQ(bytes[7], 0x02);
    FL_CHECK_EQ(bytes[8], 0x03);
    FL_CHECK_EQ(bytes[9], 0x04);
    FL_CHECK_EQ(bytes[10], 0x00);
    FL_CHECK_EQ(bytes[11], 0x00);
    FL_CHECK_EQ(bytes[12], 0x00);
    FL_CHECK_EQ(bytes[13], 0x02);
    FL_CHECK_EQ(bytes[14], 0xAA);
    FL_CHECK_EQ(bytes[15], 0xBB);
}

FL_TEST(round_trip_all_control_types) {
    const fl::FrameType types[] = {
            fl::FrameType::kHello,       fl::FrameType::kHelloOk,   fl::FrameType::kPing,
            fl::FrameType::kPong,        fl::FrameType::kOpenPty,   fl::FrameType::kPtyOpened,
            fl::FrameType::kPtyData,     fl::FrameType::kResize,    fl::FrameType::kResized,
            fl::FrameType::kSignal,      fl::FrameType::kClose,     fl::FrameType::kClosed,
            fl::FrameType::kEof,         fl::FrameType::kExit,      fl::FrameType::kWindow,
            fl::FrameType::kExport,      fl::FrameType::kExportBegin, fl::FrameType::kExportData,
            fl::FrameType::kExportEnd,   fl::FrameType::kImportBegin, fl::FrameType::kImportData,
            fl::FrameType::kImportEnd,   fl::FrameType::kImportDone, fl::FrameType::kMetrics,
            fl::FrameType::kMetricsOk,   fl::FrameType::kError,
    };
    for (const auto type : types) {
        const auto bytes = fl::encode_frame(frame(type, 42, {1, 2, 3}));
        fl::MemorySource source(bytes);
        fl::FrameReader reader(source);
        fl::Frame decoded;
        FL_CHECK(reader.next(decoded));
        FL_CHECK(decoded.type == type);
        FL_CHECK_EQ(decoded.session_id, 42u);
        FL_CHECK(decoded.payload == std::vector<uint8_t>({1, 2, 3}));
    }
}

FL_TEST(reader_tolerates_short_reads) {
    const auto bytes = fl::encode_frame(frame(fl::FrameType::kPtyData, 7, {9, 8, 7, 6, 5}));
    fl::MemorySource source(bytes, 3);
    fl::FrameReader reader(source);
    fl::Frame decoded;
    FL_CHECK(reader.next(decoded));
    FL_CHECK(decoded.type == fl::FrameType::kPtyData);
    FL_CHECK_EQ(decoded.session_id, 7u);
    FL_CHECK(decoded.payload == std::vector<uint8_t>({9, 8, 7, 6, 5}));
}

FL_TEST(reader_separates_back_to_back_frames) {
    std::vector<uint8_t> stream;
    const auto first = fl::encode_frame(frame(fl::FrameType::kPing, 1, {}));
    const auto second = fl::encode_frame(frame(fl::FrameType::kPong, 1, {4}));
    stream.insert(stream.end(), first.begin(), first.end());
    stream.insert(stream.end(), second.begin(), second.end());
    fl::MemorySource source(stream, 5);
    fl::FrameReader reader(source);
    fl::Frame one;
    fl::Frame two;
    FL_CHECK(reader.next(one));
    FL_CHECK(reader.next(two));
    FL_CHECK(one.type == fl::FrameType::kPing);
    FL_CHECK(two.type == fl::FrameType::kPong);
    FL_CHECK_EQ(two.payload.size(), 1u);
    fl::Frame end;
    FL_CHECK(!reader.next(end));
}

FL_TEST(reader_rejects_bad_magic) {
    auto bytes = fl::encode_frame(frame(fl::FrameType::kPing, 0, {}));
    bytes[0] = 'X';
    fl::MemorySource source(bytes);
    fl::FrameReader reader(source);
    fl::Frame decoded;
    FL_CHECK_THROWS_CODE(reader.next(decoded), fl::Code::kProtocolMalformed);
}

FL_TEST(reader_rejects_unknown_version) {
    auto bytes = fl::encode_frame(frame(fl::FrameType::kPing, 0, {}));
    bytes[4] = 2;
    fl::MemorySource source(bytes);
    fl::FrameReader reader(source);
    fl::Frame decoded;
    FL_CHECK_THROWS_CODE(reader.next(decoded), fl::Code::kUnsupportedVersion);
}

FL_TEST(reader_rejects_oversized_payload) {
    auto bytes = fl::encode_frame(frame(fl::FrameType::kPtyData, 0, {}));
    bytes[10] = 0x00;
    bytes[11] = 0x01;
    bytes[12] = 0x00;
    bytes[13] = 0x01;
    fl::MemorySource source(bytes);
    fl::FrameReader reader(source);
    fl::Frame decoded;
    FL_CHECK_THROWS_CODE(reader.next(decoded), fl::Code::kProtocolMalformed);
}

FL_TEST(reader_rejects_truncated_header) {
    const auto bytes = fl::encode_frame(frame(fl::FrameType::kPing, 0, {}));
    fl::MemorySource source(std::vector<uint8_t>(bytes.begin(), bytes.begin() + 6));
    fl::FrameReader reader(source);
    fl::Frame decoded;
    FL_CHECK_THROWS_CODE(reader.next(decoded), fl::Code::kProtocolMalformed);
}

FL_TEST(reader_rejects_truncated_payload) {
    auto bytes = fl::encode_frame(frame(fl::FrameType::kPtyData, 0, {1, 2, 3}));
    bytes.pop_back();
    fl::MemorySource source(bytes);
    fl::FrameReader reader(source);
    fl::Frame decoded;
    FL_CHECK_THROWS_CODE(reader.next(decoded), fl::Code::kProtocolMalformed);
}

FL_TEST(payload_round_trip) {
    fl::PayloadWriter writer;
    writer.u8(7);
    writer.u16(0x1234);
    writer.u32(0x11223344);
    writer.u64(0x1122334455667788ULL);
    writer.string("hello");
    writer.bytes("xy", 2);

    const auto buffer = writer.take();
    fl::PayloadReader parsed(buffer.data(), buffer.size());
    uint8_t a = 0;
    uint16_t b = 0;
    uint32_t c = 0;
    uint64_t d = 0;
    std::string text;
    char tail[2] = {};
    FL_CHECK(parsed.u8(&a));
    FL_CHECK_EQ(a, 7);
    FL_CHECK(parsed.u16(&b));
    FL_CHECK_EQ(b, 0x1234);
    FL_CHECK(parsed.u32(&c));
    FL_CHECK_EQ(c, 0x11223344u);
    FL_CHECK(parsed.u64(&d));
    FL_CHECK_EQ(d, 0x1122334455667788ULL);
    FL_CHECK(parsed.string(&text));
    FL_CHECK_EQ(text, std::string("hello"));
    FL_CHECK(parsed.bytes(tail, 2));
    FL_CHECK_EQ(tail[0], 'x');
    FL_CHECK_EQ(tail[1], 'y');
    FL_CHECK(parsed.done());
}

FL_TEST(payload_reader_reports_truncation) {
    const uint8_t data[] = {0x00, 0x01};
    fl::PayloadReader reader(data, sizeof(data));
    uint32_t value = 0;
    FL_CHECK(!reader.u32(&value));
    FL_CHECK(!reader.done());
}

FL_TEST(helpers_classify_replies) {
    FL_CHECK(!fl::is_reply(fl::FrameType::kHello));
    FL_CHECK(fl::is_reply(fl::FrameType::kHelloOk));
    FL_CHECK(fl::is_reply(fl::FrameType::kError));
    FL_CHECK(std::string(fl::frame_type_name(fl::FrameType::kOpenPty)) == "OPEN_PTY");
}

FL_TEST(constant_time_compare) {
    FL_CHECK(fl::constant_time_equal("", ""));
    FL_CHECK(fl::constant_time_equal("token", "token"));
    FL_CHECK(!fl::constant_time_equal("token", "tokeN"));
    FL_CHECK(!fl::constant_time_equal("token", "token "));
}
