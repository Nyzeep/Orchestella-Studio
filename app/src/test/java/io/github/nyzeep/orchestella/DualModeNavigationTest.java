package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * TDD Red Phase: Unit tests for DualModeNavigation.
 * Verifies bidirectional transitions between Terminal and Desktop activities,
 * intent parameter propagation, lifecycle intent flags, and credential defense.
 */
public final class DualModeNavigationTest {

    @Test
    public void testTerminalToDesktopNavigationRequest() {
        DualModeNavigation.NavigationRequest req = DualModeNavigation.buildDesktopNavigation(
                "instance-test-1", "token-xyz-123"
        );

        assertNotNull(req);
        assertEquals(DualModeNavigation.TargetMode.DESKTOP, req.targetMode());
        assertEquals("instance-test-1", req.instanceId());
        assertEquals("token-xyz-123", req.bridgeToken());
        assertEquals(DesktopActivity.class, req.targetActivityClass());
        assertTrue(req.preserveForegroundService());
        assertTrue(req.reorderToFront());
    }

    @Test
    public void testDesktopToTerminalNavigationRequest() {
        DualModeNavigation.NavigationRequest req = DualModeNavigation.buildTerminalNavigation(
                "instance-test-2", "token-abc-456"
        );

        assertNotNull(req);
        assertEquals(DualModeNavigation.TargetMode.TERMINAL, req.targetMode());
        assertEquals("instance-test-2", req.instanceId());
        assertEquals("token-abc-456", req.bridgeToken());
        assertEquals(TerminalActivity.class, req.targetActivityClass());
        assertTrue(req.preserveForegroundService());
        assertTrue(req.reorderToFront());
    }

    @Test
    public void testNavigationRequestRejectsNullOrBlankCredentials() {
        try {
            DualModeNavigation.buildDesktopNavigation(null, "valid-token");
            fail("Expected IllegalArgumentException for null instanceId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("instanceId"));
        }

        try {
            DualModeNavigation.buildDesktopNavigation("  ", "valid-token");
            fail("Expected IllegalArgumentException for blank instanceId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("instanceId"));
        }

        try {
            DualModeNavigation.buildTerminalNavigation("instance-1", null);
            fail("Expected IllegalArgumentException for null bridgeToken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bridgeToken"));
        }

        try {
            DualModeNavigation.buildTerminalNavigation("instance-1", "");
            fail("Expected IllegalArgumentException for empty bridgeToken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bridgeToken"));
        }
    }

    @Test
    public void testNavigationSinkPopulation() {
        DualModeNavigation.NavigationRequest req = DualModeNavigation.buildDesktopNavigation(
                "alpine-inst", "token-secret"
        );

        Map<String, Object> map = new HashMap<>();
        req.populate(new DesktopLaunchIntent.IntentSink() {
            @Override
            public void putExtra(String key, String value) {
                map.put(key, value);
            }

            @Override
            public void putExtra(String key, boolean value) {
                map.put(key, value);
            }
        });

        assertEquals("alpine-inst", map.get(DesktopLaunchIntent.EXTRA_INSTANCE_ID));
        assertEquals("token-secret", map.get(DesktopLaunchIntent.EXTRA_BRIDGE_TOKEN));
        assertEquals(Boolean.TRUE, map.get(DesktopLaunchIntent.EXTRA_FROM_TERMINAL));
        assertEquals(DualModeNavigation.MODE_DESKTOP, map.get(DualModeNavigation.EXTRA_TARGET_MODE));
    }
}
