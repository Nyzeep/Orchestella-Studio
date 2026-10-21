#include "test_support.h"

#include <dirent.h>
#include <poll.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <thread>

#include "fastlane/process.h"
#include "fastlane/pty.h"
#include "fastlane/storage.h"

namespace {

struct TempDir {
    std::string path;

    TempDir() {
        char pattern[] = "/tmp/fastlane-pty-XXXXXX";
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

std::string read_until_eof(fl::PtyManager& manager, uint32_t id, int timeout_ms, int* status) {
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    std::string output;
    bool eof = false;
    while (std::chrono::steady_clock::now() < deadline && !eof) {
        const int fd = manager.master_fd(id);
        if (fd < 0) {
            break;
        }
        pollfd descriptor{fd, POLLIN, 0};
        const int ready = ::poll(&descriptor, 1, 20);
        if (ready > 0) {
            char buffer[8192];
            const ssize_t read = manager.read_output(id, buffer, sizeof(buffer));
            if (read > 0) {
                output.append(buffer, static_cast<size_t>(read));
            } else if (read < 0) {
                eof = true;
            }
        }
    }
    for (int attempt = 0; attempt < 100; ++attempt) {
        if (manager.reap(id, status)) {
            return output;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    throw std::runtime_error("PTY child did not exit");
}

void make_dir(const std::string& path) {
    if (::mkdir(path.c_str(), 0700) != 0 && errno != EEXIST) {
        throw std::runtime_error("mkdir failed: " + path);
    }
}

size_t count_open_fds() {
    DIR* directory = ::opendir("/proc/self/fd");
    if (directory == nullptr) {
        return 0;
    }
    size_t count = 0;
    while (dirent* entry = ::readdir(directory)) {
        if (entry->d_name[0] != '.') {
            ++count;
        }
    }
    ::closedir(directory);
    return count;
}

}  // namespace

FL_TEST(resolve_executable_stays_in_toolbox) {
    const std::string shell = fl::resolve_executable("/usr", "sh");
    FL_CHECK(shell.rfind("/usr/", 0) == 0);
    FL_CHECK_EQ(fl::resolve_executable("/usr", "/usr/bin/env"), std::string("/usr/bin/env"));
    FL_CHECK_THROWS_CODE(fl::resolve_executable("/usr", "/etc/passwd"), fl::Code::kExecFailed);
    FL_CHECK_THROWS_CODE(fl::resolve_executable("/usr", "../etc/passwd"), fl::Code::kExecFailed);
    FL_CHECK_THROWS_CODE(fl::resolve_executable("/usr", "definitely-not-a-tool"), fl::Code::kExecFailed);
}

FL_TEST(child_environment_is_explicit) {
    const auto environment = fl::child_environment("/mnt/apk", "/mnt/encryptedstore");
    bool saw_path = false;
    bool saw_home = false;
    for (const std::string& entry : environment) {
        if (entry == "PATH=/mnt/apk/bin:/mnt/apk/bin/usr/bin") {
            saw_path = true;
        }
        if (entry == "HOME=/mnt/encryptedstore/home") {
            saw_home = true;
        }
        FL_CHECK(entry.rfind("FASTLANE_", 0) != 0);
    }
    FL_CHECK(saw_path);
    FL_CHECK(saw_home);
}

FL_TEST(pty_runs_shell_and_reports_exit) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 4);
    const auto opened = manager.open({"sh", "-c", "printf hello; exit 3"}, 80, 24, 1 << 20);
    FL_CHECK(opened.id > 0);
    int status = 0;
    const std::string output = read_until_eof(manager, opened.id, 3000, &status);
    FL_CHECK_EQ(output, std::string("hello"));
    FL_CHECK(WIFEXITED(status));
    FL_CHECK_EQ(WEXITSTATUS(status), 3);
    manager.erase(opened.id);
    FL_CHECK_EQ(manager.size(), 0u);
}

FL_TEST(pty_resize_updates_window) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 2);
    const auto opened = manager.open({"sleep", "3"}, 80, 24, 1 << 20);
    FL_CHECK(manager.resize(opened.id, 132, 43));
    int master = manager.master_fd(opened.id);
    FL_CHECK(master >= 0);
    struct winsize window {};
    FL_CHECK_EQ(::ioctl(master, TIOCGWINSZ, &window), 0);
    FL_CHECK_EQ(window.ws_col, 132);
    FL_CHECK_EQ(window.ws_row, 43);
    manager.close_session(opened.id, true);
}

FL_TEST(pty_signal_terminates_process_group) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 2);
    const auto opened = manager.open({"sleep", "10"}, 80, 24, 1 << 20);
    FL_CHECK(manager.signal(opened.id, SIGTERM));
    int status = 0;
    bool reaped = false;
    for (int attempt = 0; attempt < 100 && !reaped; ++attempt) {
        reaped = manager.reap(opened.id, &status);
        if (!reaped) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }
    FL_CHECK(reaped);
    FL_CHECK(WIFSIGNALED(status));
    FL_CHECK_EQ(WTERMSIG(status), SIGTERM);
    manager.erase(opened.id);
}

FL_TEST(pty_close_force_reaps_and_erases) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 2);
    const auto opened = manager.open({"sleep", "10"}, 80, 24, 1 << 20);
    FL_CHECK_EQ(manager.size(), 1u);
    manager.close_session(opened.id, true);
    FL_CHECK_EQ(manager.size(), 0u);
}

FL_TEST(pty_credit_backpressures_output) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 2);
    const auto opened = manager.open({"sh", "-c", "printf 0123456789"}, 80, 24, 4);
    FL_CHECK_EQ(manager.credit(opened.id), 4u);
    char buffer[64];
    for (int attempt = 0; attempt < 100; ++attempt) {
        const ssize_t read = manager.read_output(opened.id, buffer, sizeof(buffer));
        if (read > 0) {
            FL_CHECK_EQ(read, 4);
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    FL_CHECK_EQ(manager.credit(opened.id), 0u);
    FL_CHECK_EQ(manager.read_output(opened.id, buffer, sizeof(buffer)), 0);
    FL_CHECK(manager.grant(opened.id, 6));
    FL_CHECK_EQ(manager.credit(opened.id), 6u);
    ssize_t second = 0;
    for (int attempt = 0; attempt < 100; ++attempt) {
        second = manager.read_output(opened.id, buffer, sizeof(buffer));
        if (second > 0) {
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    FL_CHECK_EQ(second, 6);
    manager.close_session(opened.id, true);
}

FL_TEST(pty_does_not_leak_descriptors) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 4);
    const size_t before = count_open_fds();
    for (int iteration = 0; iteration < 10; ++iteration) {
        const auto opened = manager.open({"sh", "-c", "true"}, 80, 24, 1 << 20);
        int status = 0;
        read_until_eof(manager, opened.id, 3000, &status);
        manager.erase(opened.id);
    }
    const size_t after = count_open_fds();
    FL_CHECK_EQ(after, before);
}

FL_TEST(pty_session_limit_is_typed) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 2);
    const auto first = manager.open({"sleep", "3"}, 80, 24, 1 << 20);
    const auto second = manager.open({"sleep", "3"}, 80, 24, 1 << 20);
    FL_CHECK_THROWS_CODE(manager.open({"sleep", "3"}, 80, 24, 1 << 20), fl::Code::kSessionLimit);
    manager.close_session(first.id, true);
    manager.close_session(second.id, true);
}

FL_TEST(resolve_executable_rejects_directory) {
    TempDir temp;
    make_dir(temp.path + "/bin");
    make_dir(temp.path + "/bin/tool");
    // A directory has X_OK but must never be accepted as an executable.
    FL_CHECK_THROWS_CODE(fl::resolve_executable(temp.path, "tool"), fl::Code::kExecFailed);
}

FL_TEST(pty_exec_failure_closes_descriptors) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 2);
    const size_t before = count_open_fds();
    for (int iteration = 0; iteration < 5; ++iteration) {
        fl::SpawnSpec spec;
        spec.argv = {"/definitely-not-a-real-executable"};
        FL_CHECK_THROWS_CODE(fl::spawn_pty(spec, 80, 24), fl::Code::kExecFailed);
    }
    FL_CHECK_EQ(count_open_fds(), before);
}

FL_TEST(spawn_piped_rolls_back_partial_pipe_allocation) {
    struct rlimit original {};
    FL_CHECK_EQ(::getrlimit(RLIMIT_NOFILE, &original), 0);
    const size_t open_fds = count_open_fds();
    struct rlimit limited = original;
    // Two spare descriptors: the first pipe succeeds, the second must fail.
    limited.rlim_cur = static_cast<rlim_t>(open_fds + 2);
    if (limited.rlim_cur >= original.rlim_cur) {
        return;  // The process already has a tighter limit; nothing to prove.
    }
    FL_CHECK_EQ(::setrlimit(RLIMIT_NOFILE, &limited), 0);
    const size_t before = count_open_fds();
    fl::SpawnSpec spec;
    spec.argv = {"/bin/true"};
    FL_CHECK_THROWS_CODE(fl::spawn_piped(spec), fl::Code::kIo);
    FL_CHECK_EQ(::setrlimit(RLIMIT_NOFILE, &original), 0);
    FL_CHECK_EQ(count_open_fds(), before);
}

FL_TEST(pty_session_ids_are_monotonic) {
    TempDir temp;
    const auto layout = fl::StorageLayout::initialize(temp.path);
    fl::PtyManager manager("/usr", layout.root(), 2);
    uint32_t previous = 0;
    for (int iteration = 0; iteration < 3; ++iteration) {
        const auto opened = manager.open({"sh", "-c", "true"}, 80, 24, 1 << 20);
        FL_CHECK(opened.id > previous);
        previous = opened.id;
        manager.close_session(opened.id, true);
    }
}
