package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuAccelerationStatus
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuFallbackReason
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.screens.wizard.HardwareStepContent
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardPreviewStates
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreenModelStub
import io.github.nyzeep.orchestella.ui.screens.wizard.wizardGpuNoteRes
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * GPU 开关与硬件步提示反映打包版本的探测结论。会话回退副文案显示真实原因，
 * 缺少静态前置条件时开关禁用并保持关闭。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h2000dp-mdpi")
class VmHardwareGpuHonestyTest {

    @get:Rule
    val compose = createComposeRule()

    private val gib = 1024L * 1024L * 1024L

    private fun gpuToggle() = compose.onNode(
        isToggleable() and hasAnyAncestor(hasTestTag("connections:vm:gpu")),
    )

    private fun setContent(
        nativeAvailable: Boolean,
        sessionStatus: GpuAccelerationStatus?,
        deviceModelSupported: Boolean = true,
    ) {
        val profile = HostHardwareProfile(
            8,
            8L * gib,
            4L * gib,
            512L * 1024L * 1024L,
            false,
        )
        val state = VmHardwarePageState(
            VmHardwareConfig(2, 2048, 8L * gib, true, null),
            profile,
            gpuNativeAvailable = nativeAvailable,
            gpuDeviceModelSupported = deviceModelSupported,
            gpuSessionStatus = sessionStatus,
        )
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                VmHardwareContent(
                    state = state,
                    onBack = {},
                    onCpuChange = {},
                    onRamChange = {},
                    onDiskChange = {},
                    onGpuChange = {},
                    onSave = {},
                )
            }
        }
    }

    @Test
    fun nativeAvailableDeclaresVirgl() {
        setContent(nativeAvailable = true, sessionStatus = null)
        gpuToggle().assertIsEnabled().assertIsOn()
        compose.onNodeWithText("检测到 VirGL 驱动和 QEMU 3D 设备模型").assertExists()
    }

    @Test
    fun nativeUnavailableDisclosesTheTwoDFallback() {
        setContent(nativeAvailable = false, sessionStatus = null)
        gpuToggle().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("VirGL 驱动不可用，桌面将使用 2D 渲染").assertExists()
    }

    @Test
    fun activeSessionNamesTheLiveWiring() {
        setContent(nativeAvailable = false, sessionStatus = GpuAccelerationStatus.active(1024))
        compose.onNodeWithText("VirGL 设备已接线，实际 3D 渲染未验证").assertExists()
    }

    @Test
    fun fallbackSessionCarriesTheReason() {
        setContent(
            nativeAvailable = true,
            sessionStatus = GpuAccelerationStatus.fallback(GpuFallbackReason.NATIVE_UNAVAILABLE, 0, 1024),
        )
        compose.onNodeWithText("当前会话使用 2D 渲染 · 驱动未打包").assertExists()
    }

    @Test
    fun initFailedSessionCarriesTheInitReason() {
        setContent(
            nativeAvailable = true,
            sessionStatus = GpuAccelerationStatus.fallback(GpuFallbackReason.INIT_FAILED, -1, 1024),
        )
        compose.onNodeWithText("当前会话使用 2D 渲染 · 驱动初始化失败").assertExists()
    }

    @Test
    fun disabledSessionFallsBackToTheStaticCopy() {
        setContent(nativeAvailable = false, sessionStatus = GpuAccelerationStatus.disabled())
        gpuToggle().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("VirGL 驱动不可用，桌面将使用 2D 渲染").assertExists()
    }

    // ---- DW-02：QEMU 设备模型因子进入副文案三态 ----

    @Test
    fun deviceModelUnsupportedStaticCopyDisclosesTheTwoDFallback() {
        setContent(nativeAvailable = true, sessionStatus = null, deviceModelSupported = false)
        gpuToggle().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("打包 QEMU 缺少 3D 设备模型，桌面将使用 2D 渲染").assertExists()
    }

    @Test
    fun deviceModelSupportedStaticCopyNamesThePrerequisites() {
        setContent(nativeAvailable = true, sessionStatus = null, deviceModelSupported = true)
        compose.onNodeWithText("检测到 VirGL 驱动和 QEMU 3D 设备模型").assertExists()
    }

    @Test
    fun deviceModelUnsupportedSessionCarriesTheReason() {
        setContent(
            nativeAvailable = true,
            sessionStatus = GpuAccelerationStatus.fallback(
                GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED, 0, 1024,
            ),
        )
        gpuToggle().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("当前会话使用 2D 渲染 · QEMU 设备模型不支持")
            .assertExists()
    }

    @Test
    fun wizardGpuNoteResFollowsTheRealChainThreeStates() {
        // strings 单源三态映射：available() 才许诺可用；驱动缺失优先；设备模型缺失有专名。
        assertEquals(
            R.string.wizard_gpu_note_available,
            wizardGpuNoteRes(
                io.github.nyzeep.orchestella.runtime.capability.GpuCapabilityEvaluator
                    .evaluate(true, true),
            ),
        )
        assertEquals(
            R.string.wizard_gpu_note_unavailable,
            wizardGpuNoteRes(
                io.github.nyzeep.orchestella.runtime.capability.GpuCapabilityEvaluator
                    .evaluate(false, true),
            ),
        )
        assertEquals(
            R.string.wizard_gpu_note_device_model_unsupported,
            wizardGpuNoteRes(
                io.github.nyzeep.orchestella.runtime.capability.GpuCapabilityEvaluator
                    .evaluate(true, false),
            ),
        )
    }

    @Test
    fun wizardHardwareStepShowsTheUnavailableGpuNoteWithoutNative() {
        // JVM/Robolectric 没有 libvirglrenderer.so：能力声明单源必须判不可用。
        // （设备模型探测在 Robolectric 无真二进制可探，GpuDeviceModelProbeOwner
        // 保守落 false；native 缺失的优先级更高，仍走驱动未打包分支。）
        val state = WizardPreviewStates.hardware
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                HardwareStepContent(state = state, controller = WizardScreenModelStub(state))
            }
        }
        compose.onNodeWithTag("wizard:gpu-note").assertExists()
        compose.onNodeWithText("VirGL 驱动不可用，桌面与图形将使用 2D 渲染").assertExists()
    }
}
