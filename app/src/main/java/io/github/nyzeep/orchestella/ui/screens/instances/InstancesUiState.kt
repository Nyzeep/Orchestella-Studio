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
 * 渲染输入。引擎文案由接线方按实际启动策略传入，空白即不显示，不猜一个引擎。
 */
data class InstancesUiState(
    val groups: List<InstanceGroupUi>,
    val totalCount: Int,
    val runningCount: Int,
    val progress: InstancesProgressUi? = null,
    val isRefreshing: Boolean = false,
) {
    val hasInstances: Boolean get() = totalCount > 0

    /**
     * LazyColumn 的组头与条目 key，顺序与屏幕一致。屏幕直接用这些值，
     * 测试用它证明 key 唯一且不重不漏。
     */
    fun listKeys(): List<String> = groups.flatMap { group ->
        buildList {
            add(InstancesListKeys.group(group.kind))
            group.items.forEach { add(InstancesListKeys.item(it.id)) }
        }
    }

    companion object {
        /** 固定组序：运行中 → 装配中 → 已停止 → 异常。 */
        fun from(
            cards: List<InstanceCardUiModel>,
            engineLabel: String? = null,
            progress: InstancesProgressUi? = null,
            isRefreshing: Boolean = false,
        ): InstancesUiState {
            val engine = engineLabel?.trim()?.takeIf { it.isNotEmpty() }
            // 同一实例在快照切换期间可能重复出现；保留第一份，key 才唯一。
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
                // forceStop 的合法前置是 RUNNING 或 SUSPENDED（InstanceLifecycleController）。
                canPowerOff = state == InstanceLifecycle.State.RUNNING ||
                    state == InstanceLifecycle.State.SUSPENDED,
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
                hasCrashDiagnostic = hasCrashDiagnostic,
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

    val canOpenTerminal: Boolean get() = this == Running

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

/** 卡片主操作：运行中进终端，其余状态尝试启动；可用性由行状态决定。 */
enum class InstancePrimaryAction(@StringRes val labelRes: Int) {
    Terminal(R.string.ui3_instances_action_terminal),
    Launch(R.string.ui3_instances_action_launch),
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
    val hasCrashDiagnostic: Boolean,
) {
    val primaryAction: InstancePrimaryAction
        get() = if (status == InstanceStatus.Running) {
            InstancePrimaryAction.Terminal
        } else {
            InstancePrimaryAction.Launch
        }

    val primaryEnabled: Boolean
        get() = when (primaryAction) {
            InstancePrimaryAction.Terminal -> canOpenTerminal
            InstancePrimaryAction.Launch -> canLaunch
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
