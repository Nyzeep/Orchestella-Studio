package io.github.nyzeep.orchestella.ui.screens.dashboard

/**
 * 工作站页的全部副作用出口。
 *
 * 本工单只交付屏幕，真实实现由 UI3-12 在接路由时注入；默认空实现让
 * `@Preview` 与截图用例无需宿主 Activity 即可渲染。
 */
data class DashboardActions(
    /** 打开主实例终端；参数为实例 id。 */
    val openTerminal: (String) -> Unit = {},
    /** 进入创建实例流程。 */
    val createInstance: () -> Unit = {},
    /** 进入实例详情；参数为实例 id。 */
    val openInstance: (String) -> Unit = {},
    /** 打开存储设置。 */
    val openStorageSettings: () -> Unit = {},
)
