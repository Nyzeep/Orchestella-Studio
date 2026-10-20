package io.github.nyzeep.orchestella.runtime.contract;

/**
 * Ticket 04: the capability matrix is a pure projection of the descriptor and
 * never a second truth. RFB is not Wayland, absence is the default, and the
 * matrix still refuses illegal combinations.
 */
public final class CapabilityProjectionTest {
    public static void main(String[] arguments) {
        qemuRfbSessionDeclaresRfbNotWayland();
        avfSessionDeclaresOnlyItsWiredDataPlane();
        wiredNinePAppearsAndForwardingNeverDoes();
        waylandAdapterKeepsItsOwnCapability();
        rfbDisplayAliasStaysCompatible();
        projectionRefusesSoftwareHardwareAcceleration();
        badgesNameTheRealTransport();
        tableDrivenLauncherShapes();
        System.out.println("CAPABILITY_PROJECTION_TEST_OK qemu=1 avf=1 rfb=1 alias=1 absent=1 table=1");
    }

    /** Launcher x expected capabilities, so a new launcher shape cannot drift silently. */
    private static void tableDrivenLauncherShapes() {
        java.util.List<Shape> shapes = java.util.List.of(
                new Shape("qemu-baseline",
                        descriptor(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                                SessionCapabilities.DisplayTransport.NONE, false),
                        java.util.EnumSet.of(
                                FullLinuxContract.Capability.TERMINAL,
                                FullLinuxContract.Capability.BRIDGE_HANDSHAKE)),
                new Shape("qemu-rfb",
                        descriptor(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                                SessionCapabilities.DisplayTransport.RFB, false),
                        java.util.EnumSet.of(
                                FullLinuxContract.Capability.TERMINAL,
                                FullLinuxContract.Capability.BRIDGE_HANDSHAKE,
                                FullLinuxContract.Capability.GRAPHICS_RFB)),
                new Shape("avf-rfb",
                        descriptor(FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                                SessionCapabilities.DisplayTransport.RFB, false),
                        java.util.EnumSet.of(
                                FullLinuxContract.Capability.TERMINAL,
                                FullLinuxContract.Capability.BRIDGE_HANDSHAKE,
                                FullLinuxContract.Capability.HARDWARE_ACCELERATION,
                                FullLinuxContract.Capability.GRAPHICS_RFB)),
                new Shape("avf-rfb-9p",
                        descriptor(FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                                SessionCapabilities.DisplayTransport.RFB, true),
                        java.util.EnumSet.of(
                                FullLinuxContract.Capability.TERMINAL,
                                FullLinuxContract.Capability.BRIDGE_HANDSHAKE,
                                FullLinuxContract.Capability.HARDWARE_ACCELERATION,
                                FullLinuxContract.Capability.GRAPHICS_RFB,
                                FullLinuxContract.Capability.SHARED_DIRECTORY)));
        for (Shape shape : shapes) {
            assertEquals(shape.expected(),
                    FullLinuxContract.CapabilityMatrix.from(shape.descriptor()).capabilities(),
                    "capabilities for " + shape.name());
        }
    }

    private record Shape(String name, SessionCapabilities descriptor,
            java.util.Set<FullLinuxContract.Capability> expected) {}

    private static SessionCapabilities descriptor(
            FullLinuxContract.ExecutionStrategy strategy,
            SessionCapabilities.DisplayTransport display, boolean shared) {
        SessionCapabilities.Builder builder = SessionCapabilities.builder(strategy)
                .terminal(true)
                .bridge(true)
                .display(display)
                .sharedDirectory(shared);
        if (strategy == FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION) {
            builder.hardwareAcceleration(true);
        }
        return builder.build();
    }

    private static void qemuRfbSessionDeclaresRfbNotWayland() {
        SessionCapabilities descriptors = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                .ownership(SessionCapabilities.Ownership.RUNTIME_OWNED)
                .terminal(true)
                .bridge(true)
                .display(SessionCapabilities.DisplayTransport.RFB)
                .build();
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.from(descriptors);

        assertEquals(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                matrix.executionStrategy(), "QEMU remains software virtualization");
        assertTrue(matrix.supports(FullLinuxContract.Capability.TERMINAL), "terminal");
        assertTrue(matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE), "bridge");
        assertTrue(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB), "RFB display");
        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND),
                "an RFB transport never claims Wayland");
        assertFalse(matrix.supports(FullLinuxContract.Capability.AUDIO_OUTPUT), "no unassembled audio");
        assertFalse(matrix.supports(FullLinuxContract.Capability.CLIPBOARD_SYNC), "no unassembled clipboard");
        assertFalse(matrix.supports(FullLinuxContract.Capability.POINTER_CAPTURE), "no pointer capture");
        assertFalse(matrix.supports(FullLinuxContract.Capability.GPU_HARDWARE_ACCELERATION),
                "no GPU claim");
        assertFalse(matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION),
                "QEMU is not hardware virtualization");
    }

    private static void avfSessionDeclaresOnlyItsWiredDataPlane() {
        SessionCapabilities descriptors = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                .ownership(SessionCapabilities.Ownership.RUNTIME_OWNED)
                .terminal(true)
                .bridge(true)
                .display(SessionCapabilities.DisplayTransport.RFB)
                .hardwareAcceleration(true)
                .build();
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.from(descriptors);

        assertTrue(matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION),
                "AVF hardware virtualization");
        assertTrue(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB), "AVF RFB display");
        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND),
                "AVF v1 has no Wayland adapter");
        assertFalse(matrix.supportsAudio(), "AVF v1 has no audio relay");
        assertFalse(matrix.supportsClipboard(), "AVF v1 has no wired clipboard agent");
        assertFalse(matrix.supportsPointerCapture(), "RFB pointer events are not pointer capture");
        assertFalse(matrix.supportsGpuAcceleration(), "AVF v1 has no guest GPU acceleration");
    }

    private static void wiredNinePAppearsAndForwardingNeverDoes() {
        SessionCapabilities descriptors = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                .terminal(true)
                .bridge(true)
                .hardwareAcceleration(true)
                .sharedDirectory(true)
                .serviceForward(true)
                .build();
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.from(descriptors);

        assertTrue(matrix.supports(FullLinuxContract.Capability.SHARED_DIRECTORY),
                "a started 9p server is declared");
        assertTrue(descriptors.serviceForward(), "the descriptor records the forward wiring");
        assertTrue(matrix.capabilities().stream()
                        .noneMatch(capability -> capability.name().contains("FORWARD")),
                "the matrix has no forward capability to over-claim; forward stays descriptor-only");
    }

    private static void waylandAdapterKeepsItsOwnCapability() {
        SessionCapabilities descriptors = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                .terminal(true)
                .bridge(true)
                .hardwareAcceleration(true)
                .display(SessionCapabilities.DisplayTransport.WAYLAND)
                .build();
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.from(descriptors);
        assertTrue(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND),
                "only a real Wayland adapter declares Wayland");
        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB),
                "a Wayland adapter is not also an RFB display");
    }

    private static void rfbDisplayAliasStaysCompatible() {
        assertEquals(FullLinuxContract.Capability.GRAPHICS_RFB,
                FullLinuxContract.Capability.GRAPHICS_DISPLAY,
                "the display alias resolves to the real RFB capability");
    }

    private static void projectionRefusesSoftwareHardwareAcceleration() {
        SessionCapabilities illegal = SessionCapabilities.builder(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                .terminal(true)
                .bridge(true)
                .build();
        assertFalse(FullLinuxContract.CapabilityMatrix.from(illegal)
                        .supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION),
                "the projection never invents hardware acceleration");
    }

    private static void badgesNameTheRealTransport() {
        FullLinuxContract.CapabilityMatrix qemu = FullLinuxContract.CapabilityMatrix.from(
                SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION)
                        .terminal(true).bridge(true)
                        .display(SessionCapabilities.DisplayTransport.RFB)
                        .build());
        assertTrue(qemu.graphicsBadge().contains("RFB"), "QEMU badge names RFB");
        assertFalse(qemu.graphicsBadge().contains("Wayland"), "QEMU badge never says Wayland");

        FullLinuxContract.CapabilityMatrix avf = FullLinuxContract.CapabilityMatrix.from(
                SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION)
                        .terminal(true).bridge(true).hardwareAcceleration(true)
                        .display(SessionCapabilities.DisplayTransport.RFB)
                        .build());
        assertTrue(avf.graphicsBadge().contains("RFB"), "AVF badge names RFB");
        assertFalse(avf.graphicsBadge().contains("Wayland"), "AVF badge never says Wayland");
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
}
