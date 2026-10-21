package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Host-side sink for one export. The archive and manifest are separate streams;
 * {@link #commit()} makes them visible, {@link #abort()} must leave no partial
 * files. SAF adapters implement this over a user-selected document or tree.
 */
public interface ExportSink {
    OutputStream openArchive(String suggestedName) throws IOException;

    OutputStream openManifest(String suggestedName) throws IOException;

    void commit() throws IOException;

    void abort() throws IOException;
}
