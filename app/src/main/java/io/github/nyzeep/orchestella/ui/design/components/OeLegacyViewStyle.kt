package io.github.nyzeep.orchestella.ui.design.components

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.RelativeCornerSize
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.tokens.OeType
import io.github.nyzeep.orchestella.ui.design.tokens.OeTypeRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import kotlin.math.roundToInt

/** Applies the design system's typography and action geometry to programmatic Android Views. */
object OeLegacyViewStyle {

    /**
     * W1-06: the one monospace face for legacy Views — JetBrains Mono NL via the
     * font-family XML, so [Typeface.create] resolves a real shipped weight
     * instead of synthesizing fake bold. Falls back to the platform monospace
     * when resources cannot load (unit-test environments, R not resolvable).
     */
    @JvmStatic
    @JvmOverloads
    fun monoTypeface(context: Context, style: Int = Typeface.NORMAL): Typeface {
        return try {
            val family = ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            when {
                family == null -> Typeface.MONOSPACE
                style == Typeface.NORMAL -> family
                else -> Typeface.create(family, style)
            }
        } catch (error: RuntimeException) {
            // Resource load failure (e.g. NotFoundException) must never break
            // view construction; the platform monospace keeps terminal cell
            // indexing correct. Byte-level drift is pinned by the notice test,
            // so anything thrown here is an environment problem, not a
            // shipped-asset one; Errors (OOM etc.) must still propagate.
            Typeface.MONOSPACE
        }
    }

    @JvmStatic
    fun text(view: TextView, role: OeTypeRole) {
        val spec = OeType.spec(role)
        val metrics = view.resources.displayMetrics
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.sizeSp)
        TextViewCompat.setLineHeight(
            view,
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, spec.lineHeightSp, metrics).roundToInt(),
        )
        val family = if (role == OeTypeRole.Mono) monoTypeface(view.context) else Typeface.SANS_SERIF
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
