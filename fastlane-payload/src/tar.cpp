#include "fastlane/tar.h"

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <unistd.h>

#include "fastlane/error.h"
#include "fastlane/paths.h"
#include "fastlane/protocol.h"

namespace fl {
namespace {

constexpr size_t kBlockSize = 512;

void put_octal(char* field, size_t width, uint64_t value) {
    const uint64_t limit = (1ULL << (3 * (width - 1))) - 1;
    if (value > limit) {
        throw Error(Code::kLimitExceeded, "tar numeric field does not fit");
    }
    std::snprintf(field, width, "%0*llo", static_cast<int>(width - 1),
                  static_cast<unsigned long long>(value));
}

bool parse_octal(const char* field, size_t width, uint64_t* value) {
    size_t index = 0;
    while (index < width && (field[index] == ' ' || field[index] == '\0')) {
        ++index;
    }
    uint64_t result = 0;
    bool saw_digit = false;
    for (; index < width; ++index) {
        const char character = field[index];
        if (character == '\0' || character == ' ') {
            break;
        }
        if (character < '0' || character > '7') {
            return false;
        }
        result = (result << 3) | static_cast<uint64_t>(character - '0');
        saw_digit = true;
    }
    if (!saw_digit) {
        return false;
    }
    *value = result;
    return true;
}

void write_zeros(int fd, uint64_t length) {
    static const uint8_t zeros[kBlockSize] = {};
    while (length > 0) {
        const size_t chunk = static_cast<size_t>(std::min<uint64_t>(length, kBlockSize));
        write_all(fd, zeros, chunk);
        length -= chunk;
    }
}

uint64_t padding_for(uint64_t size) {
    return (kBlockSize - (size % kBlockSize)) % kBlockSize;
}

bool needs_pax(const std::string& path) {
    if (path.size() > 100) {
        return true;
    }
    for (const char character : path) {
        if (static_cast<unsigned char>(character) >= 0x80) {
            return true;
        }
    }
    return false;
}

std::string pax_record(const std::string& key, const std::string& value) {
    size_t length = key.size() + value.size() + 4;
    for (;;) {
        const std::string digits = std::to_string(length);
        const size_t computed = digits.size() + key.size() + value.size() + 3;
        if (computed == length) {
            return digits + " " + key + "=" + value + "\n";
        }
        length = computed;
    }
}

std::string fallback_name(const std::string& path) {
    if (path.size() <= 100) {
        return path;
    }
    std::string name = path.substr(path.size() - 100);
    while (!name.empty() && name.front() == '/') {
        name.erase(name.begin());
    }
    return name;
}

void split_ustar_path(const std::string& path, std::string* prefix, std::string* name) {
    *prefix = "";
    *name = path;
    if (path.size() <= 100) {
        return;
    }
    for (size_t index = path.size(); index > 0; --index) {
        if (path[index - 1] != '/') {
            continue;
        }
        const size_t prefix_length = index - 1;
        const size_t name_length = path.size() - index;
        if (prefix_length <= 155 && name_length <= 100 && name_length > 0) {
            *prefix = path.substr(0, prefix_length);
            *name = path.substr(index);
            return;
        }
    }
    *name = fallback_name(path);
}

void write_header_block(int fd, const std::string& path, char type, uint64_t size, uint32_t mode,
                        int64_t mtime) {
    std::string prefix;
    std::string name;
    split_ustar_path(path, &prefix, &name);

    uint8_t block[kBlockSize] = {};
    std::memcpy(block, name.data(), std::min<size_t>(name.size(), 100));
    put_octal(reinterpret_cast<char*>(block + 100), 8, mode & 07777);
    put_octal(reinterpret_cast<char*>(block + 108), 8, 0);
    put_octal(reinterpret_cast<char*>(block + 116), 8, 0);
    put_octal(reinterpret_cast<char*>(block + 124), 12, size);
    put_octal(reinterpret_cast<char*>(block + 136), 12,
              mtime < 0 ? 0ULL : static_cast<uint64_t>(mtime));
    std::memset(block + 148, ' ', 8);
    block[156] = static_cast<uint8_t>(type);
    std::memcpy(block + 257, "ustar", 5);
    std::memcpy(block + 263, "00", 2);
    std::memcpy(block + 345, prefix.data(), std::min<size_t>(prefix.size(), 155));

    unsigned int sum = 0;
    for (const uint8_t byte : block) {
        sum += byte;
    }
    char checksum[8];
    std::snprintf(checksum, sizeof(checksum), "%06o", sum);
    checksum[6] = '\0';
    checksum[7] = ' ';
    std::memcpy(block + 148, checksum, 8);
    write_all(fd, block, sizeof(block));
}

void read_exact_fd(int fd, void* destination, size_t length) {
    auto* bytes = static_cast<uint8_t*>(destination);
    size_t offset = 0;
    while (offset < length) {
        const ssize_t read = ::read(fd, bytes + offset, length - offset);
        if (read > 0) {
            offset += static_cast<size_t>(read);
            continue;
        }
        if (read == 0) {
            throw Error(Code::kExportIo, "truncated tar");
        }
        if (errno == EINTR) {
            continue;
        }
        throw Error(Code::kIo, std::string("tar read failed: ") + std::strerror(errno));
    }
}

void skip_exact_fd(int fd, uint64_t length) {
    uint8_t buffer[4096];
    while (length > 0) {
        const size_t chunk = static_cast<size_t>(std::min<uint64_t>(length, sizeof(buffer)));
        read_exact_fd(fd, buffer, chunk);
        length -= chunk;
    }
}

bool is_zero_block(const uint8_t* block) {
    for (size_t index = 0; index < kBlockSize; ++index) {
        if (block[index] != 0) {
            return false;
        }
    }
    return true;
}

void parse_pax_records(const std::string& data, std::string* path, uint64_t* size,
                       bool* has_size) {
    size_t offset = 0;
    while (offset < data.size()) {
        const size_t space = data.find(' ', offset);
        if (space == std::string::npos || space == offset) {
            throw Error(Code::kExportIo, "malformed pax header");
        }
        uint64_t length = 0;
        for (size_t index = offset; index < space; ++index) {
            const char character = data[index];
            if (character < '0' || character > '9') {
                throw Error(Code::kExportIo, "malformed pax length");
            }
            const uint64_t digit = static_cast<uint64_t>(character - '0');
            if (length > (UINT64_MAX - digit) / 10) {
                throw Error(Code::kExportIo, "pax record length overflow");
            }
            length = length * 10 + digit;
        }
        if (length == 0 || length > data.size() - offset) {
            throw Error(Code::kExportIo, "malformed pax record");
        }
        if (length <= space - offset + 1) {
            throw Error(Code::kExportIo, "empty pax record");
        }
        std::string record = data.substr(space + 1, offset + length - space - 1);
        if (!record.empty() && record.back() == '\n') {
            record.pop_back();
        }
        const size_t equals = record.find('=');
        if (equals != std::string::npos) {
            const std::string key = record.substr(0, equals);
            const std::string value = record.substr(equals + 1);
            if (key == "path") {
                *path = value;
            } else if (key == "size") {
                uint64_t parsed = 0;
                if (value.empty()) {
                    throw Error(Code::kExportIo, "malformed pax size");
                }
                for (const char character : value) {
                    if (character < '0' || character > '9') {
                        throw Error(Code::kExportIo, "malformed pax size");
                    }
                    const uint64_t digit = static_cast<uint64_t>(character - '0');
                    if (parsed > (UINT64_MAX - digit) / 10) {
                        throw Error(Code::kExportIo, "pax size overflow");
                    }
                    parsed = parsed * 10 + digit;
                }
                *size = parsed;
                *has_size = true;
            }
        }
        offset += length;
    }
}

}  // namespace

void TarWriter::add_directory(const std::string& path, uint32_t mode, int64_t mtime) {
    std::string directory = path;
    while (!directory.empty() && directory.back() == '/') {
        directory.pop_back();
    }
    if (!is_safe_relative_path(directory)) {
        throw Error(Code::kPathRejected, "unsafe tar path");
    }
    directory.push_back('/');
    emit_header(directory, '5', 0, mode, mtime);
}

void TarWriter::add_file(const std::string& path, int data_fd, uint64_t size, uint32_t mode,
                         int64_t mtime) {
    if (!is_safe_relative_path(path)) {
        throw Error(Code::kPathRejected, "unsafe tar path");
    }
    emit_header(path, '0', size, mode, mtime);
    uint8_t buffer[65536];
    uint64_t remaining = size;
    while (remaining > 0) {
        const size_t chunk = static_cast<size_t>(std::min<uint64_t>(remaining, sizeof(buffer)));
        const ssize_t read = ::read(data_fd, buffer, chunk);
        if (read > 0) {
            write_all(fd_, buffer, static_cast<size_t>(read));
            remaining -= static_cast<uint64_t>(read);
            continue;
        }
        if (read == 0) {
            throw Error(Code::kExportIo, "file changed while exporting");
        }
        if (errno == EINTR) {
            continue;
        }
        throw Error(Code::kIo, std::string("tar source read failed: ") + std::strerror(errno));
    }
    write_zeros(fd_, padding_for(size));
}

void TarWriter::emit_pax_path(const std::string& path) {
    const std::string record = pax_record("path", path);
    std::string header_name = "PaxHeaders/" + base_name(path);
    if (header_name.size() > 100) {
        header_name.resize(100);
    }
    write_header_block(fd_, header_name, 'x', record.size(), 0644, 0);
    write_all(fd_, record.data(), record.size());
    write_zeros(fd_, padding_for(record.size()));
}

void TarWriter::emit_header(const std::string& path, char type, uint64_t size, uint32_t mode,
                            int64_t mtime) {
    if (needs_pax(path)) {
        emit_pax_path(path);
        write_header_block(fd_, fallback_name(path), type, size, mode, mtime);
        return;
    }
    write_header_block(fd_, path, type, size, mode, mtime);
}

void TarWriter::finish() {
    if (finished_) {
        return;
    }
    write_zeros(fd_, 2 * kBlockSize);
    finished_ = true;
}

bool TarReader::next(TarEntry* entry) {
    if (finished_) {
        return false;
    }
    if (remaining_ > 0 || entry_size_ > 0) {
        finish_entry();
    }

    std::string pax_path;
    std::string long_name;
    uint64_t pax_size = 0;
    bool has_pax_size = false;

    for (;;) {
        uint8_t block[kBlockSize];
        read_exact(block, sizeof(block));
        if (is_zero_block(block)) {
            uint8_t second[kBlockSize];
            read_exact(second, sizeof(second));
            if (!is_zero_block(second)) {
                throw Error(Code::kExportIo, "malformed tar terminator");
            }
            finished_ = true;
            return false;
        }

        uint64_t stored_checksum = 0;
        if (!parse_octal(reinterpret_cast<const char*>(block + 148), 8, &stored_checksum)) {
            throw Error(Code::kExportIo, "malformed tar checksum");
        }
        unsigned int computed = 0;
        for (size_t index = 0; index < kBlockSize; ++index) {
            const uint8_t byte = (index >= 148 && index < 156) ? static_cast<uint8_t>(' ')
                                                               : block[index];
            computed += byte;
        }
        if (computed != stored_checksum) {
            throw Error(Code::kExportIo, "tar checksum mismatch");
        }

        uint64_t size = 0;
        if (!parse_octal(reinterpret_cast<const char*>(block + 124), 12, &size)) {
            throw Error(Code::kExportIo, "malformed tar size");
        }
        if (size > kMaxTarEntryBytes) {
            throw Error(Code::kLimitExceeded, "tar entry too large");
        }

        const char type = static_cast<char>(block[156]);
        if (type == 'x' || type == 'L') {
            if (size > 1024 * 1024) {
                throw Error(Code::kLimitExceeded, "tar metadata entry too large");
            }
            std::string data(static_cast<size_t>(size), '\0');
            read_exact(data.data(), data.size());
            skip_exact(padding_for(size));
            if (type == 'x') {
                parse_pax_records(data, &pax_path, &pax_size, &has_pax_size);
            } else {
                long_name = data;
            }
            continue;
        }

        std::string name(reinterpret_cast<const char*>(block),
                         strnlen(reinterpret_cast<const char*>(block), 100));
        std::string prefix(reinterpret_cast<const char*>(block + 345),
                           strnlen(reinterpret_cast<const char*>(block + 345), 155));
        std::string path = pax_path.empty()
                                   ? (prefix.empty() ? name : prefix + "/" + name)
                                   : pax_path;
        if (!long_name.empty()) {
            path = long_name;
        }
        if (has_pax_size) {
            size = pax_size;
            if (size > kMaxTarEntryBytes) {
                throw Error(Code::kLimitExceeded, "tar entry too large");
            }
        }
        const bool is_directory = type == '5';
        if (is_directory) {
            while (!path.empty() && path.back() == '/') {
                path.pop_back();
            }
        }
        if (!is_safe_relative_path(path)) {
            throw Error(Code::kPathRejected, "unsafe path in tar");
        }
        if (type == '1' || type == '2' || type == '3' || type == '4' || type == '6') {
            throw Error(Code::kPathRejected, "links and devices are not allowed in a tar import");
        }
        if (type != '0' && type != '\0' && type != '5') {
            throw Error(Code::kUnsupportedFormat, "unsupported tar entry type");
        }

        if (is_directory && size != 0) {
            // A directory with a body is malformed; consume it rather than trust it.
            skip_exact(size);
            skip_exact(padding_for(size));
            size = 0;
        }

        entry->path = path;
        entry->type = is_directory ? '5' : '0';
        entry->size = size;
        entry->mode = 0600;
        entry->mtime = 0;
        remaining_ = size;
        entry_size_ = size;
        return true;
    }
}

size_t TarReader::read_data(void* destination, size_t capacity) {
    if (remaining_ == 0 || capacity == 0) {
        return 0;
    }
    const size_t chunk = static_cast<size_t>(std::min<uint64_t>(remaining_, capacity));
    read_exact(destination, chunk);
    remaining_ -= chunk;
    return chunk;
}

void TarReader::read_exact(void* destination, size_t length) {
    read_exact_fd(fd_, destination, length);
}

void TarReader::skip_exact(uint64_t length) {
    skip_exact_fd(fd_, length);
}

void TarReader::finish_entry() {
    if (remaining_ > 0) {
        skip_exact(remaining_);
        remaining_ = 0;
    }
    skip_exact(padding_for(entry_size_));
    entry_size_ = 0;
}

}  // namespace fl
