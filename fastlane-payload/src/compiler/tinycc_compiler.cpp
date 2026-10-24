#include "compiler.h"

#include <sys/stat.h>

#include <atomic>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <thread>

#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>
#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 1u
#endif
#endif

#include "config.h"  // generated: TCC_VERSION
#include "libtcc.h"

namespace fl {
namespace {

// Platform sink for the compiler's file-oriented object output. Linux/Android
// use an anonymous memfd, so the compile never touches visible storage; the
// Windows host build has no memfd and falls back to a temp file, which is a
// host-test-only path and never runs in the payload.
class ObjectSink {
public:
    ObjectSink() {
#if defined(_WIN32)
        char temp_dir[MAX_PATH];
        char temp_file[MAX_PATH];
        if (::GetTempPathA(MAX_PATH, temp_dir) == 0 ||
            ::GetTempFileNameA(temp_dir, "pvm", 0, temp_file) == 0) {
            return;
        }
        path_ = temp_file;
        is_temp_file_ = true;
#else
        const int fd = static_cast<int>(
            ::syscall(SYS_memfd_create, "pvm-compiler-object", static_cast<unsigned>(MFD_CLOEXEC)));
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
            ::DeleteFileA(path_.c_str());
        }
#endif
        if (fd_ >= 0) {
            ::close(fd_);
        }
    }

    ObjectSink(const ObjectSink&) = delete;
    ObjectSink& operator=(const ObjectSink&) = delete;

    bool ok() const { return !path_.empty(); }
    const std::string& path() const { return path_; }

    // Rewinds and reads the sink into memory, enforcing max_object_bytes.
    bool read_into(size_t max_object_bytes, std::vector<uint8_t>* out) const {
        if (fd_ < 0) {
            return read_temp_file(max_object_bytes, out);
        }
        if (::lseek(fd_, 0, SEEK_SET) < 0) {
            return false;
        }
        struct stat info{};
        if (::fstat(fd_, &info) != 0) {
            return false;
        }
        const size_t size = static_cast<size_t>(info.st_size);
        if (size > max_object_bytes) {
            return false;
        }
        out->resize(size);
        size_t read_total = 0;
        while (read_total < size) {
            const ssize_t chunk = ::read(fd_, out->data() + read_total, size - read_total);
            if (chunk <= 0) {
                return false;
            }
            read_total += static_cast<size_t>(chunk);
        }
        return true;
    }

private:
    bool read_temp_file(size_t max_object_bytes, std::vector<uint8_t>* out) const {
        std::FILE* stream = std::fopen(path_.c_str(), "rb");
        if (stream == nullptr) {
            return false;
        }
        struct WindowsCloser {
            void operator()(std::FILE* s) const { std::fclose(s); }
        };
        std::unique_ptr<std::FILE, WindowsCloser> owner(stream);
        if (::fseek(stream, 0, SEEK_END) != 0) {
            return false;
        }
        const long size = ::ftell(stream);
        if (size < 0 || static_cast<size_t>(size) > max_object_bytes) {
            return false;
        }
        if (::fseek(stream, 0, SEEK_SET) != 0) {
            return false;
        }
        out->resize(static_cast<size_t>(size));
        return size == 0 ||
               std::fread(out->data(), 1, static_cast<size_t>(size), stream) ==
                   static_cast<size_t>(size);
    }

    int fd_ = -1;
    std::string path_;
#if defined(_WIN32)
    bool is_temp_file_ = false;
#endif
};

struct DiagSink {
    std::string text;
    size_t cap = 0;
    bool truncated = false;
};

void on_tcc_message(void* opaque, const char* message) {
    auto* sink = static_cast<DiagSink*>(opaque);
    if (message == nullptr || sink->cap == 0) {
        return;
    }
    if (sink->text.size() >= sink->cap) {
        sink->truncated = true;
        return;
    }
    sink->text.append(message);
    sink->text.push_back('\n');
    if (sink->text.size() > sink->cap) {
        sink->text.resize(sink->cap);
        sink->truncated = true;
    }
}

// One compile job. Owned by a shared_ptr so that an abandoned (timed out)
// worker keeps its TCCState alive and cannot touch freed memory.
struct JobResult {
    CompileStatus status = CompileStatus::kInternal;
    DiagSink diag;
    std::vector<uint8_t> object;
};

void run_job(std::string source, std::vector<std::string> argv, CompileLimits limits,
             JobResult* result) {
    for (const std::string& arg : argv) {
        if (!arg.empty() && arg[0] == '@') {
            result->status = CompileStatus::kArgvRejected;
            result->diag.text = "response files are rejected\n";
            return;
        }
        if (!arg.empty() && arg[0] == '-' && arg != "-c") {
            result->status = CompileStatus::kArgvRejected;
            result->diag.text = "flag not in the prototype allowlist: " + arg + "\n";
            return;
        }
    }

    ObjectSink sink;
    if (!sink.ok()) {
        result->status = CompileStatus::kInternal;
        result->diag.text = "object sink unavailable\n";
        return;
    }

    TCCState* state = tcc_new();
    if (state == nullptr) {
        result->status = CompileStatus::kInternal;
        result->diag.text = "tcc_new failed\n";
        return;
    }
    result->diag.cap = limits.max_diagnostics_bytes;
    tcc_set_error_func(state, &result->diag, on_tcc_message);
    // TinyCC loads its builtin macro definitions with "#include <tccdefs.h>",
    // which searches only system include paths; the vendored include root is
    // baked in at build time.
    tcc_add_sysinclude_path(state, FL_TINYCC_INCLUDE);
    // The prototype emits no unwinding metadata: TinyCC's .eh_frame FDEs are
    // rejected by current GNU ld (overlapping FDEs), and Microdroid payloads
    // have no unwinder use for them.
    tcc_set_options(state, "-fno-asynchronous-unwind-tables");
    if (tcc_set_output_type(state, TCC_OUTPUT_OBJ) < 0) {
        tcc_delete(state);
        result->status = CompileStatus::kInternal;
        result->diag.text += "tcc_set_output_type failed\n";
        return;
    }
    if (tcc_compile_string(state, source.c_str()) < 0) {
        tcc_delete(state);
        result->status = CompileStatus::kCompileError;
        return;
    }
    if (tcc_output_file(state, sink.path().c_str()) < 0) {
        tcc_delete(state);
        result->status = CompileStatus::kCompileError;
        return;
    }
    tcc_delete(state);

    if (!sink.read_into(limits.max_object_bytes, &result->object)) {
        result->object.clear();
        result->status = CompileStatus::kJobTooLarge;
        result->diag.text += "object output missing or beyond max_object_bytes\n";
        return;
    }
    result->status = CompileStatus::kOk;
}

std::string tinycc_compiler_id() {
    return std::string("tcc ") + TCC_VERSION +
           " (in-process prototype; LGPL-2.1, not for product distribution)";
}

}  // namespace

CompileOutcome TinyCcCompiler::compile(const CompileRequest& request) {
    const auto started = std::chrono::steady_clock::now();
    CompileOutcome outcome;
    outcome.diagnostics.compiler_id = tinycc_compiler_id();

    const auto finish = [&]() {
        outcome.elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - started);
        return outcome;
    };

    // Cheap rejections stay on the calling thread: no worker, no state.
    if (request.source.size() > request.limits.max_source_bytes) {
        outcome.status = CompileStatus::kJobTooLarge;
        outcome.diagnostics.stderr_text = "source exceeds max_source_bytes\n";
        return finish();
    }

    auto job = std::make_shared<JobResult>();
    std::mutex done_mutex;
    std::condition_variable done_cv;
    bool done = false;

    // Copies ride with the worker: a timed-out caller returns and destroys
    // its own frame while the detached worker keeps running against its own
    // buffers. The TCCState of an abandoned job is never freed; this is a
    // documented prototype limitation (one-off cost per timeout).
    std::thread worker(
        [job, source = std::string(request.source), argv = request.argv, limits = request.limits,
         &done_mutex, &done_cv, &done]() mutable {
            try {
                run_job(std::move(source), std::move(argv), limits, job.get());
            } catch (const std::exception& error) {
                job->status = CompileStatus::kInternal;
                job->diag.text = std::string("worker exception: ") + error.what() + "\n";
            } catch (...) {
                job->status = CompileStatus::kInternal;
                job->diag.text = "worker exception: unknown\n";
            }
            {
                std::lock_guard<std::mutex> lock(done_mutex);
                done = true;
            }
            done_cv.notify_one();
        });

    bool finished = false;
    {
        std::unique_lock<std::mutex> lock(done_mutex);
        finished = done_cv.wait_for(lock, request.limits.wall_budget, [&done] { return done; });
    }
    if (!finished) {
        worker.detach();
        outcome.status = CompileStatus::kJobTimeout;
        outcome.diagnostics.stderr_text = "job exceeded the wall budget; worker abandoned\n";
        return finish();
    }
    worker.join();

    outcome.status = job->status;
    outcome.object = std::move(job->object);
    outcome.diagnostics.stderr_text = std::move(job->diag.text);
    if (job->diag.truncated) {
        outcome.diagnostics.stderr_text += "...(diagnostics truncated)\n";
    }
    return finish();
}

}  // namespace fl
