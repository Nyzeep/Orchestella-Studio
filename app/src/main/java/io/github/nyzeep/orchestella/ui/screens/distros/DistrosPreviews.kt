package io.github.nyzeep.orchestella.ui.screens.distros

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.nyzeep.orchestella.UmlRootfsCardState
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * 发行版页的静态样本。目录来自 `DistroCatalogRegistry.createDefault()`，
 * UML 卡取 NOT_READY 相位；不读磁盘、不启动 Activity，预览与截图可复现。
 */
internal object DistrosPreviewStates {

    private val catalog = DistroCatalogRegistry.createDefault()

    private val umlNotReady = UmlRootfsCardState.CardModel(
        UmlRootfsCardState.Phase.NOT_READY,
        UmlRootfsCardState.CardModel.PROGRESS_INDETERMINATE,
        null,
        0,
        false,
    )

    val collapsed: DistrosUiState = DistrosUiState.from(
        catalog = catalog,
        umlModel = umlNotReady,
        compileBridgeWired = true,
    )

    val expanded: DistrosUiState = collapsed

    val empty: DistrosUiState = DistrosUiState(distros = emptyList(), uml = null)
}

@Composable
private fun DistrosPreview(
    theme: AppTheme,
    state: DistrosUiState,
    initialExpandedId: String? = null,
) {
    Oe3Theme(theme = theme) {
        DistrosScreen(
            state = state,
            actions = DistrosActions(),
            initialExpandedId = initialExpandedId,
        )
    }
}

@Preview(
    name = "Collapsed · Dark",
    group = "distros",
    widthDp = 393,
    heightDp = 1400,
    showBackground = true,
)
@Composable
fun DistrosCollapsedDarkPreview() {
    DistrosPreview(theme = ThemeCatalog.moonshotDark, state = DistrosPreviewStates.collapsed)
}

@Preview(
    name = "Expanded · Dark",
    group = "distros",
    widthDp = 393,
    heightDp = 1400,
    showBackground = true,
)
@Composable
fun DistrosExpandedDarkPreview() {
    DistrosPreview(
        theme = ThemeCatalog.moonshotDark,
        state = DistrosPreviewStates.expanded,
        initialExpandedId = "ubuntu",
    )
}

@Preview(
    name = "Empty · Dark",
    group = "distros",
    widthDp = 393,
    heightDp = 1400,
    showBackground = true,
)
@Composable
fun DistrosEmptyDarkPreview() {
    DistrosPreview(theme = ThemeCatalog.moonshotDark, state = DistrosPreviewStates.empty)
}
