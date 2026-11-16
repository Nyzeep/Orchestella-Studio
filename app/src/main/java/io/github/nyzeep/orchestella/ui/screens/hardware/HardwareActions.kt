package io.github.nyzeep.orchestella.ui.screens.hardware

/**
 * 硬件页的全部副作用出口。
 *
 * 本工单不实现权限流程：授权向导与手动刷新都由接线方（UI3-12）注入，
 * 默认空实现让 `@Preview` 与语义用例无需宿主 Activity 即可渲染。
 */
data class HardwareActions(
    /** 重新读取硬件事实；探针失败态的唯一出口。 */
    val refresh: () -> Unit = {},
    /** 进入 AVF 授权向导；只在“待授权”状态提供入口。 */
    val requestAvfAuthorization: () -> Unit = {},
)
