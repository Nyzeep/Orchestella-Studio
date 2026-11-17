package io.github.nyzeep.orchestella.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

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
 * 栈宿主：底层 pager 与推入目的地共同的可视容器。
 *
 * 切片 1（本提交）先保证**状态与返回语义正确**：栈顶目的地静态覆盖在
 * pager 上，`BackHandler` 在 [OeBackStack.canPop] 时拦截系统返回并 pop。
 * push/pop 的 spring 转场与"前一页缩至 80% 压暗 15%"由切片 2 接上。
 *
 * [base] 是 pager；背景层（壁纸/渐变）不进本组件——玻璃要采样的背景
 * 必须保持不缩放、不压暗（[Oe3App] 把本组件放在背景层之上、坞之下）。
 */
@Composable
fun OeBackStackHost(
    backStack: OeBackStack,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
) {
    BackHandler(enabled = backStack.canPop) {
        backStack.pop()
    }
    Box(modifier) {
        base()
        backStack.top?.let { top ->
            top.content()
        }
    }
}
