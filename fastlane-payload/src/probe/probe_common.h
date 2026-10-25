// DV-PVM-01 QA probe common plumbing.
//
// QA-only device-gate instrumentation: everything here exists to answer
// "can a protected Microdroid payload load a compiler and compile in
// process". The JSON contract (PVM_PROBE one-line log) is fixed by the
// ticket's observation channel and consumed by tools/device-validation/
// dv_pvm_probe.py on the host.
//
// Evidence hygiene: dlerror and version strings can embed absolute paths;
// scrub_paths() rewrites any path-like token before it reaches the log so
// the host never records device paths.

#ifndef FASTLANE_PROBE_PROBE_COMMON_H_
#define FASTLANE_PROBE_PROBE_COMMON_H_

#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <psapi.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 1u
#endif
#endif

#if defined(__ANDROID__)
#include <android/log.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>
#endif

namespace pvmprobe {

// Fixed log tag; the host driver greps this exact marker.
constexpr const char* kLogTag = "PVM_PROBE";

inline std::string json_escape(const std::string& value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (char raw : value) {
        const unsigned char c = static_cast<unsigned char>(raw);
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out += raw;
                }
        }
    }
    return out;
}

// Rewrite path-like tokens (runs containing '/') so no absolute device path
// ever reaches host evidence. Token-adjacent separators are kept.
inline std::string scrub_paths(const std::string& value) {
    static const char* kPathChars = "abcdefghijklmnopqrstuvwxyz"
                                    "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-/";
    std::string out;
    size_t i = 0;
    while (i < value.size()) {
        const size_t start = value.find_first_of(kPathChars, i);
        if (start == std::string::npos) {
            out += value.substr(i);
            break;
        }
        out += value.substr(i, start - i);
        const size_t end = value.find_first_not_of(kPathChars, start);
        const std::string token = value.substr(
            start, end == std::string::npos ? std::string::npos : end - start);
        out += token.find('/') != std::string::npos ? std::string("<p>") : token;
        i = end == std::string::npos ? value.size() : end;
    }
    return out;
}

// Peak resident set in KiB. Linux/Android: getrusage(RUSAGE_SELF); the
// Windows path exists only for the host smoke build.
inline long ru_maxrss_kb() {
#if defined(_WIN32)
    PROCESS_MEMORY_COUNTERS counters{};
    if (GetProcessMemoryInfo(GetCurrentProcess(), &counters, sizeof(counters)) == 0) {
        return -1;
    }
    return static_cast<long>(counters.PeakWorkingSetSize / 1024u);
#else
    struct rusage usage{};
    if (getrusage(RUSAGE_SELF, &usage) != 0) {
        return -1;
    }
    return static_cast<long>(usage.ru_maxrss);  // Linux: already KiB
#endif
}

inline int cpu_topology_online() {
#if defined(_WIN32)
    SYSTEM_INFO info{};
    GetSystemInfo(&info);
    return static_cast<int>(info.dwNumberOfProcessors);
#else
    return static_cast<int>(::sysconf(_SC_NPROCESSORS_ONLN));
#endif
}

inline long long steady_now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

inline long long steady_ms_since(long long started_ms) {
    return steady_now_ms() - started_ms;
}

inline void emit(const char* stage, const char* status, int iter,
                 long long compile_ms, long ru_maxrss, long long obj_bytes,
                 const std::string& version, int topology);

// QA vsock result channel. The vendor debug policy keeps the guest console
// and guest logcat from reaching the host, so the probe ALSO streams every
// PVM_PROBE line to the host over AF_VSOCK (same transport the FLN1 distcc
// payload uses). Best-effort: when no host client connects within the
// window the probe falls back to logcat-only and never blocks the result.
constexpr uint16_t kResultPort = 45699;

// Stage-encoded process exit for the failure case (visible even when every
// log channel is blocked): 1 = legacy generic failure, 12 = first compile
// status not OK, 13 = object bytes not ELF.
inline std::atomic<int> g_first_failure_exit{1};
#if defined(__ANDROID__)
inline std::atomic<int> g_result_fd{-1};

inline void start_result_listener() {
    const int listener = ::socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (listener < 0) {
        return;
    }
    sockaddr_vm address{};
    address.svm_family = AF_VSOCK;
    address.svm_cid = VMADDR_CID_ANY;
    address.svm_port = kResultPort;
    if (::bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0 ||
        ::listen(listener, 1) != 0) {
        const int saved = errno;
        ::close(listener);
        emit("listen", "BIND_LISTEN_FAILED", 0, 0, ru_maxrss_kb(), 0,
             "vsock bind/listen errno " + std::to_string(saved), cpu_topology_online());
        return;
    }
    emit("listen", "LISTENING", 0, 0, ru_maxrss_kb(), 0,
         "vsock port " + std::to_string(kResultPort), cpu_topology_online());
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(15);
    int client = -1;
    for (;;) {
        client = ::accept(listener, nullptr, nullptr);
        if (client >= 0) {
            break;
        }
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            emit("listen", "ACCEPT_ERROR", 0, 0, ru_maxrss_kb(), 0,
                 "vsock accept errno " + std::to_string(errno), cpu_topology_online());
            break;
        }
        if (std::chrono::steady_clock::now() >= deadline) {
            emit("listen", "ACCEPT_TIMEOUT", 0, 0, ru_maxrss_kb(), 0,
                 "no host connection in 15s", cpu_topology_online());
            break;
        }
        ::usleep(50 * 1000);
    }
    ::close(listener);
    if (client >= 0) {
        g_result_fd.store(client);
        emit("listen", "CONNECTED", 0, 0, ru_maxrss_kb(), 0,
             "host dialed the result channel", cpu_topology_online());
    }
}

inline void write_result_line(const std::string& line) {
    const int fd = g_result_fd.load(std::memory_order_relaxed);
    if (fd < 0) {
        return;
    }
    const std::string framed = line + "\n";
    size_t total = 0;
    while (total < framed.size()) {
        const ssize_t chunk = ::send(fd, framed.data() + total, framed.size() - total, MSG_NOSIGNAL);
        if (chunk <= 0) {
            g_result_fd.store(-1);
            ::close(fd);
            return;
        }
        total += static_cast<size_t>(chunk);
    }
}

inline void finish_result_sink() {
    const int fd = g_result_fd.exchange(-1);
    if (fd >= 0) {
        const char end[] = "PVM_PROBE_END\n";
        ::send(fd, end, sizeof(end) - 1, MSG_NOSIGNAL);
        ::close(fd);
    }
}
#else
inline void start_result_listener() {}
inline void write_result_line(const std::string&) {}
inline void finish_result_sink() {}
#endif

// One PVM_PROBE observation. Field names and order are the ticket's fixed
// contract; the host parser tolerates extra fields but requires these.
inline void emit(const char* stage, const char* status, int iter,
                 long long compile_ms, long ru_maxrss, long long obj_bytes,
                 const std::string& version, int topology) {
    std::string json = "{";
    json += "\"stage\":\"" + json_escape(stage) + "\",";
    json += "\"status\":\"" + json_escape(status) + "\",";
    json += "\"iter\":" + std::to_string(iter) + ",";
    json += "\"compile_ms\":" + std::to_string(compile_ms) + ",";
    json += "\"ru_maxrss_kb\":" + std::to_string(ru_maxrss) + ",";
    json += "\"obj_bytes\":" + std::to_string(obj_bytes) + ",";
    json += "\"llvm_version\":\"" + json_escape(scrub_paths(version)) + "\",";
    json += "\"vm_cpu_topology\":" + std::to_string(topology);
    json += "}";
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", json.c_str());
    write_result_line(kLogTag + std::string(" ") + json);
#else
    std::fprintf(stderr, "%s %s\n", kLogTag, json.c_str());
#endif
}

// QA capability map of the payload domain inside the guest: which write
// paths exist at all. One line of evidence for the ticket's seccomp/SELinux
// verdicts; runs against the payload's own fds and the guest's directories.
#if defined(__ANDROID__)
inline std::string capability_facts() {
    std::string facts;
    const int fd = static_cast<int>(::syscall(
        SYS_memfd_create, "pvm-caps", static_cast<unsigned>(MFD_CLOEXEC)));
    if (fd < 0) {
        return "memfd:errno" + std::to_string(errno) + " ";
    }
    const ssize_t wrote = ::write(fd, "PVM", 3);
    facts += wrote == 3 ? "write:OK "
                        : "write:errno" + std::to_string(errno) + " ";
    facts += ::ftruncate(fd, 0) == 0
                 ? "ftruncate:OK "
                 : "ftruncate:errno" + std::to_string(errno) + " ";
    void* mapped = ::mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (mapped == MAP_FAILED) {
        facts += "mmap:errno" + std::to_string(errno) + " ";
    } else {
        static_cast<char*>(mapped)[0] = 'P';
        ::munmap(mapped, 4096);
        facts += "mmap:OK ";
    }
    const std::string path = "/proc/self/fd/" + std::to_string(fd);
    std::FILE* reopen = std::fopen(path.c_str(), "wb");
    facts += reopen != nullptr
                 ? std::string("reopen-wb:OK ")
                 : "reopen-wb:errno" + std::to_string(errno) + " ";
    if (reopen != nullptr) {
        std::fclose(reopen);
    }
    const int raw = ::open(path.c_str(), O_WRONLY | O_TRUNC | O_CLOEXEC);
    facts += raw >= 0 ? "reopen-wo:OK "
                      : "reopen-wo:errno" + std::to_string(errno) + " ";
    if (raw >= 0) {
        ::close(raw);
    }
    ::close(fd);
    for (const char* dir : {"/data", "/data/local", "/data/local/tmp", "/dev", "/tmp"}) {
        facts += std::string(dir) +
                 (::access(dir, W_OK) == 0 ? ":W-ok " : ":W-no ");
    }
    return facts;
}
#endif

// In-memory object sink. Linux/Android use an anonymous memfd referenced by
// /proc/self/fd/N so the compiler's file-oriented output never touches
// visible storage; the Windows host smoke build falls back to a temp file.
class ObjectSink {
public:
    ObjectSink() {
#if defined(_WIN32)
        char temp_dir[MAX_PATH];
        char temp_file[MAX_PATH];
        if (GetTempPathA(MAX_PATH, temp_dir) == 0 ||
            GetTempFileNameA(temp_dir, "pvmprobe", 0, temp_file) == 0) {
            return;
        }
        path_ = temp_file;
        is_temp_file_ = true;
#else
        const int fd = static_cast<int>(::syscall(
            SYS_memfd_create, "pvm-probe-object", static_cast<unsigned>(MFD_CLOEXEC)));
        if (fd < 0) {
            return;
        }
        fd_ = fd;
        path_ = "/proc/self/fd/" + std::to_string(fd);
#endif
    }

    ~ObjectSink() {
#if defined(_WIN32)
        if (is_temp_file_) {
            DeleteFileA(path_.c_str());
        }
#else
        if (fd_ >= 0) {
            ::close(fd_);
        }
#endif
    }

    ObjectSink(const ObjectSink&) = delete;
    ObjectSink& operator=(const ObjectSink&) = delete;

    bool ok() const { return !path_.empty(); }
    const std::string& path() const { return path_; }

    bool read_into(std::vector<uint8_t>* out) const {
#if defined(_WIN32)
        return read_temp_file(out);
#else
        if (fd_ < 0) {
            return false;
        }
        if (::lseek(fd_, 0, SEEK_SET) < 0) {
            return false;
        }
        struct stat info{};
        if (::fstat(fd_, &info) != 0 || info.st_size < 0) {
            return false;
        }
        const size_t size = static_cast<size_t>(info.st_size);
        out->resize(size);
        size_t total = 0;
        while (total < size) {
            const ssize_t chunk = ::read(fd_, out->data() + total, size - total);
            if (chunk <= 0) {
                return false;
            }
            total += static_cast<size_t>(chunk);
        }
        return true;
#endif
    }

private:
    bool read_temp_file(std::vector<uint8_t>* out) const {
        FILE* stream = std::fopen(path_.c_str(), "rb");
        if (stream == nullptr) {
            return false;
        }
        if (std::fseek(stream, 0, SEEK_END) != 0) {
            std::fclose(stream);
            return false;
        }
        const long size = std::ftell(stream);
        if (size < 0) {
            std::fclose(stream);
            return false;
        }
        std::rewind(stream);
        out->resize(static_cast<size_t>(size));
        const bool ok =
            size == 0 || std::fread(out->data(), 1, static_cast<size_t>(size), stream) ==
                             static_cast<size_t>(size);
        std::fclose(stream);
        return ok;
    }

    int fd_ = -1;
    std::string path_;
#if defined(_WIN32)
    bool is_temp_file_ = false;
#endif
};

inline bool looks_like_elf(const std::vector<uint8_t>& bytes) {
    return bytes.size() >= 4 && bytes[0] == 0x7f && bytes[1] == 0x45 &&
           bytes[2] == 0x4c && bytes[3] == 0x46;
}

}  // namespace pvmprobe

#endif  // FASTLANE_PROBE_PROBE_COMMON_H_
