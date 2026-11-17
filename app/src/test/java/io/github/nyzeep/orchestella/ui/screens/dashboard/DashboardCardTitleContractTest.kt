package io.github.nyzeep.orchestella.ui.screens.dashboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-04 切片 6 + UI-09 修订：卡片标题必须在卡片内部，且首页**不再有任何
 * 卡外分区标题**——指标组的「指标」分区头随 UI-09 退役，CPU/内存两张紧凑卡
 * 各自在卡内第一行排标题（17/22 w600）。
 */
class DashboardCardTitleContractTest {

    private val screen = File(
        "src/main/java/io/github/nyzeep/orchestella/ui/screens/dashboard/DashboardScreen.kt",
    ).readText()

    @Test
    fun everyCardTitleLivesInsideItsCard() {
        listOf(
            "ui3_dashboard_section_storage",
            "ui3_dashboard_section_recent",
            "ui3_dashboard_metric_cpu",
            "ui3_dashboard_metric_ram",
        ).forEach { key ->
            assertTrue("分区文案 $key 必须继续被引用（已搬进卡内 title 槽）", screen.contains(key))
        }
        assertTrue("卡内标题槽必须被使用", screen.contains("title = {"))
        assertTrue("最近实例卡用 OeCardHeader 作卡内标题", screen.contains("OeCardHeader("))
        assertTrue("卡内标题走共享的 17/22 w600 样式", screen.contains("oeCardTitleStyle()"))
    }

    @Test
    fun noSectionHeaderSurvivesOnTheWorkbench() {
        // UI-09：卡外分区标题全部退役（Oe3SectionHeader 不在首页出现）。
        assertFalse(
            "首页不得再出现卡外分区标题",
            screen.contains("Oe3SectionHeader("),
        )
        // 指标组（CPU+内存）是一个重排/隐藏单元，刷新说明跟在组内而不是卡外。
        assertTrue(
            "指标刷新说明仍随指标组",
            screen.contains("ui3_dashboard_metric_refresh"),
        )
    }
}
