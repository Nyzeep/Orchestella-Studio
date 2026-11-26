#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

JNIEXPORT jlong JNICALL
Java_io_github_nyzeep_orchestella_spike_w405a_TrackAProbe_openMap(
        JNIEnv *env, jclass klass, jstring path, jint size) {
    (void)klass;
    const char *filename = (*env)->GetStringUTFChars(env, path, NULL);
    if (filename == NULL) {
        return -ENOMEM;
    }
    int fd = open(filename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    int open_errno = errno;
    (*env)->ReleaseStringUTFChars(env, path, filename);
    if (fd == -1) {
        return -open_errno;
    }
    void *mapping = mmap(NULL, (size_t)size, PROT_READ, MAP_SHARED, fd, 0);
    int map_errno = errno;
    close(fd);
    return mapping == MAP_FAILED ? -map_errno : (jlong)(uintptr_t)mapping;
}

JNIEXPORT jboolean JNICALL
Java_io_github_nyzeep_orchestella_spike_w405a_TrackAProbe_markerEquals(
        JNIEnv *env, jclass klass, jlong mapping, jint size, jint offset, jstring marker) {
    (void)klass;
    jsize length = (*env)->GetStringUTFLength(env, marker);
    if (offset < 0 || offset >= size || length >= size - offset) {
        return JNI_FALSE;
    }
    const char *expected = (*env)->GetStringUTFChars(env, marker, NULL);
    if (expected == NULL) {
        return JNI_FALSE;
    }
    const char *mapped = (const char *)(uintptr_t)mapping;
    int equals = memcmp(mapped + offset, expected, (size_t)length + 1) == 0;
    (*env)->ReleaseStringUTFChars(env, marker, expected);
    return equals ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_nyzeep_orchestella_spike_w405a_TrackAProbe_closeMap(
        JNIEnv *env, jclass klass, jlong mapping, jint size) {
    (void)env;
    (void)klass;
    return munmap((void *)(uintptr_t)mapping, (size_t)size) == 0 ? 0 : errno;
}
