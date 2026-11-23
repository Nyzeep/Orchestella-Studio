package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
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
        roundTripsExecutionIdentityAndRejectsBlankTruncatedValues();
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

    private static void roundTripsExecutionIdentityAndRejectsBlankTruncatedValues() throws Exception {
        java.nio.file.Path storeDir = java.nio.file.Files.createTempDirectory("codec-exec-identity");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        ExecutionPolicyContract.InstanceExecutionIdentity umlIdentity =
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                storeTestRoot("uml-identity"),
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                umlIdentity);
        assertEquals(umlIdentity, store.load(created.id()).executionIdentity(), "persisted explicit UML identity");

        // Explicit legacy strategy + explicit raw format round-trip cleanly.
        Properties legacyExplicit = created.toProperties();
        legacyExplicit.setProperty("execution.strategy", "SOFTWARE_VIRTUALIZATION");
        legacyExplicit.setProperty("storage.rootVolumeFormat", "RAW_SPARSE");
        InstanceLifecycle.Instance decodedLegacyExplicit = InstanceLifecycle.Instance.fromProperties(legacyExplicit);
        assertEquals(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                decodedLegacyExplicit.executionIdentity().strategy().strategy(), "explicit legacy strategy");
        assertEquals(StorageContract.RootVolumeFormat.RAW_SPARSE,
                decodedLegacyExplicit.executionIdentity().volumeFormat().format(), "explicit raw format");

        // Unknown new strategy and unrecognized volume format remain inspectable and lossless on rewrite.
        Properties futureProps = created.toProperties();
        futureProps.setProperty("execution.strategy", "FUTURE_KERNEL_V2");
        futureProps.setProperty("storage.rootVolumeFormat", "QCOW2_NEXT");
        InstanceLifecycle.Instance decodedFuture = InstanceLifecycle.Instance.fromProperties(futureProps);
        assertEquals(true, decodedFuture.executionIdentity().hasUnknownStrategy(), "unknown strategy surfaced");
        assertEquals("FUTURE_KERNEL_V2", decodedFuture.executionIdentity().strategy().rawValue(), "unknown strategy raw");
        assertEquals(true, decodedFuture.executionIdentity().hasUnrecognizedVolumeFormat(), "unknown format surfaced");
        assertEquals("QCOW2_NEXT", decodedFuture.executionIdentity().volumeFormat().rawValue(), "unknown format raw");
        assertEquals("FUTURE_KERNEL_V2", decodedFuture.toProperties().getProperty("execution.strategy"),
                "unknown strategy preserved on re-encode");

        // Truncated/blank strategy or volume format fails closed without corrupting saved file.
        java.nio.file.Path propsPath = storeDir.resolve(created.id() + ".properties");
        byte[] originalSavedBytes = java.nio.file.Files.readAllBytes(propsPath);
        Properties blankStrategy = created.toProperties();
        blankStrategy.setProperty("execution.strategy", "");
        assertThrows(IllegalArgumentException.class, () -> InstanceLifecycle.Instance.fromProperties(blankStrategy),
                "blank execution.strategy must fail closed");
        Properties blankFormat = created.toProperties();
        blankFormat.setProperty("storage.rootVolumeFormat", "   ");
        assertThrows(IllegalArgumentException.class, () -> InstanceLifecycle.Instance.fromProperties(blankFormat),
                "blank storage.rootVolumeFormat must fail closed");
        assertEquals(true, java.util.Arrays.equals(originalSavedBytes, java.nio.file.Files.readAllBytes(propsPath)),
                "saved metadata file remains untouched");
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
