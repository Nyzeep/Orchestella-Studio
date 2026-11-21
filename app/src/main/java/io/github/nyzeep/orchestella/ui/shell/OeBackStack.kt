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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

internal enum class StackFlight { None, Push, Pop }

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
     */
    fun pop(): Boolean {
        val current = transitionState
        return if (current.flight == StackFlight.Push) {
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
            progress.animateTo(
                targetValue = if (snapshot.flight == StackFlight.Push) 1f else 0f,
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

private enum class LayerRole { RestTop, Under, Moving }

private const val BASE_STATE_KEY = "oe-backstack-base"

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
        try {
            progressFlow.collect { event ->
                predictiveProgress = event.progress
            }
            val releaseProgress = predictiveProgress
            predictiveProgress = null
            if (releaseProgress != null) {
                backStack.popFromProgress(1f - releaseProgress)
            } else {
                backStack.pop()
            }
        } catch (cancelled: CancellationException) {
            predictiveProgress = null
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
        transition.flight == StackFlight.Pop -> buildList {
            if (activeUnderIndex >= 0 && activeUnderIndex < transition.entries.size) {
                add(transition.entries[activeUnderIndex] to LayerRole.Under)
            }
            if (pushSpring != null) {
                transition.leaving?.let { add(it to LayerRole.Moving) }
            }
        }
        else -> emptyList()
    }

    Box(modifier.fillMaxSize()) {
        if (!stackIdle && activeUnderIndex == -1) {
            stateHolder.SaveableStateProvider(key = BASE_STATE_KEY) {
                StackLayer(role = LayerRole.Under, progress = effectiveProgress, content = base)
            }
        }
        for ((entry, role) in visibleEntries) {
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
                    }
                    LayerRole.Moving -> {
                        translationX = OeBackStackTransitionSpec.movingOffsetPx(size.width, p)
                    }
                }
            },
    ) {
        if (plate) OePagePlate()
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
