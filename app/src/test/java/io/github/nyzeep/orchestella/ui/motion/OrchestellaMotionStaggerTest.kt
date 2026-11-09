package io.github.nyzeep.orchestella.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lane D (2026-11-10)：锁定 stagger 节奏数学与 oe_motion_tokens.xml 的常量镜像
 * 契约。纯 JVM——只触碰顶层纯函数与编译期常量（const val 内联，不加载任何
 * Android 视图类），动画本体仍走真机走查清单验证。
 */
public final class OrchestellaMotionStaggerTest {

    @Test
    fun `cascade delay is linear inside the cap`() {
        assertEquals(0L, staggeredDelayMs(0, 40, 5))
        assertEquals(40L, staggeredDelayMs(1, 40, 5))
        assertEquals(200L, staggeredDelayMs(5, 40, 5))
    }

    @Test
    fun `cascade delay clamps beyond max steps so long lists lose the tail delay`() {
        assertEquals(200L, staggeredDelayMs(6, 40, 5))
        assertEquals(200L, staggeredDelayMs(12, 40, 5))
        assertEquals(225L, staggeredDelayMs(99, 45, 5))
    }

    @Test
    fun `cascade delay clamps negative steps to zero`() {
        assertEquals(0L, staggeredDelayMs(-1, 40, 5))
    }

    @Test
    fun `stagger rhythm mirrors oe_motion_stagger tokens`() {
        assertEquals(40, OrchestellaMotion.STAGGER_LIST_ITEM_MS)
        assertEquals(5, OrchestellaMotion.STAGGER_LIST_MAX_STEPS)
        assertEquals(45, OrchestellaMotion.STAGGER_SECTION_MS)
    }

    @Test
    fun `semantic durations mirror oe_motion_duration tokens`() {
        assertEquals(500, OrchestellaMotion.DURATION_CONTAINER_TRANSFORM_MS)
        assertEquals(250, OrchestellaMotion.DURATION_DIALOG_ENTER_MS)
        assertEquals(150, OrchestellaMotion.DURATION_DIALOG_EXIT_MS)
        assertEquals(300, OrchestellaMotion.DURATION_LIST_ITEM_ENTER_MS)
        assertEquals(250, OrchestellaMotion.DURATION_LIST_ITEM_REORDER_MS)
    }

    @Test
    fun `existing mirror contract is unchanged`() {
        assertEquals(300, OrchestellaMotion.DURATION_ENTER_MS)
        assertEquals(150, OrchestellaMotion.DURATION_EXIT_MS)
        assertEquals(210, OrchestellaMotion.DURATION_ACTIVITY_ENTER_MS)
        assertEquals(100, OrchestellaMotion.DURATION_PRESS_IN_MS)
        assertEquals(150, OrchestellaMotion.DURATION_PRESS_OUT_MS)
        assertEquals(1000, OrchestellaMotion.DURATION_BREATH_CYCLE_MS)
    }
}
