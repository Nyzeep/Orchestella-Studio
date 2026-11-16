package io.github.nyzeep.orchestella.ui.screens.distros

/**
 * 发行版页的全部副作用出口。
 *
 * 本工单只交付屏幕，真实实现由 UI3-12 接线时注入：发行版 CTA 复用既有的
 * `create_*` 装配入口，UML CTA 复用 `startUmlRootfsProvision`。默认空实现
 * 让 `@Preview` 与截图用例无需宿主 Activity 即可渲染。
 */
data class DistrosActions(
    /** 装配指定发行版实例；参数为目录 id（如 `ubuntu`）。 */
    val install: (String) -> Unit = {},
    /** 下载/校验/解包 UML rootfs；状态与进度仍由既有状态机驱动。 */
    val provisionUmlRootfs: () -> Unit = {},
)
