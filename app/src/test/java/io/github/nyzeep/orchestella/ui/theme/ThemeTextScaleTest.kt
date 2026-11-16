package io.github.nyzeep.orchestella.ui.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.ui.theme.OeTextScale
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract for ticket 05's in-app text scale preference.
 *
 * The preference is deliberately independent of `Configuration.fontScale`:
 * the platform still applies the system factor exactly once when `sp` becomes
 * pixels, and this store only carries the additional 85–130% override that the
 * Oe Compose layer consumes. A missing/obsolete mode must never rewrite the
 * saved theme.
 */
class ThemeTextScaleTest {

    @Test
    fun textScaleDefaultsToFollowingSystemWithoutAnOverride() {
        val store = ThemeStore(FakeSharedPreferences())
        assertEquals(OeTextScaleMode.FollowSystem, store.textScaleMode())
        assertEquals(100, store.textScalePercent())
        assertEquals(1f, store.textScaleFactor(), 0.0001f)
    }

    @Test
    fun customTextScalePersistsAcrossStoreInstances() {
        val prefs = FakeSharedPreferences()
        ThemeStore(prefs).apply {
            setTextScaleMode(OeTextScaleMode.Custom)
            setTextScalePercent(115)
        }
        val restored = ThemeStore(prefs)
        assertEquals(OeTextScaleMode.Custom, restored.textScaleMode())
        assertEquals(115, restored.textScalePercent())
        assertEquals(1.15f, restored.textScaleFactor(), 0.0001f)
    }

    @Test
    fun atomicTextScaleWritePersistsBothFieldsAndClampsThePercent() {
        val prefs = FakeSharedPreferences()
        ThemeStore(prefs).setTextScale(OeTextScaleMode.Custom, 200)
        val restored = ThemeStore(prefs)
        assertEquals(OeTextScaleMode.Custom, restored.textScaleMode())
        assertEquals(130, restored.textScalePercent())
        assertEquals(1.30f, restored.textScaleFactor(), 0.0001f)
    }

    @Test
    fun textScalePercentClampsToTheTicketBand() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setTextScaleMode(OeTextScaleMode.Custom)
        store.setTextScalePercent(50)
        assertEquals(85, store.textScalePercent())
        assertEquals(0.85f, store.textScaleFactor(), 0.0001f)
        store.setTextScalePercent(200)
        assertEquals(130, store.textScalePercent())
        assertEquals(1.30f, store.textScaleFactor(), 0.0001f)
    }

    @Test
    fun followingSystemIgnoresTheStoredCustomPercent() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setTextScaleMode(OeTextScaleMode.Custom)
        store.setTextScalePercent(130)
        store.setTextScaleMode(OeTextScaleMode.FollowSystem)
        assertEquals("the saved percent is not rewritten", 130, store.textScalePercent())
        assertEquals(1f, store.textScaleFactor(), 0.0001f)
    }

    @Test
    fun unknownModeFallsBackToFollowingSystem() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(ThemeStore.KEY_TEXT_SCALE_MODE, "obsolete").apply()
        val store = ThemeStore(prefs)
        assertEquals(OeTextScaleMode.FollowSystem, store.textScaleMode())
        assertEquals(1f, store.textScaleFactor(), 0.0001f)
    }

    @Test
    fun textScaleIsIndependentOfThemeCornerAndTerminalPreferences() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setThemeId(ThemeCatalog.github.id)
        store.setCornerRadiusDp(8)
        store.setTerminalFontSizeSp(18)
        store.setTextScaleMode(OeTextScaleMode.Custom)
        store.setTextScalePercent(125)

        val restored = ThemeStore(prefs)
        assertEquals(1.25f, restored.textScaleFactor(), 0.0001f)
        assertEquals(8, restored.cornerRadiusDp())
        assertEquals(18, restored.terminalFontSizeSp())
        assertEquals(ThemeCatalog.github.id, restored.themeId())
    }

    @Test
    fun percentOptionsCoverTheWholeBandInFivePercentSteps() {
        val options = OeTextScale.PERCENT_OPTIONS
        assertEquals(85, options.first())
        assertEquals(130, options.last())
        assertEquals(options.sorted(), options)
        assertEquals(options.size, options.distinct().size)
        options.zipWithNext().forEach { (previous, next) ->
            assertEquals("steps must stay at 5 percent", 5, next - previous)
        }
    }

    @Test
    fun pickerOptionsKeepHistoricCustomPercentWithoutDuplicatingStandardSteps() {
        assertEquals(
            OeTextScale.PERCENT_OPTIONS,
            OeTextScale.optionsFor(OeTextScaleMode.FollowSystem, 117),
        )
        val historic = OeTextScale.optionsFor(OeTextScaleMode.Custom, 117)
        assertTrue(117 in historic)
        assertEquals(historic.sorted(), historic)
        assertEquals(historic.size, historic.distinct().size)
        assertEquals(OeTextScale.PERCENT_OPTIONS.size + 1, historic.size)
        // A standard step is never duplicated by the historic-value path.
        assertEquals(
            OeTextScale.PERCENT_OPTIONS,
            OeTextScale.optionsFor(OeTextScaleMode.Custom, 115),
        )
    }

    @Test
    fun effectiveFactorRejectsNonFiniteValuesAndClampsToTheBand() {
        assertEquals(1f, OeTextScale.safeFactor(Float.NaN), 0.0001f)
        assertEquals(1f, OeTextScale.safeFactor(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1f, OeTextScale.safeFactor(0f), 0.0001f)
        assertEquals(0.85f, OeTextScale.safeFactor(0.5f), 0.0001f)
        assertEquals(1.30f, OeTextScale.safeFactor(1.8f), 0.0001f)
        assertEquals(1.15f, OeTextScale.safeFactor(1.15f), 0.0001f)
    }
}
