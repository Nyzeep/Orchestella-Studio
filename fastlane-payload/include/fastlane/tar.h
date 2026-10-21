#pragma once

#include <cstdint>
#include <string>

namespace fl {

struct TarEntry {
    std::string path;
    char type = '0';  // '0' regular file, '5' directory
    uint64_t size = 0;
    uint32_t mode = 0644;
    int64_t mtime = 0;
};

/**
 * Streaming ustar/PAX writer. Paths that do not fit ustar's 100+155 byte split,
 * or that contain non-ASCII bytes, get a PAX 'path' header. Symlinks, devices,
 * and fifos cannot be written; callers must reject them before this layer.
 */
class TarWriter {
public:
    explicit TarWriter(int fd) : fd_(fd) {}

    void add_directory(const std::string& path, uint32_t mode, int64_t mtime);
    void add_file(const std::string& path, int data_fd, uint64_t size, uint32_t mode,
                  int64_t mtime);
    void finish();

private:
    void emit_header(const std::string& path, char type, uint64_t size, uint32_t mode,
                     int64_t mtime);
    void emit_pax_path(const std::string& path);

    int fd_;
    bool finished_ = false;
};

/**
 * Streaming ustar/PAX reader with validation: absolute paths, `..`, empty
 * components, hardlinks, symlinks, devices and fifos are rejected. A truncated
 * entry is an error, never a short read.
 */
class TarReader {
public:
    explicit TarReader(int fd) : fd_(fd) {}

    /** Advances to the next entry; false after the two zero blocks. */
    bool next(TarEntry* entry);

    /** Copies up to capacity bytes of the current entry's data. */
    size_t read_data(void* destination, size_t capacity);

    /** Number of unread data bytes in the current entry. */
    uint64_t remaining() const { return remaining_; }

private:
    void read_exact(void* destination, size_t length);
    void skip_exact(uint64_t length);
    void finish_entry();

    int fd_;
    uint64_t remaining_ = 0;
    uint64_t entry_size_ = 0;
    bool finished_ = false;
};

}  // namespace fl
