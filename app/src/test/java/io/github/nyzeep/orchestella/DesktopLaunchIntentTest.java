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
 * TDD Red Phase: Unit tests for DesktopLaunchIntent.
 * Verifies launch intent construction, instance ID binding, optional parameters,
 * intent parsing, and empty/null credential security defenses.
 */
public final class DesktopLaunchIntentTest {

    @Test
    public void testValidBuildAndGetters() {
        DesktopLaunchIntent launch = DesktopLaunchIntent.builder()
                .setInstanceId("alpine-01")
                .setBridgeToken("secret-token-123")
                .setFromTerminal(true)
                .setGraphicsStrategy("WAYLAND_SHM")
                .build();

        assertNotNull(launch);
        assertEquals("alpine-01", launch.instanceId());
        assertEquals("secret-token-123", launch.bridgeToken());
        assertTrue(launch.fromTerminal());
        assertEquals("WAYLAND_SHM", launch.graphicsStrategy());
    }

    @Test
    public void testDefaultFromTerminalIsFalse() {
        DesktopLaunchIntent launch = DesktopLaunchIntent.builder()
                .setInstanceId("debian-02")
                .setBridgeToken("token-abc")
                .build();

        assertFalse(launch.fromTerminal());
    }

    @Test
    public void testNullOrBlankInstanceIdThrowsSecurityException() {
        try {
            DesktopLaunchIntent.builder()
                    .setInstanceId(null)
                    .setBridgeToken("valid-token")
                    .build();
            fail("Expected IllegalArgumentException for null instanceId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("instanceId"));
        }

        try {
            DesktopLaunchIntent.builder()
                    .setInstanceId("   ")
                    .setBridgeToken("valid-token")
                    .build();
            fail("Expected IllegalArgumentException for blank instanceId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("instanceId"));
        }
    }

    @Test
    public void testNullOrBlankBridgeTokenThrowsSecurityException() {
        try {
            DesktopLaunchIntent.builder()
                    .setInstanceId("alpine-01")
                    .setBridgeToken(null)
                    .build();
            fail("Expected IllegalArgumentException for null bridgeToken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bridgeToken"));
        }

        try {
            DesktopLaunchIntent.builder()
                    .setInstanceId("alpine-01")
                    .setBridgeToken("")
                    .build();
            fail("Expected IllegalArgumentException for empty bridgeToken");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("bridgeToken"));
        }
    }

    @Test
    public void testIntentSinkPopulation() {
        DesktopLaunchIntent launch = DesktopLaunchIntent.builder()
                .setInstanceId("inst-99")
                .setBridgeToken("token-99")
                .setFromTerminal(true)
                .setGraphicsStrategy("WAYLAND_VSOCK")
                .build();

        Map<String, Object> map = new HashMap<>();
        DesktopLaunchIntent.IntentSink sink = new DesktopLaunchIntent.IntentSink() {
            @Override
            public void putExtra(String key, String value) {
                map.put(key, value);
            }

            @Override
            public void putExtra(String key, boolean value) {
                map.put(key, value);
            }
        };

        launch.populate(sink);

        assertEquals("inst-99", map.get(DesktopLaunchIntent.EXTRA_INSTANCE_ID));
        assertEquals("token-99", map.get(DesktopLaunchIntent.EXTRA_BRIDGE_TOKEN));
        assertEquals(Boolean.TRUE, map.get(DesktopLaunchIntent.EXTRA_FROM_TERMINAL));
        assertEquals("WAYLAND_VSOCK", map.get(DesktopLaunchIntent.EXTRA_GRAPHICS_STRATEGY));
    }

    @Test
    public void testParseFromMap() {
        Map<String, Object> map = new HashMap<>();
        map.put(DesktopLaunchIntent.EXTRA_INSTANCE_ID, "inst-88");
        map.put(DesktopLaunchIntent.EXTRA_BRIDGE_TOKEN, "tok-88");
        map.put(DesktopLaunchIntent.EXTRA_FROM_TERMINAL, true);

        DesktopLaunchIntent.IntentSource source = new DesktopLaunchIntent.IntentSource() {
            @Override
            public String getStringExtra(String key) {
                Object val = map.get(key);
                return val != null ? val.toString() : null;
            }

            @Override
            public boolean getBooleanExtra(String key, boolean defaultValue) {
                Object val = map.get(key);
                return val instanceof Boolean ? (Boolean) val : defaultValue;
            }
        };

        DesktopLaunchIntent parsed = DesktopLaunchIntent.fromSource(source);
        assertNotNull(parsed);
        assertEquals("inst-88", parsed.instanceId());
        assertEquals("tok-88", parsed.bridgeToken());
        assertTrue(parsed.fromTerminal());
    }

    @Test
    public void testTargetActivityClassIsDesktopActivity() {
        assertEquals(DesktopActivity.class, DesktopLaunchIntent.targetActivityClass());
    }
}
