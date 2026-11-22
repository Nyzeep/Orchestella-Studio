package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.runtime.Immutable
import kotlin.math.ceil

/**
 * 性能门的单次裁决（UI-06）：首 60 帧采样后给出是否降档。
 *
 * [downgrade] 为 true 时调用方把设备持久化到 [GlassDeviceCap.L0]；
 * [p90Ms]/[droppedRatio] 随裁决带出，便于日志与后续调参，不参与持久化。
 * 两个数值都是**剥离暖帧后的稳态子集**上的统计量（见 [GlassGatePolicy.WARMUP_FRAMES]）。
 */
@Immutable
data class GlassGateVerdict(
    val downgrade: Boolean,
    val p90Ms: Double,
    val droppedRatio: Double,
)

/**
 * 门控阈值的纯函数层（UI-06，v4 spec §1.7；W2-05 R-03 重标定）：采样对象是
 * **帧耗时**（`FrameMetrics.TOTAL_DURATION`，渲染一帧的实际耗时），不是帧交付
 * 间隔——间隔口径在匀速交付下恒≈预算，会误判一切。
 *
 * == W2-05 口径重标定（R-03 缺陷链修复，数值已按 bench-a 预演算定型）==
 *
 * 预算来源：Android 侧宿主优先取 API 31+ `FrameMetrics.DEADLINE` 作为单帧
 * 预算（该指标返回的是本帧可用预算时长（纳秒）＝FrameDeadline − IntendedVsync，
 * 系统已把刷新率与 SurfaceFlinger 松弛换算进这一值——SF 松弛可使实际预算
 * **大于** 1/刷新率，120Hz 机型实测预算 ≈8.33ms）。读不到时回退 1000/刷新率。
 *
 * - [P90_BUDGET_FACTOR] = 1.25（原 0.7）：0.7 在 8.33ms 预算下降档线只有
 *   5.83ms，低于无模糊基线的实测稳态 P90（bench-a 9–11ms）——把健康交付当
 *   卡顿，冷启动必锁 L0。预算已按 DEADLINE 实测口径后，稳态 ~9ms 帧耗时是
 *   **健康交付**（SF 松弛 + 持续交付可以跑满内容帧率）而非卡顿；只有稳态
 *   P90 超出预算 25% 才判「设备跟不上」。
 * - [DROPPED_DURATION_FACTOR] = 1.5（原 1.0）：掉帧通道只统计**真滞留帧**
 *   （超预算 50%，连 SF 松弛后的 deadline 也没赶上）。[DROPPED_RATIO_LIMIT]
 *   0.02 不变。两通道保持可分离：P90 通道覆盖 (1.25, 1.5]×预算 的持续超限，
 *   掉帧通道覆盖 >1.5×预算 的真滞留——后者在 P90 良好时也能独立检出
 *   （少而重的单帧尖峰，P90 通道看不见）。
 * - [WARMUP_FRAMES] = 6：冷启动前 6 帧混入首帧组合（布局/首次 climb）与
 *   着色器冷编译，与玻璃本身的稳态成本无关。这 6 帧**照常计入采样窗**
 *   （窗口完整性口径仍是 [SAMPLE_COUNT] 帧，假帧时钟契约不变），但**剥离出
 *   裁决窗口**：P90 与掉帧率只在暖帧后的稳态子集上统计。
 *
 * 任一通道超限即降档。所有阈值集中在这里，测试与调参只动这一处。
 */
object GlassGatePolicy {

    /** 冷启动预热帧数：计入采样窗、但剥离出裁决子集（R-03：首帧组合 + 着色器冷编译不可归因于玻璃）。 */
    const val WARMUP_FRAMES = 6

    /** P90 帧耗时相对帧预算的上限系数（预算×1.25，DEADLINE 实测口径）。 */
    const val P90_BUDGET_FACTOR = 1.25

    /** 掉帧率上限（占比超过即降档）。 */
    const val DROPPED_RATIO_LIMIT = 0.02

    /** 帧耗时超过预算×该系数记为掉帧（1.5 = 连 SF 松弛后的 deadline 也没赶上的真滞留）。 */
    const val DROPPED_DURATION_FACTOR = 1.5

    /** 采样帧数（首帧起连续 60 帧，**含**暖帧）。 */
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

    /**
     * 两个通道任一超限即降档；不满一整个采样窗（[SAMPLE_COUNT] 帧）不裁决。
     *
     * [durationsMs] 是**全部已喂样本（含暖帧）**；裁决前先把前
     * [warmupFrames] 帧剥离，P90 与掉帧率只在稳态子集上统计。防御：
     * warmupFrames ≥ 样本数时 drop 后为空 → p90/droppedRatio 空安全返回 0
     * → 不降档，符合「无数据不降档」契约（窗口完整性按含暖帧的全量口径
     * 判定，与 [Oe3GlassFrameGate.isDone] 一致）。
     */
    fun verdict(
        durationsMs: List<Double>,
        budgetMs: Double,
        warmupFrames: Int = WARMUP_FRAMES,
    ): GlassGateVerdict {
        val steady = if (warmupFrames > 0) durationsMs.drop(warmupFrames) else durationsMs
        val p90 = p90(steady)
        val dropped = droppedRatio(steady, budgetMs)
        val fullWindow = durationsMs.size >= SAMPLE_COUNT
        return GlassGateVerdict(
            downgrade = fullWindow && (p90 > budgetMs * P90_BUDGET_FACTOR || dropped > DROPPED_RATIO_LIMIT),
            p90Ms = p90,
            droppedRatio = dropped,
        )
    }
}

/**
 * 帧采样收集器（UI-06；W2-05 R-03 增加热身剥离）：宿主逐帧喂入**帧耗时**
 * （毫秒），集满 [sampleCount] 个样本后回调 [GlassGatePolicy.verdict] 并结束。
 * 纯数据收集，不碰 Android API——单测用假帧时钟直接喂 [onFrame]；Android 侧
 * 宿主用 `Window.addOnFrameMetricsAvailableListener` 取 `TOTAL_DURATION` 喂入。
 *
 * - [warmupFrames]：前 N 帧按 [GlassGatePolicy.WARMUP_FRAMES] 剥离出裁决子集
 *   （冷启动首帧组合与着色器冷编译不可归因于玻璃），但仍占满采样窗——
 *   `isDone` 口径 = 已喂总数 ≥ [sampleCount]（含暖帧）。
 * - 假帧时钟单测契约保持：喂帧即收集，集满恰好回调一次，完成后继续喂帧
 *   不崩溃、不再回调。
 *
 * @param budgetMs 单帧预算（优先 FrameMetrics.DEADLINE 实测值，回退 1000/刷新率）。
 */
class Oe3GlassFrameGate(
    private val budgetMs: Double,
    private val sampleCount: Int = GlassGatePolicy.SAMPLE_COUNT,
    private val warmupFrames: Int = GlassGatePolicy.WARMUP_FRAMES,
    private val onVerdict: (GlassGateVerdict) -> Unit,
) {

    private val durations = ArrayList<Double>(sampleCount)

    /** 采样是否已完成（完成后宿主应停止喂帧）。口径含暖帧：总喂满 [sampleCount] 即完成。 */
    val isDone: Boolean get() = durations.size >= sampleCount

    /**
     * 喂入当前帧的渲染耗时（毫秒）。返回 `true` 表示采样已结束且裁决已回调；
     * 之后继续喂帧是调用方的错误但不会崩溃（直接忽略，返回 true 便于宿主摘除监听）。
     */
    fun onFrame(frameDurationMs: Double): Boolean {
        if (isDone) return true
        durations += frameDurationMs
        if (!isDone) return false
        onVerdict(GlassGatePolicy.verdict(durations, budgetMs, warmupFrames))
        return true
    }
}
