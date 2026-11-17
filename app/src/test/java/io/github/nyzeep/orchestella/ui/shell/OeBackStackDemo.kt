package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3IconStyle
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * 栈式导航的最小演示目的地（工单 UI-03）。
 *
 * 本波只交付**栈能力本身**：真实的二级页面改造是 Wave 4（UI-13）的事，
 * 五个主页面一律不动。演示页统一按目标形态呈现——[OeScaffold]（leading =
 * 返回）+ 单标题 + 底部坞避让——用来验证 push/pop、系统返回键与留白。
 * 文案硬编码中文：演示页不进生产信息架构，不占 strings.xml。
 *
 * 源集位置（code-review 收口裁决）：本文件只在 `src/test`，生产 main 源集
 * 对它零引用——生产 APK 不再携带演示页；栈行为用例与金样在测试侧继续
 * 经 [Oe3App] 的真实组合驱动它。
 */
object OeBackStackDemo {

    const val KEY = "oe-demo-backstack"

    /** 演示页的标题（单标题规则：全屏只有栏上这一个）。 */
    const val TITLE = "演示二级页"

    /** 测试锚点：演示页根节点，与页内"再推一层"触发器。 */
    const val PAGE_TAG = "oe-demo-backstack-page"
    const val NESTED_TRIGGER_TAG = "oe-demo-push-trigger-nested"

    /** 构造演示目的地；返回按钮从注入的栈（[LocalOeBackStack]）pop。 */
    fun destination(): OeDestination = OeDestination(
        key = KEY,
        title = TITLE,
        content = { DemoPage() },
    )

    @Composable
    private fun DemoPage() {
        val stack = LocalOeBackStack.current
        val colors = Oe3ThemeValues.colors
        OeScaffold(
            title = TITLE,
            modifier = Modifier.testTag(PAGE_TAG),
            leading = {
                Box(
                    modifier = Modifier
                        .size(Oe3Sizes.minTouchTarget)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { stack?.pop() }
                        .semantics { contentDescription = "返回" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(Oe3Sizes.interactiveVisual)
                            .clip(CircleShape)
                            .background(Color(colors.surfaceHigh)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Oe3Icon(image = BackChevron(), contentDescription = null)
                    }
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Oe3Spacing.pageInset)
                    .padding(top = Oe3Spacing.lg, bottom = LocalOe3PageBottomPadding.current),
            ) {
                Oe3Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "二级页面栈已接通",
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = Color(colors.onSurface),
                    )
                    Spacer(Modifier.height(Oe3Spacing.xs))
                    Text(
                        text = "这是 OeBackStack 的最小演示目的地：push 进入、返回键 pop 回原页，真实二级页面由后续工单接入。",
                        style = Oe3Typography.style(Oe3TextRole.Body),
                        color = Color(colors.onSurfaceVariant),
                    )
                    // 多级栈验证锚点：演示页自身也是"可以 push 别人的页面"。
                    // 48dp 命中、无文字标签，只在测试语义树里可见。
                    Box(
                        modifier = Modifier
                            .size(Oe3Sizes.minTouchTarget)
                            .testTag(NESTED_TRIGGER_TAG)
                            .clickable { stack?.push(destination()) },
                    )
                }
            }
        }
    }
}

/**
 * 返回箭头：本波在壳层内自绘（24dp 网格、1.75 线宽，与 [Oe3IconStyle] 同一
 * 族）。不新增图标资产、不改既有图标——Wave 4 若把返回箭头转正，再进
 * `Oe3Icons` 并补许可清单。
 */
private fun BackChevron(): ImageVector = ImageVector.Builder(
    name = "Oe3.BackChevron",
    defaultWidth = Oe3IconStyle.size,
    defaultHeight = Oe3IconStyle.size,
    viewportWidth = Oe3IconStyle.viewport,
    viewportHeight = Oe3IconStyle.viewport,
).addPath(
    pathData = addPathNodes("M14.5 5 8 11.5l6.5 6.5"),
    fill = null,
    stroke = SolidColor(Color.Black),
    strokeLineWidth = Oe3IconStyle.strokeWidth,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()
