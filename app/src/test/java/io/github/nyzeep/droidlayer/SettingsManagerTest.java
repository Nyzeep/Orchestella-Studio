package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public final class SettingsManagerTest {
    private SettingsManager settings;

    @Before
    public void setUp() {
        settings = new SettingsManager(new FakeSharedPreferences());
    }

    @Test
    public void defaultSettingsConformToAdrs() {
        // ADR-0011 §4: boot auto-start strictly disabled by default
        assertFalse("boot auto-start must be disabled by default", settings.isBootAutoStartEnabled());

        // ADR-0015 §1: update check enabled by default
        assertTrue("update check must be enabled by default", settings.isUpdateCheckEnabled());

        // ADR-0011 §5: battery policy defaults to BALANCED
        assertEquals("battery policy must default to BALANCED", BatteryPolicy.BALANCED, settings.getBatteryPolicy());

        // No last active instance initially
        assertNull("last active instance must initially be null", settings.getLastActiveInstanceId());
    }

    @Test
    public void togglingBootAutoStartPersists() {
        settings.setBootAutoStartEnabled(true);
        assertTrue(settings.isBootAutoStartEnabled());

        settings.setBootAutoStartEnabled(false);
        assertFalse(settings.isBootAutoStartEnabled());
    }

    @Test
    public void togglingUpdateCheckPersists() {
        settings.setUpdateCheckEnabled(false);
        assertFalse(settings.isUpdateCheckEnabled());

        settings.setUpdateCheckEnabled(true);
        assertTrue(settings.isUpdateCheckEnabled());
    }

    @Test
    public void switchingBatteryPolicyPersists() {
        settings.setBatteryPolicy(BatteryPolicy.PERFORMANCE);
        assertEquals(BatteryPolicy.PERFORMANCE, settings.getBatteryPolicy());

        settings.setBatteryPolicy(BatteryPolicy.BALANCED);
        assertEquals(BatteryPolicy.BALANCED, settings.getBatteryPolicy());
    }

    @Test
    public void trackingLastActiveInstanceIdPersists() {
        settings.setLastActiveInstanceId("inst-alpine-123");
        assertEquals("inst-alpine-123", settings.getLastActiveInstanceId());

        settings.setLastActiveInstanceId("inst-debian-456");
        assertEquals("inst-debian-456", settings.getLastActiveInstanceId());
    }

    @Test
    public void onboardingCompletedDefaultsToFalseAndPersists() {
        assertFalse("onboarding must default to false on clean install", settings.isOnboardingCompleted());

        settings.setOnboardingCompleted(true);
        assertTrue("onboarding must be true after completion", settings.isOnboardingCompleted());

        settings.setOnboardingCompleted(false);
        assertFalse(settings.isOnboardingCompleted());
    }
}
