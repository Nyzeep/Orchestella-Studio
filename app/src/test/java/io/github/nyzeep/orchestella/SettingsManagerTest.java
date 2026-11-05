package io.github.nyzeep.orchestella;

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
    public void acceleratorLaneDefaultsToBuildFlagAndPersists() {
        // Default must follow the build-time opt-in, whatever the build chose.
        assertEquals("accelerator lane default must follow the build flag",
                BuildConfig.ACCELERATOR_LANE_ENABLED, settings.isAcceleratorLaneEnabled());

        // An explicit user toggle wins and persists across reads.
        settings.setAcceleratorLaneEnabled(false);
        assertFalse(settings.isAcceleratorLaneEnabled());

        settings.setAcceleratorLaneEnabled(true);
        assertTrue(settings.isAcceleratorLaneEnabled());
    }

    @Test
    public void acceleratorLanePersistsAcrossManagers() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        SettingsManager first = new SettingsManager(prefs);
        first.setAcceleratorLaneEnabled(false);

        SettingsManager second = new SettingsManager(prefs);
        assertFalse("stored user value must win over the build default on a fresh manager",
                second.isAcceleratorLaneEnabled());
    }

    @Test
    public void switchingBatteryPolicyPersists() {
        settings.setBatteryPolicy(BatteryPolicy.PERFORMANCE);
        assertEquals(BatteryPolicy.PERFORMANCE, settings.getBatteryPolicy());

        settings.setBatteryPolicy(BatteryPolicy.BALANCED);
        assertEquals(BatteryPolicy.BALANCED, settings.getBatteryPolicy());
    }

    @Test
    public void stageTraceDiagnosticsDefaultsToOff() {
        // Ticket 27: zero-collection precondition — a clean install must read
        // the diagnostics switch as strictly off, before any UI or pipeline runs.
        assertFalse("stage trace diagnostics must be off by default", settings.isStageTraceDiagnosticsEnabled());
    }

    @Test
    public void togglingStageTraceDiagnosticsPersistsUnderSharedKey() {
        // The switch UI (SettingsActivity) and the launch pipeline's raw reader
        // (StageTraceProvider) must meet on exactly this stored key.
        settings.setStageTraceDiagnosticsEnabled(true);
        assertTrue(settings.isStageTraceDiagnosticsEnabled());

        FakeSharedPreferences backing = new FakeSharedPreferences();
        SettingsManager uiMirror = new SettingsManager(backing);
        uiMirror.setStageTraceDiagnosticsEnabled(true);
        assertTrue("UI write must land on the shared stage trace key",
                backing.getBoolean(SettingsManager.KEY_STAGE_TRACE_DIAGNOSTICS, false));
        assertTrue(new SettingsManager(backing).isStageTraceDiagnosticsEnabled());

        settings.setStageTraceDiagnosticsEnabled(false);
        assertFalse("turning the switch off must persist off", settings.isStageTraceDiagnosticsEnabled());
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

    @Test
    public void legacyPreferencesAreMigratedWhenCurrentIsEmpty() {
        FakeSharedPreferences legacyPrefs = new FakeSharedPreferences();
        legacyPrefs.edit()
                .putBoolean(SettingsManager.KEY_ONBOARDING_COMPLETED, true)
                .putString(SettingsManager.KEY_BATTERY_POLICY, BatteryPolicy.PERFORMANCE.name())
                .putString(SettingsManager.KEY_LAST_ACTIVE_INSTANCE_ID, "inst-migrated-999")
                .apply();

        FakeSharedPreferences currentPrefs = new FakeSharedPreferences();
        SettingsManager.migrateFromLegacy(legacyPrefs, currentPrefs);

        SettingsManager migratedManager = new SettingsManager(currentPrefs);
        assertTrue("onboarding status must be migrated", migratedManager.isOnboardingCompleted());
        assertEquals("battery policy must be migrated", BatteryPolicy.PERFORMANCE, migratedManager.getBatteryPolicy());
        assertEquals("last active instance ID must be migrated", "inst-migrated-999", migratedManager.getLastActiveInstanceId());
    }
}
