package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 模糊半径离散桶化（W2-05）：把任意连续的请求半径对齐到固定步长网格上。
 *
 * 机理：HWUI 每个模糊半径都会生成独立的模糊管线并占用一条 ShaderCache 条目，
 * 半径连续变化时缓存被持续逐出、命中率崩塌；把半径钳到离散桶后，同一桶内的
 * 全部请求复用同一份管线缓存（ReFra 模式的净室重写）。
 *
 * 借鉴声明（工单合规）：模糊半径离散桶化的公式与语义借鉴 ReFra
 * （Apache-2.0 许可，github.com/IacobIonut01/ReFra）`BlurUtils` 的工程模式，
 * 本文件为净室重写——仅依据其公开行为描述自行实现公式与注释。由于「最近邻
 * 取整到步长网格」的数学定义唯一，本函数与上游实现公式必然一致，这属于数学
 * 事实而非表达复制；上游亦未包含对本仓代码的复制。
 */
@Stable
internal object Oe3GlassBlurQuantize {

    /**
     * 桶宽：4dp 网格。现行配方半径（scroll edge 12dp、L1 16dp、Ultra 28dp）
     * 全部落在桶心上，量化对既有产物是恒等映射。
     */
    val STEP: Dp = 4.dp
}

/**
 * 把半径对齐到 [step] 网格（纯函数）：
 *
 * - `value <= 0` 归零：非正半径就是「无模糊」语义，不参与桶化。
 * - 步长小于 1dp 钳到 1dp：桶化的前提是缓存条目数有界，亚像素步长会让
 *   网格本身退化成连续值。
 * - 正半径按最近桶取整（`.5` 上取整语义遵循 [roundToInt]），返回值保证
 *   是步长的整数倍。
 */
internal fun Dp.quantizeBlur(step: Dp = Oe3GlassBlurQuantize.STEP): Dp {
    if (value <= 0f) return 0.dp
    val s = if (step.value < 1f) 1f else step.value
    return ((value / s).roundToInt() * s).dp
}
