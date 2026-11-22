package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 栈式导航的状态契约（工单 UI-03 实施要点 1·方案 B）：
 *
 * - 栈只承载"推入的二级目的地"，底层五页由 pager 表达，pop 到底即回 pager；
 * - `pop()` 是系统返回键/预测式返回手势的唯一语义入口（宿主以 `BackHandler`
 *   接到同一个方法上），返回值即"本层是否消费了返回事件"；
 * - 键是标识，内容可变——同一键重复 push 视为新实例入栈（Wave 4 的页面
 *   自行保证键唯一或有意重复）。
 *
 * 纯 JVM 断言：`SnapshotStateList` 不依赖 Android 框架，状态机可以直接测。
 */
class OeBackStackTest {

    private fun destination(key: String): OeDestination = OeDestination(
        key = key,
        title = "页 $key",
        content = {},
    )

    @Test
    fun anEmptyStackCannotPop() {
        val stack = OeBackStack()
        assertFalse(stack.canPop)
        assertEquals(0, stack.size)
        assertNull(stack.top)
        assertFalse(stack.pop())
        assertEquals(0, stack.size)
    }

    @Test
    fun pushMakesTheDestinationTheTopAndEnablesPop() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        assertTrue(stack.canPop)
        assertEquals(1, stack.size)
        assertEquals("a", stack.top?.key)
    }

    @Test
    fun popRevealsTheEntryBeneath() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        stack.push(destination("b"))
        assertTrue(stack.pop())
        assertEquals("a", stack.top?.key)
        assertEquals(1, stack.size)
    }

    @Test
    fun poppingTheLastEntryEmptiesTheStack() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        assertTrue(stack.pop())
        assertFalse(stack.canPop)
        assertFalse(stack.pop())
    }

    @Test
    fun popAllClearsTheStackAtOnce() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        stack.push(destination("b"))
        stack.push(destination("c"))
        stack.popAll()
        assertFalse(stack.canPop)
        assertNull(stack.top)
    }

    @Test
    fun theSameKeyCanEnterTwice() {
        // 栈是位置语义，不是集合语义：Wave 4 的二级页允许从不同入口到达同名页。
        val stack = OeBackStack()
        stack.push(destination("detail"))
        stack.push(destination("detail"))
        assertEquals(2, stack.size)
        assertTrue(stack.pop())
        assertEquals("detail", stack.top?.key)
    }

    @Test
    fun entriesAreAddressableByIndex() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        stack.push(destination("b"))
        assertEquals("a", stack[0].key)
        assertEquals("b", stack[1].key)
    }

    @Test
    fun transitionIdIsMonotonicAndUnchangedOnEmptyNoOps() {
        val stack = OeBackStack()
        assertEquals(0L, stack.transition.transitionId)
        assertFalse(stack.pop())
        assertEquals(0L, stack.transition.transitionId)
        stack.popAll()
        assertEquals(0L, stack.transition.transitionId)

        stack.push(destination("a"))
        val t1 = stack.transition.transitionId
        assertEquals(StackFlight.Push, stack.transition.flight)
        assertEquals(0f, stack.transition.startProgress, 0.0001f)
        assertEquals(-1, stack.transition.underIndex)

        stack.push(destination("b"))
        val t2 = stack.transition.transitionId
        assertTrue(t2 > t1)
        assertEquals(0, stack.transition.underIndex)

        assertTrue(stack.pop())
        val t3 = stack.transition.transitionId
        assertTrue(t3 > t2)
        assertEquals(StackFlight.Pop, stack.transition.flight)
        assertEquals(0f, stack.transition.startProgress, 0.0001f)
        assertEquals("b", stack.transition.leaving?.destination?.key)
    }

    @Test
    fun popAllProducesInstantResetWithoutPopFlight() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        stack.push(destination("b"))
        val before = stack.transition.transitionId
        stack.popAll()
        assertTrue(stack.transition.transitionId > before)
        assertEquals(StackFlight.None, stack.transition.flight)
        assertNull(stack.transition.leaving)
        assertEquals(0, stack.size)
        assertEquals(-1, stack.transition.underIndex)
    }

    @Test
    fun settleTransitionClearsFlightWithoutBumpingTransitionIdAndIgnoresStaleIds() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        stack.push(destination("b"))
        val pushId = stack.transition.transitionId
        stack.settleTransition(pushId - 1)
        assertEquals(StackFlight.Push, stack.transition.flight)

        stack.settleTransition(pushId)
        assertEquals(pushId, stack.transition.transitionId)
        assertEquals(StackFlight.None, stack.transition.flight)

        assertTrue(stack.pop())
        val popId = stack.transition.transitionId
        stack.settleTransition(popId)
        assertEquals(popId, stack.transition.transitionId)
        assertEquals(StackFlight.None, stack.transition.flight)
        assertNull(stack.transition.leaving)
        assertEquals(1, stack.size)
    }

    @Test
    fun popFollowedByPushInSameDepthChangesTransitionIdAndEntryIds() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        val firstId = stack.transition.entries.single().id
        val t1 = stack.transition.transitionId
        assertTrue(stack.pop())
        stack.push(destination("a"))
        val secondId = stack.transition.entries.single().id
        val t2 = stack.transition.transitionId
        assertTrue(t2 > t1)
        assertTrue(secondId > firstId)
        assertEquals(1, stack.size)
    }

    @Test
    fun popFromProgressClampsStartProgressAndContainsKeyOrLeavingTracksLeavingEntry() {
        val stack = OeBackStack()
        stack.push(destination("theme-studio"))
        assertTrue(stack.containsKeyOrLeaving("theme-studio"))
        assertTrue(stack.popFromProgress(0.65f))
        assertEquals(0.65f, stack.transition.startProgress, 0.0001f)
        assertTrue(stack.containsKeyOrLeaving("theme-studio"))
        stack.settleTransition(stack.transition.transitionId)
        assertFalse(stack.containsKeyOrLeaving("theme-studio"))

        stack.push(destination("x"))
        assertTrue(stack.popFromProgress(1.5f))
        assertEquals(1f, stack.transition.startProgress, 0.0001f)
        stack.push(destination("y"))
        assertTrue(stack.popFromProgress(-0.5f))
        assertEquals(0f, stack.transition.startProgress, 0.0001f)
    }

    @Test
    fun reboundToRestKeepsTheEntriesAndMarksAReboundFlight() {
        // W2-03 取消回弹：条目不动（绝不弹层），只推一次 Rebound 飞行快照，
        // 收敛后 settle 恢复静止态。
        val stack = OeBackStack()
        stack.push(destination("a"))
        stack.push(destination("b"))
        val beforeId = stack.transition.transitionId
        assertTrue(stack.reboundToRest(startProgress = 0.4f, initialVelocity = -3f))
        val rebound = stack.transition
        assertTrue(rebound.transitionId > beforeId)
        assertEquals(StackFlight.Rebound, rebound.flight)
        assertEquals(2, rebound.entries.size)
        assertEquals("b", rebound.leaving?.destination?.key)
        assertEquals(0.4f, rebound.startProgress, 0.0001f)
        assertEquals(-3f, rebound.initialVelocity, 0.0001f)
        assertEquals(2, stack.size)
        assertEquals("b", stack.top?.key)

        stack.settleTransition(rebound.transitionId)
        assertEquals(StackFlight.None, stack.transition.flight)
        assertNull(stack.transition.leaving)
        assertEquals(2, stack.size)
    }

    @Test
    fun reboundToRestOnAnEmptyStackIsANoOp() {
        val stack = OeBackStack()
        assertFalse(stack.reboundToRest(0.5f))
        assertEquals(0L, stack.transition.transitionId)
    }

    @Test
    fun popDuringAnyFlightContinuesFromCurrentProgressInsteadOfSnapping() {
        // W2-03 接管边界：飞行中（Push/Pop/Rebound）的返回从当前进度续弹，
        // 不再瞬切到端点（旧行为里 Pop 飞行退化为 1f 起点瞬切）。
        val stack = OeBackStack()
        stack.push(destination("a"))

        // Push 飞行中 pop：弹出 a，起点取自 Push 飞行进度（动画未跟踪 → 0）。
        assertTrue(stack.pop())
        assertEquals(StackFlight.Pop, stack.transition.flight)
        assertEquals("a", stack.transition.leaving?.destination?.key)
        assertEquals(0f, stack.transition.startProgress, 0.0001f)
        stack.settleTransition(stack.transition.transitionId)
        assertEquals(0, stack.size)

        // Pop 飞行中再 pop：栈已空 → 不消费（不为负）。
        assertFalse(stack.pop())

        // 重建 Pop 飞行后再 pop：起点 = 上一段 Pop 飞行的快照进度（0.7），
        // 而非 1f 瞬切。
        stack.push(destination("x"))
        stack.push(destination("y"))
        assertTrue(stack.popFromProgress(0.7f))
        assertEquals(1, stack.size)
        assertTrue(stack.pop())
        assertEquals(StackFlight.Pop, stack.transition.flight)
        assertEquals("x", stack.transition.leaving?.destination?.key)
        assertEquals(0.7f, stack.transition.startProgress, 0.0001f)
        assertEquals(0, stack.size)
    }

    @Test
    fun popDuringReboundContinuesFromTheReboundProgress() {
        val stack = OeBackStack()
        stack.push(destination("a"))
        assertTrue(stack.reboundToRest(startProgress = 0.3f, initialVelocity = 2f))
        assertTrue(stack.pop())
        assertEquals(StackFlight.Pop, stack.transition.flight)
        assertEquals("a", stack.transition.leaving?.destination?.key)
        // Rebound 的动画进度未被跟踪（animatedTransitionId 不匹配）→ 退回
        // 快照 startProgress，即从取消时被拉开的位置继续弹出。
        assertEquals(0.3f, stack.transition.startProgress, 0.0001f)
        assertEquals(0, stack.size)
    }

    @Test
    fun velocityEstimatorTracksTheTailRateOfTheGestureFlow() {
        // W2-03：提交/取消初速 = progressFlow 末段变化率（事件进度单位/秒）。
        val estimator = PredictiveBackVelocityEstimator()
        assertEquals(0f, estimator.eventVelocity(), 0.0001f)

        // 首个样本没有前驱，速度保持 0。
        estimator.onSample(0.2f, atMs = 1_000L)
        assertEquals(0f, estimator.eventVelocity(), 0.0001f)

        // 0.2 → 0.5 跨 100ms：末段速率 = 0.3/0.1s = 3 进度/s。
        estimator.onSample(0.5f, atMs = 1_100L)
        assertEquals(3f, estimator.eventVelocity(), 0.0001f)

        // 同毫秒重复样本（dt=0）不产生除零，保持上一速率。
        estimator.onSample(0.5f, atMs = 1_100L)
        assertEquals(3f, estimator.eventVelocity(), 0.0001f)

        // 回拉：0.5 → 0.4 跨 50ms → −2 进度/s。
        estimator.onSample(0.4f, atMs = 1_150L)
        assertEquals(-2f, estimator.eventVelocity(), 0.0001f)
    }
}
