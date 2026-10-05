package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeClient;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;

/**
 * The Full Linux bridge contract exercised over the AVF vsock transport with
 * the simulated guest: the SAME {@link BridgeClient} the QEMU strategy uses,
 * the SAME wire lines the contract suite asserts — proving "不复制两套行为规范".
 */
public final class AvfBridgeOverVsockContractTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        handshakeSendsTheExactHostInitiatedHelloOverVsock();
        pingPongAndResizeRoundTripOverVsock();
        aWrongTokenIsRejectedByTheGuestAgent();
        notifyAndOpenUrlRoundTripOverVsock();
        powerRoundTripsOverVsockForTheGracefulStopPath();
        System.out.println(
                "AVF_BRIDGE_OVER_VSOCK_CONTRACT_TEST_OK hello=1 ping_resize=1 unauthorized=1"
                        + " notify_open_url=1 power=1");
    }

    private static AvfVirtualMachineEngine.AvfVmSession session(SimulatedAvfGuest guest)
            throws Exception {
        AvfVirtualMachineEngine engine =
                new AvfVirtualMachineEngine(AvfVirtualMachineApiFixture.apiOver(guest));
        return engine.launch(AvfLaunchSpec.builder(
                        "bridge-contract-1", "/kernel", "/initramfs", "/root.raw")
                .build());
    }

    private static void handshakeSendsTheExactHostInitiatedHelloOverVsock() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());

            BridgeClient.Result result = client.handshake(TOKEN);

            assertTrue(result.accepted(), "READY must be accepted over vsock");
            assertEquals("ready", result.responseKind(), "READY response kind");
            assertEquals(
                    "DROIDLAYER/HELLO 1 1 " + TOKEN,
                    guest.receivedControlLines().get(0),
                    "the host must initiate the exact smoke handshake line");
            session.closeChannels();
        }
    }

    private static void pingPongAndResizeRoundTripOverVsock() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());
            client.handshake(TOKEN);

            BridgeClient.Result pong = client.ping();
            assertTrue(pong.accepted(), "PONG must be accepted over vsock");
            BridgeClient.Result resize = client.resize(80, 24);
            assertTrue(resize.accepted(), "OK resize must be accepted over vsock");
            assertEquals("ok-resize", resize.responseKind(), "resize kind");
            assertTrue(
                    guest.receivedControlLines().contains("DROIDLAYER/RESIZE 80 24"),
                    "resize wire line reaches the guest");
            session.closeChannels();
        }
    }

    private static void aWrongTokenIsRejectedByTheGuestAgent() throws Exception {
        String wrongToken =
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());

            BridgeClient.Result rejected = client.handshake(wrongToken);

            assertTrue(!rejected.accepted(), "a wrong token must not be accepted");
            assertEquals("unauthorized", rejected.responseKind(), "error kind surfaces");
            session.closeChannels();
        }
    }

    private static void notifyAndOpenUrlRoundTripOverVsock() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());
            client.handshake(TOKEN);

            BridgeClient.Result notify = client.notify("Task Done", "Build finished");
            assertTrue(notify.accepted(), "OK notify must be accepted over vsock");
            assertEquals("ok-notify", notify.responseKind(), "notify kind");
            BridgeClient.Result openUrl = client.openUrl("https://droidlayer.github.io");
            assertTrue(openUrl.accepted(), "OK open-url must be accepted over vsock");
            assertEquals("ok-open-url", openUrl.responseKind(), "open-url kind");
            assertTrue(
                    guest.receivedControlLines().contains("DROIDLAYER/NOTIFY Task+Done Build+finished"),
                    "notify encoded wire line reaches the guest: "
                            + guest.receivedControlLines());
            assertTrue(
                    guest.receivedControlLines()
                            .contains("DROIDLAYER/OPEN-URL https://droidlayer.github.io"),
                    "open-url wire line reaches the guest");
            session.closeChannels();
        }
    }

    /** Ticket 28's power command rides the same vsock control channel. */
    private static void powerRoundTripsOverVsockForTheGracefulStopPath() throws Exception {
        try (SimulatedAvfGuest guest = new SimulatedAvfGuest(TOKEN, 9100, 9101)) {
            AvfVirtualMachineEngine.AvfVmSession session = session(guest);
            BridgeClient client = new BridgeClient(
                    session.controlChannel().inputStream(),
                    session.controlChannel().outputStream());
            client.handshake(TOKEN);

            BridgeClient.Result shutdown = client.power(BridgeControl.PowerAction.SHUTDOWN);
            assertTrue(shutdown.accepted(), "OK power must be accepted over vsock");
            assertEquals("ok-power", shutdown.responseKind(), "power kind");
            assertTrue(
                    guest.receivedControlLines().contains("DROIDLAYER/POWER shutdown"),
                    "the exact power wire line reaches the guest");
            BridgeClient.Result poweroff = client.power("poweroff");
            assertTrue(poweroff.accepted(), "poweroff by wire name is accepted too");
            assertEquals("ok-power", poweroff.responseKind(), "poweroff kind");
            session.closeChannels();
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected=[" + expected + "], actual=[" + actual + "]");
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_BRIDGE_OVER_VSOCK_CONTRACT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
