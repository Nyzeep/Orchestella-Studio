package io.github.nyzeep.orchestella.ui.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-06 的两个新玻璃键（`ui_glass_mode`、`ui_glass_device_cap`）与旧档位键的
 * 「显式意图探测」契约。遵循 ThemeStore 家规：全新文件等于出厂默认、写入经新
 * 实例存活（离开页面再回来）、损坏或未知值回落默认。
 *
 * 旧键 `ui_glass_level` 的默认/回退语义由 [ThemeStoreUiOptionsTest] 原样钉定，
 * 本套件只补「null = 从未选择、显式 none = 明确关闭」的区分——UI-06 的 auto
 * 模式靠它保留用户关闭能力。
 */
class ThemeStoreGlassModeTest {

    @Test
    fun freshPreferenceFileStartsAtAutoModeAndUnlimitedCap() {
        val store = ThemeStore(FakeSharedPreferences())
        assertEquals("auto", store.uiGlassMode())
        assertEquals("unlimited", store.uiGlassDeviceCap())
        // 旧键从未写过 → null（区别于显式 "none"）。
        assertNull(store.uiGlassLevelKeyOrNull())
        // 旧读取函数保持自家默认（原契约）。
        assertEquals("none", store.uiGlassLevel())
    }

    @Test
    fun glassModeRoundTripsThroughANewStoreInstance() {
        val prefs = FakeSharedPreferences()
        for (mode in listOf("off", "auto", "always")) {
            ThemeStore(prefs).setUiGlassMode(mode)
            assertEquals(mode, ThemeStore(prefs).uiGlassMode())
        }
    }

    @Test
    fun corruptGlassModeFallsBackToAuto() {
        val prefs = FakeSharedPreferences()
        ThemeStore(prefs).setUiGlassMode("bogus")
        assertEquals("auto", ThemeStore(prefs).uiGlassMode())
        assertEquals("auto", prefs.getString(ThemeStore.KEY_UI_GLASS_MODE, null))
        // 损坏文件（外部写入）读回也回落 auto。
        prefs.edit().putString(ThemeStore.KEY_UI_GLASS_MODE, "garbage").apply()
        assertEquals("auto", ThemeStore(prefs).uiGlassMode())
    }

    @Test
    fun glassDeviceCapRoundTripsAndCorruptValuesFallBack() {
        val prefs = FakeSharedPreferences()
        ThemeStore(prefs).setUiGlassDeviceCap("l0")
        assertEquals("l0", ThemeStore(prefs).uiGlassDeviceCap())
        ThemeStore(prefs).setUiGlassDeviceCap("bogus")
        assertEquals("unlimited", ThemeStore(prefs).uiGlassDeviceCap())
        prefs.edit().putString(ThemeStore.KEY_UI_GLASS_DEVICE_CAP, "garbage").apply()
        assertEquals("unlimited", ThemeStore(prefs).uiGlassDeviceCap())
    }

    @Test
    fun legacyLevelKeyDistinguishesNeverSetFromExplicitNone() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        // 从未写过 → null：auto 模式据此给出自动玻璃。
        assertNull(store.uiGlassLevelKeyOrNull())
        // 显式写过 "none" → "none"：auto 模式据此保留关闭能力。
        store.setUiGlassLevel("none")
        assertEquals("none", store.uiGlassLevelKeyOrNull())
        // 显式风格选择原样透传。
        store.setUiGlassLevel("ultra")
        assertEquals("ultra", store.uiGlassLevelKeyOrNull())
    }

    @Test
    fun corruptLegacyLevelKeyReadsAsNeverSet() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(ThemeStore.KEY_UI_GLASS_LEVEL, "bogus").apply()
        // 损坏值不可被 auto 模式当成「显式关闭」，否则关闭玻璃的路径反而被垃圾值触发。
        assertNull(ThemeStore(prefs).uiGlassLevelKeyOrNull())
    }

    @Test
    fun deviceCapIsDeviceLocalAndNeverTouchesUserOptions() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setUiGlassDeviceCap("l0")
        // 降档缓存是设备本地状态：不影响用户可选的档位/模式键。
        assertEquals("none", store.uiGlassLevel())
        assertEquals("auto", store.uiGlassMode())
        assertTrue(prefs.contains(ThemeStore.KEY_UI_GLASS_DEVICE_CAP))
        assertFalse(prefs.contains(ThemeStore.KEY_UI_GLASS_LEVEL))
    }
}
