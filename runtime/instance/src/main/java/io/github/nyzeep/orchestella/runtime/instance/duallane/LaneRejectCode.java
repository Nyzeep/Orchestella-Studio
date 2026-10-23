package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Fixed admission rejection codes. */
public enum LaneRejectCode {
    NONE,
    CPU_EXCEEDED,
    RAM_EXCEEDED,
    DISK_EXCEEDED,
    ACCELERATOR_UNAVAILABLE,
    INVALID_REQUEST
}
