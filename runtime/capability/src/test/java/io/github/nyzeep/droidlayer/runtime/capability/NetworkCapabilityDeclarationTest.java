package io.github.nyzeep.droidlayer.runtime.capability;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.capability.NetworkCapabilityDeclaration.NetworkCapability;
import io.github.nyzeep.droidlayer.runtime.capability.NetworkCapabilityDeclaration.Entry;
import io.github.nyzeep.droidlayer.runtime.capability.NetworkCapabilityDeclaration.VerificationLevel;
import java.util.List;

/**
 * Ticket 36 AC-4: the capability matrix is DATA, not code (ADR-0002). The
 * network and port-forwarding capability declarations for both execution
 * strategies must be observable as data with honest verification levels and
 * evidence provenance: the QEMU profile's outbound network is device-verified,
 * the AVF profile's network/forwarding are contract-verified (the same suites
 * as Tickets 27/29, re-run on the AVF implementation), and nothing claims a
 * device-level AVF smoke that has not been observed.
 */
public final class NetworkCapabilityDeclarationTest {
    public static void main(String[] arguments) {
        declaresSoftwareNetworkCapabilitiesWithDeviceEvidence();
        declaresAvfNetworkCapabilitiesWithContractEvidence();
        refusesToClaimUnobservedDeviceVerificationForAvf();
        bothStrategiesExposeTheFullNetworkCapabilitySet();
        System.out.println(
                "NETWORK_CAPABILITY_DECLARATION_TEST_OK software=2 avf=2"
                        + " honesty=1 parity_set=1");
    }

    private static void declaresSoftwareNetworkCapabilitiesWithDeviceEvidence() {
        List<Entry> entries =
                NetworkCapabilityDeclaration.forStrategy(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION);
        assertEquals(2, entries.size(), "software declares two network capabilities");

        Entry outbound = entry(entries, NetworkCapability.OUTBOUND_NETWORK);
        assertEquals(
                VerificationLevel.DEVICE_VERIFIED,
                outbound.level(),
                "software outbound network is device-verified (Ticket 26/27 real device apk update)");
        assertEquals("26/27", outbound.evidenceTicket(), "software outbound evidence ticket");

        Entry forwarding = entry(entries, NetworkCapability.PORT_FORWARDING);
        assertEquals(
                VerificationLevel.CONTRACT_VERIFIED,
                forwarding.level(),
                "software port forwarding is contract-verified (Ticket 29 suite)");
        assertEquals("29", forwarding.evidenceTicket(), "software forwarding evidence ticket");
    }

    private static void declaresAvfNetworkCapabilitiesWithContractEvidence() {
        List<Entry> entries =
                NetworkCapabilityDeclaration.forStrategy(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION);
        assertEquals(2, entries.size(), "AVF declares two network capabilities");

        Entry outbound = entry(entries, NetworkCapability.OUTBOUND_NETWORK);
        assertEquals(
                VerificationLevel.CONTRACT_VERIFIED,
                outbound.level(),
                "AVF outbound network is contract-verified (Ticket 36, Ticket 27 suite)");
        assertEquals("36", outbound.evidenceTicket(), "AVF outbound evidence ticket");

        Entry forwarding = entry(entries, NetworkCapability.PORT_FORWARDING);
        assertEquals(
                VerificationLevel.CONTRACT_VERIFIED,
                forwarding.level(),
                "AVF port forwarding is contract-verified (Ticket 36, Ticket 29 suite)");
        assertEquals("36", forwarding.evidenceTicket(), "AVF forwarding evidence ticket");
    }

    private static void refusesToClaimUnobservedDeviceVerificationForAvf() {
        // The real-device AVF smoke is a HITL prototype item (Ticket 17); the
        // declaration must not pre-claim what has not been observed.
        assertTrue(
                !NetworkCapabilityDeclaration.isVerified(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                        NetworkCapability.OUTBOUND_NETWORK,
                        VerificationLevel.DEVICE_VERIFIED),
                "no device-level AVF network claim before the HITL smoke");
        assertTrue(
                NetworkCapabilityDeclaration.isVerified(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                        NetworkCapability.OUTBOUND_NETWORK,
                        VerificationLevel.CONTRACT_VERIFIED),
                "contract-level AVF network claim stands");
        assertTrue(
                NetworkCapabilityDeclaration.isVerified(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                        NetworkCapability.PORT_FORWARDING,
                        VerificationLevel.CONTRACT_VERIFIED),
                "contract-level AVF forwarding claim stands");
    }

    private static void bothStrategiesExposeTheFullNetworkCapabilitySet() {
        // Parity set: L2 users get the same network capability surface as L1.
        List<NetworkCapability> software =
                NetworkCapabilityDeclaration.capabilities(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION);
        List<NetworkCapability> hardware =
                NetworkCapabilityDeclaration.capabilities(
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION);
        assertEquals(software, hardware, "both strategies declare the same network capability set");
    }

    private static Entry entry(List<Entry> entries, NetworkCapability capability) {
        return entries.stream()
                .filter(entry -> entry.capability() == capability)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing entry: " + capability));
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
        System.out.println("NETWORK_CAPABILITY_DECLARATION_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
