#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "fastlane/sha256.h"

namespace fl {

constexpr const char* kLayoutMarkerName = ".fastlane-layout";
constexpr int kLayoutVersion = 1;

/**
 * Canonical encrypted-storage layout. The marker is `.fastlane-layout` with a
 * single line `version=1`. Initialization is idempotent and refuses symlinked
 * roots, subdirectories, or marker files.
 */
class StorageLayout {
public:
    static StorageLayout initialize(const std::string& root);

    const std::string& root() const { return root_; }
    std::string home() const;
    std::string workspace() const;
    std::string tmp() const;
    std::string cache() const;
    std::string exports() const;

    /** Lexical resolution only; rejects absolute/traversal paths. */
    std::string resolve(const std::string& relative) const;

    /** Resolution plus lstat on every component; any symlink is rejected. */
    std::string resolve_no_symlink(const std::string& relative) const;

private:
    explicit StorageLayout(std::string root) : root_(std::move(root)) {}
    std::string root_;
};

struct ExportResult {
    uint64_t files = 0;
    uint64_t bytes = 0;
    uint64_t skipped = 0;
};

/**
 * Streams a tar of the named roots (currently `home` and `workspace`) to
 * output_fd. Sockets, fifos, devices, denied credential names, and symlinks are
 * not exported; symlinks are a hard PATH_REJECTED because silently following
 * them would escape the export root.
 */
ExportResult export_tar(const StorageLayout& layout, const std::vector<std::string>& roots,
                        int output_fd);

struct ImportResult {
    uint64_t files = 0;
    uint64_t bytes = 0;
};

/**
 * Verifies the tar file size and SHA-256, extracts into a staging directory,
 * then swaps only `home` and `workspace` into place. Any failure before or
 * during the swap restores the previous trees.
 */
ImportResult import_tar(const StorageLayout& layout, const std::string& tar_path,
                        uint64_t expected_size, const Sha256Digest& expected_sha256);

/** Default deny policy for names that must never leave the encrypted store. */
bool is_denied_export_name(const std::string& name);

}  // namespace fl
