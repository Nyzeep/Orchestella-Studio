package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuAccelerationStatus
import io.github.nyzeep.orchestella.runtime.engineqemu.GpuFallbackReason
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.screens.wizard.HardwareStepContent
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardPreviewStates
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreenModelStub
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
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

    @Test
    fun wizardHardwareStepShowsTheUnavailableGpuNoteWithoutNative() {
        // JVM/Robolectric 没有 libvirglrenderer.so：能力声明单源必须判不可用。
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
