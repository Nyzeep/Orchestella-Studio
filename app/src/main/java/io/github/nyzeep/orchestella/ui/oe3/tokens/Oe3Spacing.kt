package io.github.nyzeep.orchestella.ui.oe3.tokens

import io.github.nyzeep.orchestella.ui.design.tokens.OeSpacing

/**
 * Legacy entry point for the spacing rhythm. The values live in [OeSpacing]
 * (`ui/design/tokens`) since UI-01; this object only delegates so existing
 * call sites keep their names and numbers unchanged.
 */
object Oe3Spacing {
    val xxs = OeSpacing.xxs
    val xs = OeSpacing.xs
    val sm = OeSpacing.sm
    val md = OeSpacing.md
    val lg = OeSpacing.lg
    val xl = OeSpacing.xl
    val xxl = OeSpacing.xxl

    val pageInset = OeSpacing.pageInset
    val contentBottom = OeSpacing.contentBottom
    val cardPadding = OeSpacing.cardPadding

    val rowHeight = OeSpacing.rowHeight
    val rowHorizontal = OeSpacing.rowHorizontal
    val iconTextGap = OeSpacing.iconTextGap

    val sectionTop = OeSpacing.sectionTop
    val sectionBottom = OeSpacing.sectionBottom

    val gridGap = OeSpacing.gridGap
}
