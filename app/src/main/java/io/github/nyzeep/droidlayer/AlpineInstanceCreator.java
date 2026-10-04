package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.distro.DistroManifest;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Alpine 3.24.1 specific instance creator adhering to the {@link DistroInstanceCreator} pattern.
 */
public final class AlpineInstanceCreator {
    public static final long ROOT_VOLUME_BYTES = DistroInstanceCreator.DEFAULT_ROOT_VOLUME_BYTES;

    private final DistroInstanceCreator delegate;

    public AlpineInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory) {
        this(manifest, controller, privateDirectory, ROOT_VOLUME_BYTES);
    }

    public AlpineInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory,
            long rootVolumeBytes) {
        Objects.requireNonNull(manifest, "manifest");
        if (!"alpine".equals(manifest.id()) || !"3.24.1".equals(manifest.version())
                || !"aarch64".equals(manifest.architecture())) {
            throw new IllegalArgumentException("unsupported Alpine manifest");
        }
        this.delegate = new DistroInstanceCreator(manifest, controller, privateDirectory, rootVolumeBytes);
    }

    public InstanceLifecycle.Instance create(InputStream archive, long declaredSize) throws IOException {
        return delegate.create(archive, declaredSize);
    }
}
