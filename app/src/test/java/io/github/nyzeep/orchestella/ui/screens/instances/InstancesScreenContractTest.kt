package io.github.nyzeep.orchestella.ui.screens.instances

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实例页的结构门与零设备契约（UI3-06）。
 *
 * 单测运行目录是 `app/`，因此可以直接读源码与资源文件；这样能在没有
 * Compose 测试依赖的前提下锁定“不得复活旧壳元素”“必须消费 Oe3 基座”
 * 与预览矩阵。
 */
class InstancesScreenContractTest {

    private val sourceRoot = File("src/main/java/io/github/nyzeep/orchestella/ui/screens/instances")
    private val stringsFile = File("src/main/res/values/strings_ui3_instances.xml")

    private fun sources(): List<File> {
        assertTrue("missing instances source root: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .also { assertTrue("expected instances sources", it.size >= 4) }
    }

    @Test
    fun instancesSourcesDoNotReviveLegacyChromeOrPixels() {
        val forbidden = listOf(
            "MaterialCardView",
            "strokeWidth",
            "cornerRadius",
            "shadowElevation",
            "BorderStroke",
            "Modifier.border(",
            "RoundedCornerShape(0",
            "RecyclerView",
            "InstanceListAdapter",
            "layout_instance_card",
            "MainActivity",
            // UI-02：v4 把页内标题上移到 ui/shell/OeScaffold，页面允许引用壳层
            // 的页面壳原语；仍然禁止页面碰路由根与分页器（壳层状态归宿主）。
            "ui.shell.Oe3App",
            "ui.shell.Oe3Pages",
            "01/",
            "ORCHESTELLA //",
            "电量",
            "电池",
            "battery",
        )
        sources().forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse(
                    "${file.name} must not reference '$token'",
                    text.contains(token),
                )
            }
        }
    }

    @Test
    fun screenConsumesTheMandatedOe3Primitives() {
        val screen = File(sourceRoot, "InstancesScreen.kt").readText()

        listOf(
            "Oe3Card(",
            "Oe3SectionHeader(",
            "Oe3Chip(",
            "Oe3Button(",
            "Oe3EmptyState(",
            "Oe3DistroIcons.of(",
            "PullToRefreshBox(",
            "InstanceActionsSheet(",
            "InstancesListKeys.item(",
            ".animateItem()",
        ).forEach { token ->
            assertTrue("InstancesScreen must use $token", screen.contains(token))
        }
        // 卡片整体是一个可读的无障碍节点；描述由资源拼接。
        assertTrue(screen.contains("contentDescription = description"))
        assertTrue(screen.contains("ui3_instances_card_description"))
        // 主操作与页头入口都落在唯一 48dp 常量上。
        assertTrue(screen.contains("heightIn(min = InstancesSpec.actionHeight)"))
        assertTrue(screen.contains("heightIn(min = InstancesSpec.headerActionHeight)"))
        // 下拉刷新接注入回调，不自己造刷新循环。
        assertTrue(screen.contains("onRefresh = actions.refresh"))
    }

    @Test
    fun cardsEnterOnTheCardTokenExactlyOnce() {
        val screen = File(sourceRoot, "InstancesScreen.kt").readText()
        // 入场动画是页面消费点（components/card.md：卡原语不做入场动画），
        // 且只在首屏播一次——滚动进来的卡片不得重播。
        assertTrue(screen.contains("Oe3CardEnterMotion("))
        assertTrue(screen.contains("rememberSaveable"))
        // 防回退钉：300ms cardEnter + 0.96 scale-from，reduced 下 0ms / 1f。
        assertEquals(300, Oe3Motion.standard().cardEnter.durationMillis)
        assertEquals(0, Oe3Motion.reduced().cardEnter.durationMillis)
        assertEquals(0.96f, Oe3Motion.standard().cardEnterScaleFrom, 0.0001f)
        assertEquals(1f, Oe3Motion.reduced().cardEnterScaleFrom, 0.0001f)
    }

    @Test
    fun stateMappingDeduplicatesIdsAndIsExhaustiveOverLifecycleStates() {
        val state = File(sourceRoot, "InstancesUiState.kt").readText()

        assertTrue(state.contains("InstanceCardUiModel"))
        assertTrue(state.contains("distinctBy { it.instanceId }"))
        assertTrue(state.contains("InstanceStatus.of(state)"))
        // 新增 InstanceLifecycle.State 必须先补词表，编译器兜底。
        assertTrue(state.contains("when (state) {"))
        listOf(
            "InstanceLifecycle.State.RUNNING",
            "InstanceLifecycle.State.PROVISIONING",
            "InstanceLifecycle.State.STOPPED",
            "InstanceLifecycle.State.SUSPENDED",
            "InstanceLifecycle.State.INTERRUPTED",
            "InstanceLifecycle.State.CRASHED",
            "InstanceLifecycle.State.CREATED",
        ).forEach { token ->
            assertTrue("InstancesUiState must map $token", state.contains(token))
        }
    }

    @Test
    fun instancesSourcesKeepUserFacingCopyInResources() {
        val hardcodedChineseText = Regex("""Text\(\s*text\s*=\s*"[^"]*[\u4e00-\u9fff]""")
        sources().forEach { file ->
            assertFalse(
                "${file.name} must not hardcode user-facing Chinese copy in Text()",
                hardcodedChineseText.containsMatchIn(file.readText()),
            )
        }
    }

    @Test
    fun previewsCoverTwoStatesThreeSurfacesAndTwoScales() {
        val previews = File(sourceRoot, "InstancesPreviews.kt").readText()

        // 票面矩阵是运行中/空态 × 暗/亮；纯黑与 1.3 字号按 spec §6 补齐。
        assertTrue(Regex("@Preview\\(").findAll(previews).count() >= 12)
        assertTrue(previews.split("textScale = 1f").size - 1 >= 6)
        assertTrue(previews.split("textScale = 1.3f").size - 1 >= 6)
        assertTrue(previews.split("pureBlack = true").size - 1 >= 4)
        assertTrue(previews.split("pureBlack = false").size - 1 >= 8)
        assertTrue(previews.split("state = previewWithInstances").size - 1 >= 6)
        assertTrue(previews.split("state = previewEmpty").size - 1 >= 6)
        assertTrue(previews.contains("state = previewWithProgress"))
    }

    @Test
    fun stringsFileCarriesEveryStatusAndGroupLabel() {
        assertTrue("missing ${stringsFile.absolutePath}", stringsFile.isFile)

        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(stringsFile)
        val strings = document.getElementsByTagName("string")
        val names = buildSet {
            for (index in 0 until strings.length) {
                add(strings.item(index).attributes.getNamedItem("name").nodeValue)
            }
        }

        val model = File(sourceRoot, "InstancesUiState.kt").readText()
        InstanceStatus.values().forEach { status ->
            val key = "ui3_instances_status_${status.name.lowercase()}"
            assertTrue("strings_ui3_instances.xml is missing $key", names.contains(key))
            assertTrue("$key must be referenced from the instances model", model.contains("R.string.$key"))
        }
        InstanceGroup.values().forEach { group ->
            val key = "ui3_instances_group_${group.name.lowercase()}"
            assertTrue("strings_ui3_instances.xml is missing $key", names.contains(key))
        }
        InstancePrimaryAction.values().forEach { action ->
            val key = "ui3_instances_action_${action.name.lowercase()}"
            assertTrue("strings_ui3_instances.xml is missing $key", names.contains(key))
            assertTrue("$key must be referenced from the instances model", model.contains("R.string.$key"))
        }
        for (index in 0 until strings.length) {
            val element = strings.item(index)
            val key = element.attributes.getNamedItem("name").nodeValue
            assertTrue(
                "string resource $key must not be blank",
                element.textContent.orEmpty().isNotBlank(),
            )
        }
        listOf(
            "ui3_instances_title",
            "ui3_instances_create",
            "ui3_instances_import",
            "ui3_instances_action_terminal",
            "ui3_instances_action_launch",
            "ui3_instances_action_more",
            "ui3_instances_card_description",
            "ui3_instances_sheet_delete",
            "ui3_instances_empty_title",
            "ui3_instances_empty_body",
            "ui3_instances_empty_cta",
            "ui3_instances_group_count",
            "ui3_instances_progress_description",
        ).forEach { key ->
            assertTrue("strings_ui3_instances.xml is missing $key", names.contains(key))
        }

        val text = stringsFile.readText()
        listOf("电量", "电池", "ORCHESTELLA", "01/", "REPOSITORIES").forEach { token ->
            assertFalse("legacy chrome copy '$token' must not return", text.contains(token))
        }
    }

    @Test
    fun specPinsTouchTargetsAndProgressGeometry() {
        assertEquals(48, InstancesSpec.headerActionHeight.value.toInt())
        assertEquals(48, InstancesSpec.actionHeight.value.toInt())
        assertEquals(48, InstancesSpec.distroWell.value.toInt())
        assertEquals(4, InstancesSpec.progressBarHeight.value.toInt())
    }
}
