package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.EnumSet;

/** Public strategy-neutral capability-matrix behavior. */
public final class FullLinuxContractTest {
    public static void main(String[] arguments) {
        exposesSoftwareVirtualizationBadgeAsData();
        refusesAnIncompleteBaselineMatrix();
        exposesWaylandGraphicsCapabilityAndBadges();
    }

    private static void exposesSoftwareVirtualizationBadgeAsData() {
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline();

        assertEquals(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                matrix.executionStrategy(),
                "selected strategy");
        assertEquals("完整 Linux · 软件模拟（较慢）", matrix.performanceBadge(), "honest performance badge");
        assertTrue(matrix.supports(FullLinuxContract.Capability.TERMINAL), "terminal capability");
        assertTrue(matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE), "bridge capability");
        assertFalse(matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION), "no false hardware claim");
        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND), "no default graphics in baseline");
        assertEquals("无图形通道", matrix.graphicsBadge(), "no graphics badge");
    }

    private static void exposesWaylandGraphicsCapabilityAndBadges() {
        FullLinuxContract.CapabilityMatrix softwareGraphics =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithGraphics();
        assertTrue(softwareGraphics.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND), "software wayland support");
        assertEquals("Wayland 原生通道（共享内存）", softwareGraphics.graphicsBadge(), "software graphics badge");

        FullLinuxContract.CapabilityMatrix hardwareGraphics =
                FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithGraphics();
        assertTrue(hardwareGraphics.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND), "hardware wayland support");
        assertTrue(hardwareGraphics.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION), "hardware acceleration");
        assertEquals("Wayland 原生通道（vsock 硬件加速）", hardwareGraphics.graphicsBadge(), "hardware graphics badge");
    }

    private static void refusesAnIncompleteBaselineMatrix() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new FullLinuxContract.CapabilityMatrix(
                                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                                EnumSet.of(FullLinuxContract.Capability.TERMINAL)),
                "software baseline requires its normal-path capabilities");
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
        if (!java.util.Objects.equals(expected, actual)) {
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
