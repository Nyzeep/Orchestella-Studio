package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeScheme
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import org.junit.Assert.assertEquals
import org.junit.Test

class Oe3ThemeShapesTest {

    @Test
    fun nullRadiusKeepsTheFixedLadder() {
        val shapes = Oe3ThemeScheme.shapes()
        assertEquals(RoundedCornerShape(Oe3Shapes.xs), shapes.extraSmall)
        assertEquals(RoundedCornerShape(Oe3Shapes.sm), shapes.small)
        assertEquals(RoundedCornerShape(Oe3Shapes.md), shapes.medium)
        assertEquals(RoundedCornerShape(Oe3Shapes.lg), shapes.large)
        assertEquals(RoundedCornerShape(Oe3Shapes.xl), shapes.extraLarge)
    }

    @Test
    fun themeStudioRadiusProjectsIntoEveryMaterialShapeTier() {
        for (radius in ThemeStudioSpec.cornerOptions) {
            val shapes = Oe3ThemeScheme.shapes(radius.dp)
            assertEquals(RoundedCornerShape(radius.dp), shapes.extraSmall)
            assertEquals(RoundedCornerShape(radius.dp), shapes.small)
            assertEquals(RoundedCornerShape(radius.dp), shapes.medium)
            assertEquals(RoundedCornerShape(radius.dp), shapes.large)
            assertEquals(RoundedCornerShape(radius.dp), shapes.extraLarge)
        }
    }
}
