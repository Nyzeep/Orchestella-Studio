package io.github.nyzeep.orchestella.ui.screens.distros

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发行版页的结构门：只消费 Oe3 基座、不复活旧壳元素、预览矩阵与几何常量
 * 都被钉住。单测运行目录是 `app/`，因此直接读源码与资源文件。
 */
class DistrosScreenContractTest {

    private val sourceRoot = File("src/main/java/io/github/nyzeep/orchestella/ui/screens/distros")
    private val stringsFile = File("src/main/res/values/strings_ui3_distros.xml")

    private fun sources(): List<File> {
        assertTrue("missing distros source root: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .also { assertTrue("expected distros sources", it.size >= 4) }
    }

    @Test
    fun distrosSourcesDoNotReviveLegacyChromeOrNewPixels() {
        val forbidden = listOf(
            "MaterialCardView",
            "Modifier.border(",
            "elevation(",
            "androidx.compose.material3.Card",
            "shadowElevation",
            "MainActivity",
            // UI-02：v4 把页内标题上移到 ui/shell/OeScaffold，页面允许引用壳层
            // 的页面壳原语；仍然禁止页面碰路由根与分页器（壳层状态归宿主）。
            "ui.shell.Oe3App",
            "ui.shell.Oe3Pages",
            "DistroIconHelper",
            "01/",
            "ORCHESTELLA //",
        )
        sources().forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("${file.name} must not reference '$token'", text.contains(token))
            }
        }
    }

    @Test
    fun screenConsumesTheMandatedOe3Primitives() {
        val screen = File(sourceRoot, "DistrosScreen.kt").readText()

        listOf(
            "LazyVerticalGrid(",
            "GridCells.Fixed(DistrosSpec.GRID_COLUMNS)",
            "Oe3GridCard(",
            "Oe3Chip(",
            "Oe3Button(",
            "Oe3Card(",
            "Oe3EmptyState(",
            "Oe3SectionHeader(",
            "Oe3DistroIcons.of(",
            "rememberSaveable",
            "AnimatedVisibility(",
            "motion.pageFade.durationMillis",
        ).forEach { token ->
            assertTrue("DistrosScreen must use $token", screen.contains(token))
        }
        // 展开状态必须暴露给无障碍服务。
        assertTrue(screen.contains("stateDescription = stateLabel"))
        assertTrue(screen.contains("R.string.ui3_distros_state_expanded"))
        // 安装按钮的 48dp 下限来自唯一的 DistrosSpec 常量。
        assertTrue(screen.contains("heightIn(min = DistrosSpec.installButtonHeight)"))
    }

    @Test
    fun gridCardKeepsTheCompactInsetAndInheritsTheSharedCorner() {
        val source = File(
            "src/main/java/io/github/nyzeep/orchestella/ui/oe3/components/Oe3Surfaces.kt",
        ).readText()

        val gridCardBody = source.substringAfter("fun Oe3GridCard(").substringBefore("\n/**")
        assertTrue("Oe3GridCard must keep 12dp padding", gridCardBody.contains("Oe3Spacing.sm"))
        assertTrue("Oe3GridCard must use the shared card surface", gridCardBody.contains("Oe3Card("))
        assertFalse("Oe3GridCard must inherit the preferred card corner", gridCardBody.contains("shape ="))
    }

    @Test
    fun animationFollowsTheMotionTokenAt220Millis() {
        val screen = File(sourceRoot, "DistrosScreen.kt").readText()
        assertTrue(screen.contains("val animationMillis = motion.pageFade.durationMillis"))
        assertEquals(220, DistrosSpec.ACCORDION_MILLIS)
        assertEquals(DistrosSpec.ACCORDION_MILLIS, Oe3Motion.standard().pageFade.durationMillis)
        assertEquals(0, Oe3Motion.reduced().pageFade.durationMillis)
    }

    @Test
    fun umlMappingCoversEveryPhase() {
        val state = File(sourceRoot, "DistrosUiState.kt").readText()
        listOf("DISABLED", "NOT_READY", "DOWNLOADING", "READY", "FAILED").forEach { phase ->
            assertTrue("UmlRootfsPhase must map $phase", state.contains("UmlRootfsCardState.Phase.$phase"))
        }
    }

    @Test
    fun distrosSourcesKeepUserFacingCopyInResources() {
        val hardcodedChineseText = Regex("""Text\(\s*text\s*=\s*"[^"]*[\u4e00-\u9fff]""")
        sources().forEach { file ->
            assertFalse(
                "${file.name} must not hardcode user-facing Chinese copy in Text()",
                hardcodedChineseText.containsMatchIn(file.readText()),
            )
        }
    }

    @Test
    fun previewsCoverCollapsedExpandedAndEmptyInDark() {
        val previews = File(sourceRoot, "DistrosPreviews.kt").readText()

        assertEquals(3, Regex("@Preview\\(").findAll(previews).count())
        assertTrue(previews.contains("Collapsed · Dark"))
        assertTrue(previews.contains("Expanded · Dark"))
        assertTrue(previews.contains("Empty · Dark"))
        assertEquals(3, previews.split("ThemeCatalog.moonshotDark").size - 1)
        assertTrue(previews.contains("initialExpandedId = \"ubuntu\""))
    }

    @Test
    fun specPinsGridIconAndTouchTarget() {
        assertEquals(2, DistrosSpec.GRID_COLUMNS)
        assertEquals(32, DistrosSpec.iconSize.value.toInt())
        assertEquals(48, DistrosSpec.installButtonHeight.value.toInt())
        assertEquals(4, DistrosSpec.progressHeight.value.toInt())
    }

    @Test
    fun stringsFileCarriesEveryCapabilityDescriptionAndLabel() {
        assertTrue("missing ${stringsFile.absolutePath}", stringsFile.isFile)

        val factory = DocumentBuilderFactory.newInstance()
        val document = factory.newDocumentBuilder().parse(stringsFile)
        val strings = document.getElementsByTagName("string")
        val names = buildSet {
            for (index in 0 until strings.length) {
                add(strings.item(index).attributes.getNamedItem("name").nodeValue)
            }
        }

        DistroCapability.values().forEach { capability ->
            val key = when (capability) {
                DistroCapability.Lts -> "ui3_distros_capability_lts"
                DistroCapability.Rolling -> "ui3_distros_capability_rolling"
                DistroCapability.Minimal -> "ui3_distros_capability_minimal"
                DistroCapability.Current -> "ui3_distros_capability_current"
                DistroCapability.Enterprise -> "ui3_distros_capability_enterprise"
            }
            assertTrue("strings_ui3_distros.xml is missing $key", names.contains(key))
        }

        listOf(
            "ui3_distros_title",
            "ui3_distros_meta_line",
            "ui3_distros_install",
            "ui3_distros_card_meta",
            "ui3_distros_state_expanded",
            "ui3_distros_state_collapsed",
            "ui3_distros_description_ubuntu",
            "ui3_distros_description_debian",
            "ui3_distros_description_fedora",
            "ui3_distros_description_arch",
            "ui3_distros_description_opensuse",
            "ui3_distros_description_almalinux",
            "ui3_distros_description_rocky",
            "ui3_distros_empty_title",
            "ui3_distros_empty_body",
            "ui3_distros_uml_section",
            "ui3_distros_uml_title",
        ).forEach { key ->
            assertTrue("strings_ui3_distros.xml is missing $key", names.contains(key))
        }

        for (index in 0 until strings.length) {
            val element = strings.item(index)
            val key = element.attributes.getNamedItem("name").nodeValue
            assertTrue("string resource $key must not be blank", element.textContent.orEmpty().isNotBlank())
        }

        val text = stringsFile.readText()
        listOf("电量", "电池", "ORCHESTELLA", "01/").forEach { token ->
            assertFalse("legacy chrome copy '$token' must not return", text.contains(token))
        }
    }
}
