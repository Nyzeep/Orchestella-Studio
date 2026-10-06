package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;

/** JVM contract tests for VmHardwareConfig and FullLinuxContract hardware configuration. */
public final class VmHardwareConfigTest {
    public static void main(String[] arguments) {
        rejectsIllegalCpuCores();
        rejectsIllegalRamMegabytes();
        rejectsIllegalDiskCapacityBytes();
        acceptsBoundaryAndValidValues();
        exposesDefaultFactory();
        supportsBuilderAndWithHelpers();
        equalityAndHashCodeBehaveAsValueObject();
        fullLinuxContractCapabilityAndInstanceContract();
        System.out.println("VM_HARDWARE_CONFIG_TEST_OK");
    }

    private static void rejectsIllegalCpuCores() {
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(0, 2048, 8L * 1024 * 1024 * 1024, true, null),
                "cpuCores 0 must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(-1, 2048, 8L * 1024 * 1024 * 1024, true, null),
                "negative cpuCores must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(9, 2048, 8L * 1024 * 1024 * 1024, true, null),
                "cpuCores 9 must be rejected");
    }

    private static void rejectsIllegalRamMegabytes() {
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(2, 511, 8L * 1024 * 1024 * 1024, true, null),
                "ram 511MB must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(2, 0, 8L * 1024 * 1024 * 1024, true, null),
                "ram 0MB must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(2, 8193, 8L * 1024 * 1024 * 1024, true, null),
                "ram 8193MB must be rejected");
    }

    private static void rejectsIllegalDiskCapacityBytes() {
        long twoGb = 2L * 1024 * 1024 * 1024;
        long sixtyFourGb = 64L * 1024 * 1024 * 1024;

        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(2, 2048, twoGb - 1L, true, null),
                "disk below 2GB must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(2, 2048, 0L, true, null),
                "disk 0 bytes must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
                new VmHardwareConfig(2, 2048, sixtyFourGb + 1L, true, null),
                "disk above 64GB must be rejected");
    }

    private static void acceptsBoundaryAndValidValues() {
        long twoGb = 2L * 1024 * 1024 * 1024;
        long sixtyFourGb = 64L * 1024 * 1024 * 1024;

        VmHardwareConfig minConfig = new VmHardwareConfig(1, 512, twoGb, false, null);
        assertEquals(1, minConfig.cpuCores(), "min cpuCores");
        assertEquals(512, minConfig.ramMegabytes(), "min ramMegabytes");
        assertEquals(twoGb, minConfig.diskCapacityBytes(), "min diskCapacityBytes");
        assertFalse(minConfig.gpuAccelerationEnabled(), "min gpuAccelerationEnabled");
        assertEquals(null, minConfig.extraKernelArguments(), "min extraKernelArguments");

        VmHardwareConfig maxConfig = new VmHardwareConfig(8, 8192, sixtyFourGb, true, "console=ttyAMA0 loglevel=7");
        assertEquals(8, maxConfig.cpuCores(), "max cpuCores");
        assertEquals(8192, maxConfig.ramMegabytes(), "max ramMegabytes");
        assertEquals(sixtyFourGb, maxConfig.diskCapacityBytes(), "max diskCapacityBytes");
        assertTrue(maxConfig.gpuAccelerationEnabled(), "max gpuAccelerationEnabled");
        assertEquals("console=ttyAMA0 loglevel=7", maxConfig.extraKernelArguments(), "max extraKernelArguments");
    }

    private static void exposesDefaultFactory() {
        VmHardwareConfig def = VmHardwareConfig.defaultConfig();
        assertEquals(2, def.cpuCores(), "default cpu cores");
        assertEquals(2048, def.ramMegabytes(), "default ram");
        assertEquals(8L * 1024 * 1024 * 1024, def.diskCapacityBytes(), "default disk 8GB");
        assertTrue(def.gpuAccelerationEnabled(), "default gpu enabled");
        assertEquals(null, def.extraKernelArguments(), "default extra args null");
    }

    private static void supportsBuilderAndWithHelpers() {
        VmHardwareConfig base = VmHardwareConfig.defaultConfig();
        VmHardwareConfig modified = base.withCpuCores(4)
                .withRamMegabytes(4096)
                .withDiskCapacityBytes(16L * 1024 * 1024 * 1024)
                .withGpuAcceleration(false)
                .withExtraKernelArguments("quiet");

        assertEquals(4, modified.cpuCores(), "withCpuCores");
        assertEquals(4096, modified.ramMegabytes(), "withRamMegabytes");
        assertEquals(16L * 1024 * 1024 * 1024, modified.diskCapacityBytes(), "withDiskCapacityBytes");
        assertFalse(modified.gpuAccelerationEnabled(), "withGpuAcceleration");
        assertEquals("quiet", modified.extraKernelArguments(), "withExtraKernelArguments");

        // Verify immutability of base
        assertEquals(2, base.cpuCores(), "base unchanged");

        // Builder
        VmHardwareConfig built = VmHardwareConfig.builder()
                .cpuCores(6)
                .ramMegabytes(3072)
                .diskCapacityBytes(4L * 1024 * 1024 * 1024)
                .gpuAccelerationEnabled(true)
                .extraKernelArguments("earlyprintk")
                .build();
        assertEquals(6, built.cpuCores(), "builder cpuCores");
        assertEquals(3072, built.ramMegabytes(), "builder ramMegabytes");
        assertEquals(4L * 1024 * 1024 * 1024, built.diskCapacityBytes(), "builder diskCapacityBytes");
        assertTrue(built.gpuAccelerationEnabled(), "builder gpu");
        assertEquals("earlyprintk", built.extraKernelArguments(), "builder args");
    }

    private static void equalityAndHashCodeBehaveAsValueObject() {
        VmHardwareConfig a = new VmHardwareConfig(4, 2048, 8L * 1024 * 1024 * 1024, true, "arg1");
        VmHardwareConfig b = new VmHardwareConfig(4, 2048, 8L * 1024 * 1024 * 1024, true, "arg1");
        VmHardwareConfig c = new VmHardwareConfig(4, 2048, 8L * 1024 * 1024 * 1024, false, "arg1");

        assertEquals(a, b, "equal configs");
        assertEquals(a.hashCode(), b.hashCode(), "equal hashCodes");
        assertFalse(a.equals(c), "not equal configs");
    }

    private static void fullLinuxContractCapabilityAndInstanceContract() {
        // Capability
        assertTrue(FullLinuxContract.Capability.valueOf("GPU_HARDWARE_ACCELERATION") != null,
                "GPU_HARDWARE_ACCELERATION must be defined in Capability");

        // FullLinuxContract.Instance
        VmHardwareConfig config = VmHardwareConfig.defaultConfig();
        FullLinuxContract.Instance contractInstance = new FullLinuxContract.Instance("test-id-123", config);
        assertEquals("test-id-123", contractInstance.id(), "instance id");
        assertEquals(config, contractInstance.hardwareConfig(), "instance hardwareConfig");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
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
        void run();
    }
}
