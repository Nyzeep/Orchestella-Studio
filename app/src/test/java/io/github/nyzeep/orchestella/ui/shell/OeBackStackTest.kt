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
}
