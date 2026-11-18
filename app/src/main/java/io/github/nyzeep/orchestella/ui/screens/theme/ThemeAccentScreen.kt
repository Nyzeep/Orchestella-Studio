package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.shell.OeScaffold

/**
 * 强调色页（spec §2.2）：8 色板 + 跟随主题 + 自定义槽位编辑入口。
 * 槽位编辑仍是 [CustomSlotEditorDialog] 对话框（纯确认/编辑，不升级整页）。
 */
@Composable
fun ThemeAccentScreen(
    state: ThemeStudioState,
    onEvent: (ThemeStudioEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OeScaffold(
        title = stringResource(R.string.ui3_theme_page_accent),
        modifier = modifier.testTag("theme-accent:screen"),
        leading = { ThemeBackButton(onBack = onBack) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Oe3Spacing.pageInset)
                .padding(top = Oe3Spacing.xs, bottom = LocalOe3PageBottomPadding.current),
        ) {
            // AccentSection 自带「当前强调色」标题与激活态（跟随主题 chip /
            // 色板勾选），页头再加一个同名分区会与顶栏标题三重重复（code-review）。
            AccentSection(customAccent = state.customAccent, onEvent = onEvent)

            Spacer(Modifier.height(Oe3Spacing.md))
            ThemeSectionHeader(
                key = "custom-slots",
                title = stringResource(R.string.ui3_theme_custom_slots),
            )
            CustomSlotSection(
                slots = state.customSlots,
                activeId = state.theme.id,
                fallbackTheme = state.theme,
                onEvent = onEvent,
            )
            Spacer(Modifier.height(1.dp).testTag("theme-accent:end"))
        }
    }
}
