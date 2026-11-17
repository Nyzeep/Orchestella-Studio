package io.github.nyzeep.orchestella.ui.oe3.motion

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.oe3TestTheme
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3Motion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI-07: the CompositionLocal half of "one motion source".
 *
 * Pages are supposed to read `Oe3ThemeValues.motion` (backed by
 * `LocalOe3Motion`) instead of writing durations. That only holds if the theme
 * really injects the token set and really flips it to `reduced()` when the
 * shell reports reduce-motion — which is what these assertions pin.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class Oe3ThemeMotionLocalTest {

    @get:Rule
    val compose = createComposeRule()

    private val theme = oe3TestTheme(
        dark = true,
        background = 0xFF0A0B0E.toInt(),
        surface = 0xFF14151A.toInt(),
        primary = 0xFF7C9CFF.toInt(),
        text = 0xFFF4F5F7.toInt(),
        textDim = 0xFF9BA1AB.toInt(),
    )

    private fun durations(tokens: Oe3MotionTokens): List<Int> = listOf(
        tokens.navSelection.durationMillis,
        tokens.press.durationMillis,
        tokens.pageFade.durationMillis,
        tokens.pageSlide.durationMillis,
        tokens.cardEnter.durationMillis,
        tokens.dialog.durationMillis,
        tokens.themeTween.durationMillis,
    )

    @Test
    fun themeInjectsTheStandardTokensByDefault() {
        var motion: Oe3MotionTokens? = null
        compose.setContent {
            Oe3Theme(theme) {
                motion = Oe3ThemeValues.motion
            }
        }
        compose.waitForIdle()

        assertEquals(durations(Oe3Motion.standard()), durations(motion!!))
        assertEquals(Oe3Motion.standard().cardEnterScaleFrom, motion!!.cardEnterScaleFrom, 0f)
        assertEquals(0.85f, motion!!.navSelectionSpring!!.dampingRatio, 0.0001f)
    }

    @Test
    fun reduceMotionInjectionCollapsesTheLocalAndKeepsTheEndState() {
        var motion: Oe3MotionTokens? = null
        compose.setContent {
            Oe3Theme(theme, reduceMotion = true) {
                motion = Oe3ThemeValues.motion
            }
        }
        compose.waitForIdle()

        durations(motion!!).forEach { duration -> assertEquals(0, duration) }
        assertEquals(1f, motion!!.cardEnterScaleFrom, 0f)
        assertNull("reduced motion must withdraw the navigation spring", motion!!.navSelectionSpring)
    }

    @Test
    fun valuesAccessorIsTheSameLocalTheThemeProvides() {
        var fromLocal: Oe3MotionTokens? = null
        var fromAccessor: Oe3MotionTokens? = null
        compose.setContent {
            Oe3Theme(theme) {
                fromLocal = LocalOe3Motion.current
                fromAccessor = Oe3ThemeValues.motion
            }
        }
        compose.waitForIdle()

        assertSame("pages may read either name without a second token table", fromLocal, fromAccessor)
    }

    @Test
    fun theLocalFallsBackToTheStandardScaleOutsideTheTheme() {
        var motion: Oe3MotionTokens? = null
        compose.setContent {
            motion = LocalOe3Motion.current
        }
        compose.waitForIdle()

        assertEquals(durations(Oe3Motion.standard()), durations(motion!!))
    }
}
