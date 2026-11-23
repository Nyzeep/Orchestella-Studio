package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import org.junit.Test;

public final class UmlNetworkProfileTest {
    @Test
    public void guestNetworkProfileIsValidAndIsolatedFromCompileBridgePorts() {
        assertEquals("10.0.2.15", UmlNetworkProfile.GUEST_ADDRESS);
        assertEquals(24, UmlNetworkProfile.PREFIX_LENGTH);
        assertEquals("10.0.2.2", UmlNetworkProfile.GUEST_GATEWAY);
        assertEquals("10.0.2.3", UmlNetworkProfile.GUEST_DNS);
        assertTrue(UmlNetworkProfile.GUEST_RELAY_PORT >= 1 && UmlNetworkProfile.GUEST_RELAY_PORT <= 65535);
        assertFalse(PortForwardingRule.isReservedHostPort(UmlNetworkProfile.GUEST_RELAY_PORT));
    }
}
