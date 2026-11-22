package io.github.nyzeep.orchestella.ui.oe3.glass

import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * RuntimeShader 编译池（W2-05 净室增量 A）：按 key 缓存 `(source, RuntimeShader)`
 * 二元组，让 AGSL 源串在同一进程里只编译一次。
 *
 * == 借鉴声明（净室重写）==
 *
 * 池化**模式**借鉴 Glasmio（Apache-2.0，github.com/neoruaa/Glasmio）的
 * `RuntimeShaderCache`：同一 key 下「源串逐字一致 → 复用实例，否则重编译并
 * 覆盖缓存槽」。本实现为 Orchestella 自有契约的净室重写，未复制其任何源码
 * ——尤其未照搬其手写 AGSL uniform 解析器（那是为通用 idle 池服务的高表达
 * 密度代码，本仓契约更简单，见下）。
 *
 * == 为何不需要 Glasmio 式 idle 池 + uniform 归零 ==
 *
 * Glasmio 的 idle 池要先把 uniform 全部归零再转手（它不知道借用方会 set 什么），
 * 因此必须解析源串声明的每个 uniform 槽位。本仓没有这个问题：
 * - **单着色器消费点**：每个 key 只有一个消费方（[Oe3LiquidGlass] 的
 *   `effectFor`），借用方在每次复用后、构造 RenderEffect 前会**完整覆写全部
 *   uniform**（extent/radii/bezel/shift/rim/highlight/luminanceRollOff/maxLuma/
 *   aberration 一个不落）——「全量覆写」与「归零后再写」在可观测状态上等价；
 * - 换 source / 换 key 的新实例自然替代旧实例，旧实例随 RenderEffect 快照
 *   存活到不可达，无需归还逻辑。
 *
 * == 契约 ==
 *
 * - **前提：单消费点**（W2-05 审查轴二 P2-2）：每个 key 当前只有一个消费方
 *   （[Oe3LiquidGlass]，生产组合中同时只有一个 L2 表面）。共享实例的 uniform
 *   在 RenderEffect 创建时快照，此后互不传播——因此两个**不同 shape/size 的
 *   并存消费点**会各写各的 uniform 再各建快照，互踩仅发生在「快照前的窗口」；
 *   当前单消费点下不存在并发写。若未来出现第二个并存消费点，必须按消费点
 *   扩 key（或改回 per-scope 发放），不得直接复用本池单槽。
 * - 命中且 source 逐字一致 → 返回**同一实例**（调用方直接复用）。
 * - key 命中但 source 不同 → 重新编译并覆盖该槽；不同 key 同 source → 各自
 *   独立实例（不跨 key 共享）。
 * - **编译异常内部吞掉返回 null**（本仓选定的契约）：AGSL 编译失败
 *   （RuntimeException，含 IllegalArgumentException）与运行环境缺
 *   RuntimeShader（LinkageError）都不向外抛——调用方拿到 null 按「折射不可用」
 *   回落 L1 模糊，异常细节打 `Oe3Glass` warn 日志。失败不落缓存槽：下次
 *   obtain 同一坏源串会重试并再失败，不会把毒实例传给任何人。
 * - 线程安全：[Synchronized] 全量互斥。玻璃组合只发生在主线程，竞争面极小，
 *   宁可简单不做并发分级。
 *
 * RuntimeShader 本体是 API 33+（AGSL）：调用链上游
 * [io.github.nyzeep.orchestella.ui.oe3.glass.rememberOe3Refraction] 已按
 * `MIN_REFRACTION_API` 守卫（与 [Oe3LiquidGlass] 同款 `@RequiresApi` 标注模式），
 * 这里标注让 lint 认可调用链与编译期上下文。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object Oe3RuntimeShaderPool {

    private class Entry(val source: String, val shader: RuntimeShader)

    private val cache = HashMap<String, Entry>()

    /**
     * 取（或新建）key 对应的 [RuntimeShader]。命中且 [source] 逐字一致时复用
     * 同一实例；否则编译并覆盖缓存槽。编译失败返回 null（契约见类 KDoc）。
     *
     * 借用方义务：每次复用后、构造 RenderEffect 前完整覆写全部 uniform
     * （[Oe3LiquidGlass.effectFor] 本就全量 set，天然满足）。
     */
    @Synchronized
    fun obtain(key: String, source: String): RuntimeShader? {
        cache[key]?.let { entry ->
            if (entry.source == source) return entry.shader
        }
        val shader = try {
            RuntimeShader(source)
        } catch (failure: RuntimeException) {
            Log.w("Oe3Glass", "AGSL compile failed for $key, refraction falls back", failure)
            return null
        } catch (failure: LinkageError) {
            Log.w("Oe3Glass", "RuntimeShader unavailable for $key, refraction falls back", failure)
            return null
        }
        cache[key] = Entry(source, shader)
        return shader
    }
}
