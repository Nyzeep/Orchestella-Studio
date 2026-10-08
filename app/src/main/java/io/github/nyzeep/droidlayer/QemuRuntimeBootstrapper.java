package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.engineqemu.EmbeddedProvisionInput;
import io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeAssetInstaller;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Prepares the verified QEMU runtime payload in private storage before a
 * launch: extracts the manifest-verified kernel and initramfs (policy section
 * 5: digest verification before anything becomes usable), derives the
 * embedded-provision initramfs (verified base + gzipped cpio input segment
 * carrying this instance's rootfs tarball and bridge token), and exposes the
 * launch paths. Nothing here executes QEMU.
 */
public final class QemuRuntimeBootstrapper {
    /** Launch paths for one instance. */
    public static final class RuntimePaths {
        public final Path kernel;
        public final Path initramfs;
        public final Path rootVolume;

        RuntimePaths(Path kernel, Path initramfs, Path rootVolume) {
            this.kernel = kernel;
            this.initramfs = initramfs;
            this.rootVolume = rootVolume;
        }
    }

    private final RuntimeArtifactManifest manifest;
    private final java.util.function.Function<String, InputStream> assetStreams;
    private final java.util.function.Function<String, InputStream> resourceStreams;
    private final Path runtimeDir;
    private final Path distroInputsDir;

    public QemuRuntimeBootstrapper(RuntimeArtifactManifest manifest,
            java.util.function.Function<String, InputStream> assetStreams,
            java.util.function.Function<String, InputStream> resourceStreams,
            Path runtimeDir, Path distroInputsDir) {
        this.manifest = manifest;
        this.assetStreams = assetStreams;
        this.resourceStreams = resourceStreams;
        this.runtimeDir = runtimeDir;
        this.distroInputsDir = distroInputsDir;
    }

    public RuntimePaths bootstrap(String rootfsFileName, String bridgeToken, Path instanceRootVolume)
            throws IOException {
        return bootstrap(rootfsFileName, bridgeToken, null, instanceRootVolume);
    }

    public RuntimePaths bootstrap(
            String rootfsFileName, String bridgeToken, String guestPassword, Path instanceRootVolume)
            throws IOException {
        Objects.requireNonNull(instanceRootVolume, "instanceRootVolume");
        if (!Files.isRegularFile(instanceRootVolume)) {
            throw new IOException("root-volume-missing");
        }
        RuntimeAssetInstaller installer = new RuntimeAssetInstaller();
        Path kernel = installer.install(manifest, "Image",
                () -> assetStreams.apply("Image"), runtimeDir);
        Path baseInitramfs = installer.install(manifest, "initramfs.cpio.gz",
                () -> resourceStreams.apply("initramfs.cpio.gz"), runtimeDir);

        byte[] guestInit = null;
        try (InputStream in = resourceStreams.apply("guest_init")) {
            if (in != null) {
                guestInit = in.readAllBytes();
            }
        } catch (Exception ignored) {
        }

        byte[] proxyRelay = null;
        try (InputStream in = resourceStreams.apply("proxy_relay")) {
            if (in != null) {
                proxyRelay = in.readAllBytes();
            }
        } catch (Exception ignored) {
        }

        Path rootfsPath = distroInputsDir.resolve(rootfsFileName);
        if (!Files.isRegularFile(rootfsPath)) {
            throw new IOException("rootfs-file-missing: " + rootfsFileName);
        }
        long rootfsSize = Files.size(rootfsPath);
        Path initramfs = runtimeDir.resolve("initramfs-embedded.cpio.gz");
        Path partial = runtimeDir.resolve("initramfs-embedded.cpio.gz.partial");
        try {
            try (InputStream base = Files.newInputStream(baseInitramfs)) {
                Files.copy(base, partial);
            }
            try (InputStream rootfsIn = Files.newInputStream(rootfsPath);
                 java.io.OutputStream out = Files.newOutputStream(partial,
                         java.nio.file.StandardOpenOption.APPEND)) {
                EmbeddedProvisionInput.writeSegment(
                        rootfsIn, rootfsSize, rootfsFileName, bridgeToken, guestPassword, guestInit, proxyRelay, out);
            }
            Files.move(partial, initramfs, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            Files.deleteIfExists(partial);
            throw failure;
        }
        return new RuntimePaths(kernel, initramfs, instanceRootVolume);
    }
}
