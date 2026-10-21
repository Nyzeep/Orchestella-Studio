#include "fastlane/sha256.h"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

#include "fastlane/error.h"

namespace fl {
namespace {

constexpr uint32_t kRoundConstants[64] = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
};

uint32_t rotate_right(uint32_t value, unsigned int bits) {
    return (value >> bits) | (value << (32 - bits));
}

}  // namespace

Sha256::Sha256()
        : state_{0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
                 0x1f83d9ab, 0x5be0cd19} {}

void Sha256::compress(const uint8_t block[64]) {
    uint32_t schedule[64];
    for (size_t index = 0; index < 16; ++index) {
        schedule[index] = (static_cast<uint32_t>(block[index * 4]) << 24) |
                          (static_cast<uint32_t>(block[index * 4 + 1]) << 16) |
                          (static_cast<uint32_t>(block[index * 4 + 2]) << 8) |
                          static_cast<uint32_t>(block[index * 4 + 3]);
    }
    for (size_t index = 16; index < 64; ++index) {
        const uint32_t s0 = rotate_right(schedule[index - 15], 7) ^
                            rotate_right(schedule[index - 15], 18) ^
                            (schedule[index - 15] >> 3);
        const uint32_t s1 = rotate_right(schedule[index - 2], 17) ^
                            rotate_right(schedule[index - 2], 19) ^
                            (schedule[index - 2] >> 10);
        schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1;
    }

    uint32_t a = state_[0];
    uint32_t b = state_[1];
    uint32_t c = state_[2];
    uint32_t d = state_[3];
    uint32_t e = state_[4];
    uint32_t f = state_[5];
    uint32_t g = state_[6];
    uint32_t h = state_[7];

    for (size_t index = 0; index < 64; ++index) {
        const uint32_t s1 = rotate_right(e, 6) ^ rotate_right(e, 11) ^ rotate_right(e, 25);
        const uint32_t choose = (e & f) ^ ((~e) & g);
        const uint32_t temp1 = h + s1 + choose + kRoundConstants[index] + schedule[index];
        const uint32_t s0 = rotate_right(a, 2) ^ rotate_right(a, 13) ^ rotate_right(a, 22);
        const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
        const uint32_t temp2 = s0 + majority;

        h = g;
        g = f;
        f = e;
        e = d + temp1;
        d = c;
        c = b;
        b = a;
        a = temp1 + temp2;
    }

    state_[0] += a;
    state_[1] += b;
    state_[2] += c;
    state_[3] += d;
    state_[4] += e;
    state_[5] += f;
    state_[6] += g;
    state_[7] += h;
}

void Sha256::update(const void* data, size_t length) {
    if (finished_) {
        throw Error(Code::kInternal, "sha256 update after finish");
    }
    const auto* bytes = static_cast<const uint8_t*>(data);
    bit_length_ += static_cast<uint64_t>(length) * 8;
    size_t offset = 0;
    while (offset < length) {
        const size_t space = sizeof(buffer_) - buffer_length_;
        const size_t chunk = (length - offset < space) ? (length - offset) : space;
        std::memcpy(buffer_ + buffer_length_, bytes + offset, chunk);
        buffer_length_ += chunk;
        offset += chunk;
        if (buffer_length_ == sizeof(buffer_)) {
            compress(buffer_);
            buffer_length_ = 0;
        }
    }
}

Sha256Digest Sha256::finish() {
    if (finished_) {
        throw Error(Code::kInternal, "sha256 finished twice");
    }
    finished_ = true;

    const uint64_t bit_length = bit_length_;
    buffer_[buffer_length_++] = 0x80;
    if (buffer_length_ > 56) {
        std::memset(buffer_ + buffer_length_, 0, sizeof(buffer_) - buffer_length_);
        compress(buffer_);
        buffer_length_ = 0;
    }
    std::memset(buffer_ + buffer_length_, 0, 56 - buffer_length_);
    for (size_t index = 0; index < 8; ++index) {
        buffer_[56 + index] = static_cast<uint8_t>(bit_length >> (56 - index * 8));
    }
    compress(buffer_);

    Sha256Digest digest{};
    for (size_t index = 0; index < 8; ++index) {
        digest[index * 4] = static_cast<uint8_t>(state_[index] >> 24);
        digest[index * 4 + 1] = static_cast<uint8_t>(state_[index] >> 16);
        digest[index * 4 + 2] = static_cast<uint8_t>(state_[index] >> 8);
        digest[index * 4 + 3] = static_cast<uint8_t>(state_[index]);
    }
    return digest;
}

Sha256Digest Sha256::digest(const void* data, size_t length) {
    Sha256 hasher;
    hasher.update(data, length);
    return hasher.finish();
}

std::string to_hex(const Sha256Digest& digest) {
    static const char kDigits[] = "0123456789abcdef";
    std::string hex;
    hex.reserve(64);
    for (const uint8_t byte : digest) {
        hex.push_back(kDigits[byte >> 4]);
        hex.push_back(kDigits[byte & 0x0F]);
    }
    return hex;
}

bool parse_hex_32(const std::string& hex, Sha256Digest* digest) {
    if (hex.size() != 64 || digest == nullptr) {
        return false;
    }
    auto value_of = [](char character) -> int {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    };
    for (size_t index = 0; index < 32; ++index) {
        const int high = value_of(hex[index * 2]);
        const int low = value_of(hex[index * 2 + 1]);
        if (high < 0 || low < 0) {
            return false;
        }
        (*digest)[index] = static_cast<uint8_t>((high << 4) | low);
    }
    return true;
}

Sha256Digest sha256_file(const std::string& path, uint64_t* size_out) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        throw Error(Code::kIo, std::string("open failed: ") + std::strerror(errno));
    }
    Sha256 hasher;
    uint64_t total = 0;
    uint8_t buffer[65536];
    for (;;) {
        const ssize_t read = ::read(fd, buffer, sizeof(buffer));
        if (read > 0) {
            hasher.update(buffer, static_cast<size_t>(read));
            total += static_cast<uint64_t>(read);
            continue;
        }
        if (read == 0) {
            break;
        }
        if (errno == EINTR) {
            continue;
        }
        const int saved = errno;
        ::close(fd);
        throw Error(Code::kIo, std::string("read failed: ") + std::strerror(saved));
    }
    ::close(fd);
    if (size_out != nullptr) {
        *size_out = total;
    }
    return hasher.finish();
}

}  // namespace fl
