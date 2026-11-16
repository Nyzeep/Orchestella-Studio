package io.github.nyzeep.orchestella.ui.screens.common

import io.github.nyzeep.orchestella.startup.DashboardSnapshot
import kotlin.math.roundToInt

/**
 * 存储读数。生产路径复用 [DashboardSnapshot] 已有的磁盘统计结果，页面与映射层
 * 都不重新测量；[freeBytes] 越界时按 0/100% 夹取，避免负用量或超环。
 */
data class StorageFacts(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long
        get() = (totalBytes - freeBytes).coerceIn(0L, totalBytes.coerceAtLeast(0L))

    val fraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (usedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    val usedPercent: Int
        get() = (fraction * 100f).roundToInt().coerceIn(0, 100)

    companion object {
        val None = StorageFacts(totalBytes = 0L, freeBytes = 0L)

        /** 取自快照，不触发新的磁盘统计。 */
        fun from(snapshot: DashboardSnapshot): StorageFacts = StorageFacts(
            totalBytes = snapshot.storageTotalBytes,
            freeBytes = snapshot.storageFreeBytes,
        )
    }
}
