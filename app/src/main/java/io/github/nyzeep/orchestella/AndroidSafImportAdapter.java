package io.github.nyzeep.orchestella;

import android.content.Context;
import android.net.Uri;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

/**
 * Adapter integrating Instance backup import and recovery with Android's Storage Access Framework (SAF).
 *
 * <p>Enables cold restore of an instance from a user-selected SAF Document URI (archive ZIP)
 * with on-the-fly SHA-256 integrity verification (Phase 5.0 Task 4 / ADR-0007).
 */
public final class AndroidSafImportAdapter {

    /**
     * Seam for opening input streams from user-selected SAF document URIs.
     */
    public interface DocumentStreamReader {
        InputStream openInputStream(Uri uri) throws IOException;
    }

    private final DocumentStreamReader streamReader;
    private final Clock clock;

    public AndroidSafImportAdapter(DocumentStreamReader streamReader, Clock clock) {
        this.streamReader = Objects.requireNonNull(streamReader, "streamReader");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AndroidSafImportAdapter(Context context, Clock clock) {
        this(
                uri -> {
                    InputStream stream = context.getContentResolver().openInputStream(uri);
                    if (stream == null) {
                        throw new IOException("Failed to open input stream for SAF URI: " + uri);
                    }
                    return stream;
                },
                clock);
    }

    /**
     * Imports an instance archive from a user-selected SAF Document URI into the target root volume,
     * verifying SHA-256 integrity and persisting it into the store via the controller.
     */
    public InstanceLifecycle.Instance importFromDocumentUri(
            Uri documentUri,
            Path targetRootVolume,
            InstanceLifecycle.Controller controller) throws IOException {
        return importFromDocumentUri(documentUri, targetRootVolume, null, controller);
    }

    /**
     * Imports an instance archive from a user-selected SAF Document URI into the target root volume
     * with an explicit instance ID, verifying SHA-256 integrity.
     */
    public InstanceLifecycle.Instance importFromDocumentUri(
            Uri documentUri,
            Path targetRootVolume,
            String targetInstanceId,
            InstanceLifecycle.Controller controller) throws IOException {
        Objects.requireNonNull(documentUri, "documentUri");
        Objects.requireNonNull(targetRootVolume, "targetRootVolume");
        Objects.requireNonNull(controller, "controller");

        try (InputStream in = streamReader.openInputStream(documentUri)) {
            if (in == null) {
                throw new IOException("Cannot open input stream for SAF URI: " + documentUri);
            }
            return controller.importFromArchive(in, targetRootVolume, targetInstanceId);
        }
    }

    /**
     * Imports an instance archive from a user-selected SAF Document URI into an auto-generated
     * root volume under storageDir, verifying SHA-256 integrity and persisting it via the controller.
     */
    public InstanceLifecycle.Instance importArchiveToStorage(
            Uri documentUri,
            Path storageDir,
            InstanceLifecycle.Controller controller) throws IOException {
        Objects.requireNonNull(storageDir, "storageDir");
        String instanceId = java.util.UUID.randomUUID().toString();
        Path targetRootVolume = storageDir.resolve(instanceId + ".raw");
        return importFromDocumentUri(documentUri, targetRootVolume, instanceId, controller);
    }
}
