// Cross-language byte contract for FLN1. Reads the same canonical fixture as
// the Java Fln1GoldenFixtureTest (protocol/fln1-golden.json); the C payload is
// normative, so a mismatch here means the host adapter has to change, not the
// fixture.
#include <cctype>
#include <cstdint>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "fastlane/protocol.h"
#include "test_support.h"

#ifndef FLN1_GOLDEN_PATH
#error "FLN1_GOLDEN_PATH must point at protocol/fln1-golden.json"
#endif

namespace {

struct GoldenFrame {
    std::string name;
    std::string direction;
    int type = 0;
    int session_id = 0;
    std::vector<uint8_t> payload;
    std::vector<uint8_t> frame;
};

class Json {
public:
    explicit Json(std::string text) : text_(std::move(text)) {}

    void expect(char value) {
        skip();
        if (offset_ >= text_.size() || text_[offset_] != value) {
            throw std::runtime_error(std::string("expected '") + value + "'");
        }
        ++offset_;
    }

    char peek() {
        skip();
        if (offset_ >= text_.size()) {
            throw std::runtime_error("unexpected end of JSON");
        }
        return text_[offset_];
    }

    std::string string() {
        expect('"');
        std::string value;
        while (offset_ < text_.size()) {
            const char current = text_[offset_++];
            if (current == '"') {
                return value;
            }
            if (current == '\\' && offset_ < text_.size()) {
                const char escaped = text_[offset_++];
                value.push_back(escaped == 'n' ? '\n' : escaped);
            } else {
                value.push_back(current);
            }
        }
        throw std::runtime_error("unterminated string");
    }

    long number() {
        skip();
        const size_t start = offset_;
        while (offset_ < text_.size() &&
               (std::isdigit(static_cast<unsigned char>(text_[offset_])) ||
                text_[offset_] == '-')) {
            ++offset_;
        }
        if (start == offset_) {
            throw std::runtime_error("expected a number");
        }
        return std::stol(text_.substr(start, offset_ - start));
    }

    void skip_value() {
        const char next = peek();
        if (next == '"') {
            string();
        } else if (next == '{' || next == '[') {
            const char close = next == '{' ? '}' : ']';
            ++offset_;
            while (peek() != close) {
                skip_value();
                if (peek() == ',') {
                    ++offset_;
                }
            }
            expect(close);
        } else {
            number();
        }
    }

private:
    void skip() {
        while (offset_ < text_.size() &&
               std::isspace(static_cast<unsigned char>(text_[offset_]))) {
            ++offset_;
        }
    }

    std::string text_;
    size_t offset_ = 0;
};

uint8_t hex_digit(char value) {
    if (value >= '0' && value <= '9') {
        return static_cast<uint8_t>(value - '0');
    }
    if (value >= 'a' && value <= 'f') {
        return static_cast<uint8_t>(value - 'a' + 10);
    }
    if (value >= 'A' && value <= 'F') {
        return static_cast<uint8_t>(value - 'A' + 10);
    }
    throw std::runtime_error("invalid hex digit");
}

std::vector<uint8_t> decode_hex(const std::string& value) {
    if (value.size() % 2 != 0) {
        throw std::runtime_error("odd hex length");
    }
    std::vector<uint8_t> bytes(value.size() / 2);
    for (size_t index = 0; index < bytes.size(); ++index) {
        bytes[index] = static_cast<uint8_t>((hex_digit(value[index * 2]) << 4) |
                                            hex_digit(value[index * 2 + 1]));
    }
    return bytes;
}

std::string read_file(const std::string& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("cannot open " + path);
    }
    std::ostringstream buffer;
    buffer << input.rdbuf();
    return buffer.str();
}

GoldenFrame parse_frame(Json& json) {
    GoldenFrame frame;
    json.expect('{');
    for (;;) {
        const std::string key = json.string();
        json.expect(':');
        if (key == "name") {
            frame.name = json.string();
        } else if (key == "direction") {
            frame.direction = json.string();
        } else if (key == "type") {
            frame.type = static_cast<int>(json.number());
        } else if (key == "session_id") {
            frame.session_id = static_cast<int>(json.number());
        } else if (key == "payload_hex") {
            frame.payload = decode_hex(json.string());
        } else if (key == "frame_hex") {
            frame.frame = decode_hex(json.string());
        } else {
            json.skip_value();
        }
        if (json.peek() == ',') {
            json.expect(',');
            continue;
        }
        json.expect('}');
        return frame;
    }
}

std::vector<GoldenFrame> load_fixture() {
    const std::string text = read_file(FLN1_GOLDEN_PATH);
    Json json(text);
    std::vector<GoldenFrame> frames;
    bool protocol_checked = false;
    json.expect('{');
    for (;;) {
        const std::string key = json.string();
        json.expect(':');
        if (key == "protocol") {
            const std::string protocol = json.string();
            if (protocol != "FLN1") {
                throw std::runtime_error("fixture is not FLN1");
            }
            protocol_checked = true;
        } else if (key == "frames") {
            json.expect('[');
            for (;;) {
                frames.push_back(parse_frame(json));
                if (json.peek() == ',') {
                    json.expect(',');
                    continue;
                }
                json.expect(']');
                break;
            }
        } else {
            json.skip_value();
        }
        if (json.peek() == ',') {
            json.expect(',');
            continue;
        }
        json.expect('}');
        break;
    }
    if (!protocol_checked || frames.empty()) {
        throw std::runtime_error("incomplete fixture");
    }
    return frames;
}

const GoldenFrame* find(const std::vector<GoldenFrame>& frames, const std::string& name) {
    for (const GoldenFrame& frame : frames) {
        if (frame.name == name) {
            return &frame;
        }
    }
    return nullptr;
}

}  // namespace

FL_TEST(golden_fixture_decodes_and_reencodes_every_frame) {
    const std::vector<GoldenFrame> frames = load_fixture();
    FL_CHECK_EQ(frames.size(), static_cast<size_t>(16));
    for (const GoldenFrame& expected : frames) {
        fl::MemorySource source(expected.frame, 1);  // one byte per read
        fl::FrameReader reader(source);
        fl::Frame frame;
        FL_CHECK(reader.next(frame));
        FL_CHECK_EQ(static_cast<int>(frame.type), expected.type);
        FL_CHECK_EQ(frame.session_id, static_cast<uint32_t>(expected.session_id));
        FL_CHECK(frame.payload == expected.payload);
        const std::vector<uint8_t> encoded = fl::encode_frame(frame);
        FL_CHECK(encoded == expected.frame);
    }
}

FL_TEST(golden_fixture_matches_the_normative_payload_implementation) {
    const std::vector<GoldenFrame> frames = load_fixture();
    const GoldenFrame* hello = find(frames, "HELLO");
    const GoldenFrame* opened = find(frames, "PTY_OPENED");
    const GoldenFrame* exit = find(frames, "EXIT");
    const GoldenFrame* error = find(frames, "ERROR");
    FL_CHECK(hello != nullptr);
    FL_CHECK(opened != nullptr);
    FL_CHECK(exit != nullptr);
    FL_CHECK(error != nullptr);

    FL_CHECK_EQ(hello->frame[4], fl::kProtocolVersion);
    FL_CHECK_EQ(fl::kFrameHeaderSize, static_cast<size_t>(14));
    FL_CHECK_EQ(static_cast<int>(fl::FrameType::kPtyOpened), opened->type);
    FL_CHECK_EQ(static_cast<int>(fl::FrameType::kExit), exit->type);
    FL_CHECK_EQ(static_cast<int>(fl::FrameType::kError), error->type);
    FL_CHECK_EQ(opened->payload.size(), static_cast<size_t>(8));
    FL_CHECK_EQ(exit->payload.size(), static_cast<size_t>(5));
}
