#include <jni.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#include <string>
#include <vector>

namespace {

int clampWinsize(jint value) {
    if (value < 1) {
        return 1;
    }
    if (value > 1000) {
        return 1000;
    }
    return static_cast<int>(value);
}

void throwIoException(JNIEnv* env, const char* message, int err) {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass ioExceptionClass = env->FindClass("java/io/IOException");
    if (ioExceptionClass == nullptr) {
        return;
    }
    std::string full(message);
    if (err != 0) {
        full.append(": ");
        full.append(strerror(err));
        full.append(" (errno=");
        full.append(std::to_string(err));
        full.append(")");
    }
    env->ThrowNew(ioExceptionClass, full.c_str());
}

bool readStringArray(
        JNIEnv* env, jobjectArray array, std::vector<std::string>& storage) {
    if (array == nullptr) {
        return true;
    }
    jsize count = env->GetArrayLength(array);
    storage.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (item == nullptr) {
            return false;
        }
        const char* utf = env->GetStringUTFChars(item, nullptr);
        if (utf == nullptr) {
            env->DeleteLocalRef(item);
            return false;
        }
        storage.emplace_back(utf);
        env->ReleaseStringUTFChars(item, utf);
        env->DeleteLocalRef(item);
    }
    return true;
}

bool moveFdAbove(int& fd, int lastReserved) {
    if (fd < 0) {
        return false;
    }
    if (fd > lastReserved) {
        return true;
    }
    int moved = fcntl(fd, F_DUPFD_CLOEXEC, lastReserved + 1);
    if (moved < 0) {
        return false;
    }
    close(fd);
    fd = moved;
    return true;
}

void closeInheritedFds(int first) {
    long maxFd = 4096;
    struct rlimit rl {};
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur > static_cast<rlim_t>(first)) {
        maxFd = static_cast<long>(rl.rlim_cur);
        if (maxFd > 65536) {
            maxFd = 65536;
        }
    }
    for (int fd = first; fd < static_cast<int>(maxFd); ++fd) {
        close(fd);
    }
}

void closeIfValid(int fd) {
    if (fd >= 0) {
        close(fd);
    }
}

// passt uses a big-endian length and Ethernet frame on a byte stream. UML
// owns a datagram socket for the entire guest boot, even across helper exits.
constexpr size_t frameHeaderBytes = sizeof(uint32_t);
constexpr size_t maxFrameBytes = 65536;
constexpr size_t minFrameBytes = 14;

bool retryableIo() {
    return errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR;
}

void pumpPasstFrames(int guestFd, int streamFd) {
    std::vector<char> toPasst(frameHeaderBytes + maxFrameBytes);
    std::vector<char> toGuest(maxFrameBytes);
    size_t toPasstSize = 0;
    size_t toPasstSent = 0;
    uint32_t incomingHeader = 0;
    size_t headerRead = 0;
    size_t frameRead = 0;

    while (true) {
        const bool haveOutgoing = toPasstSent < toPasstSize;
        pollfd sockets[] {
            { guestFd, static_cast<short>(haveOutgoing ? 0 : POLLIN), 0 },
            { streamFd, static_cast<short>(POLLIN | (haveOutgoing ? POLLOUT : 0)), 0 },
        };
        int ready = poll(sockets, 2, -1);
        if (ready < 0 && errno == EINTR) {
            continue;
        }
        if (ready < 0 || ((sockets[0].revents | sockets[1].revents) & (POLLHUP | POLLERR | POLLNVAL))) {
            return;
        }

        if (sockets[0].revents & POLLIN) {
            ssize_t size = recv(guestFd, toPasst.data() + frameHeaderBytes, maxFrameBytes,
                                MSG_DONTWAIT | MSG_TRUNC);
            if (size < 0 && !retryableIo()) {
                return;
            }
            if (size >= static_cast<ssize_t>(minFrameBytes)
                    && size <= static_cast<ssize_t>(maxFrameBytes)) {
                uint32_t networkOrder = htonl(static_cast<uint32_t>(size));
                memcpy(toPasst.data(), &networkOrder, frameHeaderBytes);
                toPasstSize = frameHeaderBytes + static_cast<size_t>(size);
                toPasstSent = 0;
            }
        }
        if (sockets[1].revents & POLLOUT) {
            ssize_t sent = send(streamFd, toPasst.data() + toPasstSent, toPasstSize - toPasstSent,
                                MSG_DONTWAIT | MSG_NOSIGNAL);
            if (sent == 0 || (sent < 0 && !retryableIo())) {
                return;
            }
            if (sent > 0) {
                toPasstSent += static_cast<size_t>(sent);
            }
        }
        if (!(sockets[1].revents & POLLIN)) {
            continue;
        }
        if (headerRead < frameHeaderBytes) {
            char* header = reinterpret_cast<char*>(&incomingHeader);
            ssize_t bytes = recv(streamFd, header + headerRead, frameHeaderBytes - headerRead,
                                 MSG_DONTWAIT);
            if (bytes <= 0) {
                if (bytes < 0 && retryableIo()) {
                    continue;
                }
                return;
            }
            headerRead += static_cast<size_t>(bytes);
            if (headerRead < frameHeaderBytes) {
                continue;
            }
        }
        size_t frameLength = ntohl(incomingHeader);
        if (frameLength < minFrameBytes || frameLength > maxFrameBytes) {
            return;
        }
        ssize_t bytes = recv(streamFd, toGuest.data() + frameRead, frameLength - frameRead,
                             MSG_DONTWAIT);
        if (bytes <= 0) {
            if (bytes < 0 && retryableIo()) {
                continue;
            }
            return;
        }
        frameRead += static_cast<size_t>(bytes);
        if (frameRead == frameLength) {
            // A backed-up guest may drop an Ethernet frame as on a physical link.
            // Retain stream framing even when the guest cannot receive this packet.
            ssize_t sent = send(guestFd, toGuest.data(), frameLength, MSG_DONTWAIT | MSG_NOSIGNAL);
            if (sent < 0 && !retryableIo() && errno != ENOBUFS) {
                return;
            }
            headerRead = 0;
            frameRead = 0;
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jintArray JNICALL
Java_io_github_nyzeep_orchestella_UmlNativePtyBridge_nativeSpawnUml(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring executable,
        jobjectArray argv,
        jobjectArray envp,
        jstring workingDir,
        jint columns,
        jint rows,
        jboolean networkRequested) {
    if (executable == nullptr || argv == nullptr || workingDir == nullptr) {
        throwIoException(env, "null launch argument", EINVAL);
        return nullptr;
    }

    const char* execUtf = env->GetStringUTFChars(executable, nullptr);
    if (execUtf == nullptr) {
        throwIoException(env, "failed to decode executable path", EINVAL);
        return nullptr;
    }
    const char* cwdUtf = env->GetStringUTFChars(workingDir, nullptr);
    if (cwdUtf == nullptr) {
        env->ReleaseStringUTFChars(executable, execUtf);
        throwIoException(env, "failed to decode working directory path", EINVAL);
        return nullptr;
    }
    std::string execStr(execUtf);
    std::string cwdStr(cwdUtf);
    env->ReleaseStringUTFChars(executable, execUtf);
    env->ReleaseStringUTFChars(workingDir, cwdUtf);

    std::vector<std::string> argvStorage;
    std::vector<std::string> envpStorage;
    if (!readStringArray(env, argv, argvStorage) || !readStringArray(env, envp, envpStorage)) {
        throwIoException(env, "failed to read launch argv/envp", EINVAL);
        return nullptr;
    }
    if (argvStorage.empty()) {
        throwIoException(env, "empty argv", EINVAL);
        return nullptr;
    }

    std::vector<char*> envpPtrs;
    envpPtrs.reserve(envpStorage.size() + 1);
    for (std::string& entry : envpStorage) {
        envpPtrs.push_back(const_cast<char*>(entry.c_str()));
    }
    envpPtrs.push_back(nullptr);

    int masterFd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (masterFd < 0) {
        throwIoException(env, "posix_openpt failed", errno);
        return nullptr;
    }
    if (grantpt(masterFd) != 0 || unlockpt(masterFd) != 0) {
        int saved = errno;
        close(masterFd);
        throwIoException(env, "grantpt/unlockpt failed", saved);
        return nullptr;
    }

    char slaveName[256] {};
    if (ptsname_r(masterFd, slaveName, sizeof(slaveName)) != 0) {
        int saved = errno;
        close(masterFd);
        throwIoException(env, "ptsname_r failed", saved);
        return nullptr;
    }

    struct winsize ws {};
    ws.ws_col = static_cast<unsigned short>(clampWinsize(columns));
    ws.ws_row = static_cast<unsigned short>(clampWinsize(rows));
    ioctl(masterFd, TIOCSWINSZ, &ws);

    int ctrlIn[2] {-1, -1};
    int ctrlOut[2] {-1, -1};
    if (pipe2(ctrlIn, O_CLOEXEC) != 0) {
        int saved = errno;
        close(masterFd);
        throwIoException(env, "pipe2(ctrlIn) failed", saved);
        return nullptr;
    }
    if (pipe2(ctrlOut, O_CLOEXEC) != 0) {
        int saved = errno;
        closeIfValid(ctrlIn[0]);
        closeIfValid(ctrlIn[1]);
        close(masterFd);
        throwIoException(env, "pipe2(ctrlOut) failed", saved);
        return nullptr;
    }

    constexpr int umlNetworkFd = 5;
    int network[2] {-1, -1};
    // Network descriptor failure leaves the terminal/control launch path intact.
    if (networkRequested &&
            socketpair(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0, network) == 0) {
        constexpr int bufferSize = 1 << 20;
        for (int fd : network) {
            setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &bufferSize, sizeof(bufferSize));
            setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &bufferSize, sizeof(bufferSize));
        }
        uint8_t mac[6] {};
        arc4random_buf(mac, sizeof(mac));
        mac[0] = static_cast<uint8_t>((mac[0] & 0xfe) | 0x02);
        char vecArg[64] {};
        snprintf(vecArg, sizeof(vecArg),
                 "vec0:transport=fd,fd=5,mac=%02x:%02x:%02x:%02x:%02x:%02x",
                 mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        argvStorage.emplace_back(vecArg);
    }
    std::vector<char*> argvPtrs;
    argvPtrs.reserve(argvStorage.size() + 1);
    for (std::string& arg : argvStorage) {
        argvPtrs.push_back(arg.data());
    }
    argvPtrs.push_back(nullptr);

    pid_t parentPid = getpid();
    pid_t pid = fork();
    if (pid < 0) {
        int saved = errno;
        closeIfValid(ctrlIn[0]);
        closeIfValid(ctrlIn[1]);
        closeIfValid(ctrlOut[0]);
        closeIfValid(ctrlOut[1]);
        closeIfValid(network[0]);
        closeIfValid(network[1]);
        close(masterFd);
        throwIoException(env, "fork failed", saved);
        return nullptr;
    }

    if (pid == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        if (getppid() != parentPid) {
            _exit(127);
        }

        sigset_t emptyMask;
        sigemptyset(&emptyMask);
        sigprocmask(SIG_SETMASK, &emptyMask, nullptr);
        struct sigaction dfl {};
        dfl.sa_handler = SIG_DFL;
        sigemptyset(&dfl.sa_mask);
        for (int sig = 1; sig < NSIG; ++sig) {
            if (sig != SIGKILL && sig != SIGSTOP) {
                sigaction(sig, &dfl, nullptr);
            }
        }

        closeIfValid(masterFd);
        closeIfValid(ctrlIn[1]);
        closeIfValid(ctrlOut[0]);
        closeIfValid(network[0]);

        setsid();
        int slaveFd = open(slaveName, O_RDWR);
        if (slaveFd < 0) {
            _exit(126);
        }
        ioctl(slaveFd, TIOCSCTTY, 0);

        const bool usesNetwork = network[1] >= 0;
        const int lastReserved = usesNetwork ? umlNetworkFd : 4;
        if (!moveFdAbove(slaveFd, lastReserved)
                || !moveFdAbove(ctrlIn[0], lastReserved)
                || !moveFdAbove(ctrlOut[1], lastReserved)
                || (usesNetwork && !moveFdAbove(network[1], lastReserved))) {
            _exit(126);
        }

        if (dup2(slaveFd, STDIN_FILENO) < 0
                || dup2(slaveFd, STDOUT_FILENO) < 0
                || dup2(slaveFd, STDERR_FILENO) < 0
                || dup2(ctrlIn[0], 3) < 0
                || dup2(ctrlOut[1], 4) < 0
                || (usesNetwork && dup2(network[1], umlNetworkFd) < 0)) {
            _exit(126);
        }

        closeIfValid(slaveFd);
        closeIfValid(ctrlIn[0]);
        closeIfValid(ctrlOut[1]);
        closeInheritedFds(lastReserved + 1);

        if (chdir(cwdStr.c_str()) != 0) {
            _exit(125);
        }
        execve(execStr.c_str(), argvPtrs.data(), envpPtrs.data());
        _exit(127);
    }

    closeIfValid(ctrlIn[0]);
    closeIfValid(ctrlOut[1]);
    closeIfValid(network[1]);

    jintArray result = env->NewIntArray(5);
    if (result == nullptr) {
        closeIfValid(masterFd);
        closeIfValid(ctrlIn[1]);
        closeIfValid(ctrlOut[0]);
        closeIfValid(network[0]);
        if (kill(-pid, SIGKILL) != 0) {
            kill(pid, SIGKILL);
        }
        waitpid(pid, nullptr, 0);
        return nullptr;
    }
    jint values[5] = {
        static_cast<jint>(pid),
        static_cast<jint>(masterFd),
        static_cast<jint>(ctrlIn[1]),
        static_cast<jint>(ctrlOut[0]),
        static_cast<jint>(network[0]),
    };
    env->SetIntArrayRegion(result, 0, 5, values);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_orchestella_UmlNativePtyBridge_nativeResizePty(
        JNIEnv* /*env*/, jclass /*clazz*/, jint masterFd, jint columns, jint rows) {
    if (masterFd < 0) {
        return JNI_FALSE;
    }
    struct winsize ws {};
    ws.ws_col = static_cast<unsigned short>(clampWinsize(columns));
    ws.ws_row = static_cast<unsigned short>(clampWinsize(rows));
    return ioctl(masterFd, TIOCSWINSZ, &ws) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_nyzeep_orchestella_UmlNativePtyBridge_nativeWaitForExit(
        JNIEnv* /*env*/, jclass /*clazz*/, jint pid) {
    if (pid <= 0) {
        return -1;
    }
    int status = 0;
    pid_t waited = -1;
    do {
        waited = waitpid(static_cast<pid_t>(pid), &status, 0);
    } while (waited < 0 && errno == EINTR);
    if (waited < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -1;
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_orchestella_UmlNativePtyBridge_nativeSignalProcessGroup(
        JNIEnv* /*env*/, jclass /*clazz*/, jint pid, jint signalNum) {
    if (pid <= 0) {
        return JNI_FALSE;
    }
    if (kill(static_cast<pid_t>(-pid), signalNum) == 0) {
        return JNI_TRUE;
    }
    return kill(static_cast<pid_t>(pid), signalNum) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_orchestella_UmlNativePtyBridge_nativeIsProcessAlive(
        JNIEnv* /*env*/, jclass /*clazz*/, jint pid) {
    if (pid <= 0) {
        return JNI_FALSE;
    }
    if (kill(static_cast<pid_t>(pid), 0) == 0) {
        return JNI_TRUE;
    }
    return errno == EPERM ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_io_github_nyzeep_orchestella_UmlPasstNetwork_nativeSpawnPasst(
        JNIEnv* env, jclass /*clazz*/, jobjectArray argv) {
    std::vector<std::string> args;
    if (!readStringArray(env, argv, args) || args.empty()) {
        throwIoException(env, "invalid passt argv", EINVAL);
        return nullptr;
    }
    std::vector<char*> pointers;
    pointers.reserve(args.size() + 1);
    for (std::string& arg : args) {
        pointers.push_back(arg.data());
    }
    pointers.push_back(nullptr);
    char optIn[] = "PASST_ANDROID_NO_ISOLATION=1";
    char* envp[] { optIn, nullptr };

    int stream[2];
    if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, stream) < 0) {
        throwIoException(env, "cannot create passt transport", errno);
        return nullptr;
    }
    jintArray result = env->NewIntArray(2);
    if (result == nullptr) {
        close(stream[0]);
        close(stream[1]);
        return nullptr;
    }
    pid_t parent = getpid();
    pid_t pid = fork();
    if (pid == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent) {
            _exit(127);
        }
        close(stream[0]);
        constexpr int passtFd = 3;
        if (!moveFdAbove(stream[1], passtFd)) {
            _exit(126);
        }
        int devNull = open("/dev/null", O_RDWR | O_CLOEXEC);
        if (!moveFdAbove(devNull, passtFd)
                || dup2(devNull, STDIN_FILENO) < 0
                || dup2(devNull, STDOUT_FILENO) < 0
                || dup2(devNull, STDERR_FILENO) < 0
                || dup2(stream[1], passtFd) < 0) {
            _exit(126);
        }
        closeInheritedFds(passtFd + 1);
        sigset_t emptyMask;
        sigemptyset(&emptyMask);
        sigprocmask(SIG_SETMASK, &emptyMask, nullptr);
        execve(pointers.front(), pointers.data(), envp);
        _exit(127);
    }
    int saved = errno;
    close(stream[1]);
    if (pid < 0) {
        close(stream[0]);
        throwIoException(env, "cannot spawn passt", saved);
        return nullptr;
    }
    jint values[] { pid, stream[0] };
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_orchestella_UmlPasstNetwork_nativePumpPasst(
        JNIEnv* /*env*/, jclass /*clazz*/, jint guestFd, jint streamFd) {
    pumpPasstFrames(guestFd, streamFd);
}

JNIEXPORT void JNICALL
Java_io_github_nyzeep_orchestella_UmlPasstNetwork_nativeStopPump(
        JNIEnv* /*env*/, jclass /*clazz*/, jint streamFd) {
    shutdown(streamFd, SHUT_RDWR);
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_orchestella_UmlPasstNetwork_nativeReapPasst(
        JNIEnv* /*env*/, jclass /*clazz*/, jint pid, jboolean force) {
    if (pid <= 0) {
        return JNI_TRUE;
    }
    pid_t waited;
    do {
        waited = waitpid(pid, nullptr, WNOHANG);
    } while (waited < 0 && errno == EINTR);
    if (waited < 0 && errno == ECHILD) {
        return JNI_TRUE;
    }
    if (waited == 0 && force) {
        // No other waiter owns this child. A signal cannot hit a reused pid:
        // even if it exits here, the child is a zombie until the next waitpid.
        kill(pid, SIGKILL);
    }
    return waited == pid ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
