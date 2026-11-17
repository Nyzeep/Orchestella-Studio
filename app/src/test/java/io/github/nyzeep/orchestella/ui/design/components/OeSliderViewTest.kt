package io.github.nyzeep.orchestella.ui.design.components

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Contract between the four XML hosts and [OeSliderView]:
 * the three SeekBar layouts speak integer `max` / `progress`, the two wizard
 * sliders speak float `valueFrom` / `valueTo` / `stepSize`. Both must land in
 * the same internal value without changing call-site semantics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OeSliderViewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun seekBarShapeMapsIntegerProgress() {
        val slider = OeSliderView(context)
        slider.max = 90
        slider.progress = 45
        assertEquals(90, slider.max)
        assertEquals(45, slider.progress)
        assertEquals(45f, slider.value, 0f)
        assertEquals(0.5f, OeSliderSpec.fractionOf(slider.value, slider.valueFrom..slider.valueTo), 0f)
    }

    @Test
    fun wizardShapeKeepsFloatRangeAndSnapsToStepSize() {
        val slider = OeSliderView(context)
        slider.valueFrom = 512f
        slider.valueTo = 6144f
        slider.stepSize = 256f
        slider.value = 1000f
        assertEquals(1024f, slider.value, 0f)
        slider.value = 5000f
        assertEquals(5120f, slider.value, 0f)
    }

    @Test
    fun continuousSliderDoesNotSnap() {
        val slider = OeSliderView(context)
        slider.valueFrom = 0f
        slider.valueTo = 8f
        slider.value = 3.4f
        assertEquals(3.4f, slider.value, 0.0001f)
    }

    @Test
    fun programmaticProgressReportsFromUserFalse() {
        val slider = OeSliderView(context)
        slider.max = 10
        var seen: Pair<Int, Boolean>? = null
        slider.setOnProgressChangeListener(object : OeSliderView.OnProgressChangeListener {
            override fun onProgressChanged(slider: OeSliderView?, progress: Int, fromUser: Boolean) {
                seen = progress to fromUser
            }
        })
        slider.progress = 7
        assertEquals(7 to false, seen)
    }

    @Test
    fun programmaticFloatValueStaysSilent() {
        val slider = OeSliderView(context)
        slider.valueFrom = 0f
        slider.valueTo = 10f
        var notified = false
        slider.addOnChangeListener { _, _, _ -> notified = true }
        slider.value = 4f
        assertFalse(notified)
    }

    @Test
    fun glowStaysOffWhileHiddenOrDetached() {
        val slider = OeSliderView(context)
        assertFalse("detached view must not animate", slider.shouldAnimateGlow())

        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(slider)
        // Robolectric's headless window is not visible, so even an attached view stays off.
        assertFalse(slider.shouldAnimateGlow())

        slider.visibility = View.GONE
        assertFalse(slider.shouldAnimateGlow())

        slider.visibility = View.VISIBLE
        slider.isEnabled = false
        assertFalse(slider.shouldAnimateGlow())
    }

    @Test
    fun maxChangeClampsTheCurrentValue() {
        val slider = OeSliderView(context)
        slider.max = 20
        slider.progress = 18
        slider.max = 10
        assertEquals(10, slider.progress)
        assertTrue(slider.value <= 10f)
    }
}
