package io.github.nyzeep.orchestella.runtime.fastlane.capability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FastLaneCapabilitiesTest {

    @Test
    public void canonicalMatrixDeclaresEveryFastLaneCapability() {
        FastLaneCapabilities v1 = FastLaneCapabilities.v1();
        assertTrue(v1.terminal());
        assertTrue(v1.hardwareCpu());
        assertTrue(v1.encryptedStorage());
        assertTrue(v1.sealedToolbox());
        assertTrue(v1.tcpProxy());
        assertTrue(v1.exportImport());
        assertTrue(v1.distccOffload());
        assertTrue(v1.terminalOnlyDisplay());
    }

    @Test
    public void canonicalMatrixNeverClaimsFullLinuxFeatures() {
        FastLaneCapabilities v1 = FastLaneCapabilities.v1();
        assertFalse(v1.nativeNetwork());
        assertFalse(v1.nativePackageInstall());
        assertFalse(v1.docker());
        assertFalse(v1.systemd());
        assertFalse(v1.gpu());
    }

    @Test
    public void projectionNarrowsStorageAndProxyWithoutClaimingThem() {
        FastLaneCapabilities projected =
                FastLaneCapabilities.v1().project(DeviceFacts.withoutEncryptedStorage());
        assertTrue(projected.terminal());
        assertTrue(projected.hardwareCpu());
        assertFalse(projected.encryptedStorage());
        assertTrue(projected.sealedToolbox());
    }

    @Test
    public void projectionDropsExportWhenStorageIsMissing() {
        FastLaneCapabilities projected =
                FastLaneCapabilities.v1().project(DeviceFacts.withoutEncryptedStorage());
        assertFalse(projected.encryptedStorage());
        assertFalse(projected.exportImport());
    }

    @Test
    public void projectionIsEmptyWhenFastLaneIsNotViable() {
        FastLaneCapabilities projected =
                FastLaneCapabilities.v1().project(DeviceFacts.hiddenApiBlockedDevice());
        assertFalse(projected.terminal());
        assertFalse(projected.hardwareCpu());
        assertFalse(projected.encryptedStorage());
        assertFalse(projected.sealedToolbox());
        assertFalse(projected.tcpProxy());
        assertFalse(projected.exportImport());
        assertFalse(projected.distccOffload());
    }

    @Test
    public void decisionRejectsContradictoryLaneAndReason() {
        assertThrows(IllegalArgumentException.class, () -> new FastLaneDecision(
                Lane.FAST,
                FastLaneReason.REQUIRES_FULL_LINUX,
                FastLaneCapabilities.v1(),
                "contradiction"));
        assertThrows(IllegalArgumentException.class, () -> new FastLaneDecision(
                Lane.FULL,
                FastLaneReason.FAST_LANE_AVAILABLE,
                FastLaneCapabilities.v1(),
                "contradiction"));
        assertThrows(IllegalArgumentException.class, () -> new FastLaneDecision(
                Lane.FULL, FastLaneReason.NO_AVF, FastLaneCapabilities.none(), " "));
    }

    @Test
    public void everyFallbackCarriesTextAndCapabilities() {
        FastLaneDecision decision = FastLaneRouter.decide(
                DeviceFacts.noAvf(), Workload.INTERACTIVE_SHELL, null);
        assertFalse(decision.explanation().isBlank());
        assertEquals(FastLaneCapabilities.none(), decision.fastLaneCapabilities());
        assertTrue(decision.explanation().contains("不是 Full Linux"));
    }

    @Test
    public void copyStatesThatFastLaneIsNotFullLinux() {
        String limits = FastLaneCopy.limits();
        assertTrue(limits.contains("不是 Full Linux"));
        assertTrue(limits.contains("systemd"));
        assertTrue(limits.contains("Docker"));
        assertTrue(limits.contains("原生网络"));
        assertTrue(limits.contains("GPU"));
        assertTrue(limits.contains("包安装"));
        assertEquals("不可用", FastLaneCopy.badge(
                FastLaneDecision.unsupported(
                        FastLaneReason.NO_AVF, FastLaneCapabilities.none(), "x")));
    }
}
