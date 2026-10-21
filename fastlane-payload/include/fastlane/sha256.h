#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>

namespace fl {

using Sha256Digest = std::array<uint8_t, 32>;

class Sha256 {
public:
    Sha256();
    void update(const void* data, size_t length);
    Sha256Digest finish();
    static Sha256Digest digest(const void* data, size_t length);

private:
    void compress(const uint8_t block[64]);

    uint32_t state_[8];
    uint64_t bit_length_ = 0;
    uint8_t buffer_[64];
    size_t buffer_length_ = 0;
    bool finished_ = false;
};

std::string to_hex(const Sha256Digest& digest);
bool parse_hex_32(const std::string& hex, Sha256Digest* digest);
Sha256Digest sha256_file(const std::string& path, uint64_t* size_out);

}  // namespace fl
