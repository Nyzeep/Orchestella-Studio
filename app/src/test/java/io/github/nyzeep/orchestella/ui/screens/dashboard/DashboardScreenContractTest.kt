package io.github.nyzeep.orchestella.ui.screens.dashboard

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作站页的结构门与零设备语义断言。
 *
 * 单测运行目录是 `app/`，因此可以直接读源码与资源文件；这样能在没有
 * Compose 测试依赖的前提下锁定“不得复活旧壳元素”“必须消费 Oe3 基座”
 * 与预览矩阵。
 */
class DashboardScreenContractTest {

    private val sourceRoot = File("src/main/java/io/github/nyzeep/orchestella/ui/screens/dashboard")
    private val stringsFile = File("src/main/res/values/strings_ui3_dashboard.xml")

    private fun sources(): List<File> {
        assertTrue("missing dashboard source root: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .also { assertTrue("expected dashboard sources", it.size >= 4) }
    }

    @Test
    fun dashboardSourcesDoNotReviveLegacyChromeOrNewPixels() {
        val forbidden = listOf(
            "MaterialCardView",
            "strokeWidth",
            "shadowElevation",
            "01/",
            "ORCHESTELLA //",
            "电量",
            "电池",
            "battery",
            "StatFs",
            "MainActivity",
            "ui.shell",
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
        val screen = File(sourceRoot, "DashboardScreen.kt").readText()

        listOf(
            "Oe3Card(",
            "Oe3SectionHeader(",
            "Oe3Ring(",
            "Oe3DistroIcons.of(",
            "Oe3EmptyState(",
            "Oe3Button(",
            "Oe3ListRow(",
            "instance.status.labelRes",
        ).forEach { token ->
            assertTrue("DashboardScreen must use $token", screen.contains(token))
        }
        // 圆环必须把数值暴露给无障碍服务。
        assertTrue(screen.contains("contentDescription = stringResource("))
        assertTrue(screen.contains("ui3_dashboard_storage_ring_description"))
        // 主按钮的 48dp 下限来自唯一的 DashboardSpec 常量。
        assertTrue(screen.contains("heightIn(min = DashboardSpec.terminalButtonHeight)"))
    }

    @Test
    fun dashboardSourcesKeepUserFacingCopyInResources() {
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
        val previews = File(sourceRoot, "DashboardPreviews.kt").readText()

        // 票面矩阵是 2 状态 × 3 主题 × 2 字号；额外 pane（停止/异常主卡）只增不减。
        assertTrue(Regex("@Preview\\(").findAll(previews).count() >= 12)
        assertTrue(previews.split("textScale = 1f").size - 1 >= 6)
        assertTrue(previews.split("textScale = 1.3f").size - 1 >= 6)
        assertTrue(previews.split("pureBlack = true").size - 1 >= 4)
        assertTrue(previews.split("pureBlack = false").size - 1 >= 8)
        assertTrue(previews.split("state = previewWithInstances").size - 1 >= 6)
        assertTrue(previews.split("state = previewWithoutInstances").size - 1 >= 6)
    }

    @Test
    fun stringsFileCarriesEveryStatusLabelAndNoLegacyCopy() {
        assertTrue("missing ${stringsFile.absolutePath}", stringsFile.isFile)

        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(stringsFile)
        val strings = document.getElementsByTagName("string")
        val names = buildSet {
            for (index in 0 until strings.length) {
                add(strings.item(index).attributes.getNamedItem("name").nodeValue)
            }
        }

        // 每个状态词都必须有非空文案，且被模型层的 R.string.<key> 引用。
        val model = File(sourceRoot, "DashboardUiState.kt").readText()
        DashboardStatus.values().forEach { status ->
            val key = "ui3_dashboard_status_${status.name.lowercase()}"
            assertTrue("strings_ui3_dashboard.xml is missing $key", names.contains(key))
            assertTrue("$key must be referenced from the dashboard model", model.contains("R.string.$key"))
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
            "ui3_dashboard_title",
            "ui3_dashboard_open_terminal",
            "ui3_dashboard_create_instance",
            "ui3_dashboard_section_storage",
            "ui3_dashboard_section_metrics",
            "ui3_dashboard_section_recent",
            "ui3_dashboard_metric_refresh",
            "ui3_dashboard_storage_ring_description",
        ).forEach { key ->
            assertTrue("strings_ui3_dashboard.xml is missing $key", names.contains(key))
        }

        val text = stringsFile.readText()
        listOf("电量", "电池", "ORCHESTELLA", "01/").forEach { token ->
            assertFalse("legacy chrome copy '$token' must not return", text.contains(token))
        }
    }

    @Test
    fun specPinsTouchTargetRingAndRefreshCadence() {
        assertEquals(48, DashboardSpec.terminalButtonHeight.value.toInt())
        assertEquals(120, DashboardSpec.ringDiameter.value.toInt())
        assertEquals(12, DashboardSpec.ringStroke.value.toInt())
        assertEquals(3, DashboardSpec.refreshSeconds)
    }

    @Test
    fun gigabytesReadoutStaysCompactAndDeterministic() {
        assertEquals("0 GB", formatGigabytes(0L))
        assertEquals("2 GB", formatGigabytes(2L * 1024L * 1024L * 1024L))
        assertEquals("128 GB", formatGigabytes(128L * 1024L * 1024L * 1024L))
        assertEquals(
            "66.1 GB",
            formatGigabytes((66.1 * 1024.0 * 1024.0 * 1024.0).toLong()),
        )
        assertEquals("0 GB", formatGigabytes(-5L))
    }
}
