package io.github.nyzeep.orchestella.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues

/**
 * 栈式导航的目的地（工单 UI-03，方案 B）。
 *
 * [key] 是栈内标识（Wave 4 的真实二级页用它做恢复与判等），[title] 供
 * `OeScaffold` 单标题规则使用，[content] 是整页组合体——与 [Oe3Pages] 的
 * 页面槽位同一套写法，二级页面统一以 `OeScaffold(leading = 返回)` 呈现。
 */
@Immutable
data class OeDestination(
    val key: String,
    val title: String,
    val content: @Composable () -> Unit,
)

/**
 * 可返回的页面栈（工单 UI-03 实施要点 1·方案 B）。
 *
 * 方案 B 的取舍：不引入 androidx.navigation / Fragment / 多 Activity，与
 * 单 Activity Compose 壳零依赖冲突。栈只承载**推入的二级目的地**；底层
 * 五页由 pager 表达，pop 到底即回 pager。状态放在 [androidx.compose.runtime]
 * 快照列表里，所以转场与重组共享同一真值，系统返回键（[OeBackStackHost]
 * 里的 `BackHandler`）与预测式返回手势通过返回派发器拿到同一份状态。
 *
 * 页面栈本身不处理恢复：目的地携带组合体，进程级恢复交给 Wave 4（UI-13）
 * 把栈提升进 `Oe3ShellController` 时一并解决（键已预留）。
 */
class OeBackStack {

    private val entries = mutableStateListOf<OeDestination>()

    /** 推入层数；0 表示停在 pager。 */
    val size: Int get() = entries.size

    /** 是否还能 pop——返回派发器的 enabled 与它同源。 */
    val canPop: Boolean get() = entries.isNotEmpty()

    /** 栈顶目的地；空栈为 null（显示 pager）。 */
    val top: OeDestination? get() = entries.lastOrNull()

    /** 位置访问：栈是位置语义，不是集合语义。 */
    operator fun get(index: Int): OeDestination = entries[index]

    /** 推入一个二级目的地。 */
    fun push(destination: OeDestination) {
        entries.add(destination)
    }

    /**
     * 弹出栈顶。返回值即"本层是否消费了返回事件"：空栈返回 false，让
     * 宿主的上一层（tab 回工作站 / 系统退出）接管。
     */
    fun pop(): Boolean {
        if (entries.isEmpty()) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    /** 一次性清空（切换 dock tab 时走这里，不逐层播放返回动画）。 */
    fun popAll() {
        entries.clear()
    }
}

/** 壳层自持栈的工厂：宿主 Activity 未显式提供时由 [Oe3App] 记住一份。 */
@Composable
fun rememberOeBackStack(): OeBackStack = remember { OeBackStack() }

/**
 * 栈句柄的注入点：未来二级页面（UI-13）在页面槽位里读它拿 [OeBackStack]
 * 做 push；测试用同一入口驱动真实壳层。
 */
val LocalOeBackStack = staticCompositionLocalOf<OeBackStack?> { null }

/**
 * push/pop 转场的纯几何（motion token 的消费端，脱离组合可断言）。
 *
 * 单进度驱动两个方向：进入/离场页的偏移 = (1−p)×页宽（push 时 p 0→1 从
 * 右缘滑入，pop 时 p 1→0 滑回右缘）；其下一层的缩放 1→0.8 与压暗 0→15%
 * 同样绑定 p。初速用"进度速度"表达：偏移对时间的导数 = −页宽×进度速度，
 * 进度初速 9/s 正是"初速 = 9×距离/s"，与页宽、屏幕密度无关。
 */
object OeBackStackTransitionSpec {

    /** 初速 = 9 × 距离/s，换算成进度即 9/s（v4 spec §1.5）。 */
    const val INITIAL_VELOCITY_PER_SECOND = OeMotion.PAGE_PUSH_VELOCITY_FACTOR

    /** 以进度为单位的初速；方向由动画目标决定（push 向 1 收敛）。 */
    fun initialVelocity(): Float = INITIAL_VELOCITY_PER_SECOND

    /** 移动层偏移：p=0 时整页在右缘之外，p=1 时落位。 */
    fun movingOffsetPx(pageWidthPx: Float, progress: Float): Float =
        (1f - progress) * pageWidthPx

    /** 其下一层缩放：p=0 → 1（原位），p=1 → 0.8（压后的层叠态）。 */
    fun underScale(progress: Float): Float =
        1f - OeMotion.PAGE_PUSH_SCALE_DELTA * progress

    /** 其下一层压暗：p=1 → 15%。 */
    fun underDim(progress: Float): Float =
        OeMotion.PAGE_PUSH_UNDER_DIM * progress
}

/** 飞行相位：push/pop 转场进行中；None = 已落位（渲染沿用落位布局）。 */
private enum class StackFlight { None, Push, Pop }

/**
 * 栈宿主：底层 pager 与推入目的地共同的可视容器，同时是系统返回键的
 * 拦截点（[BackHandler] 与 [OeBackStack.canPop] 同源，预测式返回手势经
 * 返回派发器同样落到 [OeBackStack.pop]）。
 *
 * 转场（v4 spec §1.5，UI-03 交付 2）：push 用 [Oe3ThemeValues.motion.pagePushSpring]
 * —— spring(300ms, bounce 0)，初速 9×距离/s；同时前一层缩至 80% 并压暗
 * 15%（[OeBackStackTransitionSpec]）。pop 反向播放同一几何。减动效下
 * `pagePushSpring == null`：进度直接落到终态，无任何中间帧（motion.md 红线）。
 *
 * [base] 是 pager；背景层（壁纸/渐变）不进本组件——玻璃要采样的背景
 * 必须保持不缩放、不压暗（[Oe3App] 把本组件放在背景层之上、坞之下）。
 *
 * 实现注记：转场布局由 (飞行相位, 栈深, 进度) 纯派生，相位在组合期同步
 * 写入快照（用"上一帧栈深/栈顶"的 remember 模式），中途重组不闪帧；中断
 * 中的转场以最新栈深为准重新起跳，动效不阻塞输入（motion.md 规则 5）。
 */
@Composable
fun OeBackStackHost(
    backStack: OeBackStack,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
) {
    val motion = Oe3ThemeValues.motion
    val pushSpring = motion.pagePushSpring

    BackHandler(enabled = backStack.canPop) {
        backStack.pop()
    }

    val depth = backStack.size
    val currentTop = backStack.top

    // "上一帧"记忆：方向判定与离场层的身份都从这里来，组合期同步可得。
    val previousDepth = remember { mutableIntStateOf(depth) }
    val lastTop = remember { mutableStateOf<OeDestination?>(null) }
    val flight = remember { mutableStateOf(StackFlight.None) }
    val leaving = remember { mutableStateOf<OeDestination?>(null) }
    // 其下一层的栈下标；-1 表示 pager 充当其下一层。
    val underIndex = remember { mutableIntStateOf(-1) }

    when {
        depth > previousDepth.intValue -> {
            flight.value = StackFlight.Push
            leaving.value = null
            underIndex.intValue = if (depth >= 2) depth - 2 else -1
        }
        depth < previousDepth.intValue && previousDepth.intValue > 0 -> {
            flight.value = StackFlight.Pop
            leaving.value = lastTop.value
            underIndex.intValue = if (depth >= 1) depth - 1 else -1
        }
    }
    SideEffect {
        previousDepth.intValue = depth
        lastTop.value = currentTop
    }

    val progress = remember { Animatable(0f) }
    LaunchedEffect(depth) {
        when (flight.value) {
            StackFlight.Push -> {
                if (pushSpring == null) {
                    progress.snapTo(1f)
                } else {
                    progress.snapTo(0f)
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = pushSpring,
                        initialVelocity = OeBackStackTransitionSpec.initialVelocity(),
                    )
                }
                flight.value = StackFlight.None
            }
            StackFlight.Pop -> {
                if (pushSpring == null) {
                    progress.snapTo(0f)
                } else {
                    progress.snapTo(1f)
                    progress.animateTo(targetValue = 0f, animationSpec = pushSpring)
                }
                flight.value = StackFlight.None
                leaving.value = null
            }
            StackFlight.None -> Unit
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val pageWidthPx = with(density) { maxWidth.toPx() }
        val p = progress.value
        val underScale = OeBackStackTransitionSpec.underScale(p)
        val underDim = OeBackStackTransitionSpec.underDim(p)
        val movingOffset = OeBackStackTransitionSpec.movingOffsetPx(pageWidthPx, p)

        // 渲染序：base → 更深的目的地（静态层叠态）→ 其下一层（随进度）→ 移动层。
        Box(Modifier.fillMaxSize()) {
            if (underIndex.intValue == -1) {
                UnderLayer(scale = underScale, dim = underDim) { base() }
            } else {
                StaticLayer { base() }
                for (index in 0 until underIndex.intValue) {
                    StaticLayer { backStack[index].content() }
                }
                UnderLayer(scale = underScale, dim = underDim) {
                    backStack[underIndex.intValue].content()
                }
            }
            val movingDestination = when (flight.value) {
                StackFlight.Push -> currentTop
                StackFlight.Pop -> leaving.value
                StackFlight.None -> if (p >= 1f) currentTop else null
            }
            if (movingDestination != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = movingOffset },
                ) {
                    movingDestination.content()
                }
            }
        }
    }
}

/** 其下一层：随进度缩放并压暗（压暗是层内的黑纱，不遮到别的层）。 */
@Composable
private fun UnderLayer(scale: Float, dim: Float, content: @Composable () -> Unit) {
    // 静止在 pager 态（scale 1 / 无压暗）时不包 graphicsLayer：空栈路径与
    // 既有壳层逐像素一致，golden 不因栈宿主的存在而漂移。
    if (scale >= 1f && dim <= 0f) {
        content()
        return
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        scaleX = scale
        scaleY = scale
    }) {
        content()
        if (dim > 0f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        }
    }
}

/** 更深的目的地：固定在层叠终态（0.8 缩放 + 15% 压暗），被其上层覆盖。 */
@Composable
private fun StaticLayer(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = OeMotion.PAGE_PUSH_UNDER_SCALE
                scaleY = OeMotion.PAGE_PUSH_UNDER_SCALE
            },
    ) {
        content()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = OeMotion.PAGE_PUSH_UNDER_DIM)),
        )
    }
}
