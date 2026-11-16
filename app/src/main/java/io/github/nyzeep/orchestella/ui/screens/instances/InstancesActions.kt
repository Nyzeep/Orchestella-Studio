package io.github.nyzeep.orchestella.ui.screens.instances

/**
 * 实例页的全部副作用出口。
 *
 * 本工单只交付屏幕，真实实现由 UI3-12 接路由时注入；默认空实现让
 * `@Preview` 与截图用例无需宿主 Activity 即可渲染。
 */
data class InstancesActions(
    /** 打开运行中实例的终端；参数为实例 id。 */
    val openTerminal: (String) -> Unit = {},
    /** 启动已停止/已创建/中断的实例。 */
    val launch: (String) -> Unit = {},
    /** 恢复已挂起实例（InstancePowerController.resume）。 */
    val resume: (String) -> Unit = {},
    /** 进入实例详情或诊断；卡片整体点击走这里。 */
    val openInstance: (String) -> Unit = {},
    /** 进入创建流程。 */
    val createInstance: () -> Unit = {},
    /** 从备份归档导入实例。 */
    val importArchive: () -> Unit = {},
    /** 下拉刷新。 */
    val refresh: () -> Unit = {},
    /** 电源：关机/强制停止运行中或已挂起实例。 */
    val powerOff: (String) -> Unit = {},
    /** 打开图形桌面。 */
    val openDesktop: (String) -> Unit = {},
    /** 查看实例日志。 */
    val openLogs: (String) -> Unit = {},
    /** 导出冷备份。 */
    val exportInstance: (String) -> Unit = {},
    /** 删除实例。 */
    val deleteInstance: (String) -> Unit = {},
)
