package io.github.nyzeep.orchestella.ui.screens.wizard

import android.net.Uri
import androidx.compose.runtime.State
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract

/**
 * 向导界面的宿主接缝（UI-12）：[WizardScreen] 只依赖本接口——真实引擎
 * [WizardController] 与预览/截图桩共用同一接缝，界面渲染不触碰探测链。
 *
 * 状态全部是 Compose 快照 [State]；命令即模型 mutator 的编排入口。
 */
interface WizardScreenModel {

    val state: State<WizardUiState>

    /** 创建门禁的身份校验结论（视图按字段渲染行内错误）。 */
    val identityError: State<WizardController.IdentityError?>

    /** AVF 授权引导的对话框相位（旧 5 处平台 AlertDialog 的去向）。 */
    val authDialog: State<WizardController.AuthDialog>

    /** 自定义磁盘输入的原文（模型只收合法值；非法输入保留在输入框）。 */
    val diskInputText: String

    // ---- 存储与挂载 ----

    fun selectDistro(distroId: String)
    fun selectDiskPreset(gb: Int)
    fun onCustomDiskInput(raw: String)
    fun onSharedDirToggled(checked: Boolean)
    fun onSharedDirPicked(uri: Uri?)
    fun revalidateStorage()

    // ---- 网络与镜像源 ----

    fun selectMirror(ordinal: Int)
    fun runMirrorSpeedTest()
    fun testNetworkConnectivity()

    // ---- 硬件配额 ----

    fun onCpuCoresChange(cores: Int)
    fun onRamMbChange(mb: Int)
    fun selectExecutionStrategy(strategy: FullLinuxContract.ExecutionStrategy?) = Unit
    fun toggleAvfDetails()
    fun openAvfAuthorization()

    // ---- 启动任务与部署 ----

    fun onIdentityChange(username: String, hostname: String)
    fun onSudoChange(enabled: Boolean)
    fun onOptionalProfileChange(desktop: Boolean, podman: Boolean, editor: Boolean)
    fun onAutoUpdateChange(enabled: Boolean)
    fun clearIdentityError()
    fun startCreation()

    // ---- 步进 ----

    fun goBack()
    fun goNext()

    // ---- AVF 授权引导对话框命令 ----

    fun authUseShizukuClient()
    fun authChooseAdbFallback()
    fun authBackToShizuku()
    fun authCopyCommand()
    fun authDone()
    fun dismissAuthDialog()

    /** 恢复提示一次性消费（旧 draftNotice Toast 语义）。 */
    fun consumeDraftNotice(): String?
}
