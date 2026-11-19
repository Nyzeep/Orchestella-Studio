package io.github.nyzeep.orchestella.ui.shell.nav

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.shell.Oe3NavStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮动坞阴影的规格钉（v4 spec §1.3 / 工单 UI-03 交付 3）：
 *
 * 阴影**只属于浮动形态**（固定坞贴边，无投影）；两层投影的数值是
 * black@0.08 blur24 y8 + black@0.04 blur3 y1。坞与卡片的辨识度三要素
 * 「高一个色阶 + 阴影 + 外露 21dp」里，本对象负责第二要素的数值真源。
 */
class Oe3NavShadowSpecTest {

    @Test
    fun twoLayersMatchTheDocumentedShadow() {
        val layers = Oe3NavShadowSpec.layersFor(Oe3NavStyle.Floating)
        assertEquals(2, layers.size)
        assertEquals(0.08f, layers[0].alpha, 0.0001f)
        assertEquals(24.dp, layers[0].blur)
        assertEquals(8.dp, layers[0].offsetY)
        assertEquals(0.04f, layers[1].alpha, 0.0001f)
        assertEquals(3.dp, layers[1].blur)
        assertEquals(1.dp, layers[1].offsetY)
    }

    @Test
    fun fixedDockHasASoftShadowAboveItsTopEdge() {
        val layer = Oe3NavShadowSpec.layersFor(Oe3NavStyle.Fixed).single()
        assertEquals(0.10f, layer.alpha, 0.0001f)
        assertEquals(12.dp, layer.blur)
        assertEquals((-2).dp, layer.offsetY)
    }
}
