package io.github.nyzeep.orchestella.ui.design.components

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3DialogEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import java.util.WeakHashMap
import java.util.function.Consumer
import java.util.function.IntConsumer

/**
 * 全项目唯一的对话框实现（UI-15 §2）。
 *
 * 为什么不是 `MaterialAlertDialogBuilder`：v4 之后弹窗语言只有一种——32dp
 * 圆角（`OeShape.alert`）、`surface` 底、Oe3 字体与间距。平台 Dialog 的
 * 圆角与排版由系统主题决定，改一处控件要连带动整个 overlay，正是本波要
 * 清掉的第四套语言。
 *
 * 为什么是「Activity 内容层的 Compose 覆盖层」而不是 `Dialog` 窗口：
 * 1. `TerminalActivity` 历史上是 `Activity`，对话框若走 Dialog 窗口就需要
 *    手工补 ViewTree 生命周期所有者；内容层覆盖层随宿主 view tree 自然拿到
 *    所有者。
 * 2. 终端画面是自绘 View，覆盖层是它的兄弟节点，层级上天然在上；不会出现
 *    Dialog 窗口与 SurfaceView 抢 z 序的问题。
 * 3. 返回键走 `BackHandler`，与壳层 `OeBackStack` 的拦截顺序一致。
 *
 * 覆层只在有对话框时挂载，关闭即摘除；不常驻，因此不干扰页面触摸。
 */
class OeDialogHost private constructor(private val activity: Activity) {

    private val current = mutableStateOf<OeDialogSpec?>(null)
    private var overlay: ComposeView? = null

    fun show(spec: OeDialogSpec) {
        activity.runOnUiThread {
            if (activity.isFinishing) return@runOnUiThread
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) {
                return@runOnUiThread
            }
            current.value = spec
            ensureOverlay()
        }
    }

    fun dismiss() {
        current.value = null
        val view = overlay ?: return
        overlay = null
        // 摘除延后一帧：点击事件仍在 Compose 分发栈里时移除自己的宿主会撕裂
        // 手势流；post 让事件先完成，再拆 view。期间内容已为空，不可见。
        view.post { (view.parent as? ViewGroup)?.removeView(view) }
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val themeStore = ThemeStore(activity)
        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val theme = remember { themeStore.resolve() }
                val textScale = remember { themeStore.textScaleFactor() }
                Oe3Theme(
                    theme = theme,
                    textScale = textScale,
                    reduceMotion = !ValueAnimator.areAnimatorsEnabled(),
                ) {
                    val spec = current.value
                    if (spec != null) {
                        OeDialogOverlay(spec = spec, onDismiss = ::dismiss)
                    }
                }
            }
        }
        root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay = view
    }

    companion object {
        private val hosts = WeakHashMap<Activity, OeDialogHost>()

        @JvmStatic
        fun attach(activity: Activity): OeDialogHost =
            hosts.getOrPut(activity) { OeDialogHost(activity) }

        /** Java 调用点的入口（View 体系拿不到 Activity 时先解包 Context）。 */
        @JvmStatic
        fun of(context: Context): OeDialogHost? =
            context.findActivity()?.let { attach(it) }
    }
}

/** 对话框描述：所有 View 世界的确认/选择/输入都归一到这一个形状。 */
class OeDialogSpec(
    val title: String,
    val message: String? = null,
    val items: List<String> = emptyList(),
    val onItemSelected: ((Int) -> Unit)? = null,
    val input: OeDialogInput? = null,
    /** 长文本（许可证、说明）：等宽可选、限高滚动。 */
    val longText: String? = null,
    val positive: OeDialogAction? = null,
    val neutral: OeDialogAction? = null,
    val negative: OeDialogAction? = null,
    val cancelable: Boolean = true,
    val testTag: String? = null,
)

class OeDialogInput(
    val label: String? = null,
    val initial: String = "",
    val monospace: Boolean = false,
    val multiline: Boolean = false,
)

/**
 * [onClick] 收到输入框当前文本；没有输入框时为空串。点按先关窗再回调，
 * 回调里可以直接 `show` 下一个对话框（旧的已开始摘除）。
 */
class OeDialogAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: (String) -> Unit = {},
)

@Composable
private fun OeDialogOverlay(spec: OeDialogSpec, onDismiss: () -> Unit) {
    val colors = Oe3ThemeValues.colors
    var input by remember(spec) { mutableStateOf(spec.input?.initial.orEmpty()) }

    BackHandler(enabled = true) { if (spec.cancelable) onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .pointerInput(spec) {
                detectTapGestures { if (spec.cancelable) onDismiss() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Oe3DialogEnterMotion {
            Column(
                modifier = Modifier
                    .padding(horizontal = Oe3Spacing.xl)
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(OeShape.alert))
                    .background(colors.surface.oe3Color())
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(spec.testTag?.let { Modifier.testTag(it) } ?: Modifier)
                    .padding(
                        start = Oe3Spacing.xl,
                        end = Oe3Spacing.xl,
                        top = Oe3Spacing.xl,
                        bottom = Oe3Spacing.sm,
                    ),
            ) {
                Text(
                    text = spec.title,
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                )

                spec.message?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier.padding(top = Oe3Spacing.sm),
                        style = Oe3Typography.style(Oe3TextRole.Body),
                        color = colors.onSurfaceVariant.oe3Color(),
                    )
                }

                if (spec.items.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = Oe3Spacing.sm)) {
                        spec.items.forEachIndexed { index, label ->
                            Text(
                                text = label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        onDismiss()
                                        spec.onItemSelected?.invoke(index)
                                    }
                                    .padding(vertical = Oe3Spacing.sm),
                                style = Oe3Typography.style(Oe3TextRole.Body),
                                color = colors.onSurface.oe3Color(),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                spec.input?.let { inputSpec ->
                    val focusRequester = remember(spec) { FocusRequester() }
                    LaunchedEffect(spec) { focusRequester.requestFocus() }
                    inputSpec.label?.let { label ->
                        Text(
                            text = label,
                            modifier = Modifier.padding(top = Oe3Spacing.md),
                            style = Oe3Typography.style(Oe3TextRole.Caption),
                            color = colors.onSurfaceVariant.oe3Color(),
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Oe3Spacing.sm)
                            .clip(RoundedCornerShape(OeShape.md))
                            .background(colors.surfaceHigh.oe3Color())
                            .focusRequester(focusRequester)
                            .padding(horizontal = Oe3Spacing.lg, vertical = Oe3Spacing.md),
                        textStyle = Oe3Typography.style(Oe3TextRole.Body).copy(
                            color = colors.onSurface.oe3Color(),
                            fontFamily = if (inputSpec.monospace) FontFamily.Monospace else null,
                        ),
                        cursorBrush = SolidColor(colors.primary.oe3Color()),
                        maxLines = if (inputSpec.multiline) 6 else 1,
                    )
                }

                spec.longText?.let { text ->
                    SelectionContainer {
                        Text(
                            text = text,
                            modifier = Modifier
                                .padding(top = Oe3Spacing.md)
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            style = Oe3Typography.style(Oe3TextRole.Caption),
                            color = colors.onSurfaceVariant.oe3Color(),
                        )
                    }
                }

                // 三个及以上动作时竖排：横排会在 360dp 宽的中文按钮上挤成
                // 两行省略号（AVF 授权链就是三动作）。两动作保持右对齐横排。
                val actions = buildList {
                    addAll(listOfNotNull(spec.neutral, spec.negative))
                    spec.positive?.let { add(it) }
                }
                if (actions.size > 2) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Oe3Spacing.sm),
                        horizontalAlignment = Alignment.End,
                    ) {
                        actions.forEach { action ->
                            OeDialogTextButton(
                                label = action.label,
                                color = action.color(),
                                fillWidth = true,
                                onClick = {
                                    onDismiss()
                                    action.onClick(input)
                                },
                            )
                        }
                    }
                } else if (actions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Oe3Spacing.md),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        actions.forEach { action ->
                            OeDialogTextButton(
                                label = action.label,
                                color = action.color(),
                                onClick = {
                                    onDismiss()
                                    action.onClick(input)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OeDialogAction.color(): Color {
    val colors = Oe3ThemeValues.colors
    return if (destructive) colors.error.oe3Color() else colors.primary.oe3Color()
}

@Composable
private fun OeDialogTextButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    fillWidth: Boolean = false,
) {
    Box(
        modifier = Modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 48.dp)
            .clip(OeShape.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = Oe3Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = color,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * View 世界（Java）的便捷入口：只暴露基本类型，避免把 Compose 类型带进
 * Java 调用点。四个形态覆盖现存全部非 Compose 弹窗。
 */
object OeDialogs {

    @JvmStatic
    fun confirm(
        context: Context,
        title: String,
        message: String?,
        positiveLabel: String,
        negativeLabel: String?,
        destructive: Boolean,
        onPositive: Runnable,
    ) {
        val host = OeDialogHost.of(context) ?: return
        host.show(
            OeDialogSpec(
                title = title,
                message = message,
                positive = OeDialogAction(positiveLabel, destructive) { onPositive.run() },
                negative = negativeLabel?.let { OeDialogAction(it) },
            ),
        )
    }

    @JvmStatic
    fun choose(
        context: Context,
        title: String,
        items: Array<String>,
        onPick: IntConsumer,
    ) {
        val host = OeDialogHost.of(context) ?: return
        host.show(
            OeDialogSpec(
                title = title,
                items = items.toList(),
                onItemSelected = { index -> onPick.accept(index) },
            ),
        )
    }

    @JvmStatic
    fun input(
        context: Context,
        title: String,
        message: String?,
        initial: String,
        positiveLabel: String,
        negativeLabel: String?,
        multiline: Boolean,
        monospace: Boolean,
        onPositive: Consumer<String>,
    ) {
        val host = OeDialogHost.of(context) ?: return
        host.show(
            OeDialogSpec(
                title = title,
                message = message,
                input = OeDialogInput(
                    initial = initial,
                    monospace = monospace,
                    multiline = multiline,
                ),
                positive = OeDialogAction(positiveLabel) { text -> onPositive.accept(text) },
                negative = negativeLabel?.let { OeDialogAction(it) },
            ),
        )
    }

    /** 只用于「内容比按钮多」的确认（许可证、长说明）。 */
    @JvmStatic
    fun longText(
        context: Context,
        title: String,
        text: String,
        positiveLabel: String,
        onPositive: Runnable,
    ) {
        val host = OeDialogHost.of(context) ?: return
        host.show(
            OeDialogSpec(
                title = title,
                longText = text,
                positive = OeDialogAction(positiveLabel) { onPositive.run() },
            ),
        )
    }
}
