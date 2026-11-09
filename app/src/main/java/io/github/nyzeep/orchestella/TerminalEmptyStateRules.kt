package io.github.nyzeep.orchestella

import androidx.annotation.StringRes

/**
 * 2026-11-09 批次3 E 工单（T4⑥ 空态半项）：TerminalActivity 直启且无实例 ID
 * 时的空态契约。2026-11-07 D-lane 已落地 terminal_empty_no_instance_* 文案；
 * 本规则把「何时是空态、何时是查无此实例」从 Java Activity 里抽成纯 JVM 决策，
 * 让契约用例能锁定触发条件与文案键，Activity 只负责渲染。
 */
object TerminalEmptyStateRules {

    /** 空态标题：还没有可打开的实例。 */
    @JvmField
    @StringRes
    val EMPTY_STATE_TITLE_RES: Int = R.string.terminal_empty_no_instance_title

    /** 空态正文：请返回实例列表创建或选择一个实例。 */
    @JvmField
    @StringRes
    val EMPTY_STATE_MESSAGE_RES: Int = R.string.terminal_empty_no_instance_message

    /**
     * 直启未携带实例 ID（null 或空串）是空态，不是查询失败——应引导去创建；
     * 携带了 ID 但查不到才是「实例不存在」。与 TerminalActivity 既有判定
     * （id == null || id.isEmpty()）逐字一致，仅抽出为可测纯函数。
     */
    @JvmStatic
    fun isEmptyStateLaunch(instanceId: String?): Boolean = instanceId.isNullOrEmpty()
}
