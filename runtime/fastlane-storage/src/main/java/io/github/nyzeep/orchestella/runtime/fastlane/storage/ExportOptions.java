package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.util.Objects;

/** Export naming and encoding options. */
public final class ExportOptions {
    private final String archiveName;
    private final String manifestName;
    private final boolean gzip;

    public ExportOptions(String archiveName, String manifestName, boolean gzip) {
        Objects.requireNonNull(archiveName, "archiveName");
        Objects.requireNonNull(manifestName, "manifestName");
        if (!StoragePaths.isSafeRelative(archiveName) || !StoragePaths.isSafeRelative(manifestName)) {
            throw new IllegalArgumentException("export names must be safe relative names");
        }
        if (archiveName.equals(manifestName)) {
            throw new IllegalArgumentException("archive and manifest names must differ");
        }
        this.archiveName = archiveName;
        this.manifestName = manifestName;
        this.gzip = gzip;
    }

    public static ExportOptions tar(String baseName) {
        return new ExportOptions(baseName + ".tar", baseName + ".manifest.json", false);
    }

    public static ExportOptions tarGz(String baseName) {
        return new ExportOptions(baseName + ".tar.gz", baseName + ".manifest.json", true);
    }

    public String archiveName() {
        return archiveName;
    }

    public String manifestName() {
        return manifestName;
    }

    public boolean gzip() {
        return gzip;
    }
}
