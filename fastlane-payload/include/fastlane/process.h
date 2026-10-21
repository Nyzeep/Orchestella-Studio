#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <sys/types.h>

namespace fl {

struct SpawnSpec {
    std::vector<std::string> argv;
    std::vector<std::string> environment;
    std::string working_directory;
};

struct PipedChild {
    pid_t pid = -1;
    int input_fd = -1;   // parent writes
    int output_fd = -1;  // parent reads
};

struct PtyChild {
    pid_t pid = -1;
    int master_fd = -1;
};

/**
 * Resolves an OPEN_PTY command. A bare name is looked up in `<apk_root>/bin`;
 * a path must be absolute, must resolve (after symlinks) inside apk_root, and
 * must be executable.
 */
std::string resolve_executable(const std::string& apk_root, const std::string& command);

/** Minimal, explicit child environment; never inherits payload variables. */
std::vector<std::string> child_environment(const std::string& apk_root,
                                           const std::string& storage_root);

/** Starts a child with stdin/stdout pipes and stderr on /dev/null. */
PipedChild spawn_piped(const SpawnSpec& spec);

/** Starts a child on a new PTY; the master is non-blocking. */
PtyChild spawn_pty(const SpawnSpec& spec, uint16_t columns, uint16_t rows);

/** Sends to the child's process group (children are made group leaders). */
void terminate_child(pid_t pid, int signal_number);

/** Waits up to timeout_ms; returns true when reaped. */
bool wait_for_child(pid_t pid, int timeout_ms, int* status);

}  // namespace fl
