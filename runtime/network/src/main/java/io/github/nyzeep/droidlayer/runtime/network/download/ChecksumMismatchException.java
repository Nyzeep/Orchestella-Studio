package io.github.nyzeep.droidlayer.runtime.network.download;

import java.io.IOException;

/**
 * Thrown when downloaded content fails SHA-256 integrity validation.
 */
public class ChecksumMismatchException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String expectedHash;
    private final String actualHash;

    public ChecksumMismatchException(String message, String expectedHash, String actualHash) {
        super(message);
        this.expectedHash = expectedHash;
        this.actualHash = actualHash;
    }

    public String getExpectedHash() {
        return expectedHash;
    }

    public String getActualHash() {
        return actualHash;
    }
}
