package io.github.nyzeep.orchestella;

import android.util.Log;
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;

/**
 * Launch-time provisioning evidence for one instance, extracted verbatim from
 * {@link QemuInstanceLauncher} so the catalog/digest reading and the persisted
 * evidence recording evolve in one focused place (the InstanceLifecycle
 * file-store seam of the launch path).
 *
 * <p>Package-private on purpose: the launch path is exercised only through
 * {@link QemuInstanceLauncher}, whose static delegates keep the original API;
 * nothing here changes behavior or the public surface.
 */
final class QemuLaunchEvidence {
    private static final String TAG = "OrchestellaQemu";

    private QemuLaunchEvidence() {
    }

    /** Non-secret distro id for telemetry; unknown or ambiguous filenames stay honest. */
    static String resolveDistroId(String rootfsFileName) {
        if (rootfsFileName == null || rootfsFileName.isEmpty()) return "unknown";
        String matched = null;
        for (DistroManifest manifest : DistroCatalogRegistry.createDefault().listAll()) {
            if (!rootfsFileName.equals(manifest.rootfsFileName())) continue;
            if (matched != null && !matched.equals(manifest.id())) return "unknown";
            matched = manifest.id();
        }
        return matched == null ? "unknown" : matched;
    }

    /**
     * Resolves the pinned distro rootfs digest from the catalog by exact file name.
     *
     * <p>Unknown or ambiguous names resolve to {@code null}; the caller must then skip recording
     * rather than guess a digest.
     */
    static String resolveRootfsSha256(String rootfsFileName) {
        if (rootfsFileName == null || rootfsFileName.isEmpty()) {
            return null;
        }
        String resolved = null;
        for (DistroManifest manifest : DistroCatalogRegistry.createDefault().listAll()) {
            if (rootfsFileName.equals(manifest.rootfsFileName())) {
                if (resolved != null && !resolved.equals(manifest.sha256())) {
                    return null;
                }
                resolved = manifest.sha256();
            }
        }
        return resolved;
    }

    /**
     * Whether the freshly loaded instance record proves provisioning from exactly the
     * catalog's current digest. Any load/parse failure is a conservative false.
     */
    static boolean isProvisionedForInstance(
            java.nio.file.Path instancesDirectory, String instanceId, String rootfsSha256) {
        if (instancesDirectory == null || instanceId == null || instanceId.isEmpty()
                || rootfsSha256 == null) {
            return false;
        }
        try {
            InstanceLifecycle.Instance instance =
                    new InstanceLifecycle.FileStore(instancesDirectory).load(instanceId);
            return instance.isProvisionedFor(rootfsSha256);
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Resolves the catalog digest for the launch filename, then checks persisted evidence. */
    static boolean credentialsOnlyLaunch(
            java.nio.file.Path instancesDirectory, String instanceId, String rootfsFileName) {
        String rootfsSha256 = resolveRootfsSha256(rootfsFileName);
        return rootfsSha256 != null
                && isProvisionedForInstance(instancesDirectory, instanceId, rootfsSha256);
    }

    /**
     * Builds the best-effort evidence recorder for one instance.
     *
     * <p>Returns {@code null} when the digest or controller is unavailable, which keeps the
     * instance on the conservative "not provisioned" reading.
     */
    static PersistentTerminalSessionPool.ProvisioningListener provisioningRecorder(
            InstanceLifecycle.Controller controller, String instanceId, String rootfsSha256) {
        if (controller == null || instanceId == null || rootfsSha256 == null) {
            return null;
        }
        return (reportedInstanceId, markerVersion) -> {
            if (!instanceId.equals(reportedInstanceId)) {
                return;
            }
            try {
                controller.recordProvisioning(
                        reportedInstanceId,
                        new InstanceLifecycle.ProvisioningEvidence(
                                markerVersion, rootfsSha256, System.currentTimeMillis()));
            } catch (IOException | RuntimeException failed) {
                // Evidence is advisory: never let metadata persistence break the pump thread.
                Log.w(TAG, "provisioning-record-failed");
            }
        };
    }
}
