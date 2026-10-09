package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.network.PortForwardingManagerContractTest;

/**
 * Ticket 36 AC-3: the two-strategy forwarding-semantics equivalence proof. The
 * EXACT contract suite the QEMU profile passes ({@code
 * PortForwardingManagerContractTest}: persistence, conflict rejection, hot
 * reconciliation, replay-on-attach, and the live create→reach→delete→refuse
 * traffic) runs here against the AVF vsock forwarding engine over the fake
 * vsock network — no rewritten scenarios, only the engine run target changes.
 */
public final class AvfNetworkForwardingParityContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        theQemuForwardingContractSuitePassesOnTheAvfVsockEngine();
        System.out.println(
                "AVF_NETWORK_FORWARDING_PARITY_CONTRACT_TEST_OK"
                        + " same_suite=1 persistence=1 conflict=1 hot_reconcile=1 live_traffic=1");
    }

    private static void theQemuForwardingContractSuitePassesOnTheAvfVsockEngine()
            throws Exception {
        java.nio.file.Path tempDir =
                java.nio.file.Files.createTempDirectory("avf-forwarding-parity");
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine engine =
                    new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
            AvfLaunchSpec spec = AvfLaunchSpec.builder(
                            "forwarding-parity-1", "/data/kernel",
                            "/data/initramfs.cpio.gz", "/data/root.raw")
                    .build();
            AvfVirtualMachineEngine.AvfVmSession session = engine.launch(spec);

            // The SAME suite, the AVF engine: every forwarded guest service port
            // gets an in-guest proxy listener, and host channels are opened
            // through the production session path (session::openChannel).
            PortForwardingManagerContractTest.runAllScenarios(tempDir, guestServicePort -> {
                guest.listenForwardProxy(guestServicePort, guestServicePort);
                return new AvfVsockPortForwarder(session::openChannel);
            });

            session.closeChannels();
        } finally {
            // runAllScenarios' own finally already removes the whole tree.
            if (java.nio.file.Files.exists(tempDir)) {
                java.nio.file.Files.walk(tempDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                java.nio.file.Files.deleteIfExists(path);
                            } catch (java.io.IOException ignored) {
                                // best-effort cleanup of the test work dir
                            }
                        });
            }
        }
    }
}
