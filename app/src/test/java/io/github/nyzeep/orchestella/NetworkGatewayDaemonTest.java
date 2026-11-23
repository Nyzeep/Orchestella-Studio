package io.github.nyzeep.orchestella;

import static org.junit.Assert.*;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotLease;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class NetworkGatewayDaemonTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 15000)
    public void gatewayStopWaitsForExitThenRemovesLiveEntry() throws Exception {
        String id = "gw-stop";
        Process process = TestProcesses.longRunning();
        File apiSocket = temporaryFolder.newFile("api.sock");
        NetworkGatewayDaemon.ForwardingEngine engine = new NetworkGatewayDaemon.ForwardingEngine(
                (method, endpoint, body) -> "[]", process::isAlive);
        NetworkGatewayDaemon.Running running = new NetworkGatewayDaemon.Running(
                "/tmp/net.yaml", 24199, process, id, apiSocket, engine);
        NetworkGatewayDaemon.LIVE.put(id, running);
        try {
            assertTrue(running.isAlive());
            assertTrue("stop must confirm the gateway process exited", running.stop(200));
            assertFalse(process.isAlive());
            assertNull("confirmed stop must release the live entry",
                    NetworkGatewayDaemon.forwardingEngine(id));
            assertFalse("confirmed stop must delete the API socket", apiSocket.exists());
            assertTrue("repeated stop must keep the confirmed result", running.stop(0));
        } finally {
            process.destroyForcibly();
            NetworkGatewayDaemon.LIVE.remove(id);
        }
    }

    @Test(timeout = 10000)
    public void confirmedStopMakesForwardingUnavailableAndStaysIdempotent() throws Exception {
        String id = "gw-forward-off";
        Process process = TestProcesses.longRunning();
        FakeApi api = new FakeApi();
        NetworkGatewayDaemon.ForwardingEngine engine =
                new NetworkGatewayDaemon.ForwardingEngine(api, process::isAlive);
        NetworkGatewayDaemon.Running running = new NetworkGatewayDaemon.Running(
                "/tmp/net.yaml", 24197, process, id,
                temporaryFolder.newFile("forward-off.sock"), engine);
        NetworkGatewayDaemon.LIVE.put(id, running);
        try {
            PortForwardingRule rule = rule("svc", Protocol.TCP, 2222, 18080, false);
            engine.addRule(rule);
            assertTrue(engine.isApplied(rule));

            assertTrue(running.stop(200));
            assertFalse("a confirmed stop must make the forwarding engine unavailable",
                    engine.isRunning());
            assertNull("no forwarding engine may remain reachable after a confirmed stop",
                    NetworkGatewayDaemon.forwardingEngine(id));
            assertTrue("a repeated stop keeps the confirmed result", running.stop(0));
            assertTrue(running.stop(200));
        } finally {
            process.destroyForcibly();
            NetworkGatewayDaemon.LIVE.remove(id);
        }
    }

    @Test(timeout = 10000)
    public void unconfirmedStopKeepsTheLeaseAndLateStopPreservesTheReplacement() throws Exception {
        String id = "gw-unconfirmed";
        Path root = temporaryFolder.getRoot().toPath();
        GatewaySlotLease lease = acquireTestLease(root, id);
        ExitControlledProcess process = new ExitControlledProcess();
        File apiSocket = Files.createFile(lease.apiSocket()).toFile();
        lease.recordProcess(1, 1, "fixture");
        NetworkGatewayDaemon.Running running = new NetworkGatewayDaemon.Running(
                "/tmp/net.yaml", NetworkGatewayDaemon.listenPortFor(id), process, id, apiSocket,
                new NetworkGatewayDaemon.ForwardingEngine((method, endpoint, body) -> "[]", process::isAlive),
                lease);
        NetworkGatewayDaemon.LIVE.put(id, running);
        try {
            assertFalse(running.stop(0));
            assertTrue(apiSocket.exists());
            assertSame(running, NetworkGatewayDaemon.LIVE.get(id));
            assertThrows(IOException.class, () -> acquireTestLease(root, id));

            process.exited = true;
            assertTrue(running.stop(0));
            assertFalse(apiSocket.exists());
            assertFalse(NetworkGatewayDaemon.LIVE.containsKey(id));
            GatewaySlotLease replacement = acquireTestLease(root, id);
            try {
                Files.createFile(replacement.apiSocket());
                replacement.recordProcess(1, 1, "fixture");
                assertTrue(running.stop(0));
                assertTrue(Files.exists(replacement.apiSocket()));
                assertThrows(IOException.class, () -> acquireTestLease(root, id));
            } finally {
                replacement.releaseAfterExit();
            }
        } finally {
            process.exited = true;
            running.stop(0);
        }
    }

    private static GatewaySlotLease acquireTestLease(Path root, String id) throws IOException {
        return GatewaySlotLease.acquire(root, id, (pid, startTicks) ->
                new GatewaySlotLease.ProcessObservation(GatewaySlotLease.ProcessState.UNAVAILABLE),
                socket -> Files.exists(socket) ? "fixture" : null);
    }

    private static final class ExitControlledProcess extends Process {
        boolean exited;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return exitValue(); }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return exited; }
        @Override public boolean isAlive() { return !exited; }
        @Override public void destroy() {}
        @Override public int exitValue() {
            if (!exited) throw new IllegalThreadStateException();
            return 0;
        }
    }

    @Test(timeout = 20000)
    public void confirmedStopReleasesTheFixedPortForImmediateReuse() throws Exception {
        String id = "gw-port";
        int port = NetworkGatewayDaemon.listenPortFor(id);
        Process owner = new ProcessBuilder(
                TestProcesses.javaChildCommand(PortHolder.class.getName(),
                        String.valueOf(port))).redirectErrorStream(true).start();
        try {
            BufferedReader output = new BufferedReader(new InputStreamReader(owner.getInputStream()));
            assertEquals("the fixture must bind before the stop", "BOUND", output.readLine());
            NetworkGatewayDaemon.Running running = new NetworkGatewayDaemon.Running(
                    "/tmp/net.yaml", port, owner, id, temporaryFolder.newFile("port.sock"),
                    new NetworkGatewayDaemon.ForwardingEngine((method, endpoint, body) -> "[]",
                            owner::isAlive));
            NetworkGatewayDaemon.LIVE.put(id, running);
            try {
                assertTrue(running.stop(500));
                // Windows can take a few milliseconds to reap the dead listener; a
                // bounded retry still fails if the port is genuinely still owned.
                long deadline = System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                java.net.BindException lastBindFailure = null;
                while (System.nanoTime() < deadline) {
                    try (java.net.ServerSocket rebind = new java.net.ServerSocket()) {
                        rebind.setReuseAddress(true);
                        rebind.bind(new java.net.InetSocketAddress("127.0.0.1", port));
                        assertTrue("a confirmed stop must release the fixed port", rebind.isBound());
                        return;
                    } catch (java.net.BindException busy) {
                        lastBindFailure = busy;
                        Thread.sleep(25L);
                    }
                }
                throw new AssertionError("fixed port was not reusable after confirmed stop",
                        lastBindFailure);
            } finally {
                NetworkGatewayDaemon.LIVE.remove(id);
            }
        } finally {
            owner.destroyForcibly();
        }
    }

    @Test(timeout = 10000)
    public void boundedTerminationForcesAndIsIdempotent() throws Exception {
        Process process = TestProcesses.longRunning();
        assertTrue(NetworkGatewayDaemon.terminateProcess(process, 100, 2000));
        assertFalse(process.isAlive());
        assertTrue(NetworkGatewayDaemon.terminateProcess(process, 0, 0));
    }

    @Test public void guestDnsPrefersFirstActiveVpnIpv4OverPublicFallback() {
        assertEquals("172.19.0.2", NetworkGatewayDaemon.chooseGuestDns(
                List.of("172.19.0.2", "202.96.128.86", "223.5.5.5")));
        assertEquals("172.19.0.2", NetworkGatewayDaemon.chooseGuestDns(
                List.of("2001:db8::53", "172.19.0.2", "202.96.128.86")));
    }
    @Test public void guestDnsUsesCurrentMobileResolverInReportedOrder() {
        assertEquals("202.96.128.86", NetworkGatewayDaemon.chooseGuestDns(
                List.of("202.96.128.86", "202.96.134.133")));
        assertEquals("202.96.134.133", NetworkGatewayDaemon.chooseGuestDns(
                List.of("202.96.134.133", "202.96.128.86")));
    }
    @Test public void guestDnsFallsBackForNoResolverOrIpv6OnlyNetwork() {
        assertEquals(NetworkGatewayDaemon.GUEST_DNS, NetworkGatewayDaemon.chooseGuestDns(null));
        assertEquals(NetworkGatewayDaemon.GUEST_DNS, NetworkGatewayDaemon.chooseGuestDns(List.of()));
        assertEquals(NetworkGatewayDaemon.GUEST_DNS, NetworkGatewayDaemon.chooseGuestDns(
                List.of("2001:db8::53", "::1", "::", "::ffff:172.19.0.2", "fe80::1%tun0")));
    }
    @Test public void guestDnsRejectsLoopbackUnspecifiedMulticastAndBroadcast() {
        for (String address : List.of("0.0.0.0", "0.1.2.3", "127.0.0.1", "127.19.0.2",
                "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255")) {
            assertEquals("unusable DNS literal must use the existing fallback",
                    NetworkGatewayDaemon.GUEST_DNS, NetworkGatewayDaemon.chooseGuestDns(List.of(address)));
        }
        assertEquals("172.19.0.2", NetworkGatewayDaemon.chooseGuestDns(
                List.of("127.0.0.1", "224.0.0.1", "172.19.0.2")));
    }
    @Test public void guestDnsRejectsHostSyntaxAndMalformedIpv4WithoutNameResolution() {
        for (String address : java.util.Arrays.asList(null, "", "resolver.invalid", "localhost",
                "https://resolver.invalid/dns-query", "172.19.0.2:53", "172.19.0.2/24",
                "172.019.0.2", "172.19.0", "172.19.0.2.3", "256.19.0.2", "-1.19.0.2",
                "172.19.0.2\n", " 172.19.0.2", "172.19.0.2 ", "0xac130002", "2886926338")) {
            assertEquals("DNS input must be a literal IPv4 address",
                    NetworkGatewayDaemon.GUEST_DNS, NetworkGatewayDaemon.chooseGuestDns(
                            java.util.Collections.singletonList(address)));
        }
        assertEquals("172.19.0.2", NetworkGatewayDaemon.chooseGuestDns(
                java.util.Arrays.asList(null, "resolver.invalid", "256.0.0.1", "172.19.0.2")));
    }
    @Test public void emptyStoreNeverCreatesSshOrLanListener() throws Exception {
        String yaml = NetworkGatewayDaemon.configuration(24101, "/data/private/net-api/api.sock", List.of());
        assertTrue(yaml.contains("services: \"unix:///data/private/net-api/api.sock\""));
        assertTrue(yaml.contains("qemu: \"tcp://127.0.0.1:24101\""));
        assertTrue(yaml.contains("forwards: {}"));
        assertFalse(yaml.contains("2222"));
        assertFalse(yaml.contains("0.0.0.0"));
        assertEquals("223.5.5.5", NetworkGatewayDaemon.GUEST_DNS);
    }
    @Test public void explicitTcpRuleTargetsVmNetstackAndKeepsExistingNetwork() throws Exception {
        String yaml = NetworkGatewayDaemon.configuration(24101, "/data/private/api.sock",
                List.of(rule("ssh", Protocol.TCP, 2222, 22, false)));
        assertTrue(yaml.contains("    \"127.0.0.1:2222\": \"192.168.127.15:22\""));
        assertTrue(yaml.contains("subnet: 192.168.127.0/24"));
        assertTrue(yaml.contains("gatewayIP: 192.168.127.1"));
        assertFalse(yaml.contains("services: \"tcp:"));
    }
    @Test public void unsupportedAndReservedRulesNeverExpandListeningSurface() throws Exception {
        String yaml = NetworkGatewayDaemon.configuration(24101, "/data/private/api.sock", List.of(
                rule("udp", Protocol.UDP, 2222, 22, false), rule("lan", Protocol.TCP, 3333, 22, true),
                rule("compiler", Protocol.TCP, 3632, 22, false), rule("submitter", Protocol.TCP, 3633, 22, false)));
        assertTrue(yaml.contains("forwards: {}"));
        assertFalse(yaml.contains("3333"));
        assertFalse(yaml.contains("0.0.0.0"));
    }
    @Test public void conflictingOrInjectedConfigurationIsRejected() {
        reject(() -> NetworkGatewayDaemon.configuration(24101, "/data/private/api.sock",
                List.of(rule("conflict", Protocol.TCP, 24101, 22, false))));
        reject(() -> NetworkGatewayDaemon.configuration(24101, "/data/private/api.sock\nlisten: tcp://0.0.0.0:9", List.of()));
    }
    @Test public void confirmedApiMappingIsLiveAndDisableRemovesIt() throws Exception {
        FakeApi api = new FakeApi();
        NetworkGatewayDaemon.ForwardingEngine engine = new NetworkGatewayDaemon.ForwardingEngine(api, () -> true);
        PortForwardingRule rule = rule("ssh", Protocol.TCP, 2222, 22, false);
        engine.addRule(rule);
        assertTrue(engine.isApplied(rule));
        assertEquals("192.168.127.15:22", api.mappings.get("127.0.0.1:2222"));
        engine.addRule(rule);
        assertEquals(1, api.exposes);
        engine.removeRule(rule);
        assertFalse(engine.isApplied(rule));
        assertTrue(api.mappings.isEmpty());
    }
    @Test public void forwardingUsesFullGoServicesMuxPaths() throws Exception {
        FakeApi api = new FakeApi();
        NetworkGatewayDaemon.ForwardingEngine engine = new NetworkGatewayDaemon.ForwardingEngine(api, () -> true);
        PortForwardingRule ssh = rule("ssh", Protocol.TCP, 2222, 22, false);
        engine.refresh();
        engine.addRule(ssh);
        engine.removeRule(ssh);
        assertTrue(api.endpoints.contains("GET /services/forwarder/all"));
        assertTrue(api.endpoints.contains("POST /services/forwarder/expose"));
        assertTrue(api.endpoints.contains("POST /services/forwarder/unexpose"));
        assertFalse(api.endpoints.stream().anyMatch(endpoint -> endpoint.contains(" /forwarder/")));
    }
    @Test public void rejectedApplyOrUnconfirmedApiCannotClaimLive() {
        FakeApi api = new FakeApi();
        api.failExpose = true;
        NetworkGatewayDaemon.ForwardingEngine engine = new NetworkGatewayDaemon.ForwardingEngine(api, () -> true);
        PortForwardingRule rule = rule("ssh", Protocol.TCP, 2222, 22, false);
        reject(() -> engine.addRule(rule));
        assertFalse(engine.isApplied(rule));
        assertTrue(api.mappings.isEmpty());
        api.failExpose = false;
        api.failConfirmation = true;
        reject(() -> engine.addRule(rule));
        assertFalse(engine.isApplied(rule));
        assertTrue("unconfirmed API addition must be rolled back", api.mappings.isEmpty());
    }
    @Test public void unsafeRuleNeverReachesApi() {
        FakeApi api = new FakeApi();
        NetworkGatewayDaemon.ForwardingEngine engine = new NetworkGatewayDaemon.ForwardingEngine(api, () -> true);
        reject(() -> engine.addRule(rule("lan", Protocol.TCP, 2222, 22, true)));
        reject(() -> engine.addRule(rule("udp", Protocol.UDP, 2222, 22, false)));
        reject(() -> engine.addRule(rule("compiler", Protocol.TCP, 3632, 22, false)));
        reject(() -> engine.addRule(rule("submitter", Protocol.TCP, 3633, 22, false)));
        assertEquals(0, api.calls);
    }
    @Test public void unknownProtocolHostAndOversizedResponsesFailClosed() {
        for (String invalid : List.of("{}", "[{\"local\":\"127.0.0.1:2222\",\"remote\":\"192.168.127.15:22\",\"protocol\":\"udp\"}]",
                "[{\"local\":\"0.0.0.0:2222\",\"remote\":\"192.168.127.15:22\",\"protocol\":\"tcp\"}]",
                "[{\"local\":\"127.0.0.1:2222\",\"remote\":\"127.0.0.1:22\",\"protocol\":\"tcp\"}]",
                "[" + " ".repeat(33 * 1024) + "]")) {
            reject(() -> NetworkGatewayDaemon.parseForwards(invalid));
        }
    }
    @Test public void httpErrorsAndOversizedPayloadsAreRejected() throws Exception {
        assertEquals("[]", NetworkGatewayDaemon.readResponse(stream("HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\n[]")));
        reject(() -> NetworkGatewayDaemon.readResponse(stream("HTTP/1.0 500 Failed\r\n\r\nerror")));
        reject(() -> NetworkGatewayDaemon.readResponse(stream("HTTP/1.0 200")));
        reject(() -> NetworkGatewayDaemon.readResponse(stream("HTTP/1.0 200 OK\r\n\r\n" + "a".repeat(33 * 1024))));
    }
    @Test public void contentLengthCompletesWithoutWaitingForSocketEof() throws Exception {
        byte[] response = "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\n[]\n"
                .getBytes(StandardCharsets.US_ASCII);
        assertEquals("[]\n", NetworkGatewayDaemon.readResponse(openConnection(response)));
    }
    @Test public void goChunkedResponseIsDecodedBeforeParsingForwards() throws Exception {
        String json = "[{\"local\":\"127.0.0.1:2222\",\"remote\":\"192.168.127.15:22\",\"protocol\":\"tcp\"}]\n";
        String framed = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                + Integer.toHexString(json.length()) + ";extension=valid\r\n" + json
                + "\r\n0\r\nX-Complete: yes\r\n\r\n";
        String body = NetworkGatewayDaemon.readResponse(openConnection(framed.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(json, body);
        assertEquals("192.168.127.15:22", NetworkGatewayDaemon.parseForwards(body).get("127.0.0.1:2222"));
    }
    @Test public void emptyPostReplyCompletesWithoutSocketEof() throws Exception {
        assertEquals("", NetworkGatewayDaemon.readResponse(openConnection(
                "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII))));
    }
    @Test public void malformedOrTruncatedHttpFramingIsRejected() {
        for (String response : List.of(
                "HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n[]",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n[]\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nffffffff\r\n",
                "HTTP/1.1 200 OK\nContent-Length: 2\n\n[]")) {
            reject(() -> NetworkGatewayDaemon.readResponse(stream(response)));
        }
    }
    @Test public void apiStatusFailureReportsCodeWithoutBody() {
        try {
            NetworkGatewayDaemon.readResponse(stream("HTTP/1.1 404 Not Found\r\nContent-Length: 17\r\n\r\nsynthetic-private"));
            fail("wrong API path status must fail");
        } catch (IOException expected) {
            assertEquals("gateway HTTP status 404", expected.getMessage());
            assertFalse(expected.getMessage().contains("synthetic-private"));
        }
    }

    @Test public void diagnosticReasonPreservesGatewaySlotCodes() {
        assertEquals("gateway-slot-owner-busy",
                NetworkGatewayDaemon.diagnosticReason(new IOException("gateway-slot-owner-busy")));
        assertEquals("gateway-slot-held_by_unknown",
                NetworkGatewayDaemon.diagnosticReason(new IOException("gateway-slot-held_by_unknown")));
        assertEquals("gateway-slot-previous-owner-running",
                NetworkGatewayDaemon.diagnosticReason(new IOException("gateway-slot-previous-owner-running")));
    }

    @Test public void processStatParserHandlesParenthesesAndSpacesInComm() {
        String stat = "1234 (gv (proxy) v1) S 1 1234 1234 0 -1 4194304 100 0 0 0 1 0 0 0 20 0 1 0 987654321 1000 10";
        assertEquals(987654321L, NetworkGatewayDaemon.parseStartTicksFromStat(stat));
        assertEquals(0L, NetworkGatewayDaemon.parseStartTicksFromStat("1234 (broken)"));
    }

    @Test public void inspectProcessDetectsPidReuseAndFailsClosedWhenStatUnreadable() {
        GatewaySlotLease.ProcessObservation reused = NetworkGatewayDaemon.inspectProcess(
                42, 100L, () -> 200L, () -> GatewaySlotLease.ProcessState.RUNNING, () -> List.of("gvproxy"));
        assertEquals(GatewaySlotLease.ProcessState.EXITED, reused.state());

        GatewaySlotLease.ProcessObservation unreadable = NetworkGatewayDaemon.inspectProcess(
                42, 100L, () -> 0L, () -> GatewaySlotLease.ProcessState.RUNNING, () -> List.of("gvproxy"));
        assertEquals(GatewaySlotLease.ProcessState.UNAVAILABLE, unreadable.state());

        GatewaySlotLease.ProcessObservation dead = NetworkGatewayDaemon.inspectProcess(
                42, 100L, () -> 100L, () -> GatewaySlotLease.ProcessState.EXITED, () -> List.of());
        assertEquals(GatewaySlotLease.ProcessState.EXITED, dead.state());

        GatewaySlotLease.ProcessObservation eperm = NetworkGatewayDaemon.inspectProcess(
                42, 100L, () -> 100L, () -> GatewaySlotLease.ProcessState.UNAVAILABLE, () -> List.of());
        assertEquals(GatewaySlotLease.ProcessState.UNAVAILABLE, eperm.state());
    }

    @Test public void forwardingEngineReplaceRuleHotSwapsAndPreservesOriginalOnConflict() throws Exception {
        FakeApi api = new FakeApi();
        NetworkGatewayDaemon.ForwardingEngine engine = new NetworkGatewayDaemon.ForwardingEngine(api, () -> true);
        PortForwardingRule original = rule("ssh", Protocol.TCP, 2222, 22, false);
        PortForwardingRule conflictRule = rule("other", Protocol.TCP, 2223, 80, false);
        engine.addRule(original);
        engine.addRule(conflictRule);

        // Moving host port succeeds and removes old mapping.
        PortForwardingRule moved = rule("ssh", Protocol.TCP, 2224, 22, false);
        engine.replaceRule(original, moved);
        assertTrue(engine.isApplied(moved));
        assertFalse(engine.isApplied(original));
        assertFalse(api.mappings.containsKey("127.0.0.1:2222"));

        // Moving onto an occupied host port fails while keeping moved active.
        PortForwardingRule collide = rule("ssh", Protocol.TCP, 2223, 22, false);
        reject(() -> engine.replaceRule(moved, collide));
        assertTrue(engine.isApplied(moved));
        assertTrue(engine.isApplied(conflictRule));

        // Changing guestPort while keeping same hostPort throws ListenerRetargetUnsupported and keeps moved active.
        PortForwardingRule retargetGuest = rule("ssh", Protocol.TCP, 2224, 2222, false);
        assertThrows(io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine.ListenerRetargetUnsupported.class,
                () -> engine.replaceRule(moved, retargetGuest));
        assertTrue(engine.isApplied(moved));

        // Description-only change (same hostPort & guestPort) succeeds as a no-op.
        PortForwardingRule renamed = PortForwardingRule.builder()
                .id("ssh").protocol(Protocol.TCP).hostPort(2224).guestPort(22)
                .bindAddress("127.0.0.1").allowLanExposure(false).description("SSH renamed").build();
        engine.replaceRule(moved, renamed);
        assertTrue(engine.isApplied(renamed));
    }

    @Test public void forwardingEngineRefreshRetriesOnceOnTransientTransportFailure() throws Exception {
        final int[] calls = {0};
        NetworkGatewayDaemon.ForwardingEngine engine = new NetworkGatewayDaemon.ForwardingEngine(
                (method, endpoint, body) -> {
                    calls[0]++;
                    if (calls[0] == 1) {
                        throw NetworkGatewayDaemon.transportFailureForTest("read");
                    }
                    return "[{\"local\":\"127.0.0.1:2222\",\"remote\":\"192.168.127.15:22\",\"protocol\":\"tcp\"}]";
                }, () -> true);
        engine.refresh();
        assertEquals(2, calls[0]);
        assertTrue(engine.isApplied(rule("ssh", Protocol.TCP, 2222, 22, false)));
    }
    private static ByteArrayInputStream openConnection(byte[] response) {
        return new ByteArrayInputStream(response) {
            @Override public synchronized int read() {
                if (pos >= count) throw new AssertionError("must stop at HTTP body boundary, not wait for EOF");
                return super.read();
            }
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (pos >= count) throw new AssertionError("must stop at HTTP body boundary, not wait for EOF");
                return super.read(bytes, offset, length);
            }
        };
    }
    private static PortForwardingRule rule(String id, Protocol protocol, int host, int guest, boolean lan) {
        return PortForwardingRule.builder().id(id).protocol(protocol).hostPort(host).guestPort(guest)
                .bindAddress(lan ? "0.0.0.0" : "127.0.0.1").allowLanExposure(lan).build();
    }
    private static ByteArrayInputStream stream(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)); }
    private interface Operation { void run() throws Exception; }
    private static void reject(Operation operation) {
        try { operation.run(); fail("unverified forwarding action must fail"); }
        catch (IOException expected) { }
        catch (Exception unexpected) { throw new AssertionError(unexpected); }
    }
    private static final class FakeApi implements NetworkGatewayDaemon.Api {
        final Map<String, String> mappings = new HashMap<>();
        final java.util.List<String> endpoints = new java.util.ArrayList<>();
        int exposes;
        int calls;
        boolean failExpose;
        boolean failConfirmation;
        @Override public String request(String method, String endpoint, String body) throws IOException {
            calls++;
            endpoints.add(method + " " + endpoint);
            if (endpoint.startsWith("/forwarder/")) throw new IOException("gateway HTTP status 404");
            if (method.equals("GET") && endpoint.equals("/services/forwarder/all")) {
                if (failConfirmation && !mappings.isEmpty()) throw new IOException("synthetic API disconnect");
                StringBuilder json = new StringBuilder("[");
                for (Map.Entry<String, String> entry : mappings.entrySet()) {
                    if (json.length() > 1) json.append(',');
                    json.append("{\"local\":\"").append(entry.getKey()).append("\",\"remote\":\"")
                        .append(entry.getValue()).append("\",\"protocol\":\"tcp\"}");
                }
                return json.append(']').toString();
            }
            assertEquals("POST", method);
            assertTrue(body.contains("\"protocol\":\"tcp\""));
            String local = body.split("\"local\":\"")[1].split("\"")[0];
            String remote = body.split("\"remote\":\"")[1].split("\"")[0];
            if (endpoint.equals("/services/forwarder/expose")) {
                if (failExpose) throw new IOException("synthetic bind conflict");
                exposes++;
                mappings.put(local, remote);
            } else if (endpoint.equals("/services/forwarder/unexpose")) mappings.remove(local);
            else throw new AssertionError("unexpected API path");
            return "";
        }
    }
}
