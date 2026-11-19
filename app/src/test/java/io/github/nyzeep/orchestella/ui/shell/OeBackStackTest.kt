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
}
