package io.github.nyzeep.orchestella.ui.design.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI-13: the VM hardware sheet layout was deleted, so its inflation contract
 * is replaced by a structural check — the layout is gone and the Compose page
 * uses the single UI-05 [io.github.nyzeep.orchestella.ui.design.components.OeSlider]
 * instead. The programmatic OeSliderView range test stays: it still guards the
 * setter semantics shared by any remaining XML consumer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OeSliderLayoutInflationTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>().apply {
        setTheme(R.style.Theme_Orchestella)
    }

    private val mainSrc: File = listOf(File("src/main"), File("app/src/main"))
        .firstOrNull { it.isDirectory }
        ?: error("cannot locate src/main from ${File(".").absolutePath}")

    @Test
    fun vmHardwareSheetLayoutIsGoneAndTheComposePageOwnsTheSliders() {
        assertFalse(
            "UI-13 deleted the sheet layout; it must not come back as a fallback",
            mainSrc.resolve("res/layout/layout_vm_hardware_config_dialog.xml").exists(),
        )
        val page = mainSrc.resolve(
            "java/io/github/nyzeep/orchestella/ui/screens/connections/VmHardwarePage.kt",
        )
        assertTrue("the VM hardware page must use the single OeSlider", page.isFile)
        assertTrue(
            "the page must compose OeSlider rather than a platform SeekBar",
            page.readText().contains("OeSlider("),
        )
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
