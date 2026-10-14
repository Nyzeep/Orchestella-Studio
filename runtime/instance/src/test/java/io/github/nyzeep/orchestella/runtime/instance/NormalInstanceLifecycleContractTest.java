package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Normal-lifecycle contract only: it intentionally excludes process death, recovery, and host power.
 */
public final class NormalInstanceLifecycleContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory");
        }
        Path testDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        resetFixture(testDirectory);

        persistsNormalProvisioningStartAndGuestStopAcrossStoreReconstruction(testDirectory);
        permitsOnlyOneRunningInstance(testDirectory);
        generatesAndPersistsAUniqueBridgeTokenPerInstance(testDirectory);
        generatesAndPersistsRandomCredentialsPerInstance(testDirectory);
        preservesIdentityAndNoSudoAcrossColdRestoreAndClone(testDirectory);
        rejectsMalformedStoredIdentityWithoutRewritingMetadata(testDirectory);
    }

    private static void persistsNormalProvisioningStartAndGuestStopAcrossStoreReconstruction(
            Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-a"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path rootVolume = testDirectory.resolve("alpine-instance.raw");

        InstanceLifecycle.Instance instance =
                controller.create(new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        assertEquals(InstanceLifecycle.State.CREATED, instance.state(), "new instance state");
        assertEquals(
                FullLinuxContract.RuntimeClass.FULL_LINUX,
                instance.runtimeClass(),
                "instance Runtime Class");
        assertEquals(rootVolume, instance.rootVolume(), "root volume reference");

        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        InstanceLifecycle.Instance provisioned = store.load(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, provisioned.state(), "provisioning completes stopped");

        controller.start(instance.id());
        InstanceLifecycle.FileStore reconstructedStore =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-a"));
        InstanceLifecycle.Instance reconstructed = reconstructedStore.load(instance.id());
        assertEquals(InstanceLifecycle.State.RUNNING, reconstructed.state(), "reconstructed visible state");
        assertEquals(rootVolume, reconstructed.rootVolume(), "restart uses the same root volume");
        assertEquals("alpine", reconstructed.distro().id(), "distro remains a separate reference");

        controller.recordNormalGuestStop(instance.id());
        InstanceLifecycle.Instance stopped = reconstructedStore.load(instance.id());
        assertEquals(InstanceLifecycle.State.STOPPED, stopped.state(), "normal guest stop is persisted");

        assertThrows(
                InstanceLifecycle.InvalidTransitionException.class,
                () -> controller.completeProvisioning(instance.id()),
                "a stopped instance is not provisioned again");
    }

    private static void permitsOnlyOneRunningInstance(Path testDirectory) throws Exception {
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-b"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance first =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("first.raw"));
        InstanceLifecycle.Instance second =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("second.raw"));

        provisionToStopped(controller, first.id());
        provisionToStopped(controller, second.id());
        controller.start(first.id());

        assertThrows(
                InstanceLifecycle.SingleRunningInstanceException.class,
                () -> controller.start(second.id()),
                "a second instance cannot run concurrently");
        controller.recordNormalGuestStop(first.id());
        controller.start(second.id());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(second.id()).state(), "second starts after stop");
    }

    private static void generatesAndPersistsAUniqueBridgeTokenPerInstance(Path testDirectory)
            throws Exception {
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-c"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance first =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("first-token.raw"));
        InstanceLifecycle.Instance second =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("second-token.raw"));

        assertCanonicalToken(first.bridgeToken(), "first Instance token");
        assertCanonicalToken(second.bridgeToken(), "second Instance token");
        assertFalse(
                first.bridgeToken().equals(second.bridgeToken()),
                "Instances must not share a Bridge token");

        InstanceLifecycle.FileStore reconstructedStore =
                new InstanceLifecycle.FileStore(testDirectory.resolve("metadata-c"));
        assertEquals(
                first.bridgeToken(),
                reconstructedStore.load(first.id()).bridgeToken(),
                "first Instance token persists without disclosure");
    }

    private static void generatesAndPersistsRandomCredentialsPerInstance(Path testDirectory)
            throws Exception {
        Path storeDir = testDirectory.resolve("metadata-cred");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance first =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("first-cred.raw"));

        // New default root instances get independent credentials while retaining root identity.
        assertTrue(first.guestPassword().matches("[A-Za-z0-9]{24}"), "strong guest-safe default password");
        assertFalse("1234".equals(first.guestPassword()), "new default must not use a public password");
        InstanceLifecycle.Instance second =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("second-cred.raw"));
        assertFalse(first.guestPassword().equals(second.guestPassword()),
                "instances must have independent default passwords");
        InstanceLifecycle.Instance nullConfig = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                testDirectory.resolve("null-config.raw"),
                io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig(), null);
        assertFalse(first.guestPassword().equals(nullConfig.guestPassword()),
                "null user config must generate a fresh password");
        assertTrue(nullConfig.guestPassword().matches("[A-Za-z0-9]{24}"), "null-config strong password");
        assertEquals("orchestella", first.hostname(), "first hostname defaults to orchestella");
        assertEquals("root", first.defaultUser(), "first user defaults to root");

        // Custom credentials support
        InstanceUserConfig devConfig = new InstanceUserConfig("dev", "DevSecure2026", true, "workstation");
        InstanceLifecycle.Instance custom =
                controller.create(
                        new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                        testDirectory.resolve("custom-cred.raw"),
                        io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig(),
                        devConfig);
        assertEquals("DevSecure2026", custom.guestPassword(), "custom guest password");
        assertEquals("workstation", custom.hostname(), "custom hostname");
        assertEquals("dev", custom.defaultUser(), "custom user");
        assertTrue(custom.hasSudo(), "explicit sudo choice survives creation");
        assertTrue(new InstanceLifecycle.FileStore(storeDir).load(custom.id()).hasSudo(),
                "explicit sudo choice survives cold metadata load");

        // Legacy metadata backward-compatibility test (missing hostname & defaultUser)
        String legacyId = java.util.UUID.randomUUID().toString();
        Path legacyPropPath = storeDir.resolve(legacyId + ".properties");
        java.util.Properties legacyProps = new java.util.Properties();
        legacyProps.setProperty("id", legacyId);
        legacyProps.setProperty("distroId", "alpine");
        legacyProps.setProperty("distroVersion", "3.24.1");
        legacyProps.setProperty("rootVolume", testDirectory.resolve("legacy.raw").toString());
        legacyProps.setProperty("bridgeToken", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        legacyProps.setProperty("guestPassword", "LegacyPass999");
        legacyProps.setProperty("runtimeClass", "FULL_LINUX");
        legacyProps.setProperty("state", "STOPPED");
        legacyProps.setProperty("createdAtMillis", "1000");
        legacyProps.setProperty("updatedAtMillis", "1000");
        try (java.io.OutputStream out = Files.newOutputStream(legacyPropPath)) {
            legacyProps.store(out, "Legacy test");
        }

        // Persists across reconstructed store
        InstanceLifecycle.FileStore reconstructed = new InstanceLifecycle.FileStore(storeDir);
        assertEquals(
                first.guestPassword(),
                reconstructed.load(first.id()).guestPassword(),
                "guest password persists");
        assertEquals(
                "orchestella",
                reconstructed.load(first.id()).hostname(),
                "hostname persists");
        assertEquals(
                "root",
                reconstructed.load(first.id()).defaultUser(),
                "defaultUser persists");

        assertEquals(
                "DevSecure2026",
                reconstructed.load(custom.id()).guestPassword(),
                "custom guest password persists");
        assertEquals(
                "workstation",
                reconstructed.load(custom.id()).hostname(),
                "custom hostname persists");
        assertEquals(
                "dev",
                reconstructed.load(custom.id()).defaultUser(),
                "custom user persists");

        // Verify legacy instance migration
        InstanceLifecycle.Instance loadedLegacy = reconstructed.load(legacyId);
        assertEquals("LegacyPass999", loadedLegacy.guestPassword(), "legacy guest password preserved");
        assertEquals("orchestella", loadedLegacy.hostname(), "legacy hostname defaults to orchestella");
        assertEquals("root", loadedLegacy.defaultUser(), "legacy defaultUser defaults to root");

        // A known old password remains exact; upgrades must not reset guest accounts.
        legacyProps.setProperty("guestPassword", "1234");
        try (java.io.OutputStream out = Files.newOutputStream(legacyPropPath)) {
            legacyProps.store(out, "Synthetic known legacy password");
        }
        assertEquals("1234", reconstructed.load(legacyId).guestPassword(), "known legacy password remains exact");

        // Missing or blank legacy credentials stay unknown across transitions and persistence.
        for (String unknown : new String[] {null, "", "   "}) {
            if (unknown == null) {
                legacyProps.remove("guestPassword");
            } else {
                legacyProps.setProperty("guestPassword", unknown);
            }
            try (java.io.OutputStream out = Files.newOutputStream(legacyPropPath)) {
                legacyProps.store(out, "Synthetic unknown legacy credential");
            }
            InstanceLifecycle.Instance legacyUnknown = reconstructed.load(legacyId);
            assertEquals("", legacyUnknown.guestPassword(), "legacy unknown password must not be guessed");
            byte[] metadataBeforeRead = Files.readAllBytes(legacyPropPath);
            reconstructed.load(legacyId);
            assertTrue(java.util.Arrays.equals(metadataBeforeRead, Files.readAllBytes(legacyPropPath)),
                    "loading legacy metadata must not silently rewrite credentials");
            controller.start(legacyId);
            controller.recordNormalGuestStop(legacyId);
            assertEquals("", reconstructed.load(legacyId).guestPassword(),
                    "unknown password must remain unknown across lifecycle saves");
        }
        InstanceLifecycle.Instance imported = InstanceLifecycle.Instance.restore(
                java.util.UUID.randomUUID().toString(),
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                testDirectory.resolve("imported.raw"), FullLinuxContract.RuntimeClass.FULL_LINUX,
                InstanceLifecycle.State.STOPPED,
                io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig(), CLOCK);
        assertEquals("", imported.guestPassword(), "imported guest password must remain unknown");
        store.save(imported);
        assertEquals("", reconstructed.load(imported.id()).guestPassword(), "unknown imported password persists");

        // Provisioning and normal start/stop never regenerate a persisted credential.
        provisionToStopped(controller, second.id());
        controller.start(second.id());
        controller.recordNormalGuestStop(second.id());
        assertEquals(second.guestPassword(), reconstructed.load(second.id()).guestPassword(),
                "per-instance password survives normal lifecycle transitions");

        // Update password
        InstanceLifecycle.Instance updated =
                controller.updateGuestPassword(first.id(), "NewSecurePass2026");
        assertEquals("NewSecurePass2026", updated.guestPassword(), "updated password in memory");
        assertEquals(
                "NewSecurePass2026",
                reconstructed.load(first.id()).guestPassword(),
                "updated password in store");

        // Invalid update rejected
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.updateGuestPassword(first.id(), "short"),
                "short password rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.updateGuestPassword(first.id(), "pass with spaces"),
                "spaces rejected");
    }

    private static void preservesIdentityAndNoSudoAcrossColdRestoreAndClone(Path testDirectory)
            throws Exception {
        Path directory = testDirectory.resolve("identity-cold-store");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(directory);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                testDirectory.resolve("ordinary-user.raw"),
                io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig(),
                new InstanceUserConfig("developer", "SyntheticSecret2026", false, "Dev-Box"));
        provisionToStopped(controller, created.id());
        controller.start(created.id());
        controller.reconcileAfterHostDeath(created.id());
        InstanceLifecycle.FileStore coldStore = new InstanceLifecycle.FileStore(directory);
        InstanceLifecycle.Controller coldController = new InstanceLifecycle.Controller(coldStore, CLOCK);
        InstanceLifecycle.Instance restored = coldController.coldRestore(created.id());
        assertEquals("developer", restored.defaultUser(), "cold restore retains ordinary username");
        assertEquals("dev-box", restored.hostname(), "cold restore retains canonical hostname");
        assertFalse(restored.hasSudo(), "cold restore never grants unselected sudo");
        coldController.recordNormalGuestStop(created.id());
        coldController.updateHardwareConfig(created.id(),
                io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig());
        coldController.updateGuestPassword(created.id(), "ChangedSyntheticSecret2026");
        InstanceLifecycle.Instance clone = coldController.cloneInstance(created.id(),
                testDirectory.resolve("ordinary-user-clone.raw"));
        InstanceLifecycle.Instance loaded = new InstanceLifecycle.FileStore(directory).load(clone.id());
        assertEquals("developer", loaded.defaultUser(), "clone retains username");
        assertEquals("dev-box", loaded.hostname(), "clone retains hostname");
        assertFalse(loaded.hasSudo(), "clone retains no-sudo selection");
        assertEquals("ChangedSyntheticSecret2026", loaded.guestPassword(), "clone retains known password");

        Path metadata = directory.resolve(created.id() + ".properties");
        java.util.Properties properties = new java.util.Properties();
        try (var input = Files.newInputStream(metadata)) { properties.load(input); }
        properties.remove("hasSudo");
        properties.remove("guestPassword");
        try (var output = Files.newOutputStream(metadata)) { properties.store(output, "Synthetic legacy identity"); }
        byte[] before = Files.readAllBytes(metadata);
        InstanceLifecycle.Instance legacy = new InstanceLifecycle.FileStore(directory).load(created.id());
        assertEquals("developer", legacy.defaultUser(), "legacy ordinary identity is not replaced by root");
        assertEquals("", legacy.guestPassword(), "unknown legacy password stays unknown");
        assertFalse(legacy.hasSudo(), "absent legacy sudo choice does not grant privilege");
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(metadata)), "read does not migrate or reset metadata");
    }

    private static void rejectsMalformedStoredIdentityWithoutRewritingMetadata(Path testDirectory)
            throws Exception {
        Path directory = testDirectory.resolve("malformed-identity-store");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(directory);
        InstanceLifecycle.Instance instance = new InstanceLifecycle.Controller(store, CLOCK).create(
                new InstanceLifecycle.DistroReference("debian", "13"), testDirectory.resolve("malformed.raw"));
        Path metadata = directory.resolve(instance.id() + ".properties");
        java.util.Properties original = new java.util.Properties();
        try (var input = Files.newInputStream(metadata)) { original.load(input); }
        for (String[] field : new String[][] {
                {"defaultUser", "root;id"}, {"defaultUser", "Developer"},
                {"hostname", "box-"}, {"hostname", "box\ncommand"}, {"hasSudo", "yes"}}) {
            java.util.Properties malformed = new java.util.Properties();
            malformed.putAll(original);
            malformed.setProperty(field[0], field[1]);
            try (var output = Files.newOutputStream(metadata)) { malformed.store(output, "Synthetic invalid identity"); }
            byte[] before = Files.readAllBytes(metadata);
            assertThrows(java.io.IOException.class, () -> new InstanceLifecycle.FileStore(directory).load(instance.id()),
                    "invalid stored identity is refused");
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(metadata)), "refusal never rewrites identity or password");
        }
    }

    private static void provisionToStopped(InstanceLifecycle.Controller controller, String id) throws Exception {
        controller.beginProvisioning(id);
        controller.completeProvisioning(id);
    }

    /**
     * Each run must observe a virgin fixture: a previous run legitimately leaves its last instance
     * RUNNING, and stale store state would otherwise surface as SingleRunningInstanceException.
     */
    private static void resetFixture(Path testDirectory) throws Exception {
        if (Files.isDirectory(testDirectory)) {
            try (var paths = Files.walk(testDirectory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        Files.createDirectories(testDirectory);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": values differ (redacted)");
        }
    }

    private static void assertCanonicalToken(String token, String description) {
        if (token == null || !token.matches("[0-9a-f]{64}")) {
            throw new AssertionError(description + " must be canonical without logging its value");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertNotNull(Object value, String description) {
        if (value == null) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
            throw new AssertionError(description);
        }
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
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
