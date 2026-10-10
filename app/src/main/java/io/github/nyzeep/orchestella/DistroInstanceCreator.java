package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Strategy-neutral, multi-distro instance creator driven by {@link DistroManifest}.
 *
 * <p>Verifies the rootfs input against the pinned manifest before any persistent
 * root volume or instance metadata is created. Fails closed on any hash or size mismatch.
 */
public class DistroInstanceCreator {
    public static final long DEFAULT_ROOT_VOLUME_BYTES = 2L * 1024L * 1024L * 1024L;

    private final DistroManifest manifest;
    private final InstanceLifecycle.Controller controller;
    private final Path privateDirectory;
    private final long rootVolumeBytes;
    private final StorageContract.StorageSpaceChecker spaceChecker;

    public DistroInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory) {
        this(manifest, controller, privateDirectory, DEFAULT_ROOT_VOLUME_BYTES, new StorageContract.DefaultStorageSpaceChecker());
    }

    public DistroInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory,
            long rootVolumeBytes) {
        this(manifest, controller, privateDirectory, rootVolumeBytes, new StorageContract.DefaultStorageSpaceChecker());
    }

    public DistroInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory,
            StorageContract.StorageSpaceChecker spaceChecker) {
        this(manifest, controller, privateDirectory, DEFAULT_ROOT_VOLUME_BYTES, spaceChecker);
    }

    public DistroInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory,
            long rootVolumeBytes,
            StorageContract.StorageSpaceChecker spaceChecker) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.privateDirectory = Objects.requireNonNull(privateDirectory, "privateDirectory").toAbsolutePath().normalize();
        if (rootVolumeBytes <= 0) {
            throw new IllegalArgumentException("rootVolumeBytes must be positive");
        }
        this.rootVolumeBytes = rootVolumeBytes;
        this.spaceChecker = Objects.requireNonNull(spaceChecker, "spaceChecker");
    }

    public DistroManifest manifest() {
        return manifest;
    }

    public long rootVolumeBytes() {
        return rootVolumeBytes;
    }

    public StorageContract.StorageSpaceChecker spaceChecker() {
        return spaceChecker;
    }

    public InstanceLifecycle.Instance create(InputStream archive, long declaredSize) throws IOException {
        return create(archive, declaredSize, VmHardwareConfig.defaultConfig());
    }

    public InstanceLifecycle.Instance create(InputStream archive, long declaredSize, VmHardwareConfig hardwareConfig) throws IOException {
        return create(archive, declaredSize, hardwareConfig, io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.defaultRoot());
    }

    public InstanceLifecycle.Instance create(
            InputStream archive,
            long declaredSize,
            VmHardwareConfig hardwareConfig,
            io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig userConfig) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(hardwareConfig, "hardwareConfig");
        Objects.requireNonNull(userConfig, "userConfig");
        manifest.requireExpectedSize(declaredSize);
        Path inputs = privateDirectory.resolve("distro-inputs");
        Files.createDirectories(inputs);
        Path partial = Files.createTempFile(inputs, manifest.id() + "-", ".partial");
        try {
            try (OutputStream staged = Files.newOutputStream(partial, StandardOpenOption.WRITE);
                    InputStream tee = new FilterInputStream(archive) {
                        @Override
                        public int read() throws IOException {
                            int value = super.read();
                            if (value >= 0) staged.write(value);
                            return value;
                        }

                        @Override
                        public int read(byte[] bytes, int offset, int length) throws IOException {
                            int count = in.read(bytes, offset, length);
                            if (count > 0) staged.write(bytes, offset, count);
                            return count;
                        }
                    }) {
                manifest.requireVerified(tee, declaredSize);
            }
            Path verified = inputs.resolve(manifest.rootfsFileName());
            try {
                Files.move(partial, verified, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(partial, verified, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }

        Path volumes = privateDirectory.resolve("root-volumes");
        Files.createDirectories(volumes);
        StorageContract.StoragePreflight.checkCreationSpace(spaceChecker, volumes, rootVolumeBytes);
        Path volume = volumes.resolve(UUID.randomUUID() + ".raw");
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(volume, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(rootVolumeBytes - 1L);
                channel.write(ByteBuffer.wrap(new byte[] {0}));
            }
            return controller.create(
                    new InstanceLifecycle.DistroReference(manifest.id(), manifest.version()),
                    volume,
                    hardwareConfig,
                    userConfig);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(volume);
            throw failure;
        }
    }
}
