package io.github.nyzeep.orchestella

/** Badge copy decisions for the instance card acceleration lane. */
object BadgeCopy {
    /** 拍板文案：加速启用且车道可用 → 「QEMU 完整内核 + pVM 加速」；探测中 → 中性；未启用 → fallback（现状文案由调用方传）。 */
    fun derive(accelLaneReady: Boolean?, fallback: String): String = when (accelLaneReady) {
        true -> "QEMU 完整内核 + pVM 加速"
        null -> "虚拟化 · 检测中"
        false -> fallback
    }

    fun avfUnsupportedEntryLabel(): String = "AVF · 兼容性说明"
}
