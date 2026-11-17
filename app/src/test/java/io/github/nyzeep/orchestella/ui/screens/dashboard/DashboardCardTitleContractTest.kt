package io.github.nyzeep.orchestella.ui.screens.dashboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-04 切片 6：卡片标题必须在卡片内部。
 *
 * 存储卡与最近实例卡原先把标题画在卡外（`Oe3SectionHeader`），v4 以卡内
 * `title` 槽取代，且不得新旧并存。指标分区标题服务「两张指标卡」这一组，
 * 是分组头而非某张卡的标题，按设计系统的 section-header 规则保留在卡外。
 */
class DashboardCardTitleContractTest {

    private val screen = File(
        "src/main/java/io/github/nyzeep/orchestella/ui/screens/dashboard/DashboardScreen.kt",
    ).readText()

    @Test
    fun storageAndRecentTitlesLiveInsideTheirCards() {
        listOf("ui3_dashboard_section_storage", "ui3_dashboard_section_recent").forEach { key ->
            assertTrue("分区文案 $key 必须继续被引用（已搬进卡内 title 槽）", screen.contains(key))
            assertFalse(
                "$key 不得再以卡外分区标题的形式出现（新旧并存）",
                screen.contains("Oe3SectionHeader(title = stringResource(R.string.$key)"),
            )
        }
        assertTrue("卡内标题槽必须被使用", screen.contains("title = {"))
        assertTrue("最近实例卡用 OeCardHeader 作卡内标题", screen.contains("OeCardHeader("))
        assertTrue("存储卡标题走共享的 17/22 w600 样式", screen.contains("oeCardTitleStyle()"))
    }

    @Test
    fun metricsGroupKeepsItsSectionHeaderBecauseItTitlesTwoCards() {
        assertTrue(
            "指标分组头保留（两卡一组，不是单卡标题）",
            screen.contains("Oe3SectionHeader(title = stringResource(R.string.ui3_dashboard_section_metrics)"),
        )
    }
}
