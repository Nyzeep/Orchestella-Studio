package io.github.nyzeep.orchestella.ui.design

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the design tokens. No Android runtime, no Compose
 * renderer: these are the numbers every page will inherit, so they get pinned
 * here instead of being checked by eye on a device.
 */
class OeTokensTest {

    // --- shape -------------------------------------------------------------

    @Test
    fun controlRadiusFollowsTheThreeUserSettings() {
        assertEquals(8.dp, OeShape.controlRadius(8.dp))
        assertEquals(12.dp, OeShape.controlRadius(12.dp))
        assertEquals(16.dp, OeShape.controlRadius(16.dp))
    }

    @Test
    fun controlRadiusClampsOutsideTheSupportedBand() {
        assertEquals(8.dp, OeShape.controlRadius(0.dp))
        assertEquals(8.dp, OeShape.controlRadius(4.dp))
        assertEquals(16.dp, OeShape.controlRadius(20.dp))
        assertEquals(16.dp, OeShape.controlRadius(28.dp))
    }

    @Test
    fun surfaceRadiiAreLayoutNotPreference() {
        // The user's control rounding must never stretch hero/card/block.
        assertEquals(32.dp, OeShape.hero)
        assertEquals(24.dp, OeShape.card)
        assertEquals(20.dp, OeShape.block)
        assertEquals(14.dp, OeShape.badge)
    }

    // --- type --------------------------------------------------------------

    @Test
    fun typeUsesTheSpecifiedBaseSizes() {
        assertEquals(28f, OeType.style(OeTextRole.PageTitle).fontSize.value, 0.001f)
        assertEquals(15f, OeType.style(OeTextRole.Section).fontSize.value, 0.001f)
        assertEquals(15f, OeType.style(OeTextRole.Row).fontSize.value, 0.001f)
        assertEquals(13f, OeType.style(OeTextRole.Body).fontSize.value, 0.001f)
        assertEquals(11f, OeType.style(OeTextRole.Caption).fontSize.value, 0.001f)
    }

    @Test
    fun typeSizeScalesWithSystemFontScaleExactlyOnce() {
        val title = OeType.style(OeTextRole.PageTitle)
        val caption = OeType.style(OeTextRole.Caption)
        val normal = Density(density = 1f, fontScale = 1f)
        val large = Density(density = 1f, fontScale = 1.3f)

        val titleNormal = with(normal) { title.fontSize.toPx() }
        val titleLarge = with(large) { title.fontSize.toPx() }
        val captionNormal = with(normal) { caption.fontSize.toPx() }
        val captionLarge = with(large) { caption.fontSize.toPx() }

        assertEquals(28f, titleNormal, 0.01f)
        assertEquals(11f, captionNormal, 0.01f)
        // The platform applies the system factor when sp is converted to px.
        assertTrue("title must grow with fontScale", titleLarge > titleNormal)
        assertTrue("caption must grow with fontScale", captionLarge > captionNormal)
        // Android 14+ scales text non-linearly, so the platform result must stay
        // at or below the linear 36.4px ceiling; a token that pre-multiplied the
        // factor would overshoot it.
        assertTrue("title must not double-apply fontScale", titleLarge <= 28f * 1.3f + 0.01f)
        // The stored token stays the base size; only conversion is affected.
        assertEquals(28f, title.fontSize.value, 0.001f)
    }

    @Test
    fun inAppScaleIsAnAdditionalMultiplierOnlyWhenCustom() {
        val style = OeType.style(OeTextRole.Body, scale = 1.3f)
        assertEquals(16.9f, style.fontSize.value, 0.001f)
    }

    @Test
    fun textScaleFollowSystemNeverDoubleApplies() {
        assertEquals(
            1f,
            OeTextScale.effectiveFactor(mode = OeTextScaleMode.FollowSystem),
            0.0001f,
        )
        assertEquals(
            1.3f,
            OeTextScale.effectiveFactor(mode = OeTextScaleMode.Custom, customPercent = 130),
            0.0001f,
        )
    }

    @Test
    fun textScaleClampsToTheDocumentedBand() {
        assertEquals(
            0.85f,
            OeTextScale.effectiveFactor(mode = OeTextScaleMode.Custom, customPercent = 50),
            0.0001f,
        )
        assertEquals(
            1.30f,
            OeTextScale.effectiveFactor(mode = OeTextScaleMode.Custom, customPercent = 200),
            0.0001f,
        )
    }

    @Test
    fun numericStylesUseTabularFigures() {
        val numeric = OeType.numeric(OeType.style(OeTextRole.StatValue))
        assertEquals("tnum", numeric.fontFeatureSettings)
        assertEquals(null, OeType.style(OeTextRole.Body).fontFeatureSettings)
    }

    // --- sparkline ---------------------------------------------------------

    @Test
    fun sparklineEmptyInputStaysEmpty() {
        assertTrue(OeSparkline.normalize(emptyList()).isEmpty())
    }

    @Test
    fun sparklineSinglePointSitsInTheMiddle() {
        assertEquals(listOf(0.5f), OeSparkline.normalize(listOf(42f)))
    }

    @Test
    fun sparklineFlatSeriesSitsInTheMiddle() {
        assertEquals(listOf(0.5f, 0.5f, 0.5f), OeSparkline.normalize(listOf(7f, 7f, 7f)))
    }

    @Test
    fun sparklineNormalisesToUnitRange() {
        val result = OeSparkline.normalize(listOf(10f, 20f, 15f))
        assertEquals(listOf(0f, 1f, 0.5f), result)
        assertEquals(0f, result.min(), 0.0001f)
        assertEquals(1f, result.max(), 0.0001f)
    }

    @Test
    fun sparklineDropsNonFiniteSamplesInsteadOfProducingNaN() {
        val result = OeSparkline.normalize(
            listOf(1f, Float.NaN, 3f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY),
        )
        assertEquals(2, result.size)
        result.forEach { value ->
            assertTrue("value must be finite: $value", value.isFinite())
            assertFalse(value.isNaN())
            assertTrue("value out of range: $value", value in 0f..1f)
        }
    }
}
