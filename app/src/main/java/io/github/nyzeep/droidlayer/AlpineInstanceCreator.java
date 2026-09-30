package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.distro.DistroManifest;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
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

public final class AlpineInstanceCreator {
    public static final long ROOT_VOLUME_BYTES = 2L * 1024L * 1024L * 1024L;

    private final DistroManifest manifest;
    private final InstanceLifecycle.Controller controller;
    private final Path privateDirectory;

    public AlpineInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.privateDirectory = Objects.requireNonNull(privateDirectory, "privateDirectory").toAbsolutePath().normalize();
        if (!"alpine".equals(manifest.id()) || !"3.24.1".equals(manifest.version())
                || !"aarch64".equals(manifest.architecture())) {
            throw new IllegalArgumentException("unsupported Alpine manifest");
        }
    }

    public InstanceLifecycle.Instance create(InputStream archive, long declaredSize) throws IOException {
        Objects.requireNonNull(archive, "archive");
        manifest.requireExpectedSize(declaredSize);
        Path inputs = privateDirectory.resolve("distro-inputs");
        Files.createDirectories(inputs);
        Path partial = Files.createTempFile(inputs, "alpine-", ".partial");
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
        Path volume = volumes.resolve(UUID.randomUUID() + ".raw");
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(volume, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(ROOT_VOLUME_BYTES - 1L);
                channel.write(ByteBuffer.wrap(new byte[] {0}));
            }
            return controller.create(new InstanceLifecycle.DistroReference(manifest.id(), manifest.version()), volume);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(volume);
            throw failure;
        }
    }
}
