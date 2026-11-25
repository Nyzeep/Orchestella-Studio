package io.github.nyzeep.orchestella

import androidx.annotation.StringRes

/**
 * G-04（2026-11-19 轮）：Terminal↔Desktop 双向入口的 enabled 单一来源。
 *
 * <p>现状（诚实口径，GLOSSARY.md:180-181）：生产通道尚不携带真实 guest 图形帧与
 * 输入通道（独立 Phase 5 开发中，未真机验收），因此 [supportsDesktop] 当前恒为
 * false——三档入口（instances sheet / 终端抽屉 / GuestFeatures）全部呈现诚实禁用态；
 * Phase 5 通道接通后只改这一处，三档同时放行。
 *
 * <p>抽取前实况（代码考古 2026-11-19）：该判定曾散落三处字面量——
 * MainActivity.isHeadless() 恒 true、MainActivity.buildInstanceUiModel 注入
 * supportsDesktop=false、InstanceDetailActivity.currentSupportsDesktop() 恒 false，
 * GuestFeaturesActivity 则无判定直启。本对象收敛为唯一判定处。
 */
object DesktopEntryPolicy {

    /** 抽屉入口的条件渲染决策（可见性 + enabled + 禁用原因），纯 JVM 可测。 */
    class EntryState(
        @JvmField val visible: Boolean,
        @JvmField val enabled: Boolean,
        @JvmField val reasonRes: Int?
    )

    /** 禁用原因文案单源：运行中但图形通道尚未就绪（Phase 5，未真机验收）。 */
    @JvmField
    @StringRes
    val REASON_CHANNEL_NOT_READY_RES: Int = R.string.desktop_entry_reason_channel_not_ready

    /** 禁用原因文案单源：实例未运行，先启动实例。 */
    @JvmField
    @StringRes
    val REASON_INSTANCE_NOT_RUNNING_RES: Int = R.string.desktop_entry_reason_instance_not_running

    @Volatile
    private var qaDesktopEntryOverride: Boolean = false

    @Volatile
    private var qaExperimentalZrleOverride: Boolean = false

    /**
     * guest 图形帧/输入通道是否就绪——唯一判定处（当前恒 false：Phase 5 通道
     * 未真机验收）。三档入口的 enabled 全部经由此处或其派生重载。
     */
    @JvmStatic
    fun supportsDesktop(): Boolean = false

    /**
     * 仅隔离 QA 构建允许切换桌面入口实验开关；非 QA 构建强制保持 false。
     */
    @JvmStatic
    fun setQaDesktopEntryEnabled(enabled: Boolean): Boolean {
        if (!BuildConfig.IS_ISOLATED_QA_BUILD) {
            qaDesktopEntryOverride = false
            return false
        }
        qaDesktopEntryOverride = enabled
        return enabled
    }

    @JvmStatic
    fun isQaDesktopEntryEnabled(): Boolean = BuildConfig.IS_ISOLATED_QA_BUILD && qaDesktopEntryOverride

    /**
     * 仅隔离 QA 构建允许切换实验性 ZRLE 解码开关；非 QA 构建强制保持 false。
     */
    @JvmStatic
    fun setQaExperimentalZrleEnabled(enabled: Boolean): Boolean {
        if (!BuildConfig.IS_ISOLATED_QA_BUILD) {
            qaExperimentalZrleOverride = false
            return false
        }
        qaExperimentalZrleOverride = enabled
        return enabled
    }

    @JvmStatic
    fun isQaExperimentalZrleEnabled(): Boolean = BuildConfig.IS_ISOLATED_QA_BUILD && qaExperimentalZrleOverride

    /** 纯 JVM 可测的 QA 通道就绪判定：生产口径不变，QA 仅在开关与 RFB 能力均就绪时放行。 */
    @JvmStatic
    fun resolveChannelReady(
        isolatedQaBuild: Boolean,
        qaSwitchEnabled: Boolean,
        supportsRfbDisplay: Boolean,
    ): Boolean = supportsDesktop() || (isolatedQaBuild && qaSwitchEnabled && supportsRfbDisplay)

    @JvmStatic
    fun canLaunchDesktopInQa(
        instanceRunning: Boolean,
        isolatedQaBuild: Boolean,
        qaSwitchEnabled: Boolean,
        supportsRfbDisplay: Boolean,
    ): Boolean = canLaunchDesktop(
        instanceRunning,
        resolveChannelReady(isolatedQaBuild, qaSwitchEnabled, supportsRfbDisplay),
    )

    @JvmStatic
    fun drawerEntryInQa(
        hasBoundInstance: Boolean,
        instanceRunning: Boolean,
        isolatedQaBuild: Boolean,
        qaSwitchEnabled: Boolean,
        supportsRfbDisplay: Boolean,
    ): EntryState = drawerEntry(
        hasBoundInstance,
        instanceRunning,
        resolveChannelReady(isolatedQaBuild, qaSwitchEnabled, supportsRfbDisplay),
    )

    @JvmStatic
    fun effectiveSupportsDesktop(supportsRfbDisplay: Boolean): Boolean =
        resolveChannelReady(BuildConfig.IS_ISOLATED_QA_BUILD, qaDesktopEntryOverride, supportsRfbDisplay)

    @JvmStatic
    fun effectiveCanLaunchDesktop(instanceRunning: Boolean, supportsRfbDisplay: Boolean): Boolean =
        canLaunchDesktop(instanceRunning, effectiveSupportsDesktop(supportsRfbDisplay))

    @JvmStatic
    fun effectiveDrawerEntry(
        hasBoundInstance: Boolean,
        instanceRunning: Boolean,
        supportsRfbDisplay: Boolean,
    ): EntryState = drawerEntry(hasBoundInstance, instanceRunning, effectiveSupportsDesktop(supportsRfbDisplay))

    /** 生产入口：三档共用 enabled 规则（实例运行中 且 通道就绪），绑定 [supportsDesktop]。 */
    @JvmStatic
    fun canLaunchDesktop(instanceRunning: Boolean): Boolean =
            canLaunchDesktop(instanceRunning, supportsDesktop())

    /** 纯判定重载（测试注入通道态用）：enabled = 运行中 && 通道就绪，与 instances sheet 现规则一致。 */
    @JvmStatic
    fun canLaunchDesktop(instanceRunning: Boolean, channelReady: Boolean): Boolean =
            instanceRunning && channelReady

    /** 禁用原因（统一文案单源）：运行中 → 通道未就绪；未运行 → 先启动实例。 */
    @JvmStatic
    fun disabledReasonRes(instanceRunning: Boolean): Int =
            if (instanceRunning) REASON_CHANNEL_NOT_READY_RES else REASON_INSTANCE_NOT_RUNNING_RES

    /** 生产入口：TerminalActivity 会话抽屉「桌面模式」入口的条件渲染决策，绑定 [supportsDesktop]。 */
    @JvmStatic
    fun drawerEntry(hasBoundInstance: Boolean, instanceRunning: Boolean): EntryState =
            drawerEntry(hasBoundInstance, instanceRunning, supportsDesktop())

    /**
     * 纯判定重载（测试注入通道态用）。条件渲染口径：
     * 未绑定实例（instanceId 缺失）→ 入口整体隐藏；绑定后恒渲染，
     * enabled 由运行态与通道态共同决定，禁用时给出统一原因文案。
     */
    @JvmStatic
    fun drawerEntry(hasBoundInstance: Boolean, instanceRunning: Boolean, channelReady: Boolean): EntryState {
        if (!hasBoundInstance) return EntryState(visible = false, enabled = false, reasonRes = null)
        val enabled = canLaunchDesktop(instanceRunning, channelReady)
        return EntryState(
                visible = true,
                enabled = enabled,
                reasonRes = if (enabled) null else disabledReasonRes(instanceRunning)
        )
    }
}
