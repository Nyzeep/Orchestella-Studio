package io.github.nyzeep.orchestella.ui.screens.common

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 以 1024³ 为 1 GB 的读数（与系统存储口径一致）。整 GB 不显示小数，
 * 其余保留一位小数；1e-6 容差只吸收除法误差，不会把 1.96 GB 说成 2 GB。
 *
 * 单源（UI3-12）：工作站页与硬件页共用；[Locale.US] 保证截图与单测稳定。
 */
internal fun formatGigabytes(bytes: Long): String {
    val gib = bytes.coerceAtLeast(0L) / (1024.0 * 1024.0 * 1024.0)
    return if (abs(gib - gib.roundToInt()) < 1e-6) {
        String.format(Locale.US, "%.0f GB", gib)
    } else {
        String.format(Locale.US, "%.1f GB", gib)
    }
}
