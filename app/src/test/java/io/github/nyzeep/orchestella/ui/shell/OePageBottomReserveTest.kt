package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI-02 透传切片：底栏样式必须真的改变页面底部避让。
 *
 * 悬浮坞的避让是 `62 + max(21, systemBottom + 8)`（手势条机型为 83dp），
 * 固定坞则贴住系统栏，避让是 `62 + systemBottom`。两者不能相同，
 * 否则「切到 Fixed 看不出变化」的老问题会重演。
 */
class OePageBottomReserveTest {

    @Test
    fun floatingNavBarClearsTheTwentyOneDpMargin() {
        assertEquals(83.dp, pageBottomReserve(Oe3NavStyle.Floating, systemBottom = 0.dp))
        assertEquals(94.dp, pageBottomReserve(Oe3NavStyle.Floating, systemBottom = 24.dp))
    }

    @Test
    fun fixedNavBarOnlyClearsTheSystemBar() {
        assertEquals(62.dp, pageBottomReserve(Oe3NavStyle.Fixed, systemBottom = 0.dp))
        assertEquals(86.dp, pageBottomReserve(Oe3NavStyle.Fixed, systemBottom = 24.dp))
    }
}
