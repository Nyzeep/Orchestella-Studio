package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemePalette
import io.github.nyzeep.orchestella.ui.theme.ThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v4 §2.2 主题预设页的规则被抽成纯函数后在这里断言：
 *
 * 1. 一个 family 只出一张卡（禁止并列亮/暗两套）；
 * 2. 卡片显示的是「当前模式那一套」，明暗切换后自动跟随；
 * 3. 看板娘（familyId = mascot）单列在最前；
 * 4. 卡片色条是 4 色；
 * 5. 没有同族孪生的固定主题不会被分裂，也不会被错误地换色。
 *
 * 这些断言不碰 Compose，页面只负责把 [ThemePresetGrouping.groups] 的结果
 * 摆出来，所以「一卡一套」不依赖截图或手测。
 */
class ThemePresetGroupingTest {

    @Test
    fun oneCardPerFamilyAndMascotLeads() {
        val groups = ThemePresetGrouping.groups(ThemeCatalog.presets, isDark = true)
        val families = ThemeCatalog.presets.map { it.familyId }

        assertEquals(
            "每个 family 只能出一张卡",
            families.distinct().size,
            groups.size,
        )
        assertEquals(
            "family 不得重复",
            groups.map { it.familyId }.distinct().size,
            groups.size,
        )
        assertEquals("看板娘必须单列在最前", ThemePresetGrouping.MASCOT_FAMILY_ID, groups.first().familyId)
        assertTrue("第一张卡必须带看板娘标记", groups.first().isMascot)
        assertTrue("看板娘之后不能再出现 mascot 卡", groups.drop(1).none { it.isMascot })
    }

    @Test
    fun everyCardShowsTheCurrentModesPalette() {
        val dark = ThemePresetGrouping.groups(ThemeCatalog.presets, isDark = true)
        val light = ThemePresetGrouping.groups(ThemeCatalog.presets, isDark = false)

        dark.forEach { group ->
            assertTrue("${group.familyId} 在深色模式应为暗色", group.theme.isDark)
            assertEquals(
                "卡片配色必须属于同一 family",
                group.familyId,
                ThemeCatalog.presetById(group.theme.id)?.familyId,
            )
        }
        light.forEach { group ->
            assertFalse("${group.familyId} 在浅色模式应为浅色", group.theme.isDark)
            assertEquals(
                "卡片配色必须属于同一 family",
                group.familyId,
                ThemeCatalog.presetById(group.theme.id)?.familyId,
            )
        }
    }

    @Test
    fun switchingModeFollowsTheTwinWithoutReorderingCards() {
        val dark = ThemePresetGrouping.groups(ThemeCatalog.presets, isDark = true)
        val light = ThemePresetGrouping.groups(ThemeCatalog.presets, isDark = false)

        assertEquals(
            "明暗切换不得改变卡片顺序",
            dark.map { it.familyId },
            light.map { it.familyId },
        )

        val darkGithub = dark.first { it.familyId == "github" }
        val lightGithub = light.first { it.familyId == "github" }
        assertEquals("github", darkGithub.theme.id)
        assertEquals("github-light", lightGithub.theme.id)
        assertNotEquals(
            "同一 family 的亮/暗配色必须不同（否则跟随是假的）",
            ThemePresetGrouping.stripColors(darkGithub.theme),
            ThemePresetGrouping.stripColors(lightGithub.theme),
        )
    }

    @Test
    fun presetCardStripHasExactlyFourColours() {
        val group = ThemePresetGrouping.groups(ThemeCatalog.presets, isDark = true).first()
        assertEquals(4, ThemePresetGrouping.stripColors(group.theme).size)
    }

    @Test
    fun fixedSingleFormFamilyStaysItselfInEitherMode() {
        val solo = preset(
            id = "solo",
            familyId = "solo",
            isDark = true,
            isFixed = true,
            primary = 0xFF112233.toInt(),
        )

        val asLight = ThemePresetGrouping.groups(listOf(solo), isDark = false) { preset, _ ->
            preset.toAppTheme()
        }
        val asDark = ThemePresetGrouping.groups(listOf(solo), isDark = true) { preset, _ ->
            preset.toAppTheme()
        }

        assertEquals(1, asLight.size)
        assertEquals("solo", asLight.single().theme.id)
        assertTrue(asLight.single().theme.isDark)
        assertEquals("solo", asDark.single().theme.id)
        assertEquals(
            "固定主题不得因模式切换换色",
            ThemePresetGrouping.stripColors(asLight.single().theme),
            ThemePresetGrouping.stripColors(asDark.single().theme),
        )
    }

    private fun preset(
        id: String,
        familyId: String,
        isDark: Boolean,
        isFixed: Boolean = false,
        primary: Int,
    ): ThemePreset = ThemePreset(
        id = id,
        displayName = id,
        familyId = familyId,
        isDark = isDark,
        isFixed = isFixed,
        palette = ThemePalette(
            background = 0xFF000000.toInt(),
            surface = 0xFF111111.toInt(),
            surfaceTranslucent = 0xFF111111.toInt(),
            primary = primary,
            accent = 0xFF222222.toInt(),
            text = 0xFFFFFFFF.toInt(),
            textDim = 0xFF999999.toInt(),
            glow = primary,
            terminalBackground = 0xFF000000.toInt(),
            terminalForeground = 0xFFFFFFFF.toInt(),
            ansi = List(16) { 0xFF808080.toInt() },
        ),
    )
}
