package io.github.nyzeep.orchestella.ui.design.components

import android.content.Context
import android.view.LayoutInflater
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The layouts still speak their original attributes; this proves the XML side
 * actually lands in [OeSliderView] rather than only the programmatic setters.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OeSliderLayoutInflationTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Orchestella)
    }

    @Test
    fun seekBarLayoutKeepsMaxAndProgress() {
        val root = LayoutInflater.from(context).inflate(R.layout.layout_vm_hardware_config_dialog, null)
        val vcpu = root.findViewById<OeSliderView>(R.id.hw_vcpu_seekbar)
        assertEquals(7, vcpu.max)
        assertEquals(1, vcpu.progress)

        val ram = root.findViewById<OeSliderView>(R.id.hw_ram_seekbar)
        assertEquals(30, ram.max)
        assertEquals(6, ram.progress)
    }

    @Test
    fun materialSliderLayoutKeepsFloatRangeAndStep() {
        val root = LayoutInflater.from(context).inflate(R.layout.activity_setup_wizard, null)
        val vcpu = root.findViewById<OeSliderView>(R.id.slider_vcpu)
        assertEquals(1f, vcpu.valueFrom, 0f)
        assertEquals(8f, vcpu.valueTo, 0f)
        assertEquals(1f, vcpu.stepSize, 0f)
        assertEquals(4f, vcpu.value, 0f)

        val ram = root.findViewById<OeSliderView>(R.id.slider_ram)
        assertEquals(512f, ram.valueFrom, 0f)
        assertEquals(6144f, ram.valueTo, 0f)
        assertEquals(256f, ram.stepSize, 0f)
        assertEquals(2048f, ram.value, 0f)
    }
}
