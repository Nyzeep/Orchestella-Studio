#include "test_support.h"

#include <fcntl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <filesystem>
#include <map>
#include <set>
#include <string>
#include <vector>

#include "fastlane/paths.h"
#include "fastlane/sha256.h"
#include "fastlane/storage.h"
#include "fastlane/tar.h"

namespace {

struct TempDir {
    std::string path;

    TempDir() {
        char pattern[] = "/tmp/fastlane-test-XXXXXX";
        char* created = ::mkdtemp(pattern);
        if (created == nullptr) {
            throw std::runtime_error("mkdtemp failed");
        }
        path = created;
    }

    ~TempDir() {
        std::error_code ignored;
        std::filesystem::remove_all(path, ignored);
    }
};

void write_file(const std::string& path, const std::string& content) {
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        throw std::runtime_error("open for write failed: " + path);
    }
    size_t offset = 0;
    while (offset < content.size()) {
        const ssize_t written = ::write(fd, content.data() + offset, content.size() - offset);
        if (written <= 0) {
            ::close(fd);
            throw std::runtime_error("write failed");
        }
        offset += static_cast<size_t>(written);
    }
    ::close(fd);
}

std::string read_file(const std::string& path) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        throw std::runtime_error("open for read failed: " + path);
    }
    std::string content;
    char buffer[4096];
    for (;;) {
        const ssize_t read = ::read(fd, buffer, sizeof(buffer));
        if (read <= 0) {
            break;
        }
        content.append(buffer, static_cast<size_t>(read));
    }
    ::close(fd);
    return content;
}

void make_dir(const std::string& path) {
    if (::mkdir(path.c_str(), 0700) != 0 && errno != EEXIST) {
        throw std::runtime_error("mkdir failed: " + path);
    }
}

int make_unix_socket(const std::string& path) {
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        throw std::runtime_error("socket failed");
    }
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    if (path.size() >= sizeof(address.sun_path)) {
        throw std::runtime_error("socket path too long");
    }
    std::strncpy(address.sun_path, path.c_str(), sizeof(address.sun_path) - 1);
    if (::bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0) {
        ::close(fd);
        throw std::runtime_error("bind failed");
    }
    return fd;
}

void put_octal(char* field, size_t width, uint64_t value) {
    std::snprintf(field, width, "%0*llo", static_cast<int>(width - 1),
                  static_cast<unsigned long long>(value));
}

struct RawEntry {
    std::string name;
    char type = '0';
    std::string content;
    std::string link;
};

void write_raw_header(uint8_t block[512], const RawEntry& entry) {
    std::memset(block, 0, 512);
    std::memcpy(block, entry.name.data(), std::min<size_t>(entry.name.size(), 100));
    put_octal(reinterpret_cast<char*>(block + 100), 8, entry.type == '5' ? 0755 : 0644);
    put_octal(reinterpret_cast<char*>(block + 108), 8, 0);
    put_octal(reinterpret_cast<char*>(block + 116), 8, 0);
    put_octal(reinterpret_cast<char*>(block + 124), 12, entry.content.size());
    put_octal(reinterpret_cast<char*>(block + 136), 12, 1700000000);
    std::memset(block + 148, ' ', 8);
    block[156] = static_cast<uint8_t>(entry.type);
    std::memcpy(block + 157, entry.link.data(), std::min<size_t>(entry.link.size(), 100));
    std::memcpy(block + 257, "ustar", 5);
    std::memcpy(block + 263, "00", 2);
    unsigned int sum = 0;
    for (size_t index = 0; index < 512; ++index) {
        sum += block[index];
    }
    char checksum[8];
    std::snprintf(checksum, sizeof(checksum), "%06o", sum);
    checksum[6] = '\0';
    checksum[7] = ' ';
    std::memcpy(block + 148, checksum, 8);
}

std::string write_raw_tar_at(const std::string& directory, const std::string& name,
                             const std::vector<RawEntry>& entries) {
    const std::string path = directory + "/" + name;
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) {
        throw std::runtime_error("cannot create raw tar");
    }
    for (const auto& entry : entries) {
        uint8_t header[512];
        write_raw_header(header, entry);
        if (::write(fd, header, sizeof(header)) != sizeof(header)) {
            throw std::runtime_error("short header write");
        }
        size_t offset = 0;
        while (offset < entry.content.size()) {
            uint8_t block[512] = {};
            const size_t chunk = std::min<size_t>(512, entry.content.size() - offset);
            std::memcpy(block, entry.content.data() + offset, chunk);
            if (::write(fd, block, sizeof(block)) != sizeof(block)) {
                throw std::runtime_error("short content write");
            }
            offset += chunk;
        }
    }
    const uint8_t end[1024] = {};
    if (::write(fd, end, sizeof(end)) != sizeof(end)) {
        throw std::runtime_error("short end write");
    }
    ::close(fd);
    return path;
}

std::string write_raw_tar(const std::string& directory, const std::vector<RawEntry>& entries) {
    return write_raw_tar_at(directory, "raw.tar", entries);
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

struct ParsedTar {
    std::set<std::string> directories;
    std::map<std::string, std::string> files;
};

ParsedTar parse_tar(const std::string& path) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        throw std::runtime_error("cannot open tar for parsing");
    }
    ParsedTar parsed;
    fl::TarReader reader(fd);
    fl::TarEntry entry;
    while (reader.next(&entry)) {
        if (entry.type == '5') {
            parsed.directories.insert(entry.path);
            continue;
        }
        std::string content(entry.size, '\0');
        size_t offset = 0;
        while (offset < content.size()) {
            const size_t read = reader.read_data(&content[offset], content.size() - offset);
            if (read == 0) {
                ::close(fd);
                throw std::runtime_error("unexpected end of tar data");
            }
            offset += read;
        }
        parsed.files[entry.path] = content;
    }
    ::close(fd);
    return parsed;
}

fl::Sha256Digest file_hash(const std::string& path, uint64_t* size) {
    return fl::sha256_file(path, size);
}

}  // namespace

FL_TEST(paths_reject_unsafe_relative_paths) {
    FL_CHECK(fl::is_safe_relative_path("home/a.txt"));
    FL_CHECK(fl::is_safe_relative_path("workspace/deep/dir/file"));
    FL_CHECK(fl::is_safe_relative_path("a"));
    FL_CHECK(!fl::is_safe_relative_path(""));
    FL_CHECK(!fl::is_safe_relative_path("/etc/passwd"));
    FL_CHECK(!fl::is_safe_relative_path("../a"));
    FL_CHECK(!fl::is_safe_relative_path("a/../b"));
    FL_CHECK(!fl::is_safe_relative_path("a//b"));
    FL_CHECK(!fl::is_safe_relative_path("./a"));
    FL_CHECK(!fl::is_safe_relative_path("a/"));
    FL_CHECK(!fl::is_safe_relative_path(std::string("a\0b", 3)));
    FL_CHECK(fl::is_within("/tmp/root", "/tmp/root/a"));
    FL_CHECK(!fl::is_within("/tmp/root", "/tmp/root2/a"));
}

FL_TEST(layout_initializes_idempotently) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    for (const auto& name : {"home", "workspace", "tmp", "cache", "exports"}) {
        struct stat info {};
        FL_CHECK_EQ(::stat((temp.path + "/" + name).c_str(), &info), 0);
        FL_CHECK(S_ISDIR(info.st_mode));
    }
    const std::string marker = temp.path + "/" + fl::kLayoutMarkerName;
    const std::string first = read_file(marker);
    FL_CHECK_EQ(first, std::string("version=1\n"));
    auto again = fl::StorageLayout::initialize(temp.path);
    FL_CHECK_EQ(read_file(marker), first);
    FL_CHECK_EQ(again.home(), temp.path + "/home");
    FL_CHECK_EQ(again.resolve("home/a.txt"), temp.path + "/home/a.txt");
}

FL_TEST(layout_rejects_unknown_version) {
    TempDir temp;
    write_file(temp.path + "/" + fl::kLayoutMarkerName, "version=2\n");
    FL_CHECK_THROWS_CODE(fl::StorageLayout::initialize(temp.path), fl::Code::kLayoutInvalid);
}

FL_TEST(layout_rejects_symlinked_marker) {
    TempDir temp;
    write_file(temp.path + "/real-marker", "version=1\n");
    FL_CHECK_EQ(::symlink("real-marker", (temp.path + "/" + fl::kLayoutMarkerName).c_str()), 0);
    FL_CHECK_THROWS_CODE(fl::StorageLayout::initialize(temp.path), fl::Code::kPathRejected);
}

FL_TEST(resolve_rejects_traversal_and_symlink) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    FL_CHECK_THROWS_CODE(layout.resolve("../etc/passwd"), fl::Code::kPathRejected);
    FL_CHECK_THROWS_CODE(layout.resolve("/etc/passwd"), fl::Code::kPathRejected);
    FL_CHECK_THROWS_CODE(layout.resolve("home/../../etc/passwd"), fl::Code::kPathRejected);

    write_file(temp.path + "/home/a.txt", "a");
    FL_CHECK_EQ(::symlink("/tmp", (temp.path + "/home/link").c_str()), 0);
    FL_CHECK_THROWS_CODE(layout.resolve_no_symlink("home/link/a.txt"), fl::Code::kPathRejected);
    FL_CHECK_EQ(layout.resolve_no_symlink("home/a.txt"), temp.path + "/home/a.txt");
}

FL_TEST(export_writes_sorted_tar_and_skips_denied) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/a.txt", "hello");
    write_file(layout.home() + "/empty.txt", "");
    make_dir(layout.home() + "/dir");
    write_file(layout.home() + "/dir/b.bin", std::string(10000, 'x'));
    write_file(layout.workspace() + "/c.txt", "workspace");
    const std::string long_name(120, 'L');
    write_file(layout.home() + "/" + long_name + ".txt", "long");
    write_file(layout.home() + "/日本語.txt", "utf8");
    write_file(layout.home() + "/id_rsa", "secret");
    const int socket_fd = make_unix_socket(layout.home() + "/tmp.sock");

    const std::string tar_path = temp.path + "/export.tar";
    const int out = ::open(tar_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    FL_CHECK(out >= 0);
    const auto result = fl::export_tar(layout, {"home", "workspace"}, out);
    ::close(out);
    ::close(socket_fd);

    FL_CHECK_EQ(result.files, 6u);
    FL_CHECK_EQ(result.skipped, 2u);

    const auto parsed = parse_tar(tar_path);
    FL_CHECK(parsed.directories.count("home") == 1);
    FL_CHECK(parsed.directories.count("home/dir") == 1);
    FL_CHECK(parsed.directories.count("workspace") == 1);
    FL_CHECK_EQ(parsed.files.at("home/a.txt"), std::string("hello"));
    FL_CHECK_EQ(parsed.files.at("home/empty.txt"), std::string(""));
    FL_CHECK_EQ(parsed.files.at("home/dir/b.bin"), std::string(10000, 'x'));
    FL_CHECK_EQ(parsed.files.at("workspace/c.txt"), std::string("workspace"));
    FL_CHECK_EQ(parsed.files.at("home/" + long_name + ".txt"), std::string("long"));
    FL_CHECK_EQ(parsed.files.at("home/日本語.txt"), std::string("utf8"));
    FL_CHECK(parsed.files.count("home/id_rsa") == 0);
    FL_CHECK(parsed.files.count("home/tmp.sock") == 0);
}

FL_TEST(export_rejects_symlink) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/a.txt", "a");
    FL_CHECK_EQ(::symlink("a.txt", (layout.home() + "/link").c_str()), 0);
    const std::string tar_path = temp.path + "/export.tar";
    const int out = ::open(tar_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    FL_CHECK(out >= 0);
    FL_CHECK_THROWS_CODE(fl::export_tar(layout, {"home"}, out), fl::Code::kPathRejected);
    ::close(out);
}

FL_TEST(import_replaces_home_and_workspace) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/old.txt", "old");
    const std::string tar_path = write_raw_tar(
            temp.path,
            {{"home/", '5', "", ""},
             {"home/a.txt", '0', "new", ""},
             {"home/dir/", '5', "", ""},
             {"home/dir/b.txt", '0', "bee", ""},
             {"workspace/", '5', "", ""},
             {"workspace/c.txt", '0', "work", ""}});
    uint64_t size = 0;
    const auto digest = file_hash(tar_path, &size);
    const auto result = fl::import_tar(layout, tar_path, size, digest);
    FL_CHECK_EQ(result.files, 3u);
    FL_CHECK_EQ(read_file(layout.home() + "/a.txt"), std::string("new"));
    FL_CHECK_EQ(read_file(layout.home() + "/dir/b.txt"), std::string("bee"));
    FL_CHECK_EQ(read_file(layout.workspace() + "/c.txt"), std::string("work"));
    struct stat old_info {};
    FL_CHECK(::stat((layout.home() + "/old.txt").c_str(), &old_info) != 0);
    FL_CHECK_EQ(read_file(temp.path + "/" + fl::kLayoutMarkerName), std::string("version=1\n"));
}

FL_TEST(import_rejects_traversal_and_links) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    make_dir(temp.path + "/sub");
    make_dir(temp.path + "/links");
    make_dir(temp.path + "/top");

    const std::string traversal = write_raw_tar(
            temp.path, {{"../evil.txt", '0', "evil", ""}});
    uint64_t size = 0;
    auto digest = file_hash(traversal, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, traversal, size, digest), fl::Code::kPathRejected);
    struct stat info {};
    FL_CHECK(::stat((temp.path + "/evil.txt").c_str(), &info) != 0);

    const std::string absolute = write_raw_tar(
            temp.path + "/sub", {{"/tmp/evil.txt", '0', "evil", ""}});
    digest = file_hash(absolute, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, absolute, size, digest), fl::Code::kPathRejected);

    const std::string link = write_raw_tar(
            temp.path + "/links", {{"home/link", '2', "", "../target"}});
    digest = file_hash(link, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, link, size, digest), fl::Code::kPathRejected);

    const std::string top_level = write_raw_tar(
            temp.path + "/top", {{"etc/passwd", '0', "x", ""}});
    digest = file_hash(top_level, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, top_level, size, digest), fl::Code::kPathRejected);
}

FL_TEST(import_hash_failure_preserves_data) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/a.txt", "old");
    make_dir(temp.path + "/sub2");
    const std::string tar_path = write_raw_tar(
            temp.path + "/sub2", {{"home/a.txt", '0', "new", ""}});
    uint64_t size = 0;
    auto digest = file_hash(tar_path, &size);
    digest[0] ^= 0xFF;
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, tar_path, size, digest), fl::Code::kExportIo);
    FL_CHECK_EQ(read_file(layout.home() + "/a.txt"), std::string("old"));
}

FL_TEST(import_truncated_tar_preserves_data) {
    TempDir temp;
    auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/a.txt", "old");
    make_dir(temp.path + "/sub3");
    const std::string tar_path = write_raw_tar(
            temp.path + "/sub3",
            {{"home/a.txt", '0', std::string(5000, 'z'), ""}});
    FL_CHECK_EQ(::truncate(tar_path.c_str(), 3000), 0);
    uint64_t size = 0;
    const auto digest = file_hash(tar_path, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, tar_path, size, digest), fl::Code::kExportIo);
    FL_CHECK_EQ(read_file(layout.home() + "/a.txt"), std::string("old"));
}

FL_TEST(export_reports_short_write) {
    if (::access("/dev/full", W_OK) != 0) {
        return;
    }
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/a.txt", "hello");
    const int full = ::open("/dev/full", O_WRONLY | O_CLOEXEC);
    FL_CHECK(full >= 0);
    FL_CHECK_THROWS_CODE(fl::export_tar(layout, {"home"}, full), fl::Code::kIo);
    ::close(full);
}

FL_TEST(tar_writer_rejects_unencodable_size) {
    const int output = ::open("/dev/null", O_WRONLY | O_CLOEXEC);
    FL_CHECK(output >= 0);
    const int data = ::open("/dev/null", O_RDONLY | O_CLOEXEC);
    FL_CHECK(data >= 0);
    fl::TarWriter writer(output);
    // 8 GiB does not fit ustar's 11 octal digits; it must not be truncated.
    FL_CHECK_THROWS_CODE(writer.add_file("home/huge", data, 8ULL * 1024 * 1024 * 1024, 0644, 0),
                         fl::Code::kLimitExceeded);
    ::close(data);
    ::close(output);
}

FL_TEST(export_denylist_is_case_insensitive) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/ID_RSA", "secret");
    write_file(layout.home() + "/.NETRC", "secret");
    write_file(layout.home() + "/keep.txt", "keep");
    const std::string tar_path = temp.path + "/export.tar";
    const int out = ::open(tar_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    FL_CHECK(out >= 0);
    const auto result = fl::export_tar(layout, {"home"}, out);
    ::close(out);
    FL_CHECK_EQ(result.files, 1u);
    FL_CHECK_EQ(result.skipped, 2u);
    const auto parsed = parse_tar(tar_path);
    FL_CHECK(parsed.files.count("home/ID_RSA") == 0);
    FL_CHECK(parsed.files.count("home/.NETRC") == 0);
}

FL_TEST(import_rejects_pax_size_overflow) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/a.txt", "old");
    make_dir(temp.path + "/pax-overflow");
    // 2^64 + 1 wraps to 1 if the parser uses unchecked uint64 arithmetic.
    const std::string record = "29 size=18446744073709551617\n";
    const std::string tar_path = write_raw_tar_at(
            temp.path + "/pax-overflow", "overflow.tar",
            {{"PaxHeaders/x", 'x', record, ""}, {"home/a.txt", '0', "AB", ""}});
    uint64_t size = 0;
    const auto digest = file_hash(tar_path, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, tar_path, size, digest), fl::Code::kExportIo);
    FL_CHECK_EQ(read_file(layout.home() + "/a.txt"), std::string("old"));
}

FL_TEST(import_rejects_pax_size_above_limit) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    make_dir(temp.path + "/pax-limit");
    const std::string record = pax_record("size", "17179869184");  // 16 GiB
    const std::string tar_path = write_raw_tar_at(
            temp.path + "/pax-limit", "limit.tar",
            {{"PaxHeaders/x", 'x', record, ""}, {"home/a.txt", '0', "x", ""}});
    uint64_t size = 0;
    const auto digest = file_hash(tar_path, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, tar_path, size, digest),
                         fl::Code::kLimitExceeded);
}

FL_TEST(import_rejects_duplicate_paths_and_preserves_data) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    write_file(layout.home() + "/a.txt", "old");
    make_dir(temp.path + "/duplicate");
    const std::string tar_path = write_raw_tar_at(
            temp.path + "/duplicate", "duplicate.tar",
            {{"home/a.txt", '0', "one", ""}, {"home/a.txt", '0', "two", ""}});
    uint64_t size = 0;
    const auto digest = file_hash(tar_path, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, tar_path, size, digest),
                         fl::Code::kPathRejected);
    FL_CHECK_EQ(read_file(layout.home() + "/a.txt"), std::string("old"));
}

FL_TEST(import_leaves_no_staging_directories) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    make_dir(temp.path + "/bad");
    const std::string tar_path = write_raw_tar_at(
            temp.path + "/bad", "bad.tar", {{"../../evil", '0', "x", ""}});
    uint64_t size = 0;
    const auto digest = file_hash(tar_path, &size);
    FL_CHECK_THROWS_CODE(fl::import_tar(layout, tar_path, size, digest),
                         fl::Code::kPathRejected);
    for (const auto& entry : std::filesystem::directory_iterator(layout.tmp())) {
        FL_CHECK_EQ(entry.path().filename().string().rfind(".import-", 0), std::string::npos);
        FL_CHECK_EQ(entry.path().filename().string().rfind(".backup-", 0), std::string::npos);
    }
}

FL_TEST(sha256_rejects_non_regular_file) {
    TempDir temp;
    uint64_t size = 0;
    FL_CHECK_THROWS_CODE(fl::sha256_file(temp.path, &size), fl::Code::kIo);
}

FL_TEST(tar_round_trips_long_and_utf8_names) {
    TempDir temp;
    const std::string tar_path = temp.path + "/roundtrip.tar";
    const int fd = ::open(tar_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    FL_CHECK(fd >= 0);
    const std::string long_path = "home/" + std::string(130, 'a') + "/" + std::string(90, 'b');
    const std::string utf8_path = "home/日本語/ファイル.txt";
    const std::string content = "content";
    write_file(temp.path + "/data", content);
    const int data_fd = ::open((temp.path + "/data").c_str(), O_RDONLY | O_CLOEXEC);
    FL_CHECK(data_fd >= 0);
    {
        fl::TarWriter writer(fd);
        writer.add_file(long_path, data_fd, content.size(), 0644, 1700000000);
        ::lseek(data_fd, 0, SEEK_SET);
        writer.add_file(utf8_path, data_fd, content.size(), 0644, 1700000000);
        writer.finish();
    }
    ::close(data_fd);
    ::close(fd);

    const auto parsed = parse_tar(tar_path);
    FL_CHECK_EQ(parsed.files.at(long_path), content);
    FL_CHECK_EQ(parsed.files.at(utf8_path), content);
}
