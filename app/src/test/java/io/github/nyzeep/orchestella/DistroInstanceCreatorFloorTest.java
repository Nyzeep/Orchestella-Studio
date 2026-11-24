package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Verifies W3-05 (R-05) device support-floor hard refusal and explicit execution identity
 * persistence in {@link DistroInstanceCreator}.
 */
public final class DistroInstanceCreatorFloorTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void refusesCreationBelowSupportFloorBeforeWritingDiskOrMetadata() throws Exception {
        Path root = tmp.newFolder().toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        byte[] payload = "alpine-rootfs-stub".getBytes(StandardCharsets.UTF_8);
        DistroManifest manifest = testManifest("alpine", "3.22.2", payload);

        DistroInstanceCreator creator = new DistroInstanceCreator(
                manifest, controller, root, 4096L, path -> 1024L * 1024L * 1024L);
        DeviceCapabilityEvaluator.Assessment lowRam = DeviceCapabilityEvaluator.evaluate(
                new DeviceCapabilityEvaluator.DeviceProfile(
                        35, List.of("arm64-v8a"), 2L * 1024L * 1024L * 1024L, false));

        ExecutionPolicyContract.ExecutionPolicyRefusalException thrown = assertThrows(
                ExecutionPolicyContract.ExecutionPolicyRefusalException.class,
                () -> creator.create(
                        new ByteArrayInputStream(payload),
                        payload.length,
                        VmHardwareConfig.defaultConfig(),
                        InstanceUserConfig.defaultRoot(),
                        lowRam,
                        ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent()));

        assertEquals(ExecutionPolicyContract.PreflightCode.DEVICE_FLOOR_UNSUPPORTED, thrown.verdict().code());
        assertEquals(ErrorCopy.CODE_DEVICE_FLOOR_UNSUPPORTED, ErrorCopy.resolve(thrown).getCode());
        assertTrue(store.list().isEmpty());
        assertFalse(Files.exists(root.resolve("root-volumes")));
        assertFalse(Files.exists(root.resolve("distro-inputs")));
    }

    @Test
    public void persistsExplicitUmlIdentityWhenCompliantFloorAndStrategySelected() throws Exception {
        Path root = tmp.newFolder().toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        byte[] payload = "alpine-rootfs-stub".getBytes(StandardCharsets.UTF_8);
        DistroManifest manifest = testManifest("alpine", "3.22.2", payload);

        DistroInstanceCreator creator = new DistroInstanceCreator(
                manifest, controller, root, 4096L, path -> 1024L * 1024L * 1024L);
        DeviceCapabilityEvaluator.Assessment compliant = DeviceCapabilityEvaluator.evaluate(
                new DeviceCapabilityEvaluator.DeviceProfile(
                        35, List.of("arm64-v8a"), 8L * 1024L * 1024L * 1024L, false));

        ExecutionPolicyContract.InstanceExecutionIdentity explicitUml =
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE);

        InstanceLifecycle.Instance created = creator.create(
                new ByteArrayInputStream(payload),
                payload.length,
                VmHardwareConfig.builder().cpuCores(1).ramMegabytes(512).build(),
                InstanceUserConfig.defaultRoot(),
                compliant,
                explicitUml);

        InstanceLifecycle.Instance reloaded = store.load(created.id());
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                reloaded.executionIdentity().strategy().strategy());
        assertEquals(StorageContract.RootVolumeFormat.RAW_SPARSE,
                reloaded.executionIdentity().volumeFormat().format());
    }

    private static DistroManifest testManifest(String id, String version, byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return new DistroManifest(
                id,
                version,
                "aarch64",
                "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" + id + "-" + version + ".tar.gz",
                id + "-" + version + ".tar.gz",
                hex.toString(),
                bytes.length);
    }
}
