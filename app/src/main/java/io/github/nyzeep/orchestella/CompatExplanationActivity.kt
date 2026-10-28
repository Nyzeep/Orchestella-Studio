package io.github.nyzeep.orchestella

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = getString(R.string.compat_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(Typeface.DEFAULT_BOLD)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            content.addView(paragraph, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
        }

        val footer = TextView(this).apply {
            text = getString(R.string.compat_footer_note)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        content.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })

        setContentView(ScrollView(this).apply { addView(content) })
        ThemeApplier.bind(this)
    }
}
