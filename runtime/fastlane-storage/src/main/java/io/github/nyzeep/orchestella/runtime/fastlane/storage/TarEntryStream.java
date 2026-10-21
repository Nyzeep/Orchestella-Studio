package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/** Adapter from a tar entry to an InputStream while hashing and checking cancel. */
final class TarEntryStream extends InputStream {
    private final TarReader reader;
    private final MessageDigest digest;
    private final CancellationToken token;

    TarEntryStream(TarReader reader, MessageDigest digest, CancellationToken token) {
        this.reader = reader;
        this.digest = digest;
        this.token = token;
    }

    @Override
    public int read() throws IOException {
        final byte[] one = new byte[1];
        final int read = read(one, 0, 1);
        return read < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (token.isCancelled()) {
            throw new StorageIOException(new StorageException(
                    StorageFailure.CANCELLED, "operation cancelled"));
        }
        try {
            final int read = reader.read(buffer, offset, length);
            if (read > 0) {
                digest.update(buffer, offset, read);
            }
            return read;
        } catch (StorageException failure) {
            throw new StorageIOException(failure);
        }
    }
}
