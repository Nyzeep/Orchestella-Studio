package io.github.nyzeep.orchestella.runtime.fastlane.storage;

/** Typed storage failures at the Fast Lane boundary. */
public enum StorageFailure {
    PATH_REJECTED,
    MANIFEST_INVALID,
    LIMIT_EXCEEDED,
    UNSUPPORTED_FORMAT,
    LAYOUT_INVALID,
    IO_FAILED,
    CANCELLED
}
