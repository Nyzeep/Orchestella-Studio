package io.github.nyzeep.orchestella.runtime.engineavf;

import android.system.virtualmachine.FakeVsockNetwork;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Ticket 36 AC-2: the AVF profile's live port-forwarding engine. Host traffic
 * accepted on the rule's host port is tunneled over vsock to the in-guest
 * forward proxy (which relays to the guest-local service), with the SAME
 * hot-update semantics the QEMU profile delivers through QMP hostfwd: add →
 * reachable, remove → immediately refused, no VM restart.
 */
public final class AvfVsockPortForwarderTest {
    private static final String BANNER = "HELLO FROM AVF GUEST\n";

    public static void main(String[] arguments) throws Exception {
        forwardsHostTrafficToTheGuestThroughVsock();
        invalidatesImmediatelyOnHotRemovalAndSupportsDynamicReAdd();
        stopsServingAfterClose();
        System.out.println(
                "AVF_VSOCK_PORT_FORWARDER_TEST_OK live=1 hot_removal=1 re_add=1 close=1");
    }

    private static void forwardsHostTrafficToTheGuestThroughVsock() throws Exception {
        FakeVsockNetwork network = new FakeVsockNetwork();
        int guestServicePort = startGuestService();
        network.listen(guestServicePort, socket -> inGuestForwardProxy(socket, guestServicePort));
        int hostPort = freeLoopbackPort();

        try (AvfVsockPortForwarder forwarder =
                new AvfVsockPortForwarder(AvfVirtualMachineApiFixture.channelFactoryOver(network))) {
            assertTrue(forwarder.isRunning(), "a fresh forwarder is running");
            PortForwardingRule rule =
                    PortForwardingRule.defaultRule("live-1", Protocol.TCP, hostPort, guestServicePort);
            forwarder.addRule(rule);

            try (Socket client = new Socket("127.0.0.1", hostPort)) {
                assertEquals(BANNER.trim(), readLine(client.getInputStream()), "banner reaches the host");
                client.getOutputStream().write("PING\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                assertEquals("PING", readLine(client.getInputStream()), "echo round trip over vsock");
            }
            forwarder.removeRule(rule);
        }
    }

    private static void invalidatesImmediatelyOnHotRemovalAndSupportsDynamicReAdd()
            throws Exception {
        FakeVsockNetwork network = new FakeVsockNetwork();
        int guestServicePort = startGuestService();
        network.listen(guestServicePort, socket -> inGuestForwardProxy(socket, guestServicePort));
        int hostPort = freeLoopbackPort();

        try (AvfVsockPortForwarder forwarder = new AvfVsockPortForwarder(AvfVirtualMachineApiFixture.channelFactoryOver(network))) {
            PortForwardingRule rule =
                    PortForwardingRule.defaultRule("hot-1", Protocol.TCP, hostPort, guestServicePort);
            forwarder.addRule(rule);
            expectBanner(hostPort, "rule must be reachable right after addRule");

            forwarder.removeRule(rule);
            Thread.sleep(100);
            assertConnectRefused(hostPort, "hot removal must invalidate the host port immediately");

            forwarder.addRule(
                    PortForwardingRule.defaultRule("hot-2", Protocol.TCP, hostPort, guestServicePort));
            expectBanner(hostPort, "re-adding the same host port must serve again");
        }
    }

    private static void stopsServingAfterClose() throws Exception {
        FakeVsockNetwork network = new FakeVsockNetwork();
        int guestServicePort = startGuestService();
        network.listen(guestServicePort, socket -> inGuestForwardProxy(socket, guestServicePort));
        int hostPort = freeLoopbackPort();

        AvfVsockPortForwarder forwarder = new AvfVsockPortForwarder(AvfVirtualMachineApiFixture.channelFactoryOver(network));
        PortForwardingRule rule =
                PortForwardingRule.defaultRule("close-1", Protocol.TCP, hostPort, guestServicePort);
        forwarder.addRule(rule);
        expectBanner(hostPort, "reachable before close");
        forwarder.close();
        assertTrue(!forwarder.isRunning(), "close stops the forwarder");
        Thread.sleep(100);
        assertConnectRefused(hostPort, "closed forwarder must not serve");
        rejectsIOException(() -> forwarder.addRule(
                PortForwardingRule.defaultRule("close-2", Protocol.TCP, hostPort, guestServicePort)));
    }

    // ---- simulated in-guest side -------------------------------------------------

    /** The in-guest service: banner + echo on a loopback port (guest-local 127.0.0.1). */
    private static int startGuestService() throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread handler = new Thread(() -> serveEcho(socket));
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }, "avf-guest-service-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    private static void serveEcho(Socket socket) {
        try (socket) {
            socket.getOutputStream().write(BANNER.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] buffer = new byte[4096];
            int count;
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while ((count = in.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
                out.flush();
            }
        } catch (IOException ignored) {
            // guest-side channel end
        }
    }

    /**
     * The in-guest forward proxy: bridges one vsock connection to the
     * guest-local service on 127.0.0.1:{@code guestServicePort} — the exact
     * shape the real guest agent implements on a device.
     */
    private static void inGuestForwardProxy(Socket vsockSide, int guestServicePort) {
        try (Socket vsock = vsockSide;
                Socket guest = new Socket(InetAddress.getLoopbackAddress(), guestServicePort)) {
            Thread toGuest = pump(vsock.getInputStream(), guest.getOutputStream());
            Thread toHost = pump(guest.getInputStream(), vsock.getOutputStream());
            toGuest.join();
            toHost.join();
        } catch (Exception ignored) {
            // proxy flow ended (either side closed)
        }
    }

    private static Thread pump(InputStream in, OutputStream out) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int count;
            try {
                while ((count = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, count);
                    out.flush();
                }
            } catch (IOException ignored) {
                // relay direction ended
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ---- helpers -----------------------------------------------------------------

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return probe.getLocalPort();
        }
    }

    private static void expectBanner(int hostPort, String description) throws Exception {
        try (Socket client = new Socket("127.0.0.1", hostPort)) {
            assertEquals(BANNER.trim(), readLine(client.getInputStream()), description);
        }
    }

    private static void assertConnectRefused(int hostPort, String description) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", hostPort), 500);
            fail(description + ": connection unexpectedly succeeded");
        } catch (IOException expected) {
            // refused/timeout is the required invalidation semantics
        }
    }

    private static void rejectsIOException(ThrowingAction action) {
        try {
            action.run();
            fail("expected IOException after close");
        } catch (IOException expected) {
            // required
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws IOException;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            if (b == '\n') {
                return line.toString();
            }
            line.append((char) b);
        }
        return line.toString();
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
        System.out.println("AVF_VSOCK_PORT_FORWARDER_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
