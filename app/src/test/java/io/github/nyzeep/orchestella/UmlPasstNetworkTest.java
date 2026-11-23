package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlNetworkProfile;
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class UmlPasstNetworkTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void argvContainsRequiredIsolationFlagsAndUnreachableDnsFallback() {
        Path pidFile = tmp.getRoot().toPath().resolve("passt.pid");
        List<String> withDns = Arrays.asList(
                UmlPasstNetwork.argv("/lib/libuml_passt.so", pidFile, 41234, "8.8.8.8"));
        assertTrue(withDns.contains("--no-map-gw"));
        assertTrue(withDns.contains("--no-icmp"));
        assertTrue(withDns.contains("--no-dhcp"));
        assertTrue(withDns.contains("10.0.2.3"));
        assertTrue(withDns.contains("8.8.8.8"));
        assertTrue(withDns.contains("127.0.0.1/41234:65353"));
        assertTrue(withDns.contains("none"));

        List<String> withoutDns = Arrays.asList(
                UmlPasstNetwork.argv("/lib/libuml_passt.so", pidFile, 41234, null));
        assertTrue(withoutDns.contains("192.0.2.53"));
    }

    @Test
    public void supportsRuleAllowsLoopbackTcpAndRejectsReservedOrNonLoopback() {
        UmlPasstNetwork network = new UmlPasstNetwork(
                tmp.getRoot().toPath(),
                tmp.getRoot().toPath().resolve("network"),
                "inst-1",
                TOKEN,
                () -> "/fake/libuml_passt.so",
                0,
                () -> {},
                (argv, pidFile) -> null,
                new FakeRouteMonitor(null, null));

        assertTrue(network.supportsRule(rule("ok", Protocol.TCP, 18080, 8080, false)));
        assertFalse(network.supportsRule(rule("udp", Protocol.UDP, 18080, 8080, false)));
        assertFalse(network.supportsRule(rule("lan", Protocol.TCP, 18080, 8080, true)));
        assertFalse(network.supportsRule(rule("compile-w", Protocol.TCP, 3632, 3632, false)));
        assertFalse(network.supportsRule(rule("compile-s", Protocol.TCP, 3633, 3633, false)));
        assertFalse(network.supportsRule(rule(
                "relay", Protocol.TCP, 18080, UmlNetworkProfile.GUEST_RELAY_PORT, false)));
    }

    @Test(timeout = 15000)
    public void offlineToOnlineTransitionForwardingHotSwapAndRestartRestoreWork() throws Exception {
        Path runtimeDir = tmp.newFolder("runtime").toPath();
        Path netRoot = tmp.newFolder("network").toPath();
        FilePortForwardingStore store = new FilePortForwardingStore(netRoot);

        int hostPortA = freePort();
        int hostPortB = freePort();
        PortForwardingRule persisted = rule("persisted", Protocol.TCP, hostPortA, 9001, false);
        store.saveRules("inst-1", List.of(persisted));

        try (FakeRelayServer relay = new FakeRelayServer(TOKEN, true)) {
            FakeRouteMonitor routes = new FakeRouteMonitor(null, null);
            List<UmlPasstNetwork.Status> observedStatuses = new CopyOnWriteArrayList<>();
            AtomicInteger spawnCount = new AtomicInteger();

            UmlPasstNetwork.PasstLauncher launcher = createFakeRelayLauncher(relay, spawnCount, false, () -> false);
            UmlPasstNetwork network = new UmlPasstNetwork(
                    runtimeDir, netRoot, "inst-1", TOKEN,
                    () -> "/fake/libuml_passt.so",
                    10, () -> {}, launcher, routes);
            try {
                network.restoreBeforePublish();
                assertTrue(network.isRunning());
                assertTrue(network.isApplied(persisted));

                network.start();
                awaitStatus(network, UmlPasstNetwork.Status.OFFLINE, 5_000L);
                // Persisted rule restores forwarding as soon as guest relay is ready.
                assertEquals("guest:9001", echoRoundTrip(hostPortA, "hello"));

                // Transition offline -> online with IPv4 DNS without spurious HELPER_EXITED.
                routes.update("wifi-net", "8.8.8.8", observedStatuses::add, network);
                awaitStatus(network, UmlPasstNetwork.Status.READY, 5_000L);
                assertTrue(spawnCount.get() >= 2);
                assertFalse("route transition must not report HELPER_EXITED",
                        observedStatuses.contains(UmlPasstNetwork.Status.HELPER_EXITED));

                // Hot update retargets guest port on same host port.
                PortForwardingRule retargeted = rule("persisted", Protocol.TCP, hostPortA, 9002, false);
                network.replaceRule(persisted, retargeted);
                assertTrue(network.isApplied(retargeted));
                assertEquals("guest:9002", echoRoundTrip(hostPortA, "world"));

                // Conflict keeps original listener alive and serving.
                try (ServerSocket blocker = new ServerSocket(hostPortB, 1, InetAddress.getByName("127.0.0.1"))) {
                    PortForwardingRule conflicting = rule("persisted", Protocol.TCP, hostPortB, 9003, false);
                    assertThrows(IOException.class, () -> network.replaceRule(retargeted, conflicting));
                    assertTrue(network.isApplied(retargeted));
                    assertEquals("guest:9002", echoRoundTrip(hostPortA, "still-alive"));
                }

                // Revoke releases host listener.
                network.removeRule(retargeted);
                assertFalse(network.isApplied(retargeted));
            } finally {
                network.closeAfterGuestExit();
            }
            assertEquals(UmlPasstNetwork.Status.STOPPED, network.status());
            assertFalse(network.isRunning());
        }
    }

    @Test(timeout = 15000)
    public void invalidRelayTokenFailsAuthAndMarksGuestSetupUnavailable() throws Exception {
        Path runtimeDir = tmp.newFolder("runtime-badauth").toPath();
        Path netRoot = tmp.newFolder("network-badauth").toPath();
        String wrongToken = "f".repeat(64);
        try (FakeRelayServer relay = new FakeRelayServer(wrongToken, true)) {
            FakeRouteMonitor routes = new FakeRouteMonitor("net", null);
            UmlPasstNetwork network = new UmlPasstNetwork(
                    runtimeDir, netRoot, "inst-badauth", TOKEN,
                    () -> "/fake/libuml_passt.so",
                    10, () -> {},
                    createFakeRelayLauncher(relay, new AtomicInteger(), false, () -> false),
                    routes);
            try {
                network.restoreBeforePublish();
                network.start();
                awaitStatus(network, UmlPasstNetwork.Status.GUEST_SETUP_UNAVAILABLE, 10_000L);
            } finally {
                network.closeAfterGuestExit();
            }
        }
    }

    @Test(timeout = 15000)
    public void refreshResetsExhaustedRetryBudgetAndUnconfirmedExitThrows() throws Exception {
        Path runtimeDir = tmp.newFolder("runtime-retry").toPath();
        Path netRoot = tmp.newFolder("network-retry").toPath();
        try (FakeRelayServer relay = new FakeRelayServer(TOKEN, true)) {
            FakeRouteMonitor routes = new FakeRouteMonitor("cell", null);
            AtomicInteger spawnCount = new AtomicInteger();
            AtomicBoolean failSpawns = new AtomicBoolean(true);
            AtomicBoolean refuseReap = new AtomicBoolean(false);

            UmlPasstNetwork.PasstLauncher launcher = (argv, pidFile) -> {
                int attempt = spawnCount.incrementAndGet();
                if (failSpawns.get()) {
                    throw new IOException("transient spawn failure " + attempt);
                }
                return createFakeRelayLauncher(relay, new AtomicInteger(), false, refuseReap::get)
                        .spawn(argv, pidFile);
            };
            UmlPasstNetwork network = new UmlPasstNetwork(
                    runtimeDir, netRoot, "inst-retry", TOKEN,
                    () -> "/fake/libuml_passt.so",
                    10, () -> {}, launcher, routes);
            try {
                network.restoreBeforePublish();
                network.start();
                long deadline = System.currentTimeMillis() + 5_000L;
                while (spawnCount.get() < 3 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(30L);
                }
                assertEquals(3, spawnCount.get());

                // Exhausted retries should recover when refresh() is called after issue resolves.
                failSpawns.set(false);
                network.refresh();
                awaitStatus(network, UmlPasstNetwork.Status.NO_IPV4_DNS, 5_000L);
                assertTrue(spawnCount.get() >= 4);

                refuseReap.set(true);
                IllegalStateException unconfirmed = assertThrows(
                        IllegalStateException.class, network::closeAfterGuestExit);
                assertTrue(unconfirmed.getMessage().contains("unconfirmed"));
            } finally {
                refuseReap.set(false);
                try {
                    network.closeAfterGuestExit();
                } catch (IllegalStateException ignored) {
                    // Already closed above if unconfirmed test ran.
                }
            }
        }
    }

    @Test(timeout = 15000)
    public void refreshProbesRouteSnapshotWhenRouteMonitorObserveFailedOnStartup() throws Exception {
        Path runtimeDir = tmp.getRoot().toPath().resolve("runtime-refresh-route");
        Path netRoot = tmp.getRoot().toPath().resolve("network-refresh-route");
        Files.createDirectories(runtimeDir);
        Files.createDirectories(netRoot);
        try (FakeRelayServer relay = new FakeRelayServer(TOKEN, true)) {
            AtomicReference<UmlPasstNetwork.HostRoute> snapshotRoute =
                    new AtomicReference<>(new UmlPasstNetwork.HostRoute(null, null));
            UmlPasstNetwork.RouteMonitor failingObserverMonitor = new UmlPasstNetwork.RouteMonitor() {
                @Override public UmlPasstNetwork.HostRoute snapshot() {
                    return snapshotRoute.get();
                }

                @Override public AutoCloseable observe(Runnable onChange) {
                    throw new SecurityException("connectivity callback restricted");
                }
            };
            UmlPasstNetwork network = new UmlPasstNetwork(
                    runtimeDir, netRoot, "inst-refresh-route", TOKEN,
                    () -> "/fake/libuml_passt.so",
                    10, () -> {},
                    createFakeRelayLauncher(relay, new AtomicInteger(), false, () -> false),
                    failingObserverMonitor);
            try {
                network.restoreBeforePublish();
                network.start();
                awaitStatus(network, UmlPasstNetwork.Status.OFFLINE, 5_000L);

                snapshotRoute.set(new UmlPasstNetwork.HostRoute("wlan0", "8.8.8.8"));
                network.refresh();
                awaitStatus(network, UmlPasstNetwork.Status.READY, 5_000L);
            } finally {
                network.closeAfterGuestExit();
            }
        }
    }

    private static UmlPasstNetwork.PasstLauncher createFakeRelayLauncher(
            FakeRelayServer relay,
            AtomicInteger spawnCount,
            boolean neverWritePid,
            java.util.function.BooleanSupplier refuseReap) {
        return (argv, pidFile) -> {
            int pid = 7000 + spawnCount.incrementAndGet();
            int relayPort = extractRelayPort(argv);
            relay.acceptForPort(relayPort);
            if (!neverWritePid) {
                Files.write(pidFile, (pid + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            CountDownLatch stopped = new CountDownLatch(1);
            return new UmlPasstNetwork.PasstProcess() {
                @Override public int pid() { return pid; }
                @Override public void pump(int guestFd) {
                    try {
                        stopped.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                @Override public void stopPump() { stopped.countDown(); }
                @Override public boolean reap(boolean force) { return !refuseReap.getAsBoolean(); }
                @Override public void close() { stopped.countDown(); }
            };
        };
    }

    private static int extractRelayPort(String[] argv) {
        for (int i = 0; i < argv.length - 1; i++) {
            if ("-t".equals(argv[i])) {
                String spec = argv[i + 1];
                int slash = spec.indexOf('/');
                int colon = spec.indexOf(':');
                return Integer.parseInt(spec.substring(slash + 1, colon));
            }
        }
        throw new AssertionError("missing -t option");
    }

    private static void awaitStatus(UmlPasstNetwork network, UmlPasstNetwork.Status expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (network.status() == expected) return;
            Thread.sleep(25L);
        }
        assertEquals(expected, network.status());
    }

    private static String echoRoundTrip(int port, String body) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write((body + "\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            return reader.readLine();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static PortForwardingRule rule(String id, Protocol protocol, int host, int guest, boolean lan) {
        return PortForwardingRule.builder()
                .id(id).protocol(protocol).hostPort(host).guestPort(guest)
                .bindAddress(lan ? "0.0.0.0" : "127.0.0.1").allowLanExposure(lan).build();
    }

    private static final class FakeRouteMonitor implements UmlPasstNetwork.RouteMonitor {
        private final AtomicReference<UmlPasstNetwork.HostRoute> current;
        private Runnable callback;

        FakeRouteMonitor(Object key, String dns) {
            this.current = new AtomicReference<>(new UmlPasstNetwork.HostRoute(key, dns));
        }

        void update(Object key, String dns,
                java.util.function.Consumer<UmlPasstNetwork.Status> statusProbe,
                UmlPasstNetwork network) {
            statusProbe.accept(network.status());
            current.set(new UmlPasstNetwork.HostRoute(key, dns));
            if (callback != null) callback.run();
        }

        @Override public UmlPasstNetwork.HostRoute snapshot() { return current.get(); }
        @Override public AutoCloseable observe(Runnable onChange) {
            callback = onChange;
            return () -> callback = null;
        }
    }

    /** Test-only local TCP server mimicking passt -t + forward-connection.sh on a single relay port. */
    private static final class FakeRelayServer implements AutoCloseable {
        private final String token;
        private final boolean acceptLoop;
        private final List<ServerSocket> sockets = new ArrayList<>();
        private volatile boolean closed;

        FakeRelayServer(String token, boolean acceptLoop) {
            this.token = token;
            this.acceptLoop = acceptLoop;
        }

        void acceptForPort(int relayPort) throws IOException {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", relayPort));
            synchronized (sockets) {
                sockets.add(server);
            }
            Thread t = new Thread(() -> {
                while (!closed && !server.isClosed() && acceptLoop) {
                    try {
                        Socket client = server.accept();
                        handleClient(client);
                    } catch (IOException ignored) {
                        break;
                    }
                }
            }, "fake-relay-" + relayPort);
            t.setDaemon(true);
            t.start();
        }

        private void handleClient(Socket client) {
            Thread handler = new Thread(() -> {
                try (Socket s = client) {
                    s.setSoTimeout(3_000);
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    OutputStream out = s.getOutputStream();
                    String h1 = reader.readLine();
                    if (h1 == null || !h1.startsWith("H1 ")) return;
                    String challenge = h1.substring(3).trim();
                    String nonce = "a".repeat(64);
                    String proof = StorageContract.sha256Hex(
                            ("G1:" + challenge + ":" + token).getBytes(StandardCharsets.US_ASCII));
                    out.write(("G1 " + proof + " " + nonce + "\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String f1 = reader.readLine();
                    if (f1 == null) return;
                    String[] parts = f1.split(" ");
                    if (parts.length != 3 || !"F1".equals(parts[0])) return;
                    int guestPort = Integer.parseInt(parts[1]);
                    String expectedF1 = StorageContract.sha256Hex(
                            ("F1:" + nonce + ":" + guestPort + ":" + token).getBytes(StandardCharsets.US_ASCII));
                    if (!expectedF1.equals(parts[2])) return;
                    out.write("OK\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String payload = reader.readLine();
                    if (payload != null) {
                        out.write(("guest:" + guestPort + "\n").getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                } catch (IOException ignored) {
                }
            });
            handler.setDaemon(true);
            handler.start();
        }

        @Override public void close() {
            closed = true;
            synchronized (sockets) {
                for (ServerSocket s : sockets) {
                    try { s.close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}
