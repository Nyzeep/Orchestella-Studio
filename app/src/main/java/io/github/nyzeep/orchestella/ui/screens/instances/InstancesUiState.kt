package io.github.nyzeep.orchestella.ui.screens.instances

import androidx.annotation.StringRes
import io.github.nyzeep.orchestella.InstanceCardUiModel
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons

/**
 * 实例页的纯数据状态。
 *
 * 页面不读 Context、不测量、不推断引擎：[from] 只把既有的
 * [InstanceCardUiModel]（单实例展示事实，与旧列表同一份数据）折算成四组
 * 渲染输入。引擎是设备级事实（同一时刻只有一个运行实例），文案由接线方按
 * 当前启动策略传入，空白即不显示，不猜一个引擎。
 */
data class InstancesUiState(
    val groups: List<InstanceGroupUi>,
    val totalCount: Int,
    val runningCount: Int,
    val progress: InstancesProgressUi? = null,
    val isRefreshing: Boolean = false,
) {
    val hasInstances: Boolean get() = totalCount > 0

    companion object {
        /** 固定组序：运行中 → 装配中 → 已停止 → 异常。 */
        fun from(
            cards: List<InstanceCardUiModel>,
            engineLabel: String? = null,
            progress: InstancesProgressUi? = null,
            isRefreshing: Boolean = false,
        ): InstancesUiState {
            val engine = engineLabel?.trim()?.takeIf { it.isNotEmpty() }
            // 同一实例在快照切换期间可能重复出现；卡片模型不带时间戳，无法
            // 判断哪份更新，按调用方给的快照顺序保留第一份，key 才唯一。
            val rows = cards
                .distinctBy { it.instanceId }
                .map { it.toRow(engine) }
            val byGroup = rows.groupBy { it.status.group }
            val groups = InstanceGroup.entries.mapNotNull { kind ->
                byGroup[kind]?.let { items -> InstanceGroupUi(kind = kind, items = items) }
            }
            return InstancesUiState(
                groups = groups,
                totalCount = rows.size,
                runningCount = rows.count { it.status == InstanceStatus.Running },
                progress = progress,
                isRefreshing = isRefreshing,
            )
        }

        private fun InstanceCardUiModel.toRow(engineLabel: String?): InstanceRowUi {
            val status = InstanceStatus.of(state)
            return InstanceRowUi(
                id = instanceId,
                shortId = shortId,
                distroId = distroId,
                name = displayName(),
                status = status,
                engineLabel = engineLabel,
                specsText = hardwareSpecsText,
                ipAddress = realIpOrNull(ipAddress),
                canOpenTerminal = canOpenTerminal,
                canLaunch = canLaunch,
                canLaunchDesktop = canLaunchDesktop,
                // InstanceLifecycle 的合法转移允许从 RUNNING/PROVISIONING/
                // SUSPENDED/CRASHED/INTERRUPTED 直接落到 STOPPED；STOPPED
                // 再关机是空操作，CREATED 不能直接关机。
                canPowerOff = when (state) {
                    InstanceLifecycle.State.STOPPED,
                    InstanceLifecycle.State.CREATED,
                    -> false
                    InstanceLifecycle.State.RUNNING,
                    InstanceLifecycle.State.PROVISIONING,
                    InstanceLifecycle.State.SUSPENDED,
                    InstanceLifecycle.State.CRASHED,
                    InstanceLifecycle.State.INTERRUPTED,
                    -> true
                },
                // CREATED 从未启动过，没有可读日志。
                canShowLogs = state != InstanceLifecycle.State.CREATED,
                // 与 legacy requireColdBackup 一致：运行中/装配中/已挂起不可冷备份。
                canExport = when (state) {
                    InstanceLifecycle.State.RUNNING,
                    InstanceLifecycle.State.PROVISIONING,
                    InstanceLifecycle.State.SUSPENDED,
                    -> false
                    InstanceLifecycle.State.STOPPED,
                    InstanceLifecycle.State.CREATED,
                    InstanceLifecycle.State.CRASHED,
                    InstanceLifecycle.State.INTERRUPTED,
                    -> true
                },
                canDelete = canDelete,
            )
        }

        private fun InstanceCardUiModel.displayName(): String {
            val display = Oe3DistroIcons.brandOf(distroId)?.displayName ?: distroDisplayName
            val version = distroVersion.trim()
            return if (version.isEmpty()) display else "$display $version"
        }

        /**
         * [InstanceCardUiModel.ipAddress] 在没有 guest IP 时会写“离线/运行中”
         * 哨兵值。只放行看起来像 IPv4/IPv6 的字符串，别把状态词当地址渲染。
         */
        private fun realIpOrNull(raw: String): String? {
            val value = raw.trim()
            if (value.isEmpty() || value.any(Char::isWhitespace)) {
                return null
            }
            val looksLikeAddress = value.count { it == '.' } == 3 || value.contains(':')
            return value.takeIf { looksLikeAddress }
        }
    }
}

/**
 * 列表 key 的唯一来源。组头用固定前缀，条目 key 就是实例 id（UUID），
 * 与票面“组内 item key = instance id”一致；id 重复会在 [InstancesUiState.from]
 * 去重，因此组头与条目不会相撞。
 */
object InstancesListKeys {
    private const val GROUP_PREFIX = "instances:group:"

    fun group(kind: InstanceGroup): String = GROUP_PREFIX + kind.name.lowercase()

    fun item(id: String): String = id
}

/** 四组；[titleRes] 是组标题文字，计数由页面拼接。 */
enum class InstanceGroup(@StringRes val titleRes: Int) {
    Running(R.string.ui3_instances_group_running),
    Provisioning(R.string.ui3_instances_group_provisioning),
    Stopped(R.string.ui3_instances_group_stopped),
    Error(R.string.ui3_instances_group_error),
}

/** 状态 chip 的语义权重；颜色只是辅助，文字始终表达状态。 */
enum class InstanceStatusTone { Active, Busy, Idle, Error }

/**
 * 实例页的七个状态词，每个都带字符串资源。分组是展示事实，不是另立一套
 * 生命周期：[of] 用 `when` 穷举 [InstanceLifecycle.State]，新增状态会先编译失败。
 */
enum class InstanceStatus(
    @StringRes val labelRes: Int,
    val tone: InstanceStatusTone,
    val group: InstanceGroup,
) {
    Running(R.string.ui3_instances_status_running, InstanceStatusTone.Active, InstanceGroup.Running),
    Provisioning(R.string.ui3_instances_status_provisioning, InstanceStatusTone.Busy, InstanceGroup.Provisioning),
    Stopped(R.string.ui3_instances_status_stopped, InstanceStatusTone.Idle, InstanceGroup.Stopped),
    Suspended(R.string.ui3_instances_status_suspended, InstanceStatusTone.Idle, InstanceGroup.Stopped),
    Interrupted(R.string.ui3_instances_status_interrupted, InstanceStatusTone.Error, InstanceGroup.Error),
    Crashed(R.string.ui3_instances_status_crashed, InstanceStatusTone.Error, InstanceGroup.Error),
    Created(R.string.ui3_instances_status_created, InstanceStatusTone.Idle, InstanceGroup.Stopped);

    companion object {
        fun of(state: InstanceLifecycle.State): InstanceStatus = when (state) {
            InstanceLifecycle.State.RUNNING -> Running
            InstanceLifecycle.State.PROVISIONING -> Provisioning
            InstanceLifecycle.State.STOPPED -> Stopped
            InstanceLifecycle.State.SUSPENDED -> Suspended
            InstanceLifecycle.State.INTERRUPTED -> Interrupted
            InstanceLifecycle.State.CRASHED -> Crashed
            InstanceLifecycle.State.CREATED -> Created
        }
    }
}

/** 卡片主操作：运行中进终端，已挂起继续，其余状态尝试启动。 */
enum class InstancePrimaryAction(@StringRes val labelRes: Int) {
    Terminal(R.string.ui3_instances_action_terminal),
    Launch(R.string.ui3_instances_action_launch),
    Resume(R.string.ui3_instances_action_resume),
}

/**
 * 单实例行的渲染事实。能力位来自 [InstanceCardUiModel] 的既有规则，
 * 页面不再二次推断。
 */
data class InstanceRowUi(
    val id: String,
    val shortId: String,
    val distroId: String,
    val name: String,
    val status: InstanceStatus,
    val engineLabel: String?,
    val specsText: String,
    val ipAddress: String?,
    val canOpenTerminal: Boolean,
    val canLaunch: Boolean,
    val canLaunchDesktop: Boolean,
    val canPowerOff: Boolean,
    val canShowLogs: Boolean,
    val canExport: Boolean,
    val canDelete: Boolean,
) {
    val primaryAction: InstancePrimaryAction
        get() = when (status) {
            InstanceStatus.Running -> InstancePrimaryAction.Terminal
            InstanceStatus.Suspended -> InstancePrimaryAction.Resume
            else -> InstancePrimaryAction.Launch
        }

    val primaryEnabled: Boolean
        get() = when (primaryAction) {
            InstancePrimaryAction.Terminal -> canOpenTerminal
            InstancePrimaryAction.Launch -> canLaunch
            // 挂起是唯一可直接恢复的状态；控制器提供 resume。
            InstancePrimaryAction.Resume -> true
        }
}

/** 一个状态组；组标题在卡外，组内行使用 24dp 大卡。 */
data class InstanceGroupUi(
    val kind: InstanceGroup,
    val items: List<InstanceRowUi>,
) {
    val count: Int get() = items.size
}

/**
 * 创建/导入进度横幅。只有存在状态文案时才显示；[fraction] 为 null 表示
 * 只报文字、不画确定进度。
 */
data class InstancesProgressUi(
    val statusText: String,
    val fraction: Float? = null,
) {
    companion object {
        fun of(statusText: String, fraction: Float?): InstancesProgressUi? {
            val text = statusText.trim()
            if (text.isEmpty()) {
                return null
            }
            val safeFraction = fraction
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
            return InstancesProgressUi(statusText = text, fraction = safeFraction)
        }
    }
}
