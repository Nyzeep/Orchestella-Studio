package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-01: `ui/design/tokens` is the single source of truth for shape, spacing
 * and type. The legacy `Oe3*` objects may only delegate — the ladder values
 * below are pinned independently of the code so a silent numeric edit in the
 * source cannot pass review.
 */
class DesignTokensContractTest {

    @Test
    fun shapeLadderMatchesTheDesignSystemAuthority() {
        assertEquals(8.dp, OeShape.xs)
        assertEquals(12.dp, OeShape.sm)
        assertEquals(16.dp, OeShape.md)
        assertEquals(20.dp, OeShape.lg)
        assertEquals(24.dp, OeShape.xl)
        assertEquals(32.dp, OeShape.alert)
        assertEquals(24.dp, OeShape.groupedOuter)
        assertEquals(0.dp, OeShape.groupedInner)
        assertEquals(RoundedCornerShape(percent = 50), OeShape.pill)
    }

    @Test
    fun shapeLadderIsMonotonicAndCardsStayAt24dp() {
        val ladder = listOf(OeShape.xs, OeShape.sm, OeShape.md, OeShape.lg, OeShape.xl, OeShape.alert)
        ladder.zipWithNext().forEach { (smaller, larger) ->
            assertTrue("$smaller must be below $larger", smaller < larger)
        }
        assertEquals("page cards stay on Shape.xl", OeShape.xl, OeShape.groupedOuter)
    }

    @Test
    fun spacingRhythmKeepsTheDocumentedSteps() {
        assertEquals(4.dp, OeSpacing.xxs)
        assertEquals(8.dp, OeSpacing.xs)
        assertEquals(12.dp, OeSpacing.sm)
        assertEquals(16.dp, OeSpacing.md)
        assertEquals(20.dp, OeSpacing.lg)
        assertEquals(24.dp, OeSpacing.xl)
        assertEquals(32.dp, OeSpacing.xxl)
        assertEquals(20.dp, OeSpacing.pageInset)
        assertEquals(83.dp, OeSpacing.contentBottom)
        assertEquals(20.dp, OeSpacing.cardPadding)
        assertEquals(56.dp, OeSpacing.rowHeight)
        assertEquals(16.dp, OeSpacing.rowHorizontal)
        assertEquals(16.dp, OeSpacing.iconTextGap)
        assertEquals(24.dp, OeSpacing.sectionTop)
        assertEquals(8.dp, OeSpacing.sectionBottom)
        assertEquals(8.dp, OeSpacing.gridGap)
    }

    @Test
    fun typeScaleIsTheDocumentedEightRoleTable() {
        assertEquals(9, OeTypeRole.entries.size)
        fun spec(role: OeTypeRole, size: Float, lineHeight: Float, weight: FontWeight) {
            val actual = OeType.spec(role)
            assertEquals("$role size", size, actual.sizeSp, 0.001f)
            assertEquals("$role lineHeight", lineHeight, actual.lineHeightSp, 0.001f)
            assertEquals("$role weight", weight, actual.weight)
        }
        spec(OeTypeRole.Brand, 24f, 30f, FontWeight.SemiBold)
        spec(OeTypeRole.PageTitle, 30f, 36f, FontWeight.SemiBold)
        spec(OeTypeRole.Section, 17f, 22f, FontWeight.Medium)
        spec(OeTypeRole.Body, 14f, 20f, FontWeight.Normal)
        spec(OeTypeRole.BodyStrong, 14f, 20f, FontWeight.SemiBold)
        spec(OeTypeRole.Caption, 12f, 16f, FontWeight.Normal)
        spec(OeTypeRole.NavLabel, 10f, 14.5f, FontWeight.Medium)
        spec(OeTypeRole.Mono, 13f, 18f, FontWeight.Normal)
    }

    @Test
    fun oe3ObjectsDelegateToTheSingleSource() {
        assertEquals(OeShape.xs, Oe3Shapes.xs)
        assertEquals(OeShape.sm, Oe3Shapes.sm)
        assertEquals(OeShape.md, Oe3Shapes.md)
        assertEquals(OeShape.lg, Oe3Shapes.lg)
        assertEquals(OeShape.xl, Oe3Shapes.xl)
        assertEquals(OeShape.groupedOuter, Oe3Shapes.groupedOuter)
        assertEquals(OeShape.groupedInner, Oe3Shapes.groupedInner)
        assertEquals(OeShape.pill, Oe3Shapes.pill)

        assertEquals(OeSpacing.xxs, Oe3Spacing.xxs)
        assertEquals(OeSpacing.xs, Oe3Spacing.xs)
        assertEquals(OeSpacing.sm, Oe3Spacing.sm)
        assertEquals(OeSpacing.md, Oe3Spacing.md)
        assertEquals(OeSpacing.lg, Oe3Spacing.lg)
        assertEquals(OeSpacing.xl, Oe3Spacing.xl)
        assertEquals(OeSpacing.xxl, Oe3Spacing.xxl)
        assertEquals(OeSpacing.pageInset, Oe3Spacing.pageInset)
        assertEquals(OeSpacing.contentBottom, Oe3Spacing.contentBottom)
        assertEquals(OeSpacing.cardPadding, Oe3Spacing.cardPadding)
        assertEquals(OeSpacing.rowHeight, Oe3Spacing.rowHeight)
        assertEquals(OeSpacing.rowHorizontal, Oe3Spacing.rowHorizontal)
        assertEquals(OeSpacing.iconTextGap, Oe3Spacing.iconTextGap)
        assertEquals(OeSpacing.sectionTop, Oe3Spacing.sectionTop)
        assertEquals(OeSpacing.sectionBottom, Oe3Spacing.sectionBottom)
        assertEquals(OeSpacing.gridGap, Oe3Spacing.gridGap)

        // The legacy role enum is an alias of the design-token role set.
        assertEquals(OeTypeRole.entries.toList(), Oe3TextRole.entries.toList())
        Oe3TextRole.entries.forEach { role ->
            assertEquals(role.name, OeType.spec(role), Oe3Typography.spec(role))
        }
    }
}
