#include "test_support.h"

#include <string>

#include "fastlane/sha256.h"

FL_TEST(sha256_known_vectors) {
    const auto empty = fl::Sha256::digest("", 0);
    FL_CHECK_EQ(fl::to_hex(empty),
                std::string("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

    const std::string abc = "abc";
    const auto abc_digest = fl::Sha256::digest(abc.data(), abc.size());
    FL_CHECK_EQ(fl::to_hex(abc_digest),
                std::string("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));

    const std::string quick = "The quick brown fox jumps over the lazy dog";
    const auto quick_digest = fl::Sha256::digest(quick.data(), quick.size());
    FL_CHECK_EQ(fl::to_hex(quick_digest),
                std::string("d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"));
}

FL_TEST(sha256_streaming_matches_one_shot) {
    std::string payload;
    for (int index = 0; index < 5000; ++index) {
        payload.push_back(static_cast<char>(index % 251));
    }
    const auto one_shot = fl::Sha256::digest(payload.data(), payload.size());

    fl::Sha256 streaming;
    size_t offset = 0;
    size_t chunk = 1;
    while (offset < payload.size()) {
        const size_t length = std::min(chunk, payload.size() - offset);
        streaming.update(payload.data() + offset, length);
        offset += length;
        chunk = (chunk * 7 + 3) % 1031 + 1;
    }
    FL_CHECK_EQ(fl::to_hex(streaming.finish()), fl::to_hex(one_shot));
}

FL_TEST(sha256_hex_round_trip) {
    fl::Sha256Digest digest{};
    for (size_t index = 0; index < digest.size(); ++index) {
        digest[index] = static_cast<uint8_t>(index * 7);
    }
    const std::string hex = fl::to_hex(digest);
    FL_CHECK_EQ(hex.size(), 64u);
    fl::Sha256Digest parsed{};
    FL_CHECK(fl::parse_hex_32(hex, &parsed));
    FL_CHECK(parsed == digest);
    FL_CHECK(!fl::parse_hex_32("zz", &parsed));
}

FL_TEST(sha256_file_matches_memory) {
    const std::string path = "/tmp/fastlane-sha256-test.bin";
    {
        FILE* file = std::fopen(path.c_str(), "wb");
        FL_CHECK(file != nullptr);
        const std::string data = "payload bytes";
        FL_CHECK_EQ(std::fwrite(data.data(), 1, data.size(), file), data.size());
        std::fclose(file);
    }
    uint64_t size = 0;
    const auto from_file = fl::sha256_file(path, &size);
    const std::string data = "payload bytes";
    FL_CHECK_EQ(size, static_cast<uint64_t>(data.size()));
    FL_CHECK_EQ(fl::to_hex(from_file), fl::to_hex(fl::Sha256::digest(data.data(), data.size())));
    std::remove(path.c_str());
}
