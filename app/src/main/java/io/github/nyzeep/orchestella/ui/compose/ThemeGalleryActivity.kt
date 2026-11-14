package io.github.nyzeep.orchestella.ui.compose

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.SettingsSubActivity
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Theme gallery — the first fully Compose screen of the 2026-11-14 UI round.
 *
 * It exists to prove the theme bridge end to end: every palette the user owns
 * (the four shipped families plus every catalog entry and the custom slot) is
 * rendered from [AppTheme] into Material 3, and picking one here goes through the
 * same [ThemeStore] write the XML settings page uses. No second palette, no
 * parallel preference key.
 *
 * Visual rules carried over from the reviewed defects: one hairline per card, a
 * 12dp card gap, and buttons that are the same height in the same tier.
 */
class ThemeGalleryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ThemeStore(this)
        val composeView = ComposeView(this)
        composeView.setContent {
            var appliedId by remember { mutableStateOf(store.themeId()) }
            val cornerDp = store.cornerRadiusDp()
            val theme = ThemeCatalog.resolve(appliedId)
            OrchestellaComposeTheme(theme, cornerDp) {
                GalleryContent(
                    appliedId = appliedId,
                    cornerDp = cornerDp,
                    onPick = { picked ->
                        store.setThemeId(picked.id)
                        appliedId = picked.id
                    },
                    onOpenCustom = {
                        startActivity(SettingsSubActivity.intent(this, SettingsSubActivity.SECTION_APPEARANCE))
                    },
                    onBack = { finish() },
                )
            }
        }
        setContentView(composeView)
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ThemeGalleryActivity::class.java)
    }
}

@Composable
private fun GalleryContent(
    appliedId: String,
    cornerDp: Int,
    onPick: (AppTheme) -> Unit,
    onOpenCustom: () -> Unit,
    onBack: () -> Unit,
) {
    var selectedId by remember { mutableStateOf(appliedId) }
    val themes = remember { ThemeCatalog.all }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = OeSpacing.lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = OeSpacing.lg, bottom = OeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.theme_gallery_back), fontSize = 14.sp)
            }
            Spacer(Modifier.width(OeSpacing.sm))
            Text(
                text = stringResource(R.string.theme_gallery_title),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = stringResource(R.string.theme_gallery_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = OeSpacing.md),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(OeSizes.cardGap),
            horizontalArrangement = Arrangement.spacedBy(OeSizes.cardGap),
            modifier = Modifier.weight(1f),
        ) {
            items(themes, key = { it.id }) { candidate ->
                ThemePreviewCard(
                    theme = candidate,
                    cornerDp = cornerDp,
                    selected = candidate.id == selectedId,
                    onClick = {
                        selectedId = candidate.id
                        onPick(candidate)
                    },
                )
            }
            item(span = { GridItemSpan(2) }) {
                Column(modifier = Modifier.padding(vertical = OeSpacing.md)) {
                    Button(
                        onClick = onOpenCustom,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(OeSizes.btnPrimary),
                        shape = RoundedCornerShape(cornerDp.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(stringResource(R.string.theme_gallery_custom), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(OeSpacing.sm))
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(OeSizes.btnSecondary),
                        shape = RoundedCornerShape(cornerDp.dp),
                    ) {
                        Text(stringResource(R.string.theme_gallery_done), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePreviewCard(
    theme: AppTheme,
    cornerDp: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(cornerDp.dp)
    val borderColor = if (selected) {
        Color(theme.primary)
    } else {
        Color(theme.glow).copy(alpha = 0.35f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(theme.background))
            .border(
                width = if (selected) 2.dp else OeSizes.cardBorder,
                color = borderColor,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(OeSpacing.sm),
    ) {
        // Miniature of the real thing: one surface card on the page background,
        // so the user judges material rather than a colour chip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape((cornerDp / 2).dp))
                .background(Color(theme.surface))
                .border(
                    width = OeSizes.cardBorder,
                    color = Color(theme.glow).copy(alpha = 0.30f),
                    shape = RoundedCornerShape((cornerDp / 2).dp),
                )
                .padding(OeSpacing.sm),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .width(56.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(Color(theme.text)),
                )
                Spacer(Modifier.height(OeSpacing.xs))
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(Color(theme.textDim).copy(alpha = 0.7f)),
                )
            }
        }
        Spacer(Modifier.height(OeSpacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = theme.displayName,
                color = Color(theme.text),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(theme.primary)),
            )
        }
        Text(
            text = theme.selectionLabel,
            color = Color(theme.textDim),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
