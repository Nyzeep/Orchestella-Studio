package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

/**
 * Locks the extracted {@link InstanceMetadataCodec} boundary: the Properties encoding must
 * stay lossless for every persisted aspect of an Instance, must preserve unknown keys
 * verbatim, and must fail closed on malformed or newer-schema metadata. Public behavior is
 * unchanged; this test only pins the extracted collaborator.
 */
public final class InstanceMetadataCodecContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        roundTripsEveryPersistedAspect();
        preservesUnknownMetadataKeysVerbatim();
        failsClosedOnMalformedMetadata();
        failsClosedOnNewerSchemaVersion();
    }

    private static void roundTripsEveryPersistedAspect() throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(java.nio.file.Files.createTempDirectory("codec-roundtrip"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                storeTestRoot("roundtrip"),
                new VmHardwareConfig(4, 4096, 8_589_934_592L, true, "quiet splash", false),
                new InstanceUserConfig("deploy", "deploy-password-1", true, "orch-host"));

        controller.beginProvisioning(created.id());
        controller.recordProvisioning(created.id(), new InstanceLifecycle.ProvisioningEvidence(
                InstanceLifecycle.PROVISIONING_MARKER_VERSION,
                "a".repeat(64),
                CLOCK.millis()));
        controller.completeProvisioning(created.id());
        controller.start(created.id());
        controller.recordCrash(created.id(), 42, storeTestRoot("crash.log"), "segfault");

        InstanceLifecycle.Instance original = store.load(created.id());
        Properties encoded = original.toProperties();
        InstanceLifecycle.Instance decoded = InstanceLifecycle.Instance.fromProperties(encoded);

        assertEquals(original.id(), decoded.id(), "id");
        assertEquals(original.distro().id(), decoded.distro().id(), "distro id");
        assertEquals(original.distro().version(), decoded.distro().version(), "distro version");
        assertEquals(original.rootVolume(), decoded.rootVolume(), "root volume");
        assertEquals(original.bridgeToken(), decoded.bridgeToken(), "bridge token");
        assertEquals(original.guestPassword(), decoded.guestPassword(), "guest password");
        assertEquals(original.runtimeClass(), decoded.runtimeClass(), "runtime class");
        assertEquals(original.state(), decoded.state(), "state");
        assertEquals(original.createdAtMillis(), decoded.createdAtMillis(), "created at");
        assertEquals(original.updatedAtMillis(), decoded.updatedAtMillis(), "updated at");
        assertEquals(original.hostname(), decoded.hostname(), "hostname");
        assertEquals(original.defaultUser(), decoded.defaultUser(), "default user");
        assertEquals(original.hasSudo(), decoded.hasSudo(), "sudo choice");
        assertEquals(original.crashDiagnostic().exitCode(), decoded.crashDiagnostic().exitCode(), "crash exit code");
        assertEquals(original.crashDiagnostic().message(), decoded.crashDiagnostic().message(), "crash message");
        assertEquals(original.crashDiagnostic().logPath(), decoded.crashDiagnostic().logPath(), "crash log path");
        assertEquals(original.provisioningEvidence().rootfsSha256(),
                decoded.provisioningEvidence().rootfsSha256(), "provisioning rootfs digest");
        assertEquals(original.hardwareConfig().cpuCores(), decoded.hardwareConfig().cpuCores(), "cpu cores");
        assertEquals(original.hardwareConfig().extraKernelArguments(),
                decoded.hardwareConfig().extraKernelArguments(), "extra kernel arguments");
        assertEquals(original.hardwareConfig().isLegacyDefaults(),
                decoded.hardwareConfig().isLegacyDefaults(), "legacy defaults flag");
    }

    private static void preservesUnknownMetadataKeysVerbatim() throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(java.nio.file.Files.createTempDirectory("codec-unknown"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), storeTestRoot("unknown"));

        Properties encoded = created.toProperties();
        // A newer writer's metadata must survive an older reader's rewrite untouched.
        encoded.setProperty("future.field", "future-value");
        InstanceLifecycle.Instance decoded = InstanceLifecycle.Instance.fromProperties(encoded);
        assertEquals("future-value", decoded.toProperties().getProperty("future.field"),
                "unknown metadata key survives a rewrite");
        store.save(decoded);
        assertEquals("future-value", store.load(created.id()).toProperties().getProperty("future.field"),
                "unknown metadata key survives persistence");
    }

    private static void failsClosedOnMalformedMetadata() {
        Properties malformed = new Properties();
        malformed.setProperty("id", "not-a-valid-instance");
        assertThrows(IllegalArgumentException.class, () -> InstanceLifecycle.Instance.fromProperties(malformed),
                "malformed metadata must fail closed");
    }

    private static void failsClosedOnNewerSchemaVersion() throws Exception {
        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(java.nio.file.Files.createTempDirectory("codec-schema"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), storeTestRoot("schema"));
        Properties encoded = created.toProperties();
        encoded.setProperty("metadata.schemaVersion", "999");
        assertThrows(IllegalArgumentException.class, () -> InstanceLifecycle.Instance.fromProperties(encoded),
                "newer schema version must fail closed");
    }

    private static java.nio.file.Path storeTestRoot(String name) throws Exception {
        return java.nio.file.Files.createTempFile("codec-" + name, ".raw");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": values differ (redacted)");
        }
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
