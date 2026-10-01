package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral contract tests for port forwarding rules, security boundaries,
 * and port conflict definitions (ADR-0008, ADR-0010).
 */
public final class PortForwardingContractTest {
    public static void main(String[] args) {
        testProtocolParsingAndWireFormat();
        testDefaultRuleBindsLoopbackOnly();
        testExplicitLanExposureRequiresSecurityFlag();
        testRejectsInvalidPorts();
        testRejectsInvalidBindAddresses();
        testRuleImmutabilityAndEquality();
        testPortConflictExceptionMetadata();
        System.out.println("PORT_FORWARDING_CONTRACT_TEST_OK");
    }

    private static void testProtocolParsingAndWireFormat() {
        assertEquals("tcp", Protocol.TCP.wireName(), "tcp wireName");
        assertEquals("udp", Protocol.UDP.wireName(), "udp wireName");
        assertEquals(Protocol.TCP, Protocol.fromWire("tcp"), "parse lowercase tcp");
        assertEquals(Protocol.TCP, Protocol.fromWire("TCP"), "parse uppercase tcp");
        assertEquals(Protocol.UDP, Protocol.fromWire("udp"), "parse lowercase udp");
        assertEquals(Protocol.UDP, Protocol.fromWire("UDP"), "parse uppercase udp");

        assertThrows(
                IllegalArgumentException.class,
                () -> Protocol.fromWire("sctp"),
                "unsupported protocol must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> Protocol.fromWire(null),
                "null protocol must be rejected");
    }

    private static void testDefaultRuleBindsLoopbackOnly() {
        PortForwardingRule rule = PortForwardingRule.defaultRule("rule-1", Protocol.TCP, 8080, 80);

        assertEquals("rule-1", rule.id(), "rule id");
        assertEquals(Protocol.TCP, rule.protocol(), "protocol");
        assertEquals(8080, rule.hostPort(), "host port");
        assertEquals(80, rule.guestPort(), "guest port");
        assertEquals(PortForwardingRule.LOOPBACK_ADDRESS, rule.bindAddress(), "default bind address (127.0.0.1)");
        assertFalse(rule.isLanExposed(), "default rule must not be LAN exposed (ADR-0008)");
    }

    private static void testExplicitLanExposureRequiresSecurityFlag() {
        // Attempting to bind 0.0.0.0 without explicit allowLanExposure must throw SecurityException
        assertThrows(
                SecurityException.class,
                () -> PortForwardingRule.builder()
                        .id("rule-lan-unauthorized")
                        .protocol(Protocol.TCP)
                        .hostPort(8080)
                        .guestPort(80)
                        .bindAddress(PortForwardingRule.LAN_EXPOSED_ADDRESS)
                        .build(),
                "LAN exposure without explicit allowLanExposure must be rejected per ADR-0008/ADR-0010");

        // Explicitly enabling LAN exposure must succeed
        PortForwardingRule authorizedRule = PortForwardingRule.builder()
                .id("rule-lan-authorized")
                .protocol(Protocol.TCP)
                .hostPort(8080)
                .guestPort(80)
                .bindAddress(PortForwardingRule.LAN_EXPOSED_ADDRESS)
                .allowLanExposure(true)
                .description("Public Web Service")
                .build();

        assertEquals(PortForwardingRule.LAN_EXPOSED_ADDRESS, authorizedRule.bindAddress(), "LAN bind address");
        assertTrue(authorizedRule.isLanExposed(), "must report isLanExposed as true");
        assertEquals("Public Web Service", authorizedRule.description(), "rule description");
    }

    private static void testRejectsInvalidPorts() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PortForwardingRule.defaultRule("r-invalid-hp-0", Protocol.TCP, 0, 80),
                "host port 0 must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> PortForwardingRule.defaultRule("r-invalid-hp-65536", Protocol.TCP, 65536, 80),
                "host port > 65535 must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> PortForwardingRule.defaultRule("r-invalid-gp-0", Protocol.TCP, 8080, 0),
                "guest port 0 must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> PortForwardingRule.defaultRule("r-invalid-gp-65536", Protocol.TCP, 8080, 65536),
                "guest port > 65535 must be rejected");
    }

    private static void testRejectsInvalidBindAddresses() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PortForwardingRule.builder()
                        .id("r-invalid-ip")
                        .protocol(Protocol.TCP)
                        .hostPort(8080)
                        .guestPort(80)
                        .bindAddress("invalid-ip")
                        .build(),
                "invalid IP format must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> PortForwardingRule.builder()
                        .id("r-leading-zero")
                        .protocol(Protocol.TCP)
                        .hostPort(8080)
                        .guestPort(80)
                        .bindAddress("0127.0.0.1")
                        .build(),
                "leading zero in IP address must be rejected");
    }

    private static void testRuleImmutabilityAndEquality() {
        PortForwardingRule r1 = PortForwardingRule.builder()
                .id("r1")
                .protocol(Protocol.TCP)
                .hostPort(8080)
                .guestPort(80)
                .bindAddress(PortForwardingRule.LOOPBACK_ADDRESS)
                .description("HTTP")
                .build();

        PortForwardingRule r2 = PortForwardingRule.builder(r1).build();

        assertEquals(r1, r2, "equality of replayed rule");
        assertEquals(r1.hashCode(), r2.hashCode(), "hashCode equality");
    }

    private static void testPortConflictExceptionMetadata() {
        PortConflictException ex = new PortConflictException(
                Protocol.TCP, 8080, "127.0.0.1", "rule-existing");

        assertEquals(Protocol.TCP, ex.protocol(), "conflict protocol");
        assertEquals(8080, ex.hostPort(), "conflict host port");
        assertEquals("127.0.0.1", ex.bindAddress(), "conflict bind address");
        assertEquals("rule-existing", ex.conflictingRuleId(), "conflict rule id");
        assertTrue(ex.getMessage().contains("8080"), "message contains port");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
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
