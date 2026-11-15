package io.github.nyzeep.orchestella.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.SettingsSubActivity
import io.github.nyzeep.orchestella.ui.design.OeButton
import io.github.nyzeep.orchestella.ui.design.OeButtonStyle
import io.github.nyzeep.orchestella.ui.design.OeCard
import io.github.nyzeep.orchestella.ui.design.OeChip
import io.github.nyzeep.orchestella.ui.design.OeChipTone
import io.github.nyzeep.orchestella.ui.design.OePage
import io.github.nyzeep.orchestella.ui.design.OeShape
import io.github.nyzeep.orchestella.ui.design.OeSpace
import io.github.nyzeep.orchestella.ui.design.OeType
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Theme gallery — the first fully Compose screen of the 2026-11-14 UI round,
 * now the first production consumer of the Oe v2 component set.
 *
 * It exists to prove the theme bridge end to end: every palette the user owns
 * (the four shipped families plus every catalog entry and the custom slot) is
 * rendered from [AppTheme] into Material 3, and picking one here goes through the
 * same [ThemeStore] write the XML settings page uses. No second palette, no
 * parallel preference key.
 *
 * The activity keeps the store-derived values in snapshot state and re-reads
 * them in [onResume], so a corner-radius or in-app text-scale change made in the
 * appearance page is visible when the user returns instead of surviving only
 * until the next cold start.
 */
class ThemeGalleryActivity : AppCompatActivity() {

    private lateinit var store: ThemeStore
    private val appliedId = mutableStateOf("")
    private val cornerDp = mutableIntStateOf(16)
    private val textScale = mutableFloatStateOf(1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ThemeStore(this)
        syncFromStore()
        val composeView = ComposeView(this)
        composeView.setContent {
            val theme = ThemeCatalog.resolve(appliedId.value)
            OrchestellaComposeTheme(theme, cornerDp.intValue, textScale.floatValue) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GalleryContent(
                        appliedId = appliedId.value,
                        onPick = { picked ->
                            store.setThemeId(picked.id)
                            appliedId.value = picked.id
                        },
                        onOpenCustom = {
                            startActivity(
                                SettingsSubActivity.intent(
                                    this@ThemeGalleryActivity,
                                    SettingsSubActivity.SECTION_APPEARANCE,
                                ),
                            )
                        },
                        onBack = { finish() },
                    )
                }
            }
        }
        setContentView(composeView)
    }

    override fun onResume() {
        super.onResume()
        // The appearance sub-page can change these while the gallery sits in the
        // back stack; re-reading here is cheaper than forcing a recreation.
        if (::store.isInitialized) syncFromStore()
    }

    private fun syncFromStore() {
        appliedId.value = store.themeId()
        cornerDp.intValue = store.cornerRadiusDp()
        textScale.floatValue = store.textScaleFactor()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ThemeGalleryActivity::class.java)
    }
}

@Composable
private fun GalleryContent(
    appliedId: String,
    onPick: (AppTheme) -> Unit,
    onOpenCustom: () -> Unit,
    onBack: () -> Unit,
) {
    val themes = ThemeCatalog.all
    OePage(
        title = stringResource(R.string.theme_gallery_title),
        onBack = onBack,
        // No floating dock on this page: reserve one group gap instead of the
        // 96dp dock clearance OePage uses by default.
        bottomClearance = OeSpace.group,
    ) {
        item {
            Text(
                text = stringResource(R.string.theme_gallery_subtitle),
                style = OeType.body(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Two-column grid without a nested scroll container: OePage already owns
        // the LazyColumn, and a LazyVerticalGrid inside it would need a fixed
        // height. Chunking keeps one scrollable surface and the 12dp card gap.
        themes.chunked(2).forEach { row ->
            item(key = "theme-row:${row.first().id}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(OeSpace.gap),
                ) {
                    row.forEach { candidate ->
                        ThemePreviewCard(
                            theme = candidate,
                            isSelected = candidate.id == appliedId,
                            onClick = { onPick(candidate) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                    // A lone last card keeps half width instead of stretching
                    // across the page and breaking the grid rhythm.
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            Column(
                modifier = Modifier.padding(top = OeSpace.gap, bottom = OeSpace.group),
            ) {
                OeButton(
                    text = stringResource(R.string.theme_gallery_custom),
                    onClick = onOpenCustom,
                    modifier = Modifier.fillMaxWidth(),
                    style = OeButtonStyle.Primary,
                )
                Spacer(Modifier.height(OeSpace.gap))
                OeButton(
                    text = stringResource(R.string.theme_gallery_done),
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    style = OeButtonStyle.Secondary,
                )
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    OeCard(
        modifier = modifier.then(
            if (isSelected) Modifier.semantics { this.selected = true } else Modifier,
        ),
        onClick = onClick,
        padding = PaddingValues(OeSpace.card),
    ) {
        // Miniature of the real thing on the candidate's own background, so the
        // user judges material rather than a colour chip. These are illustration
        // dimensions, not layout tokens.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OeShape.block))
                .background(Color(theme.background))
                .border(
                    width = OeSpace.hairline,
                    color = Color(theme.glow).copy(alpha = 0.32f),
                    shape = RoundedCornerShape(OeShape.block),
                )
                .padding(OeSpace.gap),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(OeShape.badge))
                    .background(Color(theme.surface))
                    .border(
                        width = OeSpace.hairline,
                        color = Color(theme.glow).copy(alpha = 0.30f),
                        shape = RoundedCornerShape(OeShape.badge),
                    )
                    .padding(8.dp),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(Color(theme.text)),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color(theme.textDim).copy(alpha = 0.7f)),
                    )
                }
            }
        }
        Spacer(Modifier.height(OeSpace.gap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = theme.displayName,
                style = OeType.label(),
                color = if (isSelected) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isSelected) {
                Spacer(Modifier.width(OeSpace.gap))
                OeChip(
                    text = stringResource(R.string.theme_active_badge),
                    tone = OeChipTone.Accent,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = theme.selectionLabel,
            style = OeType.caption(),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
