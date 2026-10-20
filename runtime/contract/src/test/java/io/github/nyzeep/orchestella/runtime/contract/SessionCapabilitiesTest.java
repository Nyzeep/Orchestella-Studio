package io.github.nyzeep.orchestella.runtime.contract;

/**
 * Ticket 04: a session capability descriptor records only what this launch
 * actually assembled. It is not a product promise and it cannot claim
 * hardware acceleration on a software strategy or vice versa.
 */
public final class SessionCapabilitiesTest {
    public static void main(String[] arguments) {
        recordsOnlyTheAssembledParts();
        hardwareStrategyRequiresHardwareAcceleration();
        softwareStrategyRefusesHardwareAcceleration();
        waylandIsAChoiceNotAnUpgradeFromRfb();
        sharedDirectoryIsAToggle();
        System.out.println("SESSION_CAPABILITIES_TEST_OK parts=1 hardware=2 rfb=1 share=1");
    }

    private static void recordsOnlyTheAssembledParts() {
        SessionCapabilities capabilities = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                .ownership(SessionCapabilities.Ownership.RUNTIME_OWNED)
                .terminal(true)
                .bridge(true)
                .display(SessionCapabilities.DisplayTransport.RFB)
                .hardwareAcceleration(true)
                .build();

        assertTrue(capabilities.terminal(), "terminal was assembled");
        assertTrue(capabilities.bridge(), "bridge was assembled");
        assertEquals(SessionCapabilities.DisplayTransport.RFB, capabilities.display(),
                "display transport is RFB");
        assertTrue(capabilities.hardwareAcceleration(), "hardware virtualization was assembled");
        assertEquals(SessionCapabilities.Ownership.RUNTIME_OWNED, capabilities.ownership(),
                "the session owns its runtime");
        assertFalse(capabilities.audioOutput(), "audio was not assembled");
        assertFalse(capabilities.clipboardSync(), "clipboard was not assembled");
        assertFalse(capabilities.pointerCapture(), "pointer capture was not assembled");
        assertFalse(capabilities.sharedDirectory(), "9p was not assembled");
        assertFalse(capabilities.serviceForward(), "forwarding was not assembled");
        assertFalse(capabilities.gpuAcceleration(), "guest GPU acceleration was not assembled");
    }

    private static void hardwareStrategyRequiresHardwareAcceleration() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionCapabilities.builder(
                                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                        .terminal(true)
                        .bridge(true)
                        .build(),
                "a hardware strategy without the acceleration bit would under-claim its own strategy");
    }

    private static void softwareStrategyRefusesHardwareAcceleration() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionCapabilities.builder(
                                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                        .terminal(true)
                        .bridge(true)
                        .hardwareAcceleration(true)
                        .build(),
                "software virtualization cannot claim hardware acceleration");
    }

    private static void waylandIsAChoiceNotAnUpgradeFromRfb() {
        SessionCapabilities wayland = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                .terminal(true)
                .bridge(true)
                .hardwareAcceleration(true)
                .display(SessionCapabilities.DisplayTransport.WAYLAND)
                .build();
        assertTrue(wayland.hasDisplay(), "a Wayland adapter has a display");
        assertEquals(SessionCapabilities.DisplayTransport.WAYLAND, wayland.display(),
                "the descriptor keeps the declared transport");

        SessionCapabilities none = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                .terminal(true)
                .bridge(true)
                .build();
        assertFalse(none.hasDisplay(), "an unassembled display stays absent");
        assertEquals(SessionCapabilities.DisplayTransport.NONE, none.display(),
                "the default transport is none, never a guessed RFB");
    }

    private static void sharedDirectoryIsAToggle() {
        SessionCapabilities wired = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                .terminal(true)
                .bridge(true)
                .hardwareAcceleration(true)
                .sharedDirectory(true)
                .build();
        assertTrue(wired.sharedDirectory(), "the 9p server was started");
        assertFalse(wired.withSharedDirectory(false).sharedDirectory(),
                "the declaration can be withdrawn when the server is not serving");
        assertTrue(wired.withSharedDirectory(false).terminal(),
                "withdrawing 9p keeps the rest of the declaration");
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

    private static <T extends Throwable> void assertThrows(
            Class<T> type, Runnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected " + type.getName());
    }
}
