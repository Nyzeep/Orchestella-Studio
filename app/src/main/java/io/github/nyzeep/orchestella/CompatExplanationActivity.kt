package io.github.nyzeep.orchestella

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import io.github.nyzeep.orchestella.ui.design.components.OeLegacyViewStyle
import io.github.nyzeep.orchestella.ui.design.tokens.OeTypeRole
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier

/**
 * Secondary Activity: explains why pVM/AVF acceleration may be unavailable.
 * Pure linear layout; every string lives in strings.xml and the page is
 * recolored by the global ThemeApplier pass like any other chrome surface.
 */
class CompatExplanationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val inset = resources.getDimensionPixelSize(R.dimen.oe_space_xl)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, inset, inset, inset)
        }

        val title = TextView(this).apply {
            text = getString(R.string.compat_title)
            OeLegacyViewStyle.text(this, OeTypeRole.Brand)
            ViewCompat.setAccessibilityHeading(this, true)
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        for (res in intArrayOf(
            R.string.compat_body_pvm,
            R.string.compat_body_unavailable,
            R.string.compat_body_supported,
        )) {
            val paragraph = TextView(this).apply {
                text = getString(res)
                OeLegacyViewStyle.text(this, OeTypeRole.Body)
            }
            content.addView(paragraph, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        val footer = TextView(this).apply {
            text = getString(R.string.compat_footer_note)
            OeLegacyViewStyle.text(this, OeTypeRole.Caption)
        }
        content.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })

        setContentView(ScrollView(this).apply { addView(content) })
        ThemeApplier.bind(this)
    }
}
