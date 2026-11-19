package io.github.nyzeep.orchestella.ui.screens.connections

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
 * G-07 GPU 开关诚实副文案的零设备渲染契约：副文案随
 * [GpuToggleStatus] 三态取词（strings 单源，禁硬编码），开关本体保持纯配置
 * 字段。会话实时状态优先（DISABLED 落回静态）；无会话时按能力声明单源。
 * 向导硬件步的 GPU 标注同票锁定（JVM 无 native 库 → 不可用分支）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h2000dp-mdpi")
class VmHardwareGpuHonestyTest {

    @get:Rule
    val compose = createComposeRule()

    private val gib = 1024L * 1024L * 1024L

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
        compose.onNodeWithTag("connections:vm:gpu").assertExists()
        compose.onNodeWithText("GPU 加速（virgl）").assertExists()
    }

    @Test
    fun nativeUnavailableDisclosesTheTwoDFallback() {
        setContent(nativeAvailable = false, sessionStatus = null)
        compose.onNodeWithText("GPU 加速（驱动未打包，将回退 2D 渲染）").assertExists()
    }

    @Test
    fun activeSessionNamesTheLiveWiring() {
        setContent(nativeAvailable = false, sessionStatus = GpuAccelerationStatus.active(1024))
        compose.onNodeWithText("GPU 加速（virgl · 当前会话已启用）").assertExists()
    }

    @Test
    fun fallbackSessionCarriesTheReason() {
        setContent(
            nativeAvailable = true,
            sessionStatus = GpuAccelerationStatus.fallback(GpuFallbackReason.NATIVE_UNAVAILABLE, 0, 1024),
        )
        compose.onNodeWithText("GPU 加速（当前会话已回退 2D 渲染 · 驱动未打包）").assertExists()
    }

    @Test
    fun initFailedSessionCarriesTheInitReason() {
        setContent(
            nativeAvailable = true,
            sessionStatus = GpuAccelerationStatus.fallback(GpuFallbackReason.INIT_FAILED, -1, 1024),
        )
        compose.onNodeWithText("GPU 加速（当前会话已回退 2D 渲染 · 驱动初始化失败）").assertExists()
    }

    @Test
    fun disabledSessionFallsBackToTheStaticCopy() {
        setContent(nativeAvailable = false, sessionStatus = GpuAccelerationStatus.disabled())
        compose.onNodeWithText("GPU 加速（驱动未打包，将回退 2D 渲染）").assertExists()
    }

    // ---- DW-02：QEMU 设备模型因子进入副文案三态 ----

    @Test
    fun deviceModelUnsupportedStaticCopyDisclosesTheTwoDFallback() {
        setContent(nativeAvailable = true, sessionStatus = null, deviceModelSupported = false)
        compose.onNodeWithTag("connections:vm:gpu").assertExists()
        compose.onNodeWithText("GPU 加速（QEMU 设备模型不支持，将回退 2D 渲染）").assertExists()
    }

    @Test
    fun deviceModelSupportedStaticCopyKeepsTheAvailableClaim() {
        setContent(nativeAvailable = true, sessionStatus = null, deviceModelSupported = true)
        compose.onNodeWithText("GPU 加速（virgl）").assertExists()
    }

    @Test
    fun deviceModelUnsupportedSessionCarriesTheReason() {
        setContent(
            nativeAvailable = true,
            sessionStatus = GpuAccelerationStatus.fallback(
                GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED, 0, 1024,
            ),
        )
        compose.onNodeWithText("GPU 加速（当前会话已回退 2D 渲染 · QEMU 设备模型不支持）")
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
        compose.onNodeWithText("GPU 加速（驱动未打包，创建后开启将回退 2D 渲染）").assertExists()
    }
}
