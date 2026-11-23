#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
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

bool moveFdAboveFour(int& fd) {
    if (fd < 0) {
        return false;
    }
    if (fd >= 5) {
        return true;
    }
    int moved = fcntl(fd, F_DUPFD_CLOEXEC, 5);
    if (moved < 0) {
        return false;
    }
    close(fd);
    fd = moved;
    return true;
}

void closeInheritedFdsFromFive() {
    long maxFd = 4096;
    struct rlimit rl {};
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur > 5) {
        maxFd = static_cast<long>(rl.rlim_cur);
        if (maxFd > 65536) {
            maxFd = 65536;
        }
    }
    for (int fd = 5; fd < static_cast<int>(maxFd); ++fd) {
        close(fd);
    }
}

void closeIfValid(int fd) {
    if (fd >= 0) {
        close(fd);
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
        jint rows) {
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

    std::vector<char*> argvPtrs;
    argvPtrs.reserve(argvStorage.size() + 1);
    for (std::string& arg : argvStorage) {
        argvPtrs.push_back(const_cast<char*>(arg.c_str()));
    }
    argvPtrs.push_back(nullptr);

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

    pid_t parentPid = getpid();
    pid_t pid = fork();
    if (pid < 0) {
        int saved = errno;
        closeIfValid(ctrlIn[0]);
        closeIfValid(ctrlIn[1]);
        closeIfValid(ctrlOut[0]);
        closeIfValid(ctrlOut[1]);
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

        setsid();
        int slaveFd = open(slaveName, O_RDWR);
        if (slaveFd < 0) {
            _exit(126);
        }
        ioctl(slaveFd, TIOCSCTTY, 0);

        if (!moveFdAboveFour(slaveFd)
                || !moveFdAboveFour(ctrlIn[0])
                || !moveFdAboveFour(ctrlOut[1])) {
            _exit(126);
        }

        if (dup2(slaveFd, STDIN_FILENO) < 0
                || dup2(slaveFd, STDOUT_FILENO) < 0
                || dup2(slaveFd, STDERR_FILENO) < 0
                || dup2(ctrlIn[0], 3) < 0
                || dup2(ctrlOut[1], 4) < 0) {
            _exit(126);
        }

        closeIfValid(slaveFd);
        closeIfValid(ctrlIn[0]);
        closeIfValid(ctrlOut[1]);
        closeInheritedFdsFromFive();

        if (chdir(cwdStr.c_str()) != 0) {
            _exit(125);
        }
        execve(execStr.c_str(), argvPtrs.data(), envpPtrs.data());
        _exit(127);
    }

    closeIfValid(ctrlIn[0]);
    closeIfValid(ctrlOut[1]);

    jintArray result = env->NewIntArray(4);
    if (result == nullptr) {
        closeIfValid(masterFd);
        closeIfValid(ctrlIn[1]);
        closeIfValid(ctrlOut[0]);
        if (kill(-pid, SIGKILL) != 0) {
            kill(pid, SIGKILL);
        }
        waitpid(pid, nullptr, 0);
        return nullptr;
    }
    jint values[4] = {
        static_cast<jint>(pid),
        static_cast<jint>(masterFd),
        static_cast<jint>(ctrlIn[1]),
        static_cast<jint>(ctrlOut[0]),
    };
    env->SetIntArrayRegion(result, 0, 4, values);
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

}  // extern "C"
