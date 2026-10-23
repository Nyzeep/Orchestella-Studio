package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.NetworkContract;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Error;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** AX-04: guest-to-host accelerator uplink planning. */
public final class AcceleratorUplinkTest {
    private static final String TOKEN = "01234567".repeat(4);
    private static final int PORT = 3632;
    private static final int JOBS = 4;

    private static NetworkContract.OutboundNetworkSpec gvproxySpec() {
        return NetworkContract.OutboundNetworkSpec.builder()
                .guestNetworkCidr("192.168.127.0/24")
                .guestDhcpStart("192.168.127.15")
                .build();
    }

    private static NetworkContract.OutboundNetworkSpec slirpSpec() {
        return NetworkContract.OutboundNetworkSpec.defaultSpec();
    }

    @Test
    public void gvproxyGoldenPlanUsesTheHostAliasNotTheGateway() throws Exception {
        AcceleratorUplink.Plan plan = AcceleratorUplink.plan(
                AcceleratorUplink.Backend.GVPROXY, gvproxySpec(), PORT, JOBS, TOKEN);

        assertEquals("192.168.127.254", plan.guestHost());
        assertEquals("127.0.0.1", plan.hostBindAddress());
        assertEquals("192.168.127.254:3632/4", plan.distccHosts());
        assertEquals(PORT, plan.guestPort());
        assertEquals(TOKEN, plan.token());
        assertFalse(plan.ipv6Enabled());
    }

    @Test
    public void slirpGoldenPlanUses10_0_2_2() throws Exception {
        AcceleratorUplink.Plan plan = AcceleratorUplink.plan(
                AcceleratorUplink.Backend.SLIRP, slirpSpec(), PORT, JOBS, TOKEN);

        assertEquals("10.0.2.2", plan.guestHost());
        assertEquals("127.0.0.1", plan.hostBindAddress());
        assertEquals("10.0.2.2:3632/4", plan.distccHosts());
    }

    @Test
    public void customSlirpSubnetDerivesTheSecondHostAddress() throws Exception {
        NetworkContract.OutboundNetworkSpec spec = NetworkContract.OutboundNetworkSpec.builder()
                .guestNetworkCidr("192.168.76.0/24")
                .guestDhcpStart("192.168.76.9")
                .build();

        AcceleratorUplink.Plan plan = AcceleratorUplink.plan(
                AcceleratorUplink.Backend.SLIRP, spec, PORT, JOBS, TOKEN);

        assertEquals("192.168.76.2", plan.guestHost());
    }

    @Test
    public void gvproxyRejectsASlirpSubnet() {
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                () -> AcceleratorUplink.plan(
                        AcceleratorUplink.Backend.GVPROXY, slirpSpec(), PORT, JOBS, TOKEN));
    }

    @Test
    public void missingSpecIsUnavailable() {
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                () -> AcceleratorUplink.plan(
                        AcceleratorUplink.Backend.GVPROXY, null, PORT, JOBS, TOKEN));
    }

    @Test
    public void occupiedPortIsUnavailable() {
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                () -> AcceleratorUplink.plan(AcceleratorUplink.Backend.GVPROXY,
                        gvproxySpec(), PORT, JOBS, TOKEN, Set.of(PORT)));
    }

    @Test
    public void invalidPortIsUnavailable() {
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                () -> AcceleratorUplink.plan(AcceleratorUplink.Backend.GVPROXY,
                        gvproxySpec(), 0, JOBS, TOKEN));
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                () -> AcceleratorUplink.plan(AcceleratorUplink.Backend.GVPROXY,
                        gvproxySpec(), 70000, JOBS, TOKEN));
    }

    @Test
    public void invalidJobLimitIsUnavailable() {
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                () -> AcceleratorUplink.plan(AcceleratorUplink.Backend.GVPROXY,
                        gvproxySpec(), PORT, 0, TOKEN));
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                () -> AcceleratorUplink.plan(AcceleratorUplink.Backend.GVPROXY,
                        gvproxySpec(), PORT, 65, TOKEN));
    }

    @Test
    public void tokenMustBe32LowercaseHex() {
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_AUTH_FAILED,
                () -> AcceleratorUplink.plan(AcceleratorUplink.Backend.GVPROXY,
                        gvproxySpec(), PORT, JOBS, "not-a-token"));
    }

    @Test
    public void generatedTokensAre32HexAndUnique() {
        String first = AcceleratorUplink.newToken();
        String second = AcceleratorUplink.newToken();

        assertTrue(first.matches("[0-9a-f]{32}"));
        assertTrue(second.matches("[0-9a-f]{32}"));
        assertNotEquals(first, second);
    }

    @Test
    public void guestEnvironmentCarriesHostsAndToken() throws Exception {
        AcceleratorUplink.Plan plan = AcceleratorUplink.plan(
                AcceleratorUplink.Backend.GVPROXY, gvproxySpec(), PORT, JOBS, TOKEN);
        Map<String, String> environment = plan.guestEnvironment();

        assertEquals("192.168.127.254:3632/4", environment.get(AcceleratorUplink.DISTCC_HOSTS_ENV));
        assertEquals(TOKEN, environment.get(AcceleratorUplink.TOKEN_ENV));
        try {
            environment.put("X", "y");
            throw new AssertionError("environment must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void planNeverPrintsTheToken() throws Exception {
        AcceleratorUplink.Plan plan = AcceleratorUplink.plan(
                AcceleratorUplink.Backend.GVPROXY, gvproxySpec(), PORT, JOBS, TOKEN);

        assertFalse(plan.toString().contains(TOKEN));
        assertTrue(plan.toString().contains("<redacted>"));
    }

    @Test
    public void onlyLoopbackBindIsAccepted() throws Exception {
        AcceleratorUplink.requirePrivateBind("127.0.0.1");
        assertBindFailure("0.0.0.0");
        assertBindFailure("::");
        assertBindFailure("192.168.1.5");
        assertBindFailure("");
    }

    @Test
    public void axp1ErrorsMapToUplinkFailureCodes() {
        assertEquals(AcceleratorUplink.FailureCode.UPLINK_AUTH_FAILED,
                AcceleratorUplink.classify(Axp1Error.AUTH_FAILED));
        assertEquals(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                AcceleratorUplink.classify(Axp1Error.TIMEOUT));
        assertEquals(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                AcceleratorUplink.classify(Axp1Error.PROTOCOL_ERROR));
        assertEquals(AcceleratorUplink.FailureCode.UPLINK_UNAVAILABLE,
                AcceleratorUplink.classify(null));
    }

    @Test
    public void ipv6IsAlwaysDisabled() throws Exception {
        AcceleratorUplink.Plan plan = AcceleratorUplink.plan(
                AcceleratorUplink.Backend.SLIRP, slirpSpec(), PORT, JOBS, TOKEN);

        assertFalse(plan.ipv6Enabled());
        assertFalse(plan.distccHosts().contains("["));
        assertFalse(plan.distccHosts().contains("::"));
    }

    private static void assertBindFailure(String address) {
        assertFailure(AcceleratorUplink.FailureCode.UPLINK_BIND_FAILED,
                () -> AcceleratorUplink.requirePrivateBind(address));
    }

    private static void assertFailure(
            AcceleratorUplink.FailureCode expected, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("expected " + expected);
        } catch (AcceleratorUplink.UplinkException failure) {
            assertEquals(expected, failure.code());
        } catch (Exception other) {
            throw new AssertionError("unexpected exception", other);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
