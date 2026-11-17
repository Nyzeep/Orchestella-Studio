package io.github.nyzeep.orchestella.ui.screens.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level gates for ZD-W3（零设备收尾）：Theme Studio 子页头返回钮接线与
 * 元信息 13/18 字号分类。13/18 是逐处判断的结果（交付 4 的纪律），这里钉住
 * 「元信息升级、caption 语义保持」的分类不被机械替换回退；返回钮语义本身由
 * [ThemeStudioScreenTest] 在 compose 层验证。
 */
class ThemeStudioUiContractTest {

    private fun source(relative: String): String {
        val direct = File(relative)
        val file = if (direct.exists()) direct else File("app/$relative")
        assertTrue("missing source file: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun screenAcceptsAnOptionalBackCallback() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/theme/ThemeStudioScreen.kt")
        assertTrue(
            "ThemeStudioScreen must take onBack: (() -> Unit)? = null (absent → no back button, page stays reusable)",
            text.contains("onBack: (() -> Unit)? = null"),
        )
        assertTrue(
            "the back button must expose a stable testTag for semantics tests",
            text.contains("testTag(\"theme-studio:back\")"),
        )
        assertTrue(
            "contentDescription must come from the existing 返回 string",
            text.contains("R.string.ui3_theme_back"),
        )
    }

    @Test
    fun metaInfoUsesThirteenEighteenAndCaptionSemanticsStayCaption() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/ui/screens/theme/ThemeStudioScreen.kt")
        assertTrue(
            "MetaTextStyle must be the 13/18 page-local style (spec §2.3), not the mono role",
            text.contains("private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)"),
        )
        assertEquals(
            "meta-info positions pinned to 13/18: page meta, fixed-mode note, preset title, " +
                "preset tag, slot meta, wallpaper state, opacity value, terminal background state",
            8,
            Regex("style = MetaTextStyle").findAll(text).count(),
        )
        assertEquals(
            "caption semantics stay Caption: 编辑 action link + terminal background note",
            2,
            Regex("""Oe3TextRole\.Caption""").findAll(text).count(),
        )
    }

    @Test
    fun hostWiresTheBackCallbackAtTheThemeStudioCallSite() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/MainActivity.kt")
        assertTrue(
            "the call site must close the studio on back, matching the OnBackPressedCallback (shell returns to the config tab)",
            Regex("onBack = \\{ oe3ThemeStudioOpen\\.value = false \\}").containsMatchIn(text),
        )
    }
}
