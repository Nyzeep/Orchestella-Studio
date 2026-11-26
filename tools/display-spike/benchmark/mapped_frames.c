#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

JNIEXPORT jobject JNICALL
Java_io_github_nyzeep_orchestella_spike_w405bench_BenchRun_mapReadOnly(
        JNIEnv *env, jclass type, jstring path, jint size) {
    (void)type;
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) return NULL;
    int fd = open(name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    (*env)->ReleaseStringUTFChars(env, path, name);
    if (fd < 0) return NULL;
    struct stat info;
    if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size != size) {
        close(fd);
        return NULL;
    }
    void *data = mmap(NULL, (size_t)size, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (data == MAP_FAILED) return NULL;
    jobject result = (*env)->NewDirectByteBuffer(env, data, size);
    if (!result) munmap(data, (size_t)size);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_nyzeep_orchestella_spike_w405bench_BenchRun_unmap(
        JNIEnv *env, jclass type, jobject buffer) {
    (void)type;
    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    jlong size = (*env)->GetDirectBufferCapacity(env, buffer);
    if (!data || size <= 0) return EINVAL;
    return munmap(data, (size_t)size) == 0 ? 0 : errno;
}
