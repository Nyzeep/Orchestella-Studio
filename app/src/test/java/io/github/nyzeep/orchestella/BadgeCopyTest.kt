package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Test

/** Badge copy decisions for the instance card acceleration lane (W-B). */
class BadgeCopyTest {

    @Test
    fun readyLaneUsesPinnedLabel() {
        assertEquals("QEMU 完整内核 + pVM 加速", BadgeCopy.derive(true, "任意回退文案"))
    }

    @Test
    fun probingShowsNeutralLabel() {
        assertEquals("虚拟化 · 检测中", BadgeCopy.derive(null, "任意回退文案"))
    }

    @Test
    fun unsupportedPassesFallbackThrough() {
        assertEquals("仅 QEMU 模拟", BadgeCopy.derive(false, "仅 QEMU 模拟"))
    }

    @Test
    fun avfEntryLabelIsStable() {
        assertEquals("AVF · 兼容性说明", BadgeCopy.avfUnsupportedEntryLabel())
    }
}
