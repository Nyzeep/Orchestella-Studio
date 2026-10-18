package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.EmbeddedProvisionInput;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeAssetInstaller;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
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
    /** Opener seam so tests can prove the credentials-only path never touches the tarball. */
    @FunctionalInterface
    interface RootfsStreamOpener {
        InputStream open(Path path) throws IOException;
    }

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
    private final java.util.List<DistroManifest> distroManifests;
    private final RootfsStreamOpener rootfsStreams;

    public QemuRuntimeBootstrapper(RuntimeArtifactManifest manifest,
            java.util.function.Function<String, InputStream> assetStreams,
            java.util.function.Function<String, InputStream> resourceStreams,
            Path runtimeDir, Path distroInputsDir) {
        this(manifest, assetStreams, resourceStreams, runtimeDir, distroInputsDir,
                DistroCatalogRegistry.createDefault().listAll());
    }

    /** Supplies a versioned, trusted manifest catalog; never derive it from downloaded files. */
    public QemuRuntimeBootstrapper(RuntimeArtifactManifest manifest,
            java.util.function.Function<String, InputStream> assetStreams,
            java.util.function.Function<String, InputStream> resourceStreams,
            Path runtimeDir, Path distroInputsDir, java.util.List<DistroManifest> distroManifests) {
        this(manifest, assetStreams, resourceStreams, runtimeDir, distroInputsDir, distroManifests,
                Files::newInputStream);
    }

    /** Test seam: an opener that must never be called on the credentials-only path. */
    QemuRuntimeBootstrapper(RuntimeArtifactManifest manifest,
            java.util.function.Function<String, InputStream> assetStreams,
            java.util.function.Function<String, InputStream> resourceStreams,
            Path runtimeDir, Path distroInputsDir, java.util.List<DistroManifest> distroManifests,
            RootfsStreamOpener rootfsStreams) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.assetStreams = Objects.requireNonNull(assetStreams, "assetStreams");
        this.resourceStreams = Objects.requireNonNull(resourceStreams, "resourceStreams");
        this.runtimeDir = Objects.requireNonNull(runtimeDir, "runtimeDir");
        this.distroInputsDir = Objects.requireNonNull(distroInputsDir, "distroInputsDir");
        this.distroManifests = java.util.List.copyOf(distroManifests);
        this.rootfsStreams = Objects.requireNonNull(rootfsStreams, "rootfsStreams");
    }

    public RuntimePaths bootstrap(String rootfsFileName, String bridgeToken, Path instanceRootVolume)
            throws IOException {
        return bootstrap(rootfsFileName, bridgeToken, null, instanceRootVolume);
    }

    public RuntimePaths bootstrap(
            String rootfsFileName, String bridgeToken, String guestPassword, Path instanceRootVolume)
            throws IOException {
        return bootstrap(rootfsFileName, bridgeToken, guestPassword, instanceRootVolume, null, null, null);
    }

    public RuntimePaths bootstrap(
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            Path instanceRootVolume,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript)
            throws IOException {
        return bootstrap(rootfsFileName, bridgeToken, guestPassword, instanceRootVolume,
                mirrorConfigFileName, mirrorConfigFileContent, firstBootScript, null, null, false);
    }

    public RuntimePaths bootstrap(
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            Path instanceRootVolume,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            String hostname,
            String guestUser,
            boolean hasSudo)
            throws IOException {
        return bootstrap(rootfsFileName, bridgeToken, guestPassword, instanceRootVolume,
                mirrorConfigFileName, mirrorConfigFileContent, firstBootScript,
                hostname, guestUser, hasSudo, null);
    }

    /** Optional launch timeline; {@code null} keeps stage recording disabled. */
    public RuntimePaths bootstrap(
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            Path instanceRootVolume,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            String hostname,
            String guestUser,
            boolean hasSudo,
            LaunchTimeline timeline)
            throws IOException {
        return bootstrap(rootfsFileName, bridgeToken, guestPassword, instanceRootVolume,
                mirrorConfigFileName, mirrorConfigFileContent, firstBootScript,
                hostname, guestUser, hasSudo, timeline, false);
    }

    /**
     * Bootstraps launch inputs, optionally for an instance whose guest root volume already
     * reports {@code provisioning_version=1}. The credentials-only path must not open the
     * distribution tarball; callers remain responsible for producing trustworthy evidence.
     */
    public RuntimePaths bootstrap(
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            Path instanceRootVolume,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            String hostname,
            String guestUser,
            boolean hasSudo,
            LaunchTimeline timeline,
            boolean credentialsOnly)
            throws IOException {
        String validatedHostname = hostname == null ? null
                : io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.normalizeHostname(hostname);
        if (guestUser != null && !io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.isValidUsername(guestUser)) {
            throw new IllegalArgumentException("invalid guest username");
        }
        Objects.requireNonNull(instanceRootVolume, "instanceRootVolume");
        if (!Files.isRegularFile(instanceRootVolume)) {
            throw new IOException("root-volume-missing");
        }
        DistroManifest distroManifest = requireDistroManifest(rootfsFileName);
        Path rootfsPath = distroInputsDir.resolve(rootfsFileName);
        if (!credentialsOnly) {
            // Stat and size checks are part of the full path. An already provisioned
            // instance must neither open nor depend on the downloaded tarball being present.
            if (!Files.isRegularFile(rootfsPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("rootfs-file-missing");
            }
            distroManifest.requireExpectedSize(Files.size(rootfsPath));
        }
        RuntimeAssetInstaller installer = new RuntimeAssetInstaller();
        Path kernel = installer.install(manifest, "Image",
                () -> assetStreams.apply("Image"), runtimeDir);
        Path baseInitramfs = installer.install(manifest, "initramfs.cpio.gz",
                () -> resourceStreams.apply("initramfs.cpio.gz"), runtimeDir);
        if (timeline != null) timeline.mark(LaunchTimeline.Stage.ASSETS_VERIFIED);

        byte[] guestInit = null;
        try (InputStream in = resourceStreams.apply("guest_init")) {
            if (in != null) {
                guestInit = readGuestScript(in);
            }
        } catch (Exception failure) {
            throw new IOException("guest-init-unavailable", failure);
        }

        byte[] proxyRelay = null;
        try (InputStream in = resourceStreams.apply("proxy_relay")) {
            if (in != null) {
                proxyRelay = readGuestScript(in);
            }
        } catch (Exception ignored) {
        }

        // The manifest is the trust anchor; observed download metadata is used only for comparison.
        long rootfsSize = distroManifest.sizeBytes();
        Path initramfs = runtimeDir.resolve("initramfs-embedded.cpio.gz");
        Path partial = runtimeDir.resolve("initramfs-embedded.cpio.gz.partial");
        try {
            try (InputStream base = Files.newInputStream(baseInitramfs)) {
                Files.copy(base, partial);
            }
            if (credentialsOnly) {
                try (java.io.OutputStream out = Files.newOutputStream(partial,
                        java.nio.file.StandardOpenOption.APPEND)) {
                    EmbeddedProvisionInput.writeCredentialsOnlySegment(
                            rootfsSize,
                            rootfsFileName,
                            bridgeToken,
                            guestPassword,
                            guestInit,
                            proxyRelay,
                            validatedHostname,
                            guestUser,
                            hasSudo,
                            mirrorConfigFileName,
                            mirrorConfigFileContent,
                            firstBootScript,
                            distroManifest.sha256(),
                            out);
                }
            } else {
                try (InputStream rootfsIn = rootfsStreams.open(rootfsPath);
                     java.io.OutputStream out = Files.newOutputStream(partial,
                             java.nio.file.StandardOpenOption.APPEND)) {
                    EmbeddedProvisionInput.writeSegment(
                            rootfsIn,
                            rootfsSize,
                            rootfsFileName,
                            bridgeToken,
                            guestPassword,
                            guestInit,
                            proxyRelay,
                            validatedHostname,
                            guestUser,
                            hasSudo,
                            mirrorConfigFileName,
                            mirrorConfigFileContent,
                            firstBootScript,
                            distroManifest.sha256(),
                            out);
                }
            }
            Files.move(partial, initramfs, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        if (credentialsOnly) {
            long segmentBytes = Files.size(initramfs) - Files.size(baseInitramfs);
            if (segmentBytes > EmbeddedProvisionInput.CREDENTIALS_ONLY_MAX_BYTES) {
                try {
                    Files.deleteIfExists(initramfs);
                } catch (IOException cleanupFailure) {
                    // The size violation is the primary failure.
                }
                throw new IOException("credentials-only-initramfs-too-large");
            }
        }
        if (timeline != null) timeline.markInitramfsBuilt(credentialsOnly ? 0L : rootfsSize);
        return new RuntimePaths(kernel, initramfs, instanceRootVolume);
    }

    private static byte[] readGuestScript(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count == 0) {
                int value = input.read();
                if (value == -1) break;
                if (output.size() >= 1024 * 1024) throw new IOException("guest-script-too-large");
                output.write(value);
            } else {
                if (count > 1024 * 1024 - output.size()) throw new IOException("guest-script-too-large");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private DistroManifest requireDistroManifest(String rootfsFileName) throws IOException {
        if (rootfsFileName == null || !rootfsFileName.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IOException("rootfs-manifest-unknown");
        }
        DistroManifest selected = null;
        for (DistroManifest candidate : distroManifests) {
            if (candidate.rootfsFileName().equals(rootfsFileName)) {
                if (selected != null) {
                    throw new IOException("rootfs-manifest-ambiguous");
                }
                selected = candidate;
            }
        }
        if (selected == null) {
            throw new IOException("rootfs-manifest-unknown");
        }
        return selected;
    }
}
