package io.github.nyzeep.orchestella.runtime.fastlane.storage;

/** Result of one committed export. */
public final class ExportResult {
    private final ExportManifest manifest;
    private final String archiveName;
    private final long archiveBytes;
    private final String archiveSha256;

    public ExportResult(
            ExportManifest manifest, String archiveName, long archiveBytes, String archiveSha256) {
        this.manifest = manifest;
        this.archiveName = archiveName;
        this.archiveBytes = archiveBytes;
        this.archiveSha256 = archiveSha256;
    }

    public ExportManifest manifest() {
        return manifest;
    }

    public String archiveName() {
        return archiveName;
    }

    public long archiveBytes() {
        return archiveBytes;
    }

    public String archiveSha256() {
        return archiveSha256;
    }
}
