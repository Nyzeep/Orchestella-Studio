package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import java.io.IOException

/**
 * 冷备份导出的共享闸门（UI3-12 去重）。
 *
 * 规则本体收敛在 [canExport]：只有完全关机且无运行会话、无在途启动的实例
 * 才允许导出完整冷备份（运行中/装配中/已挂起一律拒绝）。此前
 * MainActivity 与 SettingsActivity 各持一份逐字相同的私有实现、
 * InstanceDetailActivity 的 InstanceDetailActionRules 持有同一谓词的集合
 * 表达，三处并立；现在全部指向这一个事实源。
 *
 * 错误文案保持各调用方自己的既有资源串（主界面与配置页措辞不同是既有
 * 事实），闸门只负责判定与装载，不发明新的拒绝理由。
 */
object ColdBackupGuard {

    /**
     * @param hasSession `ExecutionSessionRegistry.get(id) != null`
     * @param launching  `InstanceLaunchGuard.isInFlight(id)`
     */
    fun canExport(
        state: InstanceLifecycle.State,
        hasSession: Boolean,
        launching: Boolean,
    ): Boolean = !hasSession && !launching && state in EXPORTABLE_STATES

    /**
     * 装载并校验一个可冷备份的实例；不可导出时抛 [IOException]，消息由
     * 调用方传入的既有资源串（或旧壳原文）决定。
     */
    fun requireExportable(
        instanceId: String,
        store: InstanceLifecycle.FileStore,
        refusalMessage: String,
    ): InstanceLifecycle.Instance {
        val current = store.load(instanceId) ?: throw IOException("实例已不存在")
        if (!canExport(
                current.state(),
                hasSession = ExecutionSessionRegistry.get(instanceId) != null,
                launching = InstanceLaunchGuard.isInFlight(instanceId),
            )
        ) {
            throw IOException(refusalMessage)
        }
        return current
    }

    private val EXPORTABLE_STATES = setOf(
        InstanceLifecycle.State.STOPPED,
        InstanceLifecycle.State.CREATED,
        InstanceLifecycle.State.CRASHED,
        InstanceLifecycle.State.INTERRUPTED,
    )
}
