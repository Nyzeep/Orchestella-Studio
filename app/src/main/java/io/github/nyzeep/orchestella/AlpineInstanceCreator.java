package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
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
        this(manifest, controller, privateDirectory, ROOT_VOLUME_BYTES, new StorageContract.DefaultStorageSpaceChecker());
    }

    public AlpineInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory,
            StorageContract.StorageSpaceChecker spaceChecker) {
        this(manifest, controller, privateDirectory, ROOT_VOLUME_BYTES, spaceChecker);
    }

    public AlpineInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory,
            long rootVolumeBytes) {
        this(manifest, controller, privateDirectory, rootVolumeBytes, new StorageContract.DefaultStorageSpaceChecker());
    }

    public AlpineInstanceCreator(
            DistroManifest manifest,
            InstanceLifecycle.Controller controller,
            Path privateDirectory,
            long rootVolumeBytes,
            StorageContract.StorageSpaceChecker spaceChecker) {
        Objects.requireNonNull(manifest, "manifest");
        if (!"alpine".equals(manifest.id()) || !"3.24.1".equals(manifest.version())
                || !"aarch64".equals(manifest.architecture())) {
            throw new IllegalArgumentException("unsupported Alpine manifest");
        }
        this.delegate = new DistroInstanceCreator(manifest, controller, privateDirectory, rootVolumeBytes, spaceChecker);
    }

    public InstanceLifecycle.Instance create(InputStream archive, long declaredSize) throws IOException {
        return delegate.create(archive, declaredSize);
    }
}
