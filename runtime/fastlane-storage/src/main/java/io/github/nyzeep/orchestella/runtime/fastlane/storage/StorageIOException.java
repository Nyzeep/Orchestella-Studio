package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;

/** Carries a typed StorageException through an InputStream boundary. */
final class StorageIOException extends IOException {
    private static final long serialVersionUID = 1L;

    private final transient StorageException storage;

    StorageIOException(StorageException storage) {
        super(storage.getMessage(), storage);
        this.storage = storage;
    }

    StorageException storage() {
        return storage;
    }
}
