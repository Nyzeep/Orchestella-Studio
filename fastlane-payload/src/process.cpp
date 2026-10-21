#include "fastlane/process.h"

#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <thread>

#include "fastlane/error.h"
#include "fastlane/paths.h"

namespace fl {
namespace {

std::vector<char*> make_pointers(std::vector<std::string>* values) {
    std::vector<char*> pointers;
    pointers.reserve(values->size() + 1);
    for (std::string& value : *values) {
        pointers.push_back(value.data());
    }
    pointers.push_back(nullptr);
    return pointers;
}

[[noreturn]] void child_fail(int error_fd, int error_number) {
    const int saved = error_number;
    if (error_fd >= 0) {
        ssize_t ignored = ::write(error_fd, &saved, sizeof(saved));
        (void)ignored;
    }
    _exit(127);
}

void prepare_child(const SpawnSpec& spec, int error_fd) {
    if (!spec.working_directory.empty()) {
        if (::chdir(spec.working_directory.c_str()) != 0) {
            child_fail(error_fd, errno);
        }
    }
}

void close_pair(int fds[2]) {
    for (int index = 0; index < 2; ++index) {
        if (fds[index] >= 0) {
            ::close(fds[index]);
            fds[index] = -1;
        }
    }
}

}  // namespace

std::string resolve_executable(const std::string& apk_root, const std::string& command) {
    if (command.empty() || command.size() > 4096 || command.find('\0') != std::string::npos) {
        throw Error(Code::kExecFailed, "empty or invalid command");
    }
    std::string candidate;
    if (command.find('/') != std::string::npos) {
        if (command.front() != '/') {
            throw Error(Code::kExecFailed, "relative executable path is not allowed");
        }
        if (!is_within(apk_root, command)) {
            throw Error(Code::kExecFailed, "executable is outside the sealed toolbox");
        }
        candidate = command;
    } else {
        candidate = join_path(join_path(apk_root, "bin"), command);
    }

    char resolved[4097];
    if (::realpath(candidate.c_str(), resolved) == nullptr) {
        throw Error(Code::kExecFailed, "executable not found in the sealed toolbox");
    }
    if (!is_within(apk_root, resolved)) {
        throw Error(Code::kExecFailed, "executable resolves outside the sealed toolbox");
    }
    struct stat info {};
    if (::stat(resolved, &info) != 0 || !S_ISREG(info.st_mode)) {
        throw Error(Code::kExecFailed, "tool is not a regular file");
    }
    if (::access(resolved, X_OK) != 0) {
        throw Error(Code::kExecFailed, "tool is not executable");
    }
    return resolved;
}

std::vector<std::string> child_environment(const std::string& apk_root,
                                           const std::string& storage_root) {
    const std::string bin = join_path(apk_root, "bin");
    return {
            "PATH=" + bin + ":" + join_path(bin, "usr/bin"),
            "HOME=" + join_path(storage_root, "home"),
            "TMPDIR=" + join_path(storage_root, "tmp"),
            "XDG_CACHE_HOME=" + join_path(storage_root, "cache"),
            "SHELL=" + join_path(bin, "sh"),
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
    };
}

PipedChild spawn_piped(const SpawnSpec& spec) {
    if (spec.argv.empty() || spec.argv.front().empty() || spec.argv.front().front() != '/') {
        throw Error(Code::kExecFailed, "argv[0] must be a resolved absolute path");
    }
    int input[2] = {-1, -1};
    int output[2] = {-1, -1};
    int errors[2] = {-1, -1};
    if (::pipe2(input, O_CLOEXEC) != 0) {
        throw Error(Code::kIo, std::string("pipe failed: ") + std::strerror(errno));
    }
    if (::pipe2(output, O_CLOEXEC) != 0) {
        const int saved = errno;
        close_pair(input);
        throw Error(Code::kIo, std::string("pipe failed: ") + std::strerror(saved));
    }
    if (::pipe2(errors, O_CLOEXEC) != 0) {
        const int saved = errno;
        close_pair(input);
        close_pair(output);
        throw Error(Code::kIo, std::string("pipe failed: ") + std::strerror(saved));
    }

    std::vector<std::string> argv = spec.argv;
    std::vector<std::string> environment = spec.environment;
    std::vector<char*> argv_pointers = make_pointers(&argv);
    std::vector<char*> env_pointers = make_pointers(&environment);

    const pid_t pid = ::fork();
    if (pid < 0) {
        const int saved = errno;
        close_pair(input);
        close_pair(output);
        close_pair(errors);
        throw Error(Code::kIo, std::string("fork failed: ") + std::strerror(saved));
    }
    if (pid == 0) {
        ::close(input[1]);
        ::close(output[0]);
        ::close(errors[0]);
        ::setpgid(0, 0);
        if (::dup2(input[0], STDIN_FILENO) < 0 || ::dup2(output[1], STDOUT_FILENO) < 0) {
            child_fail(errors[1], errno);
        }
        const int null_fd = ::open("/dev/null", O_WRONLY | O_CLOEXEC);
        if (null_fd >= 0) {
            ::dup2(null_fd, STDERR_FILENO);
            ::close(null_fd);
        }
        if (input[0] > STDERR_FILENO) {
            ::close(input[0]);
        }
        if (output[1] > STDERR_FILENO) {
            ::close(output[1]);
        }
        prepare_child(spec, errors[1]);
        ::execve(argv_pointers[0], argv_pointers.data(), env_pointers.data());
        child_fail(errors[1], errno);
    }

    ::close(input[0]);
    input[0] = -1;
    ::close(output[1]);
    output[1] = -1;
    ::close(errors[1]);
    errors[1] = -1;
    int child_error = 0;
    ssize_t read = 0;
    do {
        read = ::read(errors[0], &child_error, sizeof(child_error));
    } while (read < 0 && errno == EINTR);
    ::close(errors[0]);
    errors[0] = -1;
    if (read != 0) {
        ::close(input[1]);
        ::close(output[0]);
        if (read > 0) {
            ::waitpid(pid, nullptr, 0);
            throw Error(Code::kExecFailed, "child exec failed");
        }
        ::kill(pid, SIGKILL);
        ::waitpid(pid, nullptr, 0);
        throw Error(Code::kIo, "child status pipe failed");
    }
    ::fcntl(input[1], F_SETFD, FD_CLOEXEC);
    ::fcntl(output[0], F_SETFD, FD_CLOEXEC);
    return PipedChild{pid, input[1], output[0]};
}

PtyChild spawn_pty(const SpawnSpec& spec, uint16_t columns, uint16_t rows) {
    if (spec.argv.empty() || spec.argv.front().empty() || spec.argv.front().front() != '/') {
        throw Error(Code::kExecFailed, "argv[0] must be a resolved absolute path");
    }
    const int master = ::posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || ::grantpt(master) != 0 || ::unlockpt(master) != 0) {
        if (master >= 0) {
            ::close(master);
        }
        throw Error(Code::kIo, std::string("pty allocation failed: ") + std::strerror(errno));
    }
    char slave_name[256];
    if (::ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        ::close(master);
        throw Error(Code::kIo, "ptsname failed");
    }
    const int slave = ::open(slave_name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) {
        ::close(master);
        throw Error(Code::kIo, std::string("pty slave open failed: ") + std::strerror(errno));
    }
    struct winsize window {};
    window.ws_col = columns == 0 ? 80 : columns;
    window.ws_row = rows == 0 ? 24 : rows;
    ::ioctl(slave, TIOCSWINSZ, &window);

    int errors[2] = {-1, -1};
    if (::pipe2(errors, O_CLOEXEC) != 0) {
        ::close(master);
        ::close(slave);
        throw Error(Code::kIo, "pty error pipe failed");
    }

    std::vector<std::string> argv = spec.argv;
    std::vector<std::string> environment = spec.environment;
    std::vector<char*> argv_pointers = make_pointers(&argv);
    std::vector<char*> env_pointers = make_pointers(&environment);

    const pid_t pid = ::fork();
    if (pid < 0) {
        ::close(master);
        ::close(slave);
        ::close(errors[0]);
        ::close(errors[1]);
        throw Error(Code::kIo, std::string("fork failed: ") + std::strerror(errno));
    }
    if (pid == 0) {
        ::close(master);
        ::close(errors[0]);
        if (::setsid() < 0 || ::ioctl(slave, TIOCSCTTY, 0) != 0) {
            child_fail(errors[1], errno);
        }
        if (::dup2(slave, STDIN_FILENO) < 0 || ::dup2(slave, STDOUT_FILENO) < 0 ||
            ::dup2(slave, STDERR_FILENO) < 0) {
            child_fail(errors[1], errno);
        }
        if (slave > STDERR_FILENO) {
            ::close(slave);
        }
        prepare_child(spec, errors[1]);
        ::execve(argv_pointers[0], argv_pointers.data(), env_pointers.data());
        child_fail(errors[1], errno);
    }

    ::close(slave);
    ::close(errors[1]);
    int child_error = 0;
    ssize_t read = 0;
    do {
        read = ::read(errors[0], &child_error, sizeof(child_error));
    } while (read < 0 && errno == EINTR);
    ::close(errors[0]);
    if (read != 0) {
        ::close(master);
        if (read > 0) {
            ::waitpid(pid, nullptr, 0);
            throw Error(Code::kExecFailed, "child exec failed");
        }
        ::kill(pid, SIGKILL);
        ::waitpid(pid, nullptr, 0);
        throw Error(Code::kIo, "child status pipe failed");
    }
    const int flags = ::fcntl(master, F_GETFL, 0);
    if (flags >= 0) {
        ::fcntl(master, F_SETFL, flags | O_NONBLOCK);
    }
    return PtyChild{pid, master};
}

void terminate_child(pid_t pid, int signal_number) {
    if (pid <= 0) {
        return;
    }
    if (::kill(-pid, signal_number) != 0 && errno == ESRCH) {
        ::kill(pid, signal_number);
    }
}

bool wait_for_child(pid_t pid, int timeout_ms, int* status) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    for (;;) {
        int local_status = 0;
        const pid_t result = ::waitpid(pid, &local_status, WNOHANG);
        if (result == pid) {
            if (status != nullptr) {
                *status = local_status;
            }
            return true;
        }
        if (result < 0 && errno == ECHILD) {
            return true;
        }
        if (std::chrono::steady_clock::now() >= deadline) {
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

}  // namespace fl
