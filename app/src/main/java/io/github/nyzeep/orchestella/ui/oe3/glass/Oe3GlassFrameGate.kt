package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.runtime.Immutable
import kotlin.math.ceil

/**
 * 性能门的单次裁决（UI-06）：首 60 帧采样后给出是否降档。
 *
 * [downgrade] 为 true 时调用方把设备持久化到 [GlassDeviceCap.L0]；
 * [p90Ms]/[droppedRatio] 随裁决带出，便于日志与后续调参，不参与持久化。
 */
@Immutable
data class GlassGateVerdict(
    val downgrade: Boolean,
    val p90Ms: Double,
    val droppedRatio: Double,
)

/**
 * 门控阈值的纯函数层（UI-06，v4 spec §1.7）：采样对象是**帧耗时**
 * （`FrameMetrics.TOTAL_DURATION`，渲染一帧的实际耗时），不是帧交付间隔——
 * 「P90 > 预算×0.7」只对帧耗时口径成立（匀速交付的间隔恒≈预算，误判一切）。
 *
 * - P90 帧耗时 > 预算×0.7：连续大量帧逼近甚至超出预算，设备跟不上。
 * - 掉帧率 > 2%：帧耗时 > 预算（错过 vsync deadline）的占比。
 * 任一超限即降档。所有阈值集中在这里，测试与调参只动这一处。
 */
object GlassGatePolicy {

    /** P90 帧耗时相对帧预算的上限系数（预算×0.7）。 */
    const val P90_BUDGET_FACTOR = 0.7

    /** 掉帧率上限（占比超过即降档）。 */
    const val DROPPED_RATIO_LIMIT = 0.02

    /** 帧耗时超过预算×该系数记为掉帧（1.0 = 错过 vsync deadline）。 */
    const val DROPPED_DURATION_FACTOR = 1.0

    /** 采样帧数（首帧起连续 60 帧）。 */
    const val SAMPLE_COUNT = 60

    private const val P90_RANK = 0.9

    /** 帧耗时 P90（毫秒，nearest-rank）。少于 1 个样本时返回 0（无数据不降档）。 */
    fun p90(durationsMs: List<Double>): Double {
        if (durationsMs.isEmpty()) return 0.0
        val sorted = durationsMs.sorted()
        val index = (ceil(sorted.size * P90_RANK) - 1).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }

    /** 掉帧占比：帧耗时 > 预算×[DROPPED_DURATION_FACTOR] 的样本比例。 */
    fun droppedRatio(durationsMs: List<Double>, budgetMs: Double): Double {
        if (durationsMs.isEmpty()) return 0.0
        val dropped = durationsMs.count { it > budgetMs * DROPPED_DURATION_FACTOR }
        return dropped.toDouble() / durationsMs.size
    }

    /** 两个通道任一超限即降档；不满一整个采样窗（[SAMPLE_COUNT] 帧）不裁决。 */
    fun verdict(durationsMs: List<Double>, budgetMs: Double): GlassGateVerdict {
        val p90 = p90(durationsMs)
        val dropped = droppedRatio(durationsMs, budgetMs)
        val fullWindow = durationsMs.size >= SAMPLE_COUNT
        return GlassGateVerdict(
            downgrade = fullWindow && (p90 > budgetMs * P90_BUDGET_FACTOR || dropped > DROPPED_RATIO_LIMIT),
            p90Ms = p90,
            droppedRatio = dropped,
        )
    }
}

/**
 * 帧采样收集器（UI-06）：宿主逐帧喂入**帧耗时**（毫秒），集满 [sampleCount]
 * 个样本后回调 [GlassGatePolicy.verdict] 并结束。纯数据收集，不碰 Android
 * API——单测用假帧时钟直接喂 [onFrame]；Android 侧宿主用
 * `Window.addOnFrameMetricsAvailableListener` 取 `TOTAL_DURATION` 喂入。
 *
 * @param budgetMs 单帧预算（1000 / 刷新率）。
 */
class Oe3GlassFrameGate(
    private val budgetMs: Double,
    private val sampleCount: Int = GlassGatePolicy.SAMPLE_COUNT,
    private val onVerdict: (GlassGateVerdict) -> Unit,
) {

    private val durations = ArrayList<Double>(sampleCount)

    /** 采样是否已完成（完成后宿主应停止喂帧）。 */
    val isDone: Boolean get() = durations.size >= sampleCount

    /**
     * 喂入当前帧的渲染耗时（毫秒）。返回 `true` 表示采样已结束且裁决已回调；
     * 之后继续喂帧是调用方的错误但不会崩溃（直接忽略，返回 true 便于宿主摘除监听）。
     */
    fun onFrame(frameDurationMs: Double): Boolean {
        if (isDone) return true
        durations += frameDurationMs
        if (!isDone) return false
        onVerdict(GlassGatePolicy.verdict(durations, budgetMs))
        return true
    }
}
