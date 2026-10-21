package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;
import java.util.Objects;

/** A typed storage failure; messages never contain file contents or credentials. */
public final class StorageException extends Exception {
    private static final long serialVersionUID = 1L;

    private final StorageFailure failure;

    public StorageException(StorageFailure failure, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public StorageException(StorageFailure failure, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public StorageFailure failure() {
        return failure;
    }

    public static StorageException io(IOException cause) {
        return new StorageException(StorageFailure.IO_FAILED, "storage I/O failed", cause);
    }
}
