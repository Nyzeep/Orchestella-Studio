package io.github.nyzeep.orchestella.runtime.engineqemu;

import io.github.nyzeep.orchestella.runtime.contract.NetworkContract;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Contract tests for QEMU hostfwd formatting, CLI launch spec injection,
 * and QMP dynamic runtime emission (ADR-0008, Ticket 29 AC-1, AC-2).
 */
public final class QemuHostfwdContractTest {
    public static void main(String[] args) throws Exception {
        formatsHostfwdPatternForLoopbackAndLan();
        formatsHmpAndQmpCommands();
        injectsHostfwdIntoQemuLaunchSpec();
        injectsQmpSocketIntoQemuLaunchSpec();
        qmpClientExecutesHandshakeAndDynamicHostfwdAddAndRemove();
        qmpClientHandlesErrorResponsesGracefully();
        formatsBlockResizeAndExecutesQmpCommand();
        System.out.println("QEMU_HOSTFWD_CONTRACT_TEST_OK");
    }

    private static void formatsHostfwdPatternForLoopbackAndLan() {
        PortForwardingRule loopback = PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80);
        String pattern1 = QemuHostfwdFormatter.formatHostfwdPattern(loopback);
        assertEquals("tcp:127.0.0.1:8080-:80", pattern1, "loopback pattern");

        PortForwardingRule lanRule = PortForwardingRule.builder()
                .id("r2")
                .protocol(Protocol.UDP)
                .hostPort(5353)
                .guestPort(53)
                .bindAddress(PortForwardingRule.LAN_EXPOSED_ADDRESS)
                .allowLanExposure(true)
                .build();
        String pattern2 = QemuHostfwdFormatter.formatHostfwdPattern(lanRule);
        assertEquals("udp:0.0.0.0:5353-:53", pattern2, "lan pattern");
    }

    private static void formatsHmpAndQmpCommands() {
        PortForwardingRule rule = PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80);

        String addHmp = QemuHostfwdFormatter.formatHostfwdAddHmp("net0", rule);
        assertEquals("hostfwd_add net0 tcp:127.0.0.1:8080-:80", addHmp, "hmp add command");

        String removeHmp = QemuHostfwdFormatter.formatHostfwdRemoveHmp("net0", rule);
        assertEquals("hostfwd_remove net0 tcp:127.0.0.1:8080", removeHmp, "hmp remove command");

        String addQmp = QemuHostfwdFormatter.formatQmpHostfwdAdd("net0", rule);
        assertEquals("{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\"hostfwd_add net0 tcp:127.0.0.1:8080-:80\"}}",
                addQmp, "qmp add command");

        String removeQmp = QemuHostfwdFormatter.formatQmpHostfwdRemove("net0", rule);
        assertEquals("{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\"hostfwd_remove net0 tcp:127.0.0.1:8080\"}}",
                removeQmp, "qmp remove command");
    }

    private static void injectsHostfwdIntoQemuLaunchSpec() {
        PortForwardingRule r1 = PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80);
        PortForwardingRule r2 = PortForwardingRule.builder()
                .id("r2")
                .protocol(Protocol.UDP)
                .hostPort(5353)
                .guestPort(53)
                .bindAddress(PortForwardingRule.LAN_EXPOSED_ADDRESS)
                .allowLanExposure(true)
                .build();

        List<PortForwardingRule> rules = Arrays.asList(r1, r2);

        String[] spec = QemuLaunchSpec.build(
                "/native/libdroidlayer_qemu.so",
                "/kernel",
                "/initrd",
                "/root.raw",
                "control-sock",
                "terminal-sock",
                512L,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                rules);

        String netdevArg = findArgValue(spec, "-netdev");
        assertTrue(netdevArg.contains("user,id=net0"), "netdev user id");
        assertTrue(netdevArg.contains("hostfwd=tcp:127.0.0.1:8080-:80"), "contains r1 hostfwd");
        assertTrue(netdevArg.contains("hostfwd=udp:0.0.0.0:5353-:53"), "contains r2 hostfwd");
    }

    private static void injectsQmpSocketIntoQemuLaunchSpec() {
        String[] spec = QemuLaunchSpec.build(
                "/native/libdroidlayer_qemu.so",
                "/kernel",
                "/initrd",
                "/root.raw",
                "control-sock",
                "terminal-sock",
                "droidlayer-qmp-inst1",
                512L,
                NetworkContract.OutboundNetworkSpec.defaultSpec(),
                Collections.emptyList());

        String joined = String.join("\u0000", spec);
        assertTrue(joined.contains("-chardev\u0000socket,id=qmp,path=droidlayer-qmp-inst1,abstract=on,server=on,wait=off"),
                "spec contains qmp chardev");
        assertTrue(joined.contains("-mon\u0000chardev=qmp,mode=control"),
                "spec contains qmp monitor");
    }

    private static void qmpClientExecutesHandshakeAndDynamicHostfwdAddAndRemove() throws Exception {
        String serverReplies = "{\"QMP\": {\"version\": {\"qemu\": {\"micro\": 0, \"minor\": 0, \"major\": 9}}}}\r\n"
                + "{\"return\": {}}\r\n"
                + "{\"return\": \"\"}\r\n"
                + "{\"return\": \"\"}\r\n";

        ByteArrayInputStream in = new ByteArrayInputStream(serverReplies.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        QemuQmpHostfwdClient client = new QemuQmpHostfwdClient(in, out, "net0");
        client.init();

        PortForwardingRule rule = PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80);
        client.addRule(rule);
        client.removeRule(rule);

        String sentRequests = out.toString(StandardCharsets.UTF_8);
        assertTrue(sentRequests.contains("{\"execute\":\"qmp_capabilities\"}\n"), "capabilities sent");
        assertTrue(sentRequests.contains("hostfwd_add net0 tcp:127.0.0.1:8080-:80"), "hostfwd_add sent");
        assertTrue(sentRequests.contains("hostfwd_remove net0 tcp:127.0.0.1:8080"), "hostfwd_remove sent");
        assertTrue(client.isRunning(), "client is running");
    }

    private static void qmpClientHandlesErrorResponsesGracefully() throws Exception {
        String serverReplies = "{\"QMP\": {\"version\": {\"qemu\": {\"micro\": 0, \"minor\": 0, \"major\": 9}}}}\r\n"
                + "{\"return\": {}}\r\n"
                + "{\"error\": {\"class\": \"GenericError\", \"desc\": \"could not set up host forwarding rule\"}}\r\n";

        ByteArrayInputStream in = new ByteArrayInputStream(serverReplies.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        QemuQmpHostfwdClient client = new QemuQmpHostfwdClient(in, out, "net0");
        client.init();

        PortForwardingRule rule = PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80);
        assertThrows(
                IOException.class,
                () -> client.addRule(rule),
                "QMP error must throw IOException");
    }

    private static String findArgValue(String[] spec, String flag) {
        for (int i = 0; i < spec.length - 1; i++) {
            if (spec[i].equals(flag)) {
                return spec[i + 1];
            }
        }
        throw new AssertionError("Flag " + flag + " not found in spec");
    }

    private static void formatsBlockResizeAndExecutesQmpCommand() throws IOException {
        String json = QemuHostfwdFormatter.formatQmpBlockResize("root", 4294967296L);
        assertEquals("{\"execute\":\"block_resize\",\"arguments\":{\"device\":\"root\",\"size\":4294967296}}",
                json, "formatQmpBlockResize");

        assertThrows(
                IllegalArgumentException.class,
                () -> QemuHostfwdFormatter.formatQmpBlockResize("root", 0),
                "zero size must be rejected");

        String simulatedBanner = "{\"QMP\": {\"version\": {\"qemu\": {\"micro\": 0, \"minor\": 2, \"major\": 8}}}}\n";
        String simulatedCapabilitiesOk = "{\"return\": {}}\n";
        String simulatedResizeOk = "{\"return\": {}}\n";

        String inboundFeed = simulatedBanner + simulatedCapabilitiesOk + simulatedResizeOk;
        ByteArrayInputStream inbound = new ByteArrayInputStream(inboundFeed.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outbound = new ByteArrayOutputStream();

        try (QemuQmpHostfwdClient client = new QemuQmpHostfwdClient(inbound, outbound, "net0")) {
            client.init();
            client.resizeBlockDevice("root", 4294967296L);
            String transcript = outbound.toString(StandardCharsets.UTF_8);
            assertTrue(transcript.contains("{\"execute\":\"block_resize\",\"arguments\":{\"device\":\"root\",\"size\":4294967296}}"),
                    "outbound transcript contains block_resize");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
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
        void run() throws Throwable;
    }
}
