package io.github.nyzeep.orchestella.ui.shell

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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

internal data class StackEntry(
    val id: Long,
    val destination: OeDestination,
    val argument: String? = null,
)

internal enum class StackFlight { None, Push, Pop, Rebound }

@Immutable
internal data class StackTransition(
    val transitionId: Long = 0L,
    val entries: List<StackEntry> = emptyList(),
    val flight: StackFlight = StackFlight.None,
    val leaving: StackEntry? = null,
    val underIndex: Int = -1,
    val startProgress: Float = 0f,
    val initialVelocity: Float = 0f,
)

/**
 * 可返回的页面栈（工单 UI-03 实施要点 1·方案 B）。
 *
 * 状态以不可变的 [StackTransition] 快照保存，每一次 `push` / `pop` / `popAll`
 * 递增单调 [StackTransition.transitionId]，避免组合期写 `MutableState`
 * 或仅靠栈深猜测方向引发的首帧闪烁与残影。
 */
class OeBackStack {

    internal var nextEntryId = 1L
        private set
    private var nextTransitionId = 1L
    private var transitionState by mutableStateOf(StackTransition())
    private val progress = Animatable(0f)
    private var animatedTransitionId by mutableLongStateOf(-1L)

    internal val transition: StackTransition get() = transitionState

    /** 推入层数；0 表示停在 pager。 */
    val size: Int get() = transitionState.entries.size

    /** 是否还能 pop——返回派发器的 enabled 与它同源。 */
    val canPop: Boolean get() = transitionState.entries.isNotEmpty()

    /** 栈顶目的地；空栈为 null（显示 pager）。 */
    val top: OeDestination? get() = transitionState.entries.lastOrNull()?.destination

    /** 位置访问：栈是位置语义，不是集合语义。 */
    operator fun get(index: Int): OeDestination = transitionState.entries[index].destination

    /** 推入一个二级目的地。 */
    fun push(destination: OeDestination) = push(destination, argument = null)

    internal fun push(destination: OeDestination, argument: String?) {
        val nextEntries = transitionState.entries + StackEntry(nextEntryId++, destination, argument)
        transitionState = StackTransition(
            transitionId = nextTransitionId++,
            entries = nextEntries,
            flight = StackFlight.Push,
            leaving = null,
            underIndex = nextEntries.size - 2,
            startProgress = 0f,
            initialVelocity = OeBackStackTransitionSpec.initialVelocity(),
        )
    }

    /**
     * 弹出栈顶。返回值即"本层是否消费了返回事件"：空栈返回 false，让
     * 宿主的上一层（tab 回工作站 / 系统退出）接管。
     *
     * W2-03 接管边界：任何飞行中（Push/Pop/Rebound）的返回都从当前进度与
     * 速度续弹，绝不瞬切到端点——中途的返回键按下只是把动画接过来。
     */
    fun pop(): Boolean {
        val current = transitionState
        return if (current.flight != StackFlight.None && current.entries.isNotEmpty()) {
            popFromProgress(progressFor(current), velocityFor(current))
        } else {
            popFromProgress(1f)
        }
    }

    /**
     * 从预测式返回手势的当前进度 [initialProgress]（1f = 未拉开，0f = 已滑出）
     * 继续弹出动画，供 M3 `PredictiveBackHandler` 接线使用。
     */
    internal fun popFromProgress(initialProgress: Float, initialVelocity: Float = 0f): Boolean {
        val current = transitionState.entries
        if (current.isEmpty()) return false
        val popped = current.last()
        val nextEntries = current.dropLast(1)
        transitionState = StackTransition(
            transitionId = nextTransitionId++,
            entries = nextEntries,
            flight = StackFlight.Pop,
            leaving = popped,
            underIndex = nextEntries.size - 1,
            startProgress = initialProgress.coerceIn(0f, 1f),
            initialVelocity = initialVelocity,
        )
        return true
    }

    /**
     * 预测式返回手势取消（W2-03）：不弹层，从被拉开的当前位置 [startProgress]
     * （1f = 未拉开）带初速弹簧回弹归位。条目不动，只推一次新的转场快照，
     * 动画收敛后 [settleTransition] 把飞行清回 None、恢复静止态。
     */
    internal fun reboundToRest(startProgress: Float, initialVelocity: Float = 0f): Boolean {
        val current = transitionState.entries
        if (current.isEmpty()) return false
        transitionState = StackTransition(
            transitionId = nextTransitionId++,
            entries = current,
            flight = StackFlight.Rebound,
            leaving = current.last(),
            underIndex = current.size - 2,
            startProgress = startProgress.coerceIn(0f, 1f),
            initialVelocity = initialVelocity,
        )
        return true
    }

    internal fun restore(entries: List<StackEntry>, firstNewEntryId: Long) {
        check(nextTransitionId == 1L && transitionState.entries.isEmpty())
        nextEntryId = maxOf(firstNewEntryId, (entries.maxOfOrNull { it.id } ?: 0L) + 1)
        transitionState = StackTransition(entries = entries)
    }

    internal fun progressFor(snapshot: StackTransition): Float =
        if (animatedTransitionId == snapshot.transitionId) progress.value else snapshot.startProgress

    private fun velocityFor(snapshot: StackTransition): Float =
        if (animatedTransitionId == snapshot.transitionId) progress.velocity else 0f

    internal suspend fun animate(snapshot: StackTransition, spring: SpringSpec<Float>?) {
        if (snapshot.flight == StackFlight.None) return
        if (spring != null) {
            val start = progressFor(snapshot)
            val velocity = if (animatedTransitionId == snapshot.transitionId) {
                progress.velocity
            } else {
                snapshot.initialVelocity
            }
            progress.snapTo(start)
            animatedTransitionId = snapshot.transitionId
            // Pop 向 0（弹离）；Push 与 Rebound（取消回弹）都向 1（落位）。
            progress.animateTo(
                targetValue = if (snapshot.flight == StackFlight.Pop) 0f else 1f,
                animationSpec = spring,
                initialVelocity = velocity,
            )
        }
        settleTransition(snapshot.transitionId)
    }

    /** 一次性清空（切换 dock tab 时走这里，显式即时重置，不播放返回动画）。 */
    fun popAll() {
        if (transitionState.entries.isEmpty() && transitionState.flight == StackFlight.None) return
        transitionState = StackTransition(
            transitionId = nextTransitionId++,
            entries = emptyList(),
            flight = StackFlight.None,
            leaving = null,
            underIndex = -1,
            startProgress = 0f,
        )
    }

    internal fun settleTransition(transitionId: Long) {
        val current = transitionState
        if (current.transitionId == transitionId && current.flight != StackFlight.None) {
            transitionState = current.copy(flight = StackFlight.None, leaving = null)
        }
    }

    internal fun containsKeyOrLeaving(key: String): Boolean {
        val current = transitionState
        return current.entries.any { it.destination.key == key } ||
            current.leaving?.destination?.key == key
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
 * 右缘滑入，pop 时 p 1→0 滑回右缘）；其下一层的缩放 1→0.92、压暗 0→10% 与
 * 同向视差 0→−12% 页宽同样绑定 p，三者共享"p=0 恒等位、p=1 满层叠"的锚定
 * （W2-03 M2 重定标）。初速用"进度速度"表达：偏移对时间的导数 = −页宽×进度
 * 速度，进度初速 6/s 正是"初速 = 6×距离/s"，与页宽、屏幕密度无关。
 */
object OeBackStackTransitionSpec {

    /** 初速 = 6 × 距离/s，换算成进度即 6/s（W2-03 M2 重定标）。 */
    const val INITIAL_VELOCITY_PER_SECOND = OeMotion.PAGE_PUSH_VELOCITY_FACTOR

    /** 以进度为单位的初速；方向由动画目标决定（push 向 1 收敛）。 */
    fun initialVelocity(): Float = INITIAL_VELOCITY_PER_SECOND

    /** 移动层偏移：p=0 时整页在右缘之外，p=1 时落位。 */
    fun movingOffsetPx(pageWidthPx: Float, progress: Float): Float =
        (1f - progress) * pageWidthPx

    /** 其下一层缩放：p=0 → 1（原位），p=1 → 0.92（压后的层叠态）。 */
    fun underScale(progress: Float): Float =
        1f - OeMotion.PAGE_PUSH_SCALE_DELTA * progress

    /** 其下一层压暗：p=1 → 10%。 */
    fun underDim(progress: Float): Float =
        OeMotion.PAGE_PUSH_UNDER_DIM * progress

    /**
     * 其下一层水平视差（W2-03）：p=0 → 0（与缩放/压暗同为恒等位——静止栈、
     * pop 收敛端零平移，不产生分数像素与 settle 跳变），p=1 → −12% 页宽。
     * 与移动层同向、幅度更小，构成前景快/背景慢的纵深视差。
     */
    fun underParallaxPx(pageWidthPx: Float, progress: Float): Float =
        OeMotion.PAGE_PUSH_UNDER_PARALLAX * progress * pageWidthPx

    /**
     * 栈分离渐变带（W2-03）：跟随移动层左缘的 24dp 黑色渐隐带，画在未裁剪的
     * 兄弟层上（[StackLayer.Moving] 自带 clip，渐变带不能画进它的
     * graphicsLayer）。p=0 / p=1 / 栈静止时零绘制；峰值不透明度
     * [OeMotion.PAGE_PUSH_SEAM_BAND_ALPHA]，紧贴页面边缘最深、向外渐隐。
     */
    fun seamBandLeftPx(pageWidthPx: Float, progress: Float, bandWidthPx: Float): Float =
        movingOffsetPx(pageWidthPx, progress) - bandWidthPx
}

private enum class LayerRole { RestTop, Under, Moving }

private const val BASE_STATE_KEY = "oe-backstack-base"

/**
 * 预测式返回手势的末段速度估算器（W2-03，净室借鉴 Metrolist `SwipeBackLayout`
 * 的"速度+距离"释放哲学，零源码拷贝）：只看最近两次手势事件，用其时间差估算
 * 事件进度（event.progress，0→1 越拉越开）的变化率。提交与取消动画把它取负
 * 换成屏幕进度速度作初速，让释放/回弹带真实手感。
 *
 * 纯数据层：时间由调用方注入（处理器传 `System.nanoTime()`），单测直接喂样本。
 */
internal class PredictiveBackVelocityEstimator {

    private var lastProgress = Float.NaN
    private var lastAtMs = 0L
    private var velocity = 0f

    /** 事件进度速度（进度单位/秒，方向与"越拉越开"一致）。 */
    fun eventVelocity(): Float = velocity

    fun onSample(progress: Float, atMs: Long) {
        if (!lastProgress.isNaN()) {
            val dtSeconds = (atMs - lastAtMs) / 1000f
            if (dtSeconds > 0f) {
                velocity = (progress - lastProgress) / dtSeconds
            }
        }
        lastProgress = progress
        lastAtMs = atMs
    }
}

/**
 * 栈宿主：底层 pager 与推入目的地共同的可视容器，同时是系统返回键的
 * 拦截点（[PredictiveBackHandler] 与 [OeBackStack.canPop] 同源，预测式返回手势
 * 跟手搓动并提交或取消恢复）。
 */
@Composable
fun OeBackStackHost(
    backStack: OeBackStack,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
) {
    OeBackStackHost(
        backStack = backStack,
        modifier = modifier,
        gestureProgress = null,
        base = base,
    )
}

@Composable
internal fun OeBackStackHost(
    backStack: OeBackStack,
    modifier: Modifier = Modifier,
    gestureProgress: Float? = null,
    base: @Composable () -> Unit,
) {
    val motion = Oe3ThemeValues.motion
    val pushSpring = motion.pagePushSpring

    var predictiveProgress by remember(backStack) { mutableStateOf<Float?>(null) }
    LaunchedEffect(backStack.canPop) {
        if (!backStack.canPop) {
            predictiveProgress = null
        }
    }
    PredictiveBackHandler(enabled = backStack.canPop && gestureProgress == null) { progressFlow ->
        val estimator = PredictiveBackVelocityEstimator()
        try {
            progressFlow.collect { event ->
                // 接管边界（W2-03）：飞行动画（Push/Pop/Rebound）独占进度，
                // 飞行中到达的手势事件不写搓动状态，避免与动画抢进度撕裂。
                if (backStack.transition.flight == StackFlight.None) {
                    estimator.onSample(event.progress, System.nanoTime() / 1_000_000L)
                    predictiveProgress = event.progress
                }
            }
            val releaseProgress = predictiveProgress
            predictiveProgress = null
            if (releaseProgress != null && backStack.transition.flight == StackFlight.None) {
                // 提交：从拉开的当前位置续弹，初速取手势末段速度（屏幕进度
                // 方向与事件进度相反）。一次提交恰好走一次 popFromProgress。
                backStack.popFromProgress(
                    initialProgress = 1f - releaseProgress,
                    initialVelocity = -estimator.eventVelocity(),
                )
            } else {
                backStack.pop()
            }
        } catch (cancelled: CancellationException) {
            val cancelledProgress = predictiveProgress
            predictiveProgress = null
            if (cancelledProgress != null && cancelledProgress > 0f) {
                // 取消（W2-03）：从当前位置弹簧回弹归位，不再瞬切；初速带
                // 手势末段速度，回弹手感与拉开速度一致。减动效下
                // pagePushSpring 为 null，animate 直接落位（瞬归）。
                backStack.reboundToRest(
                    startProgress = 1f - cancelledProgress,
                    initialVelocity = -estimator.eventVelocity(),
                )
            }
            throw cancelled
        }
    }

    val transition = backStack.transition
    val stateHolder = rememberSaveableStateHolder()
    val trackedIds = remember { mutableSetOf<Long>() }
    val activeEntryIds = remember(transition.entries, transition.leaving) {
        transition.entries.map { it.id } + listOfNotNull(transition.leaving?.id)
    }
    LaunchedEffect(activeEntryIds) {
        val activeSet = activeEntryIds.toSet()
        val retired = trackedIds.filter { it !in activeSet }
        retired.forEach { stateHolder.removeState(it) }
        trackedIds.removeAll(retired.toSet())
        trackedIds.addAll(activeSet)
    }

    LaunchedEffect(backStack, transition.transitionId, pushSpring) {
        backStack.animate(transition, pushSpring)
    }

    val hasGesture by remember(backStack, gestureProgress) {
        derivedStateOf { (gestureProgress ?: predictiveProgress) != null }
    }
    val isScrubbing = hasGesture &&
        transition.flight == StackFlight.None &&
        transition.entries.isNotEmpty()

    val effectiveProgress: () -> Float = {
        val currentGesture = (gestureProgress ?: predictiveProgress)?.coerceIn(0f, 1f)
        when {
            isScrubbing && currentGesture != null -> 1f - currentGesture
            transition.flight == StackFlight.None -> if (transition.entries.isEmpty()) 0f else 1f
            pushSpring == null -> if (transition.flight == StackFlight.Push) 1f else 0f
            else -> backStack.progressFor(transition)
        }
    }

    val stackIdle = transition.entries.isNotEmpty() &&
        (transition.flight == StackFlight.None || pushSpring == null) &&
        !isScrubbing
    val activeUnderIndex = if (isScrubbing) transition.entries.size - 2 else transition.underIndex

    val visibleEntries: List<Pair<StackEntry, LayerRole>> = when {
        stackIdle -> listOf(transition.entries.last() to LayerRole.RestTop)
        isScrubbing -> buildList {
            if (activeUnderIndex >= 0) {
                add(transition.entries[activeUnderIndex] to LayerRole.Under)
            }
            add(transition.entries.last() to LayerRole.Moving)
        }
        transition.flight == StackFlight.Push && transition.entries.isNotEmpty() -> buildList {
            if (activeUnderIndex >= 0) {
                add(transition.entries[activeUnderIndex] to LayerRole.Under)
            }
            add(transition.entries.last() to LayerRole.Moving)
        }
        transition.flight == StackFlight.Pop || transition.flight == StackFlight.Rebound ->
            buildList {
                if (activeUnderIndex >= 0 && activeUnderIndex < transition.entries.size) {
                    add(transition.entries[activeUnderIndex] to LayerRole.Under)
                }
                if (pushSpring != null) {
                    transition.leaving?.let { add(it to LayerRole.Moving) }
                }
            }
        else -> emptyList()
    }

    // 分离渐变带（W2-03）画在未裁剪的兄弟层上：Under 之上、Moving 之下，
    // 跟随移动层左缘。base 作为 Under 时（深度 1）同样有效。
    val bandVisible = visibleEntries.any { it.second == LayerRole.Moving } &&
        (visibleEntries.any { it.second == LayerRole.Under } || (!stackIdle && activeUnderIndex == -1))

    Box(modifier.fillMaxSize()) {
        if (!stackIdle && activeUnderIndex == -1) {
            stateHolder.SaveableStateProvider(key = BASE_STATE_KEY) {
                StackLayer(role = LayerRole.Under, progress = effectiveProgress, content = base)
            }
        }
        for ((entry, role) in visibleEntries) {
            if (role == LayerRole.Moving && bandVisible) {
                StackSeamBand(progress = effectiveProgress)
            }
            key(entry.id) {
                stateHolder.SaveableStateProvider(key = entry.id) {
                    StackLayer(
                        role = role,
                        progress = effectiveProgress,
                        // 目的地层自带底片：push/pop 期间它与 base 同时在屏，
                        // 没有不透明底就会透出上一页（残影）。base 是 pager，
                        // 子页已各自带底片，不能重复挂载。
                        plate = true,
                        content = entry.destination.content,
                    )
                }
            }
        }
    }
}

@Composable
private fun StackLayer(
    role: LayerRole,
    progress: () -> Float,
    plate: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress()
                // 移动层必须裁剪：底片随页面平移，不裁会盖住坞与相邻像素。
                clip = role == LayerRole.Moving
                when (role) {
                    LayerRole.RestTop -> Unit
                    LayerRole.Under -> {
                        val s = OeBackStackTransitionSpec.underScale(p)
                        scaleX = s
                        scaleY = s
                        // W2-03：底层同向视差（−12% 页宽 × p，前景快/背景慢的
                        // 纵深）；p=0 恒等位、p=1 满偏移（被页面覆盖，不可见）。
                        translationX = OeBackStackTransitionSpec.underParallaxPx(size.width, p)
                    }
                    LayerRole.Moving -> {
                        translationX = OeBackStackTransitionSpec.movingOffsetPx(size.width, p)
                    }
                }
            },
    ) {
        if (plate) {
            // W2-03 底片对位：Moving 平移时反向补偿底片内的壁纸偏移，让壁纸
            // 与根背景在屏幕坐标系保持对齐——只有页面内容随卡片横向滑入。
            OePagePlate(
                wallpaperParallaxFraction = if (role == LayerRole.Moving) {
                    { 1f - progress() }
                } else {
                    null
                },
            )
        }
        content()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (role == LayerRole.Under) {
                        val dim = OeBackStackTransitionSpec.underDim(progress())
                        if (dim > 0f) {
                            drawRect(Color.Black.copy(alpha = dim))
                        }
                    }
                },
        )
    }
}

/**
 * 栈分离渐变带（W2-03 M2）：宽度 [OeMotion.PAGE_PUSH_SEAM_BAND_DP]dp、峰值
 * [OeMotion.PAGE_PUSH_SEAM_BAND_ALPHA] 的黑色垂直渐隐带，紧贴移动层左缘
 * （最深处贴边、向外渐隐），承担移动页与底层的边缘分离（边缘分离一律走
 * drawBehind 渐变，不进阴影/描边体系，见 ConsistencySweepTest 红线）。
 * 画在宿主未裁剪的兄弟层上，[StackLayer.Moving] 渲染前插入；p == 0 / p == 1
 * / 栈静止时零绘制。
 */
@Composable
private fun StackSeamBand(progress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val p = progress()
                if (p <= 0f || p >= 1f) return@drawBehind
                val bandWidth = OeMotion.PAGE_PUSH_SEAM_BAND_DP.dp.toPx()
                val bandLeft = OeBackStackTransitionSpec.seamBandLeftPx(size.width, p, bandWidth)
                if (bandLeft >= size.width || bandLeft + bandWidth <= 0f) return@drawBehind
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = OeMotion.PAGE_PUSH_SEAM_BAND_ALPHA),
                    ),
                    topLeft = Offset(bandLeft, 0f),
                    size = Size(bandWidth, size.height),
                )
            },
    )
}
