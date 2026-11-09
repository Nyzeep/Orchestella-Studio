package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-09 批次3 E 工单（T4⑥ 空态半项）：TerminalActivity 无实例直启空态的
 * JVM 契约。真机清空实例不可安全执行，本用例锁定两件事——
 * 1. 触发条件：直启未携带实例 ID（null/空串）是空态；携带 ID 但查不到是
 *    「实例不存在」，不是空态；
 * 2. 文案键：空态必须渲染 terminal_empty_no_instance_title / message（2026-11-07
 *    D-lane 落地），键被改名或删除即测试失败。
 * 真机复验步骤（模拟器/次设备）留走查清单：`adb shell am start -n
 * io.github.nyzeep.orchestella/.TerminalActivity`（不带 extras），期望空态卡片。
 */
class TerminalEmptyStateContractTest {

    @Test
    fun launchWithoutInstanceIdIsEmptyStateNotLookupFailure() {
        assertTrue("null ID（直启不带 extras）必须是空态",
            TerminalEmptyStateRules.isEmptyStateLaunch(null))
        assertTrue("空串 ID 必须是空态",
            TerminalEmptyStateRules.isEmptyStateLaunch(""))
    }

    @Test
    fun launchWithInstanceIdIsLookupFailureNotEmptyState() {
        assertFalse("携带了实例 ID 但查不到，应走「实例不存在」而不是空态",
            TerminalEmptyStateRules.isEmptyStateLaunch("0b073e2e-3b7c-4d1e-9c9f-0f2a6f9d5a01"))
    }

    @Test
    fun emptyStateCopyIsPinnedToTerminalEmptyKeys() {
        assertEquals(R.string.terminal_empty_no_instance_title, TerminalEmptyStateRules.EMPTY_STATE_TITLE_RES)
        assertEquals(R.string.terminal_empty_no_instance_message, TerminalEmptyStateRules.EMPTY_STATE_MESSAGE_RES)
        assertNotEquals("标题与正文不得同键",
            TerminalEmptyStateRules.EMPTY_STATE_TITLE_RES, TerminalEmptyStateRules.EMPTY_STATE_MESSAGE_RES)
    }
}
