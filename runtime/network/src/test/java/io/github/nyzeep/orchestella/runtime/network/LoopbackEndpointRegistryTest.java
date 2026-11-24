package io.github.nyzeep.orchestella.runtime.network;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import java.io.IOException;
import java.util.Objects;

/**
 * Contract test for {@link LoopbackEndpointRegistry}.
 * Executed via JavaExec under {@code :runtime:network:check}.
 */
public final class LoopbackEndpointRegistryTest {

    public static void main(String[] args) throws Exception {
        LoopbackEndpointRegistryTest suite = new LoopbackEndpointRegistryTest();
        suite.acquiresDistinctEndpointsAndBlocksCrossInstanceCollision();
        suite.rejectsReservedCompilerPortsAndProtectsNewOwnerFromLateRelease();
        System.out.println("LOOPBACK_ENDPOINT_REGISTRY_TEST_OK");
    }

    private void acquiresDistinctEndpointsAndBlocksCrossInstanceCollision() throws Exception {
        LoopbackEndpointRegistry registry = new LoopbackEndpointRegistry();
        LoopbackEndpointRegistry.EndpointLease leaseA =
                registry.acquire(PortForwardingRule.LOOPBACK_ADDRESS, 18080, "inst-a", "gen-a", "rule-a");
        LoopbackEndpointRegistry.EndpointLease leaseB =
                registry.acquire(PortForwardingRule.LOOPBACK_ADDRESS, 18081, "inst-b", "gen-b", "rule-b");
        assertEquals(2, registry.activeEndpointCount());
        assertEquals("inst-a", registry.ownerOf(PortForwardingRule.LOOPBACK_ADDRESS, 18080).instanceId());
        assertEquals("inst-b", registry.ownerOf(PortForwardingRule.LOOPBACK_ADDRESS, 18081).instanceId());

        // Same owner + generation + rule id is idempotent.
        LoopbackEndpointRegistry.EndpointLease same =
                registry.acquire(PortForwardingRule.LOOPBACK_ADDRESS, 18080, "inst-a", "gen-a", "rule-a");
        assertTrue(same == leaseA);

        // Different instance or generation trying to claim port 18080 fails closed.
        boolean collided = false;
        try {
            registry.acquire(PortForwardingRule.LOOPBACK_ADDRESS, 18080, "inst-b", "gen-b", "rule-x");
        } catch (IOException expected) {
            collided = true;
        }
        assertTrue(collided);
        assertEquals("inst-a", registry.ownerOf(PortForwardingRule.LOOPBACK_ADDRESS, 18080).instanceId());

        assertTrue(registry.release(leaseA));
        assertFalse(registry.release(leaseA));
        assertTrue(registry.release(leaseB));
        assertEquals(0, registry.activeEndpointCount());
    }

    private void rejectsReservedCompilerPortsAndProtectsNewOwnerFromLateRelease() throws Exception {
        LoopbackEndpointRegistry registry = new LoopbackEndpointRegistry();
        for (int reserved : new int[] {
            PortForwardingRule.COMPILE_WORKER_HOST_PORT,
            PortForwardingRule.COMPILE_SUBMITTER_HOST_PORT
        }) {
            boolean rejected = false;
            try {
                registry.acquire(PortForwardingRule.LOOPBACK_ADDRESS, reserved, "inst-a", "gen-a", "r");
            } catch (IOException expected) {
                rejected = true;
            }
            assertTrue(rejected);
        }

        LoopbackEndpointRegistry.EndpointLease gen1 =
                registry.acquire(PortForwardingRule.LOOPBACK_ADDRESS, 18090, "inst-a", "gen-1", "r1");
        assertTrue(registry.release(gen1));
        LoopbackEndpointRegistry.EndpointLease gen2 =
                registry.acquire(PortForwardingRule.LOOPBACK_ADDRESS, 18090, "inst-a", "gen-2", "r1");

        // Late release from gen-1 must not touch gen-2.
        assertFalse(registry.release(gen1));
        assertEquals("gen-2", registry.ownerOf(PortForwardingRule.LOOPBACK_ADDRESS, 18090).generation());
        assertTrue(registry.release(gen2));
        assertEquals(0, registry.activeEndpointCount());
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected false");
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
