package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemePreset

/**
 * 预设页的一张卡：一个 family 只出一条，配色是**当前模式那一套**。
 *
 * [theme] 由 [ThemeCatalog.resolveForMode] 派生，所以切换明暗时卡片的色条
 * 自动换成同族孪生，不会缓存旧色值，也不会并列亮/暗两套缩略图。
 */
data class ThemePresetGroup(
    val familyId: String,
    /** 目录里的展示名（同一 family 的亮/暗成员同名）。 */
    val title: String,
    val isMascot: Boolean,
    val theme: AppTheme,
)

/**
 * 预设页的分组规则（v4 §2.2），脱离 Compose 可单测。
 *
 * 规则只有四条：
 * 1. [ThemePreset.familyId] 相同只出一张卡（首见者决定顺序）；
 * 2. 卡片配色 = [ThemeCatalog.resolveForMode] 在目标明暗下的结果；
 * 3. 看板娘 family 固定排在最前；
 * 4. 无孪生的固定主题解析回自己，不分裂、不换色。
 */
object ThemePresetGrouping {

    const val MASCOT_FAMILY_ID = "mascot"

    /**
     * [resolve] 是「取目标明暗下那一套」的接缝，默认就是线上语义
     * [ThemeCatalog.resolveForMode]；单测用合成目录时注入同语义实现，
     * 生产路径不会绕过目录。
     */
    fun groups(
        presets: List<ThemePreset> = ThemeCatalog.presets,
        isDark: Boolean,
        resolve: (ThemePreset, Boolean) -> AppTheme = { preset, dark ->
            ThemeCatalog.resolveForMode(preset.id, dark)
        },
    ): List<ThemePresetGroup> {
        val seen = HashSet<String>(presets.size)
        val groups = ArrayList<ThemePresetGroup>(presets.size)
        presets.forEach { preset ->
            if (!seen.add(preset.familyId)) return@forEach
            groups += ThemePresetGroup(
                familyId = preset.familyId,
                title = preset.displayName,
                isMascot = preset.familyId == MASCOT_FAMILY_ID,
                theme = resolve(preset, isDark),
            )
        }
        // 稳定排序：看板娘在前，其余保持目录顺序。
        return groups.sortedBy { if (it.isMascot) 0 else 1 }
    }

    /** 卡片色条：背景 / 表面 / 主色 / 强调色 —— 恰好 4 色。 */
    fun stripColors(theme: AppTheme): List<Int> =
        listOf(theme.background, theme.surface, theme.primary, theme.accent)
}
