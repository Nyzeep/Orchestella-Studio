package io.github.nyzeep.orchestella.ui.screens.theme

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码级门（零设备收尾）：v4 IA 的结构性红线。
 *
 * - 根页与四个子页都走 `OeScaffold` 单标题 + `ThemeBackButton`；
 * - 主题工坊不再自带 `Oe3Theme`（壳内二次字号缩放）；
 * - 页面不再出现页内 30sp `PageTitle`（v4 §0.1 单标题）；
 * - 宿主把根页 push 进 `OeBackStack`，并彻底删掉 overlay 开关；
 * - 预设页的「一卡一套」由纯函数驱动，而不是页面内临时判断。
 */
class ThemeStudioUiContractTest {

    private fun source(relative: String): String {
        val direct = File(relative)
        val file = if (direct.exists()) direct else File("app/$relative")
        assertTrue("missing source file: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun themeSource(name: String): String =
        source("src/main/java/io/github/nyzeep/orchestella/ui/screens/theme/$name")

    @Test
    fun rootPageUsesScaffoldAndTheSharedBackButton() {
        val text = themeSource("ThemeStudioScreen.kt")
        assertTrue("root page must use OeScaffold (single title bar)", text.contains("OeScaffold("))
        assertTrue(
            "root page must take onBack: (() -> Unit)? = null",
            text.contains("onBack: (() -> Unit)? = null"),
        )
        assertTrue("root page must delegate its leading slot to ThemeBackButton", text.contains("ThemeBackButton("))
        assertFalse(
            "root page must not wrap itself in Oe3Theme (the shell already provides it)",
            text.contains("Oe3Theme("),
        )
    }

    @Test
    fun theFourIndependentPagesEachOwnScaffoldAndBack() {
        listOf(
            "ThemePresetScreen.kt",
            "ThemeAccentScreen.kt",
            "ThemeBackgroundScreen.kt",
            "ThemeTerminalScreen.kt",
        ).forEach { name ->
            val text = themeSource(name)
            assertTrue("$name must be a real page with OeScaffold", text.contains("OeScaffold("))
            assertTrue("$name must expose the shared back button", text.contains("ThemeBackButton("))
            assertTrue("$name must take an explicit onBack", text.contains("onBack: () -> Unit"))
        }
    }

    @Test
    fun themePagesDrawNoInPageThirtySpTitle() {
        listOf(
            "ThemeStudioScreen.kt",
            "ThemePresetScreen.kt",
            "ThemeAccentScreen.kt",
            "ThemeBackgroundScreen.kt",
            "ThemeTerminalScreen.kt",
        ).forEach { name ->
            val text = themeSource(name)
            assertFalse("$name must not reintroduce Oe3TextRole.PageTitle", text.contains("Oe3TextRole.PageTitle"))
        }
    }

    @Test
    fun metaStyleHasOneDefinitionAndPagesConsumeIt() {
        val shared = themeSource("ThemeOptionRow.kt")
        assertTrue(
            "MetaTextStyle must be the shared 13/18 style (spec §2.3)",
            shared.contains("internal val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)"),
        )
        listOf(
            "ThemeStudioScreen.kt",
            "ThemePresetScreen.kt",
            "ThemeBackgroundScreen.kt",
            "ThemeTerminalScreen.kt",
            "ThemeAccentComponents.kt",
        ).forEach { name ->
            assertTrue("$name must consume the shared 13/18 meta style", themeSource(name).contains("style = MetaTextStyle"))
        }
    }

    @Test
    fun presetPageIsDrivenByThePureGroupingFunction() {
        val text = themeSource("ThemePresetScreen.kt")
        assertTrue(
            "preset page must group via ThemePresetGrouping.groups (one card per family)",
            text.contains("ThemePresetGrouping.groups("),
        )
        assertTrue(
            "preset strip must come from the 4-colour pure function",
            text.contains("ThemePresetGrouping.stripColors("),
        )
    }

    @Test
    fun hostPushesTheStudioOntoTheBackStackAndDropsTheOverlaySwitch() {
        val text = source("src/main/java/io/github/nyzeep/orchestella/MainActivity.kt")
        assertTrue(
            "ConfigDestination.ThemeStudio must push a destination onto the shell stack",
            text.contains("stack.push(themeStudioDestination())"),
        )
        assertTrue(
            "the root destination key must be stable",
            text.contains("THEME_STUDIO_DESTINATION_KEY"),
        )
        assertFalse(
            "the overlay path must be gone (no oe3ThemeStudioOpen anywhere)",
            text.contains("oe3ThemeStudioOpen"),
        )
    }
}
