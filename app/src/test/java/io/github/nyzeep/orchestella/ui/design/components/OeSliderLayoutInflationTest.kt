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

    /**
     * UI-12 迁移注记：`activity_setup_wizard.xml` 是浮点属性（valueFrom /
     * valueTo / stepSize / value）唯一的 XML 消费者，随向导 Compose 化删除
     * （Compose 侧改走 UI-05 的 OeSlider）。浮点区间不变量改以同样的数值
     * （旧 XML 的 vCPU 1..8 步 1 值 4 / RAM 512..6144 步 256 值 2048）钉在
     * [OeSliderView] 的编程 API 上——这正是 XML 解析器喂给它的同一组 setter。
     */
    @Test
    fun floatRangeSemanticsMatchFormerWizardXml() {
        val vcpu = OeSliderView(context)
        vcpu.valueFrom = 1f
        vcpu.valueTo = 8f
        vcpu.stepSize = 1f
        vcpu.value = 4f
        assertEquals(1f, vcpu.valueFrom, 0f)
        assertEquals(8f, vcpu.valueTo, 0f)
        assertEquals(1f, vcpu.stepSize, 0f)
        assertEquals(4f, vcpu.value, 0f)

        val ram = OeSliderView(context)
        ram.valueFrom = 512f
        ram.valueTo = 6144f
        ram.stepSize = 256f
        ram.value = 2048f
        assertEquals(512f, ram.valueFrom, 0f)
        assertEquals(6144f, ram.valueTo, 0f)
        assertEquals(256f, ram.stepSize, 0f)
        assertEquals(2048f, ram.value, 0f)
    }
}
