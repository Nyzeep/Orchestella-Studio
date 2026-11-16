package io.github.nyzeep.orchestella.ui.screens.hardware

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬件页的结构门与零设备契约（UI3-07）。
 *
 * 单测运行目录是 `app/`，直接读源码与资源即可锁定：必须消费 Oe3 基座、
 * 圆环必须有无障碍描述、底部参数表必须可滚动、预览矩阵必须覆盖
 * 正常/失败 × 暗/亮，且页面源码不得接触任何设备唯一标识或平台探针。
 */
class HardwareScreenContractTest {

    private val sourceRoot = File("src/main/java/io/github/nyzeep/orchestella/ui/screens/hardware")
    private val stringsFile = File("src/main/res/values/strings_ui3_hardware.xml")

    private fun sources(): List<File> {
        assertTrue("missing hardware source root: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .also { assertTrue("expected hardware sources", it.size >= 3) }
    }

    @Test
    fun screenConsumesTheMandatedOe3PrimitivesAndSheet() {
        val screen = File(sourceRoot, "HardwareScreen.kt").readText()

        listOf(
            "Oe3Card(",
            "Oe3SectionHeader(",
            "Oe3Ring(",
            "Oe3EmptyState(",
            "Oe3Button(",
            "ModalBottomSheet(",
            "LazyColumn(",
        ).forEach { token ->
            assertTrue("HardwareScreen must use $token", screen.contains(token))
        }
        // 圆环把数值暴露给无障碍服务，中心数字本身不重复朗读。
        assertTrue(screen.contains("contentDescription = "))
        assertTrue(screen.contains("clearAndSetSemantics"))
        assertTrue(screen.contains("ui3_hardware_ring_description"))
        // 底部参数表可滚动。
        assertTrue(screen.contains("hardware:details-list"))
    }

    @Test
    fun hardwareSourcesNeverProbeTheDeviceOrCarryIdentifiers() {
        val forbidden = listOf(
            "android.os.Build",
            "android.content",
            "StatFs",
            "BatteryManager",
            "TelephonyManager",
            "getSerial",
            "ANDROID_ID",
            "FINGERPRINT",
            "serial",
            "imei",
            "MacAddress",
            "MaterialCardView",
            "strokeWidth",
            "shadowElevation",
            "MainActivity",
            "ui.shell",
        )
        sources().forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse(
                    "${file.name} must not reference '$token'",
                    text.contains(token, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun userFacingCopyStaysInResources() {
        val hardcodedChineseText = Regex("""Text\(\s*text\s*=\s*"[^"]*[\u4e00-\u9fff]""")
        sources().forEach { file ->
            assertFalse(
                "${file.name} must not hardcode user-facing Chinese copy in Text()",
                hardcodedChineseText.containsMatchIn(file.readText()),
            )
        }
    }

    @Test
    fun previewsCoverNormalAndFailureInBothBrightnesses() {
        val previews = File(sourceRoot, "HardwarePreviews.kt").readText()

        assertTrue(Regex("@Preview\\(").findAll(previews).count() >= 4)
        assertTrue(previews.contains("ThemeCatalog.moonshotDark"))
        assertTrue(previews.contains("ThemeCatalog.moonshotLight"))
        assertTrue(previews.contains("HardwareUiState.Failed("))
        assertTrue(previews.contains("previewReady"))
        assertTrue(previews.contains("previewFailed"))
        assertTrue(previews.split("textScale = 1f").size - 1 >= 2)
        assertTrue(previews.split("textScale = 1.3f").size - 1 >= 2)
    }

    @Test
    fun stringsFileCarriesEveryAvfStateAndNoLegacyChrome() {
        assertTrue("missing ${stringsFile.absolutePath}", stringsFile.isFile)

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile)
        val strings = document.getElementsByTagName("string")
        val names = buildSet {
            for (index in 0 until strings.length) {
                add(strings.item(index).attributes.getNamedItem("name").nodeValue)
            }
        }

        listOf(
            "ui3_hardware_avf_ready",
            "ui3_hardware_avf_needs_authorization",
            "ui3_hardware_avf_interface_blocked",
            "ui3_hardware_avf_unsupported",
            "ui3_hardware_avf_unknown",
            "ui3_hardware_ring_description",
            "ui3_hardware_ring_unavailable",
            "ui3_hardware_probe_failed_title",
            "ui3_hardware_probe_failed_body",
            "ui3_hardware_retry",
            "ui3_hardware_details_title",
            "ui3_hardware_details_action",
        ).forEach { key ->
            assertTrue("strings_ui3_hardware.xml is missing $key", names.contains(key))
        }

        for (index in 0 until strings.length) {
            val element = strings.item(index)
            assertTrue(
                "string resource ${element.attributes.getNamedItem("name").nodeValue} must not be blank",
                element.textContent.orEmpty().isNotBlank(),
            )
        }

        val text = stringsFile.readText()
        listOf("01/K", "ORCHESTELLA", "pKVM", "序列号", "IMEI", "Android ID").forEach { token ->
            assertFalse("legacy or sensitive copy '$token' must not return", text.contains(token))
        }
    }

    @Test
    fun specPinsRingEngineAndTouchGeometry() {
        assertEquals(72, HardwareSpec.ringDiameter.value.toInt())
        assertEquals(8, HardwareSpec.ringStroke.value.toInt())
        assertEquals(56, HardwareSpec.engineRowHeight.value.toInt())
        assertEquals(48, HardwareSpec.detailsButtonHeight.value.toInt())
        assertEquals(48, HardwareSpec.detailRowMinHeight.value.toInt())
    }

    @Test
    fun storageRingUsesTheSharedStorageFactsType() {
        val state = File(sourceRoot, "HardwareUiState.kt").readText()
        assertTrue(state.contains("import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts"))
        assertTrue(state.contains("storage: StorageFacts"))
        assertFalse(state.contains("class HardwareStorageFacts"))
        assertFalse(state.contains("data class HardwareStorage"))
    }
}
