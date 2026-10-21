package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;

/**
 * Host-side interface to the guest staging importer. A P02 adapter maps these
 * calls to IMPORT_BEGIN/DATA/END frames; the guest applies the archive with
 * rollback and reports a typed result.
 */
public interface GuestImportSink {
    /** Unknown total size; the guest streams into staging until end(). */
    void begin() throws IOException;

    void write(byte[] data, int offset, int length) throws IOException;

    void end(long totalBytes, byte[] sha256) throws IOException;

    void abort();
}
