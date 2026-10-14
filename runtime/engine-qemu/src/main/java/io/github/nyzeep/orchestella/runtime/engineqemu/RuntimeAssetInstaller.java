package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Installs manifest-verified runtime assets into private storage.
 *
 * <p>Policy QEMU_JNI section 5 requires the expected digest to be verified
 * before a packaged kernel or initramfs becomes usable in private storage.
 * The installer copies the caller-supplied stream to a {@code .partial} file,
 * re-verifies the on-disk bytes through {@link RuntimeArtifactManifest}, and
 * only then atomically moves the file to its final name. Any rejection deletes
 * the partial file and propagates the failure; nothing is left behind.
 */
public final class RuntimeAssetInstaller {
    /** Opens a fresh stream over the packaged asset bytes. */
    public interface Source {
        InputStream open() throws IOException;
    }

    public Path install(RuntimeArtifactManifest manifest, String artifactPath, Source source,
            Path targetDir) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(artifactPath, "artifactPath");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(targetDir, "targetDir");
        if (artifactPath.isEmpty() || artifactPath.indexOf('/') >= 0 || artifactPath.contains("\\")
                || artifactPath.equals(".") || artifactPath.equals("..")) {
            throw new IOException("runtime-asset-installer-invalid-path");
        }
        Files.createDirectories(targetDir);
        Path finalPath = targetDir.resolve(artifactPath);
        if (Files.isRegularFile(finalPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (InputStream check = Files.newInputStream(finalPath)) {
                manifest.requireVerifiedPackagedAsset(artifactPath, check);
                return finalPath;
            } catch (Exception ignored) {
                // If invalid or tampered, proceed to re-extract below
            }
        }
        Path partial = targetDir.resolve(artifactPath + ".partial");
        try {
            try (InputStream in = source.open(); OutputStream out = Files.newOutputStream(partial)) {
                in.transferTo(out);
            }
            // Verify the on-disk bytes through the production verifier: the
            // digest gate sees exactly the bytes that will be used.
            try (InputStream verified = Files.newInputStream(partial)) {
                manifest.requireVerifiedPackagedAsset(artifactPath, verified);
            }
            Files.move(partial, finalPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return finalPath;
        } catch (IOException failure) {
            Files.deleteIfExists(partial);
            throw failure;
        }
    }
}
