#include "fastlane/storage.h"

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <set>
#include <string>
#include <vector>

#include "fastlane/error.h"
#include "fastlane/paths.h"
#include "fastlane/protocol.h"
#include "fastlane/tar.h"

namespace fl {
namespace {

constexpr uint64_t kMaxImportFiles = 100000;
constexpr uint64_t kMaxImportBytes = 16ULL * 1024 * 1024 * 1024;
constexpr uint64_t kMaxExportBytes = 16ULL * 1024 * 1024 * 1024;

struct Stat {
    struct stat value {};
    bool exists = false;
};

Stat lstat_path(const std::string& path) {
    Stat result;
    if (::lstat(path.c_str(), &result.value) == 0) {
        result.exists = true;
        return result;
    }
    if (errno != ENOENT && errno != ENOTDIR) {
        throw Error(Code::kIo, std::string("lstat failed: ") + std::strerror(errno));
    }
    return result;
}

void ensure_plain_directory(const std::string& path) {
    const Stat info = lstat_path(path);
    if (!info.exists) {
        return;
    }
    if (S_ISLNK(info.value.st_mode)) {
        throw Error(Code::kPathRejected, "refusing symlinked directory");
    }
    if (!S_ISDIR(info.value.st_mode)) {
        throw Error(Code::kLayoutInvalid, "path is not a directory");
    }
}

void ensure_directory(const std::string& path, mode_t mode) {
    const Stat info = lstat_path(path);
    if (info.exists) {
        if (S_ISLNK(info.value.st_mode)) {
            throw Error(Code::kPathRejected, "refusing symlinked directory");
        }
        if (!S_ISDIR(info.value.st_mode)) {
            throw Error(Code::kLayoutInvalid, "path is not a directory");
        }
        return;
    }
    const std::string parent = parent_of(path);
    if (!parent.empty() && parent != "/") {
        ensure_directory(parent, mode);
    }
    if (::mkdir(path.c_str(), mode) != 0 && errno != EEXIST) {
        throw Error(Code::kIo, std::string("mkdir failed: ") + std::strerror(errno));
    }
    ensure_plain_directory(path);
}

std::string read_file_limited(const std::string& path, size_t limit) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        throw Error(Code::kIo, std::string("open failed: ") + std::strerror(errno));
    }
    std::string content;
    char buffer[512];
    for (;;) {
        const ssize_t read = ::read(fd, buffer, sizeof(buffer));
        if (read > 0) {
            content.append(buffer, static_cast<size_t>(read));
            if (content.size() > limit) {
                ::close(fd);
                throw Error(Code::kLayoutInvalid, "layout marker is too large");
            }
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
    return content;
}

void write_marker(const std::string& path) {
    const std::string temporary = path + ".tmp." + std::to_string(::getpid());
    const int fd = ::open(temporary.c_str(),
                          O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) {
        throw Error(Code::kIo, std::string("marker create failed: ") + std::strerror(errno));
    }
    static const char kContents[] = "version=1\n";
    try {
        write_all(fd, kContents, sizeof(kContents) - 1);
        if (::fsync(fd) != 0) {
            throw Error(Code::kIo, std::string("marker fsync failed: ") + std::strerror(errno));
        }
        ::close(fd);
    } catch (...) {
        ::close(fd);
        ::unlink(temporary.c_str());
        throw;
    }
    if (::rename(temporary.c_str(), path.c_str()) != 0) {
        const int saved = errno;
        ::unlink(temporary.c_str());
        throw Error(Code::kIo, std::string("marker rename failed: ") + std::strerror(saved));
    }
}

std::vector<std::string> list_directory(const std::string& path) {
    DIR* directory = ::opendir(path.c_str());
    if (directory == nullptr) {
        throw Error(Code::kIo, std::string("opendir failed: ") + std::strerror(errno));
    }
    std::vector<std::string> names;
    while (dirent* entry = ::readdir(directory)) {
        const std::string name = entry->d_name;
        if (name == "." || name == "..") {
            continue;
        }
        names.push_back(name);
    }
    ::closedir(directory);
    std::sort(names.begin(), names.end());
    return names;
}

bool ends_with(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

bool starts_with(const std::string& value, const std::string& prefix) {
    return value.size() >= prefix.size() && value.compare(0, prefix.size(), prefix) == 0;
}

struct ExportState {
    ExportResult result;
    uint64_t total = 0;
};

void walk_export(const std::string& absolute, const std::string& relative, TarWriter* writer,
                 ExportState* state);

void export_file(const std::string& absolute, const std::string& relative, const struct stat& info,
                 TarWriter* writer, ExportState* state) {
    if (is_denied_export_name(base_name(relative))) {
        ++state->result.skipped;
        return;
    }
    const uint64_t file_size = static_cast<uint64_t>(info.st_size);
    if (file_size > kMaxTarEntryBytes) {
        throw Error(Code::kLimitExceeded, "file is too large for a ustar entry");
    }
    if (file_size > kMaxExportBytes - state->total) {
        throw Error(Code::kLimitExceeded, "export exceeds the size limit");
    }
    const int fd = ::open(absolute.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) {
        throw Error(Code::kIo, std::string("export open failed: ") + std::strerror(errno));
    }
    struct stat opened {};
    if (::fstat(fd, &opened) != 0 || !S_ISREG(opened.st_mode) || opened.st_ino != info.st_ino ||
        opened.st_dev != info.st_dev) {
        ::close(fd);
        throw Error(Code::kPathRejected, "file changed or is not a regular file");
    }
    try {
        writer->add_file(relative, fd, static_cast<uint64_t>(opened.st_size),
                         static_cast<uint32_t>(opened.st_mode & 0777),
                         static_cast<int64_t>(opened.st_mtime));
    } catch (...) {
        ::close(fd);
        throw;
    }
    ::close(fd);
    ++state->result.files;
    state->result.bytes += static_cast<uint64_t>(opened.st_size);
    state->total += static_cast<uint64_t>(opened.st_size);
}

void walk_export(const std::string& absolute, const std::string& relative, TarWriter* writer,
                 ExportState* state) {
    for (const std::string& name : list_directory(absolute)) {
        const std::string child_absolute = absolute + "/" + name;
        const std::string child_relative = relative + "/" + name;
        const Stat info = lstat_path(child_absolute);
        if (!info.exists) {
            continue;
        }
        if (S_ISLNK(info.value.st_mode)) {
            throw Error(Code::kPathRejected, "symlink in export tree");
        }
        if (S_ISDIR(info.value.st_mode)) {
            writer->add_directory(child_relative, 0755, static_cast<int64_t>(info.value.st_mtime));
            walk_export(child_absolute, child_relative, writer, state);
            continue;
        }
        if (S_ISREG(info.value.st_mode)) {
            export_file(child_absolute, child_relative, info.value, writer, state);
            continue;
        }
        ++state->result.skipped;
    }
}

void remove_tree(const std::string& path) {
    std::error_code ignored;
    std::filesystem::remove_all(path, ignored);
}

std::string make_staging(const std::string& parent, const std::string& prefix) {
    std::string pattern = parent + "/" + prefix + "XXXXXX";
    std::vector<char> buffer(pattern.begin(), pattern.end());
    buffer.push_back('\0');
    char* created = ::mkdtemp(buffer.data());
    if (created == nullptr) {
        throw Error(Code::kIo, std::string("mkdtemp failed: ") + std::strerror(errno));
    }
    return created;
}

void ensure_parent_directory(const std::string& path) {
    const std::string parent = parent_of(path);
    if (!parent.empty()) {
        ensure_directory(parent, 0700);
    }
}

struct Imported {
    std::string name;
    std::string backup;
    bool installed = false;
    bool had_original = false;
};

void rollback(std::vector<Imported>* imported, const std::string& root) {
    for (auto iterator = imported->rbegin(); iterator != imported->rend(); ++iterator) {
        const std::string target = root + "/" + iterator->name;
        if (iterator->installed) {
            remove_tree(target);
        }
        if (iterator->had_original) {
            std::error_code error;
            std::filesystem::rename(iterator->backup, target, error);
        }
    }
}

}  // namespace

bool is_denied_export_name(const std::string& name) {
    std::string lowered = name;
    std::transform(lowered.begin(), lowered.end(), lowered.begin(), [](unsigned char character) {
        return static_cast<char>(std::tolower(character));
    });
    static const char* kExact[] = {"id_rsa",   "id_ed25519", "id_ecdsa",       "id_dsa",
                                   "token",    ".fastlane-token", ".netrc",   ".git-credentials"};
    for (const char* denied : kExact) {
        if (lowered == denied) {
            return true;
        }
    }
    if (starts_with(lowered, "id_rsa") || starts_with(lowered, "id_ed25519")) {
        return true;
    }
    static const char* kSuffixes[] = {".sock", ".pid",  ".pem",     ".key",      ".p12",
                                      ".jks",  ".keystore", ".token"};
    for (const char* suffix : kSuffixes) {
        if (ends_with(lowered, suffix)) {
            return true;
        }
    }
    return false;
}

StorageLayout StorageLayout::initialize(const std::string& root) {
    if (root.empty() || root.front() != '/') {
        throw Error(Code::kLayoutInvalid, "storage root must be absolute");
    }
    std::string normalized = root;
    while (normalized.size() > 1 && normalized.back() == '/') {
        normalized.pop_back();
    }
    ensure_directory(normalized, 0700);
    StorageLayout layout(normalized);
    for (const char* name : {"home", "workspace", "tmp", "cache", "exports"}) {
        ensure_directory(layout.root_ + "/" + name, 0700);
    }

    const std::string marker = layout.root_ + "/" + kLayoutMarkerName;
    const Stat info = lstat_path(marker);
    if (info.exists) {
        if (S_ISLNK(info.value.st_mode)) {
            throw Error(Code::kPathRejected, "layout marker must not be a symlink");
        }
        if (!S_ISREG(info.value.st_mode)) {
            throw Error(Code::kLayoutInvalid, "layout marker is not a regular file");
        }
        const std::string content = read_file_limited(marker, 64);
        if (content != "version=1\n") {
            throw Error(Code::kLayoutInvalid, "unsupported layout version");
        }
    } else {
        write_marker(marker);
    }
    return layout;
}

std::string StorageLayout::home() const {
    return root_ + "/home";
}

std::string StorageLayout::workspace() const {
    return root_ + "/workspace";
}

std::string StorageLayout::tmp() const {
    return root_ + "/tmp";
}

std::string StorageLayout::cache() const {
    return root_ + "/cache";
}

std::string StorageLayout::exports() const {
    return root_ + "/exports";
}

std::string StorageLayout::resolve(const std::string& relative) const {
    if (!is_safe_relative_path(relative)) {
        throw Error(Code::kPathRejected, "unsafe relative path");
    }
    const std::string path = join_path(root_, relative);
    if (!is_within(root_, path)) {
        throw Error(Code::kPathRejected, "path escapes the storage root");
    }
    return path;
}

std::string StorageLayout::resolve_no_symlink(const std::string& relative) const {
    const std::string path = resolve(relative);
    const std::vector<std::string> components = split_relative_path(relative);
    std::string current = root_;
    for (const std::string& component : components) {
        current += "/" + component;
        const Stat info = lstat_path(current);
        if (!info.exists) {
            throw Error(Code::kPathRejected, "path does not exist");
        }
        if (S_ISLNK(info.value.st_mode)) {
            throw Error(Code::kPathRejected, "symlink in storage path");
        }
    }
    return path;
}

ExportResult export_tar(const StorageLayout& layout, const std::vector<std::string>& roots,
                        int output_fd) {
    if (roots.empty()) {
        throw Error(Code::kPathRejected, "no export roots");
    }
    std::vector<std::string> sorted = roots;
    std::sort(sorted.begin(), sorted.end());
    for (const std::string& root : sorted) {
        if (root != "home" && root != "workspace") {
            throw Error(Code::kPathRejected, "export root is not allowed");
        }
    }

    TarWriter writer(output_fd);
    ExportState state;
    for (const std::string& root : sorted) {
        const std::string absolute = layout.resolve_no_symlink(root);
        const Stat info = lstat_path(absolute);
        if (!S_ISDIR(info.value.st_mode)) {
            throw Error(Code::kPathRejected, "export root is not a directory");
        }
        writer.add_directory(root, 0755, static_cast<int64_t>(info.value.st_mtime));
        walk_export(absolute, root, &writer, &state);
    }
    writer.finish();
    return state.result;
}

ImportResult import_tar(const StorageLayout& layout, const std::string& tar_path,
                        uint64_t expected_size, const Sha256Digest& expected_sha256) {
    uint64_t actual_size = 0;
    const Sha256Digest actual = sha256_file(tar_path, &actual_size);
    if (actual_size != expected_size || actual != expected_sha256) {
        throw Error(Code::kExportIo, "import archive integrity check failed");
    }

    const std::string staging = make_staging(layout.tmp(), ".import-");
    std::string backup_root;
    try {
        backup_root = make_staging(layout.tmp(), ".backup-");
    } catch (...) {
        remove_tree(staging);
        throw;
    }
    ImportResult result;
    std::vector<Imported> imported;
    bool success = false;

    try {
        const int fd = ::open(tar_path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) {
            throw Error(Code::kIo, std::string("import open failed: ") + std::strerror(errno));
        }
        std::set<std::string> seen_files;
        std::set<std::string> top_levels;
        TarReader reader(fd);
        TarEntry entry;
        try {
            while (reader.next(&entry)) {
                const std::vector<std::string> components = split_relative_path(entry.path);
                if (components.empty()) {
                    throw Error(Code::kPathRejected, "empty tar path");
                }
                if (components[0] != "home" && components[0] != "workspace") {
                    throw Error(Code::kPathRejected, "import path outside home/workspace");
                }
                top_levels.insert(components[0]);
                const std::string destination = staging + "/" + entry.path;
                if (entry.type == '5') {
                    ensure_directory(destination, 0700);
                    continue;
                }
                if (!seen_files.insert(entry.path).second) {
                    throw Error(Code::kPathRejected, "duplicate path in tar");
                }
                if (result.files >= kMaxImportFiles ||
                    result.bytes + entry.size > kMaxImportBytes) {
                    throw Error(Code::kLimitExceeded, "import exceeds the size limit");
                }
                ensure_parent_directory(destination);
                const int out = ::open(destination.c_str(),
                                       O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
                if (out < 0) {
                    throw Error(Code::kIo, std::string("import create failed: ") +
                                                   std::strerror(errno));
                }
                uint8_t buffer[65536];
                uint64_t remaining = entry.size;
                try {
                    while (remaining > 0) {
                        const size_t chunk =
                                static_cast<size_t>(std::min<uint64_t>(remaining, sizeof(buffer)));
                        const size_t read = reader.read_data(buffer, chunk);
                        if (read == 0) {
                            throw Error(Code::kExportIo, "truncated tar entry");
                        }
                        write_all(out, buffer, read);
                        remaining -= read;
                    }
                } catch (...) {
                    ::close(out);
                    throw;
                }
                ::close(out);
                ++result.files;
                result.bytes += entry.size;
            }
        } catch (...) {
            ::close(fd);
            throw;
        }
        ::close(fd);

        for (const char* name : {"home", "workspace"}) {
            if (top_levels.count(name) == 0) {
                continue;
            }
            const std::string source = staging + "/" + name;
            const std::string target = layout.root() + "/" + name;
            Imported record;
            record.name = name;
            record.backup = backup_root + "/" + name;
            const Stat existing = lstat_path(target);
            if (existing.exists) {
                if (S_ISLNK(existing.value.st_mode)) {
                    throw Error(Code::kPathRejected, "target is a symlink");
                }
                if (::rename(target.c_str(), record.backup.c_str()) != 0) {
                    throw Error(Code::kIo, std::string("backup rename failed: ") +
                                                   std::strerror(errno));
                }
                record.had_original = true;
            }
            imported.push_back(record);
            if (::rename(source.c_str(), target.c_str()) != 0) {
                throw Error(Code::kIo, std::string("install rename failed: ") +
                                               std::strerror(errno));
            }
            imported.back().installed = true;
        }
        success = true;
    } catch (...) {
        rollback(&imported, layout.root());
        remove_tree(staging);
        remove_tree(backup_root);
        throw;
    }

    if (success) {
        remove_tree(staging);
        remove_tree(backup_root);
    }
    return result;
}

}  // namespace fl
