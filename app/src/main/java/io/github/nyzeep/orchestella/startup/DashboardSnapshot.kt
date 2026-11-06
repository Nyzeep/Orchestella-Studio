package io.github.nyzeep.orchestella.startup

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle

/**
 * 仪表盘快照（冷启动/主线程卫生，2026-11-07 Lane C）：实例列表、主实例选择、SSH
 * 规则、硬件与存储读数都在后台 executor 一次取齐，UI 线程只消费不可变结果做渲染。
 * 替代原先 onCreate/onResume/每 3 秒刷新里散落的多次主线程磁盘读。
 *
 * <p>纯数据 + 纯选择逻辑，JVM 单测锁定语义（主实例选择、状态指纹）。
 */
data class DashboardSnapshot(
    val instances: List<InstanceLifecycle.Instance>,
    /** 实例 id:state 的拼接指纹；刷新循环用它判断列表是否真的变化，决定重渲染还是轻更新。 */
    val stateKey: String,
    /** 列表读取失败（IOException）。严格模式渲染既有失败文案；周期刷新按空列表继续。 */
    val listFailed: Boolean,
    /** 主实例：RUNNING 优先 → lastActive → 首个；空列表为 null。 */
    val primary: InstanceLifecycle.Instance?,
    /** SSH 规则预取；非 null 表示已在后台取过（command 可为 null=无规则）。 */
    val ssh: SshPrefetch?,
    /** 加速车道就绪判定（facts + policy，与启动派发同源），后台预取。 */
    val acceleratorLaneReady: Boolean,
    val hardwareProfile: HostHardwareProfile,
    val storageTotalBytes: Long,
    val storageFreeBytes: Long,
) {
    /**
     * 哨兵类型：区别「还没取过 SSH 规则」与「取过了，结果是没有规则（command=null）」，
     * 防止迁移回退成主线程读盘。
     */
    data class SshPrefetch(val command: String?)

    companion object {
        /** 主实例选择语义（行为保持）：RUNNING → lastActive → 首个 → null。 */
        fun pickPrimary(
            instances: List<InstanceLifecycle.Instance>,
            lastActiveInstanceId: String?,
        ): InstanceLifecycle.Instance? =
            instances.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
                ?: instances.firstOrNull { it.id() == lastActiveInstanceId }
                ?: instances.firstOrNull()

        /** 刷新循环的变更指纹；顺序稳定、仅含 id 与状态，不含频繁抖动的读数。 */
        fun stateKeyOf(instances: List<InstanceLifecycle.Instance>): String =
            instances.joinToString { "${it.id()}:${it.state()}" }
    }
}
