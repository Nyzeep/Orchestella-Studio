package io.github.nyzeep.orchestella.runtime.fastlane.storage;

/** Result of one import streamed to the guest. */
public final class ImportResult {
    private final ExportManifest manifest;
    private final long archiveBytes;
    private final String archiveSha256;

    public ImportResult(ExportManifest manifest, long archiveBytes, String archiveSha256) {
        this.manifest = manifest;
        this.archiveBytes = archiveBytes;
        this.archiveSha256 = archiveSha256;
    }

    public ExportManifest manifest() {
        return manifest;
    }

    public long archiveBytes() {
        return archiveBytes;
    }

    public String archiveSha256() {
        return archiveSha256;
    }
}
