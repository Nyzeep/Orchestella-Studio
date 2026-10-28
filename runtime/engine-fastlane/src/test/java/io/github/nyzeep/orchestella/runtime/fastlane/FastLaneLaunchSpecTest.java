package io.github.nyzeep.orchestella.runtime.fastlane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class FastLaneLaunchSpecTest {
    private static final String SHA = "a".repeat(64);

    private static FastLaneLaunchSpec spec(
            String instanceId,
            String payload,
            int memoryMb,
            long storage,
            int control,
            int terminal,
            int proxy,
            int distcc) {
        return new FastLaneLaunchSpec(
                instanceId,
                payload,
                memoryMb,
                FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                storage,
                FastLaneLaunchSpec.DebugLevel.NONE,
                control,
                terminal,
                proxy,
                distcc,
                SHA);
    }

    @Test
    public void validSpecExposesEveryField() {
        FastLaneLaunchSpec spec = spec("fastlane-01", "libfastlane_payload.so", 1024, 268435456L,
                5000, 5001, 5002, 5003);
        assertEquals("fastlane-01", spec.instanceId());
        assertEquals("libfastlane_payload.so", spec.payloadBinaryName());
        assertEquals(1024, spec.memoryMb());
        assertEquals(268435456L, spec.encryptedStorageBytes());
        assertEquals(5000, spec.controlPort());
        assertEquals(5001, spec.terminalPort());
        assertEquals(5002, spec.proxyPort());
        assertEquals(5003, spec.distccPort());
    }

    @Test
    public void rejectsPathInPayloadName() {
        assertThrows(IllegalArgumentException.class,
                () -> spec("fastlane-01", "lib/evil.so", 1024, 1L, 5000, 5001, 5002, 5003));
    }

    @Test
    public void rejectsNonPositiveMemoryOrStorage() {
        assertThrows(IllegalArgumentException.class,
                () -> spec("fastlane-01", "payload.so", 0, 1L, 5000, 5001, 5002, 5003));
        assertThrows(IllegalArgumentException.class,
                () -> spec("fastlane-01", "payload.so", 1024, 0L, 5000, 5001, 5002, 5003));
    }

    @Test
    public void rejectsDuplicateOrPrivilegedPorts() {
        assertThrows(IllegalArgumentException.class,
                () -> spec("fastlane-01", "payload.so", 1024, 1L, 5000, 5000, 5002, 5003));
        assertThrows(IllegalArgumentException.class,
                () -> spec("fastlane-01", "payload.so", 1024, 1L, 80, 5001, 5002, 5003));
    }

    @Test
    public void rejectsInvalidToolboxHash() {
        assertThrows(IllegalArgumentException.class, () -> new FastLaneLaunchSpec(
                "fastlane-01", "payload.so", 1024, FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                1L, FastLaneLaunchSpec.DebugLevel.NONE, 5000, 5001, 5002, 5003, "not-a-hash"));
    }

    /** A payload without an FLN1 toolbox states the absence instead of faking a digest. */
    @Test
    public void acceptsEmptyToolboxHash() {
        FastLaneLaunchSpec spec = new FastLaneLaunchSpec(
                "fastlane-01", "libpvm_compiler_payload.so", 2048,
                FastLaneLaunchSpec.CpuTopology.ONE_CPU, 1048576L,
                FastLaneLaunchSpec.DebugLevel.NONE, 5100, 5101, 5102, 5103, "");
        assertEquals("", spec.toolboxManifestSha256());
    }

    @Test
    public void rejectsToolboxHashOfWrongLengthOrCase() {
        assertThrows(IllegalArgumentException.class, () -> new FastLaneLaunchSpec(
                "fastlane-01", "payload.so", 1024, FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                1L, FastLaneLaunchSpec.DebugLevel.NONE, 5000, 5001, 5002, 5003,
                "a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new FastLaneLaunchSpec(
                "fastlane-01", "payload.so", 1024, FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                1L, FastLaneLaunchSpec.DebugLevel.NONE, 5000, 5001, 5002, 5003,
                "A".repeat(64)));
    }

    @Test
    public void legacySpecCarriesNoPayloadApkPath() {
        FastLaneLaunchSpec spec = spec("fastlane-01", "libfastlane_payload.so", 1024, 1L,
                5000, 5001, 5002, 5003);
        assertEquals(null, spec.payloadApkPath());
    }

    @Test
    public void withPayloadApkPathCarriesAbsolutePath() {
        FastLaneLaunchSpec base = spec("fastlane-01", "libfastlane_payload.so", 1024, 1L,
                5000, 5001, 5002, 5003);
        FastLaneLaunchSpec redirected =
                base.withPayloadApkPath("/data/app/~~abc==/ext-hash==/base.apk");
        assertEquals("/data/app/~~abc==/ext-hash==/base.apk", redirected.payloadApkPath());
        // Immutable: the original keeps the platform-default semantics.
        assertEquals(null, base.payloadApkPath());
        assertEquals(base.instanceId(), redirected.instanceId());
        assertEquals(base.payloadBinaryName(), redirected.payloadBinaryName());
        assertEquals(base.distccPort(), redirected.distccPort());
    }

    @Test
    public void withPayloadApkPathRejectsRelativePaths() {
        FastLaneLaunchSpec base = spec("fastlane-01", "libfastlane_payload.so", 1024, 1L,
                5000, 5001, 5002, 5003);
        assertThrows(IllegalArgumentException.class,
                () -> base.withPayloadApkPath("relative/base.apk"));
        assertThrows(IllegalArgumentException.class, () -> base.withPayloadApkPath(" "));
    }

    @Test
    public void fullConstructorAcceptsAndValidatesPayloadApkPath() {
        FastLaneLaunchSpec spec = new FastLaneLaunchSpec(
                "fastlane-01", "libfastlane_payload.so", "/data/app/x/base.apk",
                1024, FastLaneLaunchSpec.CpuTopology.ONE_CPU, 1L,
                FastLaneLaunchSpec.DebugLevel.NONE, 5000, 5001, 5002, 5003, SHA);
        assertEquals("/data/app/x/base.apk", spec.payloadApkPath());
        assertThrows(IllegalArgumentException.class, () -> new FastLaneLaunchSpec(
                "fastlane-01", "libfastlane_payload.so", "no-leading-slash.apk",
                1024, FastLaneLaunchSpec.CpuTopology.ONE_CPU, 1L,
                FastLaneLaunchSpec.DebugLevel.NONE, 5000, 5001, 5002, 5003, SHA));
    }
}
