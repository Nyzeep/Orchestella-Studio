package io.github.nyzeep.orchestella.runtime.contract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Contract tests for {@link ExecutionPolicyContract}: persisted identity declarations
 * (absent legacy, explicit known, explicit unknown) versus per-launch diagnostics,
 * read-only volume format conflict checks, and refusal/fallback vocabulary.
 */
public final class ExecutionPolicyContractTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final Set<FullLinuxContract.ExecutionStrategy> ALL_STRATEGIES =
            EnumSet.allOf(FullLinuxContract.ExecutionStrategy.class);
    private static final Set<FullLinuxContract.ExecutionStrategy> NON_QA_STRATEGIES =
            EnumSet.of(
                    FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                    FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION);

    @Test
    public void parsesStrategyAndVolumeFormatDeclarationsAcrossAllStates() {
        ExecutionPolicyContract.StrategyDeclaration absentStrategy =
                ExecutionPolicyContract.StrategyDeclaration.parse(null);
        assertTrue(absentStrategy.isAbsent());
        assertFalse(absentStrategy.isKnown());
        assertFalse(absentStrategy.isUnknown());
        assertNull(absentStrategy.strategy());
        assertNull(absentStrategy.rawValue());

        ExecutionPolicyContract.StrategyDeclaration uml =
                ExecutionPolicyContract.StrategyDeclaration.parse("USER_MODE_KERNEL");
        assertTrue(uml.isKnown());
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL, uml.strategy());
        assertEquals("USER_MODE_KERNEL", uml.rawValue());

        ExecutionPolicyContract.StrategyDeclaration futureStrategy =
                ExecutionPolicyContract.StrategyDeclaration.parse("FUTURE_HYPERVISOR");
        assertTrue(futureStrategy.isUnknown());
        assertNull(futureStrategy.strategy());
        assertEquals("FUTURE_HYPERVISOR", futureStrategy.rawValue());

        ExecutionPolicyContract.VolumeFormatDeclaration absentFormat =
                ExecutionPolicyContract.VolumeFormatDeclaration.parse(null);
        assertTrue(absentFormat.isAbsent());
        assertNull(absentFormat.format());

        ExecutionPolicyContract.VolumeFormatDeclaration rawFormat =
                ExecutionPolicyContract.VolumeFormatDeclaration.parse("RAW_SPARSE");
        assertTrue(rawFormat.isKnown());
        assertEquals(StorageContract.RootVolumeFormat.RAW_SPARSE, rawFormat.format());

        ExecutionPolicyContract.VolumeFormatDeclaration unknownFormat =
                ExecutionPolicyContract.VolumeFormatDeclaration.parse("QCOW2_EXPERIMENTAL");
        assertTrue(unknownFormat.isUnknown());
        assertNull(unknownFormat.format());
        assertEquals("QCOW2_EXPERIMENTAL", unknownFormat.rawValue());
    }

    @Test
    public void rejectsBlankExplicitStrategyOrVolumeFormatDeclarations() {
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPolicyContract.StrategyDeclaration.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPolicyContract.StrategyDeclaration.parse("   "));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPolicyContract.VolumeFormatDeclaration.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionPolicyContract.VolumeFormatDeclaration.parse("\t\n"));
    }

    @Test
    public void evaluatesLegacyAndKnownLaunchRoutingWithoutMutation() throws Exception {
        Path rawVolume = temporaryFolder.newFile("raw-instance.raw").toPath();
        Files.write(rawVolume, new byte[] { 0x00, 0x00, 0x00, 0x00 });

        ExecutionPolicyContract.LaunchDiagnostic legacy = ExecutionPolicyContract.evaluateLaunch(
                ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent(),
                rawVolume,
                NON_QA_STRATEGIES,
                1000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.ALLOWED, legacy.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.LEGACY_DEFAULT_ROUTE, legacy.preflightVerdict().code());
        assertNull(legacy.resolvedStrategy());
        assertEquals(ExecutionPolicyContract.FallbackReason.NONE, legacy.fallbackReason());

        ExecutionPolicyContract.InstanceExecutionIdentity umlIdentity =
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE);
        ExecutionPolicyContract.LaunchDiagnostic umlAllowed = ExecutionPolicyContract.evaluateLaunch(
                umlIdentity,
                rawVolume,
                ALL_STRATEGIES,
                2000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.ALLOWED, umlAllowed.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.READY, umlAllowed.preflightVerdict().code());
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL, umlAllowed.resolvedStrategy());

        // Without QA gate or launcher availability, explicit USER_MODE_KERNEL refuses rather than falling back to QEMU.
        ExecutionPolicyContract.LaunchDiagnostic umlDeniedOutsideQa = ExecutionPolicyContract.evaluateLaunch(
                umlIdentity,
                rawVolume,
                NON_QA_STRATEGIES,
                3000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.REFUSED, umlDeniedOutsideQa.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.STRATEGY_NOT_AVAILABLE, umlDeniedOutsideQa.preflightVerdict().code());
        assertNull(umlDeniedOutsideQa.resolvedStrategy());

        ExecutionPolicyContract.LaunchDiagnostic umlDeniedNoLauncher = ExecutionPolicyContract.evaluateLaunch(
                umlIdentity,
                rawVolume,
                Set.of(),
                4000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.REFUSED, umlDeniedNoLauncher.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.STRATEGY_NOT_AVAILABLE, umlDeniedNoLauncher.preflightVerdict().code());
    }

    @Test
    public void refusesUnknownStrategyUnrecognizedFormatAndContainerMagicConflictsReadOnly() throws Exception {
        Path rawVolume = temporaryFolder.newFile("normal.raw").toPath();
        Files.write(rawVolume, new byte[] { 0x01, 0x02, 0x03, 0x04 });

        ExecutionPolicyContract.InstanceExecutionIdentity unknownStrategy =
                ExecutionPolicyContract.InstanceExecutionIdentity.of(
                        ExecutionPolicyContract.StrategyDeclaration.parse("MICRO_KERNEL_V9"),
                        ExecutionPolicyContract.VolumeFormatDeclaration.of(StorageContract.RootVolumeFormat.RAW_SPARSE));
        ExecutionPolicyContract.LaunchDiagnostic unknownStrategyVerdict =
                ExecutionPolicyContract.evaluateLaunch(unknownStrategy, rawVolume, ALL_STRATEGIES, 5000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.REFUSED, unknownStrategyVerdict.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.UNKNOWN_EXECUTION_STRATEGY,
                unknownStrategyVerdict.preflightVerdict().code());
        assertTrue(unknownStrategyVerdict.preflightVerdict().detail().contains("MICRO_KERNEL_V9"));

        ExecutionPolicyContract.InstanceExecutionIdentity unknownFormat =
                ExecutionPolicyContract.InstanceExecutionIdentity.of(
                        ExecutionPolicyContract.StrategyDeclaration.of(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION),
                        ExecutionPolicyContract.VolumeFormatDeclaration.parse("SPARSE_V99"));
        ExecutionPolicyContract.LaunchDiagnostic unknownFormatVerdict =
                ExecutionPolicyContract.evaluateLaunch(unknownFormat, rawVolume, ALL_STRATEGIES, 6000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.REFUSED, unknownFormatVerdict.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.UNRECOGNIZED_VOLUME_FORMAT,
                unknownFormatVerdict.preflightVerdict().code());

        Path qcow2Volume = temporaryFolder.newFile("spoofed-qcow2.raw").toPath();
        byte[] qcow2Header = new byte[] { 0x51, 0x46, 0x49, (byte) 0xFB, 0x00, 0x00, 0x00, 0x03 };
        Files.write(qcow2Volume, qcow2Header);
        ExecutionPolicyContract.LaunchDiagnostic qcow2Conflict = ExecutionPolicyContract.evaluateLaunch(
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE),
                qcow2Volume,
                ALL_STRATEGIES,
                7000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.REFUSED, qcow2Conflict.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.VOLUME_FORMAT_CONFLICT, qcow2Conflict.preflightVerdict().code());
        assertEquals(qcow2Header.length, Files.size(qcow2Volume));

        Path dirVolume = temporaryFolder.newFolder("dir-not-raw").toPath();
        ExecutionPolicyContract.LaunchDiagnostic dirConflict = ExecutionPolicyContract.evaluateLaunch(
                ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent(),
                dirVolume,
                ALL_STRATEGIES,
                8000L);
        assertEquals(ExecutionPolicyContract.PreflightStatus.REFUSED, dirConflict.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.PreflightCode.VOLUME_FORMAT_CONFLICT, dirConflict.preflightVerdict().code());

        ExecutionPolicyContract.ExecutionPolicyRefusalException exception =
                new ExecutionPolicyContract.ExecutionPolicyRefusalException(qcow2Conflict);
        assertNotNull(exception.diagnostic());
        assertEquals(qcow2Conflict.preflightVerdict(), exception.verdict());
    }

    @Test
    public void launchDiagnosticTracksFallbackTransitionWithoutMutatingPersistedIdentity() {
        ExecutionPolicyContract.InstanceExecutionIdentity identity =
                ExecutionPolicyContract.InstanceExecutionIdentity.legacyAbsent();
        ExecutionPolicyContract.LaunchDiagnostic initial = ExecutionPolicyContract.evaluateLaunch(
                identity, null, NON_QA_STRATEGIES, 9000L);
        ExecutionPolicyContract.LaunchDiagnostic fallback = initial.withFallback(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                ExecutionPolicyContract.FallbackReason.HARDWARE_PERMISSION_DENIED,
                "permission denied");
        assertEquals(ExecutionPolicyContract.PreflightStatus.FALLBACK_ALLOWED,
                fallback.preflightVerdict().status());
        assertEquals(ExecutionPolicyContract.FallbackReason.HARDWARE_PERMISSION_DENIED,
                fallback.fallbackReason());
        assertEquals(identity, fallback.persistedIdentity());
    }

    private static void assertThrows(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) {
                return;
            }
            fail("expected " + type.getName() + " but threw " + thrown.getClass().getName());
        }
        fail("expected " + type.getName());
    }
}
