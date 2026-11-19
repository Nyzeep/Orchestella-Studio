package io.github.nyzeep.orchestella.ui.design.components

import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.RelativeCornerSize
import io.github.nyzeep.orchestella.ui.design.tokens.OeType
import io.github.nyzeep.orchestella.ui.design.tokens.OeTypeRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import kotlin.math.roundToInt

/** Applies the design system's typography and action geometry to programmatic Android Views. */
object OeLegacyViewStyle {

    @JvmStatic
    fun text(view: TextView, role: OeTypeRole) {
        val spec = OeType.spec(role)
        val metrics = view.resources.displayMetrics
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.sizeSp)
        TextViewCompat.setLineHeight(
            view,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, spec.lineHeightSp, metrics).roundToInt(),
        )
        val family = if (role == OeTypeRole.Mono) Typeface.MONOSPACE else Typeface.SANS_SERIF
        view.typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(family, spec.weight.weight, false)
        } else {
            Typeface.create(family, if (spec.weight.weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    @JvmStatic
    fun action(view: TextView) {
        text(view, OeTypeRole.BodyStrong)
        view.setAllCaps(false)
        val density = view.resources.displayMetrics.density
        val minTarget = (Oe3Sizes.minTouchTarget.value * density).roundToInt()
        view.minimumHeight = minTarget
        view.minimumWidth = minTarget
        val padding = (Oe3Spacing.sm.value * density).roundToInt()
        if (view is MaterialButton) {
            val inset = ((Oe3Sizes.button - Oe3Sizes.interactiveVisual).value * density / 2f).roundToInt()
            view.insetTop = inset
            view.insetBottom = inset
            view.shapeAppearanceModel = view.shapeAppearanceModel.toBuilder()
                .setAllCornerSizes(RelativeCornerSize(0.5f))
                .build()
        }
        view.setPaddingRelative(padding, view.paddingTop, padding, view.paddingBottom)
    }
}
