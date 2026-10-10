package io.github.nyzeep.orchestella.runtime.distro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Unified catalog registry managing the supported official Linux distribution manifests.
 */
public final class DistroCatalogRegistry {

    private static final DistroCatalogRegistry INSTANCE = createDefault();

    private final Map<String, DistroManifest> manifests;

    public DistroCatalogRegistry() {
        this.manifests = new LinkedHashMap<>();
    }

    public static DistroCatalogRegistry getInstance() {
        return INSTANCE;
    }

    public static DistroCatalogRegistry createNewRegistry() {
        return new DistroCatalogRegistry();
    }

    public static DistroCatalogRegistry createDefault() {
        DistroCatalogRegistry registry = new DistroCatalogRegistry();
        registry.register(OfficialUbuntuCatalog.ubuntu());
        registry.register(OfficialDebianCatalog.debian());
        registry.register(OfficialArchCatalog.arch());
        registry.register(OfficialAlpineCatalog.alpine());
        registry.register(OfficialFedoraCatalog.fedora());
        registry.register(OfficialOpenSuseCatalog.opensuse());
        registry.register(OfficialKaliCatalog.kali());
        return registry;
    }

    public synchronized void register(DistroManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        manifests.put(manifest.id(), manifest);
    }

    public synchronized DistroManifest get(String distroId) {
        if (distroId == null) {
            return null;
        }
        return manifests.get(distroId.toLowerCase());
    }

    public synchronized DistroManifest require(String distroId) {
        DistroManifest manifest = get(distroId);
        if (manifest == null) {
            throw new IllegalArgumentException("unsupported distro id: " + distroId);
        }
        return manifest;
    }

    public synchronized boolean contains(String distroId) {
        if (distroId == null) {
            return false;
        }
        return manifests.containsKey(distroId.toLowerCase());
    }

    public synchronized List<DistroManifest> listAll() {
        return Collections.unmodifiableList(new ArrayList<>(manifests.values()));
    }

    public synchronized Set<String> supportedDistroIds() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(manifests.keySet()));
    }

    public PackageManagerType getPackageManager(String distroId) {
        return PackageManagerType.forDistroId(distroId);
    }
}
