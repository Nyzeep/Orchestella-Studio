package io.github.nyzeep.orchestella.ui.screens.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory stand-in for SharedPreferences; no Android runtime needed. */
private class FakeConfigStore : ConfigStore {
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()

    override fun getString(key: String): String? = strings[key]

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        booleans[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        strings[key] = value
    }

    override fun putBoolean(key: String, value: Boolean) {
        booleans[key] = value
    }
}

/**
 * Persistence contract for the four playability options. Each option is written
 * and read back; unknown values fall back to the documented default instead of
 * crashing the settings page.
 */
class ConfigPreferencesTest {

    @Test
    fun defaultsMatchTheDocumentedValues() {
        val options = ConfigPreferences(FakeConfigStore()).readOptions()
        assertEquals(SectionMotion.Standard, options.sectionMotion)
        assertEquals(DefaultMirror.MultiTier, options.defaultMirror)
        assertFalse("boot restore is opt-in (ADR-0011)", options.bootRestore)
        assertTrue("update check is on by default", options.updateCheck)
    }

    @Test
    fun everyOptionRoundTripsThroughTheStore() {
        val store = FakeConfigStore()
        val preferences = ConfigPreferences(store)

        preferences.setSectionMotion(SectionMotion.Off)
        preferences.setDefaultMirror(DefaultMirror.Ustc)
        preferences.setBootRestore(true)
        preferences.setUpdateCheck(false)

        val readBack = ConfigPreferences(store).readOptions()
        assertEquals(SectionMotion.Off, readBack.sectionMotion)
        assertEquals(DefaultMirror.Ustc, readBack.defaultMirror)
        assertTrue(readBack.bootRestore)
        assertFalse(readBack.updateCheck)
    }

    @Test
    fun unknownValuesFallBackToDefaults() {
        val store = FakeConfigStore().apply {
            strings[ConfigPreferences.KEY_SECTION_MOTION] = "warp-speed"
            strings[ConfigPreferences.KEY_DEFAULT_MIRROR] = "some-removed-mirror"
        }
        val options = ConfigPreferences(store).readOptions()
        assertEquals(SectionMotion.Standard, options.sectionMotion)
        assertEquals(DefaultMirror.MultiTier, options.defaultMirror)
    }

    @Test
    fun writeOptionsOnlyTouchesChangedKeys() {
        val store = FakeConfigStore()
        val preferences = ConfigPreferences(store)
        val initial = preferences.readOptions()

        preferences.writeOptions(initial, initial.copy(sectionMotion = SectionMotion.Reduced))

        assertEquals(SectionMotion.Reduced.key, store.strings[ConfigPreferences.KEY_SECTION_MOTION])
        assertEquals(null, store.strings[ConfigPreferences.KEY_DEFAULT_MIRROR])
        assertTrue("unchanged switches must not be rewritten", store.booleans.isEmpty())
    }

    @Test
    fun persistenceKeyNamesAreStable() {
        assertEquals("ui3_config_section_motion", ConfigPreferences.KEY_SECTION_MOTION)
        assertEquals("ui3_config_default_mirror", ConfigPreferences.KEY_DEFAULT_MIRROR)
        assertEquals("boot_auto_start", ConfigPreferences.KEY_BOOT_RESTORE)
        assertEquals("update_check_enabled", ConfigPreferences.KEY_UPDATE_CHECK)
    }

    @Test
    fun existingProductKeysAreReusedInsteadOfDuplicated() {
        val store = FakeConfigStore()
        val preferences = ConfigPreferences(store)

        preferences.setBootRestore(true)
        preferences.setUpdateCheck(false)

        // Same keys SettingsManager/BootCompletedReceiver and GitHubUpdateChecker read.
        assertEquals(true, store.booleans["boot_auto_start"])
        assertEquals(false, store.booleans["update_check_enabled"])
        assertEquals(2, store.booleans.size)
    }

    @Test
    fun mirrorKeysMatchTheProvisionerEnumNames() {
        // The mirror page maps these strings onto DistroMirrorProvisioner.MirrorSource.
        assertEquals(
            listOf("MULTI_TIER", "TUNA", "USTC", "ALIYUN"),
            DefaultMirror.entries.map { it.key },
        )
    }
}
