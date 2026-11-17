package io.github.nyzeep.orchestella.ui.screens.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI-09 编辑工作台的硬门：
 * 1. 编辑态是同页状态切换——源码里不得出现任何对话框原语（严禁弹窗承载编辑）。
 * 2. 动效时长单一真源——dashboard 源码里的 `tween(` 不得内嵌数字字面量，
 *    时长一律来自 [OeMotion]/motion token。
 * 3. 摆动/让位/出现/拖起的数值与工单参考规格一致。
 * 4. 新增的三个编辑态字形（铅笔/对勾/减号）与 Oe3 线性家族同规矩：
 *    单路径、1.75dp 描边、可任意着色、24dp 视口。
 */
class DashboardEditContractTest {

    private val dashboardSources: List<File> = File(
        "src/main/java/io/github/nyzeep/orchestella/ui/screens/dashboard",
    ).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private val screen = File(
        "src/main/java/io/github/nyzeep/orchestella/ui/screens/dashboard/DashboardScreen.kt",
    ).readText()

    @Test
    fun editModeIsASamePageStateSwitchWithNoDialog() {
        val forbidden = listOf("AlertDialog", "ModalBottomSheet", "Dialog(", "Popup(", "BottomSheet(")
        dashboardSources.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse(
                    "${file.name} must not host the edit mode in a dialog ($token)",
                    text.contains(token),
                )
            }
        }
        // 编辑开关是同页动作：右上动作槽切换标题与图标。
        assertTrue(screen.contains("ui3_dashboard_edit_title"))
        assertTrue(screen.contains("Oe3Icons.Check"))
        assertTrue(screen.contains("Oe3Icons.Edit"))
    }

    @Test
    fun noHardcodedDurationsInDashboardMotion() {
        // tween( 带数字字面量 = 违规；时长只允许来自 token/局部派生值。
        val hardcoded = Regex("""tween\([^)]*\d{2,}""")
        dashboardSources.forEach { file ->
            assertFalse(
                "${file.name} hardcodes a motion duration; use OeMotion tokens",
                hardcoded.containsMatchIn(file.readText()),
            )
        }
        assertTrue("编辑态摆动周期必须来自 motion token", screen.contains("editWiggle.durationMillis"))
    }

    @Test
    fun editMotionTokensMatchTheTicketSpec() {
        // 工单参考规格：摆动 ±0.7° / ±1.25dp / 3600ms；让位 260ms；
        // 出现 200ms scale 0.92→1；拖起 scale 1.03（delta 0.03）。
        assertEquals(0.7f, OeMotion.EDIT_WIGGLE_ROTATION_DEG, 0.0001f)
        assertEquals(1.25f, OeMotion.EDIT_WIGGLE_SHIFT_DP, 0.0001f)
        assertEquals(3600, OeMotion.EDIT_WIGGLE_PERIOD_MS)
        assertEquals(600, OeMotion.EDIT_WIGGLE_PHASE_STEP_MS)
        assertEquals(260, OeMotion.EDIT_REORDER_SETTLE_MS)
        assertEquals(200, OeMotion.EDIT_APPEAR_MS)
        assertEquals(0.92f, OeMotion.EDIT_APPEAR_SCALE_FROM, 0.0001f)
        assertEquals(0.03f, OeMotion.EDIT_DRAG_LIFT_SCALE_DELTA, 0.0001f)
    }

    @Test
    fun dragLiftShadowIsTheOnlyShadowOnThePage() {
        // v4 spec §1.4：阴影无，仅编辑态拖起时 e2（瞬态）。全页 shadowElevation
        // 只允许出现一次——在拖拽 graphicsLayer 内随 lift 归零；其余任何静态
        // 投影（含 Modifier.shadow）都被 DashboardScreenContractTest 禁止。
        val occurrences = dashboardSources.sumOf { file ->
            Regex("shadowElevation").findAll(file.readText()).count()
        }
        assertTrue("拖起瞬态投影必须存在且仅一处（编辑态 graphicsLayer）", occurrences == 1)
        assertTrue("瞬态投影必须随拖拽 lift 归零", screen.contains("editDragElevation.toPx() * lifted"))
    }

    @Test
    fun deleteBadgeNeverDeletesUserData() {
        // 隐藏 = 从显示列表移除；恢复入口（chips 行）必须在编辑态可用。
        assertTrue(screen.contains("HideBadge("))
        assertTrue(screen.contains("RestoreRow("))
        assertTrue("恢复走 DashboardEditState.restore", screen.contains("edit.restore(cardId)"))
        // 徽标图标是减号（隐藏语义），不是叉（销毁语义）。
        assertTrue(screen.contains("Oe3Icons.Remove"))
    }

    @Test
    fun editGlyphsFollowTheOe3LineFamilyRules() {
        listOf(Oe3Icons.Edit, Oe3Icons.Check, Oe3Icons.Remove).forEach { icon ->
            val paths = pathsOf(icon)
            assertEquals("${icon.name} must be one path", 1, paths.size)
            val path = paths.single()
            assertEquals("${icon.name} must not fill", null, path.fill)
            assertEquals("${icon.name} must be paint-any-colour black", Color.Black, (path.stroke as SolidColor).value)
            assertEquals("${icon.name} line weight", 1.75f, path.strokeLineWidth, 0.0001f)
            assertEquals("${icon.name} cap", StrokeCap.Round, path.strokeLineCap)
            assertEquals("${icon.name} join", StrokeJoin.Round, path.strokeLineJoin)
            assertEquals("${icon.name} viewport width", 24f, icon.viewportWidth, 0.0001f)
            assertEquals("${icon.name} viewport height", 24f, icon.viewportHeight, 0.0001f)
            assertEquals("${icon.name} default size", 24.dp, icon.defaultWidth)
        }
    }

    @Test
    fun editGlyphsStayOutOfThePinnedActionLists() {
        // 图标板金样只钉 navigation/actions 两张列表；编辑态字形独立存在，
        // 不挤进任何会触发共享金样重录的集合。
        val names = (Oe3Icons.navigation + Oe3Icons.actions).map { it.name }
        assertFalse(names.contains("Oe3.Edit"))
        assertFalse(names.contains("Oe3.Check"))
        assertFalse(names.contains("Oe3.Remove"))
    }

    private fun pathsOf(icon: androidx.compose.ui.graphics.vector.ImageVector): List<VectorPath> =
        pathsOf(icon.root)

    private fun pathsOf(group: VectorGroup): List<VectorPath> {
        val result = mutableListOf<VectorPath>()
        for (node: VectorNode in group) {
            when (node) {
                is VectorPath -> result += node
                is VectorGroup -> result += pathsOf(node)
            }
        }
        return result
    }
}
