package io.github.nyzeep.orchestella;

import android.util.Log;
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

    private static List<DistroManifest> allManifests(List<DistroManifest> extraManifests) {
        List<DistroManifest> defaults = DistroCatalogRegistry.createDefault().listAll();
        if (extraManifests == null || extraManifests.isEmpty()) {
            return defaults;
        }
        List<DistroManifest> merged = new ArrayList<>(defaults.size() + extraManifests.size());
        merged.addAll(defaults);
        merged.addAll(extraManifests);
        return merged;
    }

    /** Non-secret distro id for telemetry; unknown or ambiguous filenames stay honest. */
    static String resolveDistroId(String rootfsFileName) {
        return resolveDistroId(rootfsFileName, List.of());
    }

    static String resolveDistroId(String rootfsFileName, List<DistroManifest> extraManifests) {
        if (rootfsFileName == null || rootfsFileName.isEmpty()) return "unknown";
        String matched = null;
        for (DistroManifest manifest : allManifests(extraManifests)) {
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
        return resolveRootfsSha256(rootfsFileName, List.of());
    }

    static String resolveRootfsSha256(String rootfsFileName, List<DistroManifest> extraManifests) {
        if (rootfsFileName == null || rootfsFileName.isEmpty()) {
            return null;
        }
        String resolved = null;
        for (DistroManifest manifest : allManifests(extraManifests)) {
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

    /**
     * W1-04 (R-08): resolves the credentials-only decision and its observed
     * reason, so the launch timeline can distinguish a fast-path hit from each
     * miss shape (first provision, digest mismatch/absent, unresolved catalog
     * name, unreadable evidence) instead of collapsing them into one boolean.
     * {@link LaunchTimeline.CredentialsOnlyOutcome#HIT} is the only outcome
     * that enables the fast path.
     */
    static LaunchTimeline.CredentialsOnlyOutcome credentialsOnlyOutcome(
            java.nio.file.Path instancesDirectory, String instanceId, String rootfsFileName) {
        return credentialsOnlyOutcome(instancesDirectory, instanceId, rootfsFileName, List.of());
    }

    static LaunchTimeline.CredentialsOnlyOutcome credentialsOnlyOutcome(
            java.nio.file.Path instancesDirectory,
            String instanceId,
            String rootfsFileName,
            List<DistroManifest> extraManifests) {
        String rootfsSha256 = resolveRootfsSha256(rootfsFileName, extraManifests);
        if (rootfsSha256 == null) {
            return LaunchTimeline.CredentialsOnlyOutcome.CATALOG_UNRESOLVED;
        }
        try {
            // A missing metadata file is a genuine first provisioning, not an
            // evidence-read failure: the instance record simply does not exist
            // yet, so the honest miss reason stays FIRST_PROVISION.
            if (instancesDirectory != null && instanceId != null && !instanceId.isEmpty()
                    && !java.nio.file.Files.isRegularFile(
                            instancesDirectory.resolve(instanceId + ".properties"))) {
                return LaunchTimeline.CredentialsOnlyOutcome.FIRST_PROVISION;
            }
            InstanceLifecycle.Instance instance =
                    new InstanceLifecycle.FileStore(instancesDirectory).load(instanceId);
            if (instance.isProvisionedFor(rootfsSha256)) {
                return LaunchTimeline.CredentialsOnlyOutcome.HIT;
            }
            InstanceLifecycle.ProvisioningEvidence evidence = instance.provisioningEvidence();
            if (instance.isProvisioned() && evidence != null) {
                // Provisioned, but the recorded digest is absent or differs:
                // the fast path cannot prove the root volume matches the
                // catalog payload, so the full assembly path stays mandatory.
                return LaunchTimeline.CredentialsOnlyOutcome.PAYLOAD_SHA_MISMATCH;
            }
            return LaunchTimeline.CredentialsOnlyOutcome.FIRST_PROVISION;
        } catch (IOException | RuntimeException unavailable) {
            return LaunchTimeline.CredentialsOnlyOutcome.EVIDENCE_UNAVAILABLE;
        }
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
