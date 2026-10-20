package io.github.nyzeep.orchestella.runtime.engineavf;

/**
 * Ticket 08 zero-device acceptance for the network plan and the Android 17
 * failure signature. The plan is data, not a boolean: NONE and NATIVE_TAP with
 * DHCP/static addressing, DNS, and an explicit degradation policy.
 */
public final class AvfNetworkPlanTest {
    public static void main(String[] arguments) {
        noneKeepsDnsButNoTap();
        dhcpDeclaresNoStaticKeys();
        staticPlanCarriesGuestAddressing();
        invalidStaticAddressingIsRejected();
        classifiesTheAndroid17EarlyStopSignature();
        System.out.println("AVF_NETWORK_PLAN_TEST_OK none=1 dhcp=1 static=1 validation=1"
                + " status35=1 early_stop=1 console_observed=1");
    }

    private static void noneKeepsDnsButNoTap() {
        AvfNetworkPlan plan = AvfNetworkPlan.none("1.1.1.1");
        assertFalse(plan.enabled(), "NONE must not enable TAP");
        assertEquals(AvfNetworkPlan.Addressing.NONE, plan.addressing(), "no addressing");
        assertEquals("1.1.1.1", plan.dnsServer(), "DNS stays available for the cmdline");
    }

    private static void dhcpDeclaresNoStaticKeys() {
        AvfNetworkPlan plan = AvfNetworkPlan.nativeTapDhcp("1.1.1.1", true);
        assertTrue(plan.enabled(), "DHCP plan enables TAP");
        assertEquals(AvfNetworkPlan.Addressing.DHCP, plan.addressing(), "DHCP addressing");
        assertTrue(plan.guestIp() == null && plan.gateway() == null && plan.netmask() == null,
                "DHCP must not fake static keys");
        assertTrue(plan.allowDegradeToNone(), "degradation policy is explicit");
    }

    private static void staticPlanCarriesGuestAddressing() {
        AvfNetworkPlan plan = AvfNetworkPlan.nativeTapStatic(
                "10.0.2.15", "10.0.2.2", "255.255.255.0", "1.1.1.1", false);
        assertTrue(plan.enabled(), "static plan enables TAP");
        assertEquals(AvfNetworkPlan.Addressing.STATIC, plan.addressing(), "static addressing");
        assertEquals("10.0.2.15", plan.guestIp(), "guest IP");
        assertEquals("10.0.2.2", plan.gateway(), "gateway");
        assertEquals("255.255.255.0", plan.netmask(), "netmask");
    }

    private static void invalidStaticAddressingIsRejected() {
        try {
            AvfNetworkPlan.nativeTapStatic("10.0.2.999", "10.0.2.2", "255.255.255.0", "1.1.1.1", true);
            fail("an invalid IPv4 literal must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("guestIp"), "the invalid field is named");
        }
        try {
            AvfNetworkPlan.nativeTapDhcp(" ", true);
            fail("a blank DNS server must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static void classifiesTheAndroid17EarlyStopSignature() {
        assertTrue(AvfNetworkFailure.isBackendUnavailable(true, 35, 1200L, false),
                "network + status 35 + early stop + empty console is the Android 17 signature");
        assertFalse(AvfNetworkFailure.isBackendUnavailable(false, 35, 1200L, false),
                "status 35 without a network request is a different failure");
        assertFalse(AvfNetworkFailure.isBackendUnavailable(true, 0, 1200L, false),
                "a normal exit is not the signature");
        assertFalse(AvfNetworkFailure.isBackendUnavailable(true, 35, 5000L, false),
                "a late exit is not the minimal-crosvm startup signature");
        assertFalse(AvfNetworkFailure.isBackendUnavailable(true, 35, 1200L, true),
                "console output means the guest ran, not the missing-net-feature signature");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) fail(description);
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_NETWORK_PLAN_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
