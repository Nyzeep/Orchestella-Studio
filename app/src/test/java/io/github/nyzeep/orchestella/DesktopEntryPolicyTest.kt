package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G-04（2026-11-19 轮）：桌面入口 enabled 单一来源的 JVM 契约测试。
 *
 * 锁定三件事：
 * 1. 通道判定的诚实现状——Phase 5 真机验收前 [DesktopEntryPolicy.supportsDesktop] 恒 false；
 * 2. enabled 合取式（运行中 且 通道就绪）与 instances sheet 既有规则逐字一致；
 * 3. 终端抽屉「桌面模式」入口的条件渲染（可见性/enabled/统一禁用原因）。
 * 生产 1 参重载必须与「2 参 + supportsDesktop()」等价，防止单源旁路。
 */
class DesktopEntryPolicyTest {

    // ---------- 单一来源的现状契约 ----------

    @Test
    fun supportsDesktop_staysFalseUntilPhase5ChannelVerified() {
        // 诚实口径：生产通道尚不携带 guest 图形帧与输入（未真机验收）。
        // Phase 5 接通后此断言随单源一起翻转，三档入口同时放行。
        assertFalse(DesktopEntryPolicy.supportsDesktop())
    }

    @Test
    fun canLaunchDesktop_requiresRunningInstanceAndReadyChannel() {
        assertFalse(DesktopEntryPolicy.canLaunchDesktop(instanceRunning = false, channelReady = false))
        assertFalse(DesktopEntryPolicy.canLaunchDesktop(instanceRunning = true, channelReady = false))
        assertFalse(DesktopEntryPolicy.canLaunchDesktop(instanceRunning = false, channelReady = true))
        assertTrue(DesktopEntryPolicy.canLaunchDesktop(instanceRunning = true, channelReady = true))
    }

    @Test
    fun canLaunchDesktop_productionOverloadBindsSingleSource() {
        // 1 参生产入口必须等价于 2 参 + supportsDesktop()——单源不可被旁路。
        for (running in listOf(true, false)) {
            assertEquals(
                DesktopEntryPolicy.canLaunchDesktop(running, DesktopEntryPolicy.supportsDesktop()),
                DesktopEntryPolicy.canLaunchDesktop(running)
            )
        }
    }

    // ---------- 抽屉入口条件渲染 ----------

    @Test
    fun drawerEntry_hidesWholeBlockWhenNoInstanceBound() {
        val state = DesktopEntryPolicy.drawerEntry(hasBoundInstance = false, instanceRunning = true, channelReady = true)
        assertFalse(state.visible)
        assertFalse(state.enabled)
        assertNull(state.reasonRes)
    }

    @Test
    fun drawerEntry_enabledWhenRunningAndChannelReady_noReasonShown() {
        val state = DesktopEntryPolicy.drawerEntry(hasBoundInstance = true, instanceRunning = true, channelReady = true)
        assertTrue(state.visible)
        assertTrue(state.enabled)
        assertNull(state.reasonRes)
    }

    @Test
    fun drawerEntry_runningButChannelNotReady_showsUnifiedChannelReason() {
        val state = DesktopEntryPolicy.drawerEntry(hasBoundInstance = true, instanceRunning = true, channelReady = false)
        assertTrue(state.visible)
        assertFalse(state.enabled)
        assertEquals(DesktopEntryPolicy.REASON_CHANNEL_NOT_READY_RES, state.reasonRes)
    }

    @Test
    fun drawerEntry_instanceNotRunning_showsUnifiedStartInstanceReason() {
        val state = DesktopEntryPolicy.drawerEntry(hasBoundInstance = true, instanceRunning = false, channelReady = false)
        assertTrue(state.visible)
        assertFalse(state.enabled)
        assertEquals(DesktopEntryPolicy.REASON_INSTANCE_NOT_RUNNING_RES, state.reasonRes)
    }

    @Test
    fun drawerEntry_productionOverloadBindsSingleSource() {
        for (running in listOf(true, false)) {
            val bound = DesktopEntryPolicy.drawerEntry(true, running, DesktopEntryPolicy.supportsDesktop())
            val produced = DesktopEntryPolicy.drawerEntry(true, running)
            assertEquals(bound.visible, produced.visible)
            assertEquals(bound.enabled, produced.enabled)
            assertEquals(bound.reasonRes, produced.reasonRes)
        }
    }

    // ---------- 禁用原因文案单源 ----------

    @Test
    fun disabledReasonRes_matchesRunningState() {
        assertEquals(DesktopEntryPolicy.REASON_CHANNEL_NOT_READY_RES, DesktopEntryPolicy.disabledReasonRes(true))
        assertEquals(DesktopEntryPolicy.REASON_INSTANCE_NOT_RUNNING_RES, DesktopEntryPolicy.disabledReasonRes(false))
        // 两个原因档位必须是不同的文案键（统一单源，不允许三档各自措辞）。
        assertNotEquals(DesktopEntryPolicy.REASON_CHANNEL_NOT_READY_RES, DesktopEntryPolicy.REASON_INSTANCE_NOT_RUNNING_RES)
    }
}
