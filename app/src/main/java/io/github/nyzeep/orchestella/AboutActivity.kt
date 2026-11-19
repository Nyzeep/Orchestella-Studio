package io.github.nyzeep.orchestella

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3BackButton
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3DialogEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageTopPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.oeScrollOffsetPx
import io.github.nyzeep.orchestella.ui.shell.setOe3HostContent
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.update.UpdateCenter
import io.github.nyzeep.orchestella.update.UpdateCoordinator
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AboutActivity : AppCompatActivity() {

    companion object {
        const val AUTHOR_GITHUB_URL = "https://github.com/Nyzeep"
        const val AUTHOR_AVATAR_URL = "https://avatars.githubusercontent.com/Nyzeep?size=256"
        const val PROJECT_REPO_URL = "https://github.com/Nyzeep/Orchestella-Studio"
        private const val AVATAR_CACHE_FILE = "avatar_cache.png"
        private const val AVATAR_CONNECT_TIMEOUT_MS = 8000
        private const val AVATAR_READ_TIMEOUT_MS = 12000
    }

    private val avatarExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "about-avatar-loader").apply { isDaemon = true }
    }
    private val updateExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "about-update-check").apply { isDaemon = true }
    }
    private val avatarLoadStarted = AtomicBoolean(false)

    private lateinit var updateCenter: UpdateCenter
    private var avatarBitmap by mutableStateOf<ImageBitmap?>(null)
    private var updateChecking by mutableStateOf(false)
    private var checksEnabled by mutableStateOf(true)
    private var pendingUpdate by mutableStateOf<UpdateCenter.Available?>(null)
    private var noticesText by mutableStateOf<String?>(null)
    private var failureReason by mutableStateOf<String?>(null)
    private var failureDialogVisible by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)

        updateCenter = UpdateCoordinator.center(this)
        refreshUpdateState()
        UpdateCoordinator.checkInBackground(this, BuildConfig.VERSION_NAME) {
            if (!isFinishing && !isDestroyed) refreshUpdateState()
        }

        val cached = File(filesDir, AVATAR_CACHE_FILE)
        val decoded = if (cached.isFile) decodeAvatar(cached) else null
        if (decoded != null) {
            avatarBitmap = decoded.asImageBitmap()
        } else {
            loadAvatar()
        }

        setOe3HostContent {
            AboutScreen(
                avatar = avatarBitmap,
                checksEnabled = checksEnabled,
                checking = updateChecking,
                pending = pendingUpdate,
                onBack = ::finish,
                onCheckUpdate = ::runManualUpdateCheck,
                onOpenRelease = {
                    pendingUpdate?.let { openUrl(it.pageUrl()) }
                },
                onOpenAuthor = { openUrl(AUTHOR_GITHUB_URL) },
                onCopyAuthor = { copyToClipboard(AUTHOR_GITHUB_URL, getString(R.string.about_link_copied)) },
                onOpenProject = { openUrl(PROJECT_REPO_URL) },
                onCopyProject = { copyToClipboard(PROJECT_REPO_URL, getString(R.string.about_link_copied)) },
                onOpenNotices = ::openThirdPartyNotices,
            )

            noticesText?.let { notice ->
                Oe3DialogEnterMotion {
                    AlertDialog(
                        onDismissRequest = { noticesText = null },
                        shape = RoundedCornerShape(OeShape.alert),
                        title = {
                            Text(
                                text = stringResource(R.string.third_party_notices_title),
                                style = Oe3Typography.style(Oe3TextRole.Section),
                            )
                        },
                        text = {
                            Text(
                                text = notice,
                                style = Oe3Typography.style(Oe3TextRole.Meta),
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { noticesText = null }) {
                                Text(stringResource(R.string.credentials_close))
                            }
                        },
                    )
                }
            }

            if (failureDialogVisible) {
                Oe3DialogEnterMotion {
                    AlertDialog(
                        onDismissRequest = { failureDialogVisible = false },
                        shape = RoundedCornerShape(OeShape.alert),
                        title = {
                            Text(
                                text = stringResource(R.string.version_update_failed_title),
                                style = Oe3Typography.style(Oe3TextRole.Section),
                            )
                        },
                        text = {
                            Text(
                                text = failureReason ?: getString(R.string.version_update_hint),
                                style = Oe3Typography.style(Oe3TextRole.Body),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    failureDialogVisible = false
                                    openUrl("$PROJECT_REPO_URL/releases")
                                },
                            ) {
                                Text(stringResource(R.string.version_update_open_releases))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { failureDialogVisible = false }) {
                                Text(stringResource(R.string.version_update_dismiss))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUpdateState()
    }

    override fun onDestroy() {
        avatarExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshUpdateState() {
        checksEnabled = SettingsManager(this).isUpdateCheckEnabled()
        pendingUpdate = updateCenter.pending(BuildConfig.VERSION_NAME)
    }

    private fun openThirdPartyNotices() {
        noticesText = try {
            assets.open("third-party-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: java.io.IOException) {
            getString(R.string.third_party_notices_unavailable)
        }
    }

    private fun runManualUpdateCheck() {
        if (updateChecking || updateExecutor.isShutdown) return
        updateChecking = true
        val checker = GitHubUpdateChecker(SettingsManager(this))
        updateExecutor.execute {
            val result = checker.checkForUpdate(BuildConfig.VERSION_NAME, true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateChecking = false
                updateCenter.record(result, System.currentTimeMillis())
                refreshUpdateState()
                when (result.status()) {
                    GitHubUpdateChecker.Status.UPDATE_AVAILABLE -> Unit
                    GitHubUpdateChecker.Status.UP_TO_DATE -> Toast.makeText(
                        this,
                        getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT,
                    ).show()
                    else -> {
                        failureReason = result.errorMessage()
                        failureDialogVisible = true
                    }
                }
            }
        }
    }

    private fun loadAvatar() {
        if (!avatarLoadStarted.compareAndSet(false, true) || avatarExecutor.isShutdown) {
            return
        }
        avatarExecutor.execute {
            val bitmap = try {
                val url = URL(AUTHOR_AVATAR_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = AVATAR_CONNECT_TIMEOUT_MS
                connection.readTimeout = AVATAR_READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Orchestella-Studio/${BuildConfig.VERSION_NAME}")
                connection.inputStream.use { input ->
                    BitmapFactory.decodeStream(input)
                }.also { connection.disconnect() }
            } catch (_: Exception) {
                null
            }
            if (bitmap != null) {
                try {
                    val out = File(filesDir, AVATAR_CACHE_FILE)
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (_: Exception) {
                    // Best-effort avatar cache; decoded bitmap is already held in memory.
                }
            }
            runOnUiThread {
                if (bitmap != null && !isFinishing && !isDestroyed) {
                    avatarBitmap = bitmap.asImageBitmap()
                }
            }
        }
    }

    private fun decodeAvatar(file: File): Bitmap? = try {
        BitmapFactory.decodeFile(file.absolutePath)
    } catch (_: Exception) {
        null
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            copyToClipboard(url, "无法唤起浏览器，已将链接复制到剪贴板")
        }
    }

    private fun copyToClipboard(text: String, successMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", text))
        Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutScreen(
    avatar: ImageBitmap?,
    checksEnabled: Boolean,
    checking: Boolean,
    pending: UpdateCenter.Available?,
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit,
    onOpenRelease: () -> Unit,
    onOpenAuthor: () -> Unit,
    onCopyAuthor: () -> Unit,
    onOpenProject: () -> Unit,
    onCopyProject: () -> Unit,
    onOpenNotices: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val scrollState = rememberScrollState()
    val backDescription = stringResource(R.string.wizard_back_desc)

    OeScaffold(
        title = stringResource(R.string.about_title),
        leading = {
            Oe3BackButton(
                onBack = onBack,
                contentDescription = backDescription,
            )
        },
        scrollOffsetPx = { scrollState.oeScrollOffsetPx() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = Oe3Spacing.pageInset,
                    end = Oe3Spacing.pageInset,
                    top = LocalOe3PageTopPadding.current + Oe3Spacing.sm,
                    bottom = Oe3Spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.lg),
        ) {
            // 1. App Introduction Card
            Oe3Card {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.lg),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sym_terminal),
                        contentDescription = stringResource(R.string.app_name),
                        tint = colors.primary.oe3Color(),
                        modifier = Modifier.size(48.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
                        Text(
                            text = "Orchestella Studio",
                            style = Oe3Typography.style(Oe3TextRole.Brand),
                            color = colors.onSurface.oe3Color(),
                        )
                        Text(
                            text = "版本：v${BuildConfig.VERSION_NAME} · Phase 5.0",
                            style = Oe3Typography.style(Oe3TextRole.Meta),
                            color = colors.onSurfaceVariant.oe3Color(),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.about_app_desc),
                    style = Oe3Typography.style(Oe3TextRole.Body),
                    color = colors.onSurfaceVariant.oe3Color(),
                    modifier = Modifier.padding(top = Oe3Spacing.md),
                )
            }

            // 2. Version & Update Card
            Oe3Card {
                Text(
                    text = stringResource(R.string.version_update_title),
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                )
                val statusText = when {
                    !checksEnabled -> stringResource(R.string.version_update_status_disabled)
                    pending == null -> stringResource(R.string.version_update_status_none)
                    else -> stringResource(R.string.version_update_status_available, pending.version())
                }
                Text(
                    text = statusText,
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = colors.primary.oe3Color(),
                    modifier = Modifier.padding(top = Oe3Spacing.xs),
                )
                Text(
                    text = stringResource(
                        R.string.version_update_channel,
                        GitHubUpdateChecker.channelForVersion(BuildConfig.VERSION_NAME).label(),
                        BuildConfig.VERSION_NAME,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                    modifier = Modifier.padding(top = Oe3Spacing.xs),
                )
                val summaryText = when {
                    !checksEnabled -> stringResource(R.string.update_disabled_msg)
                    pending == null -> stringResource(R.string.version_update_hint)
                    else -> pending.summary().ifBlank { stringResource(R.string.version_update_notes_fallback) }
                }
                Text(
                    text = summaryText,
                    style = Oe3Typography.style(Oe3TextRole.Meta),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 6,
                    modifier = Modifier.padding(top = Oe3Spacing.sm),
                )
                if (checksEnabled) {
                    FlowRow(
                        modifier = Modifier.padding(top = Oe3Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                    ) {
                        Oe3Button(
                            text = stringResource(
                                if (checking) R.string.version_update_checking else R.string.version_update_check_now,
                            ),
                            onClick = onCheckUpdate,
                            enabled = !checking,
                            style = Oe3ButtonStyle.Text,
                        )
                        if (pending != null) {
                            Oe3Button(
                                text = stringResource(R.string.version_update_open_release),
                                onClick = onOpenRelease,
                                style = Oe3ButtonStyle.Text,
                            )
                        }
                    }
                }
            }

            // 3. Mascot Original Artwork Card (zero crop)
            Oe3Card {
                Text(
                    text = stringResource(R.string.about_mascot_title),
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                )
                Text(
                    text = stringResource(R.string.about_mascot_caption),
                    style = Oe3Typography.style(Oe3TextRole.Meta),
                    color = colors.onSurfaceVariant.oe3Color(),
                    modifier = Modifier.padding(top = Oe3Spacing.xs),
                )
                Image(
                    painter = painterResource(R.drawable.mascot_original),
                    contentDescription = stringResource(R.string.about_mascot_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Oe3Spacing.md),
                )
            }

            // 4. Author Profile Card
            Oe3Card {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.lg),
                ) {
                    val avatarDesc = stringResource(R.string.about_avatar_desc)
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(colors.primary.oe3Color().copy(alpha = 0.25f))
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.about_btn_visit_github),
                                onClick = onOpenAuthor,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (avatar != null) {
                            Image(
                                bitmap = avatar,
                                contentDescription = avatarDesc,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(70.dp)
                                    .clip(CircleShape),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                colors.primary.oe3Color(),
                                                colors.accent.oe3Color(),
                                            ),
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "N",
                                    style = Oe3Typography.style(Oe3TextRole.Brand),
                                    color = colors.onPrimary.oe3Color(),
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
                        Text(
                            text = stringResource(R.string.about_author_card_title),
                            style = Oe3Typography.style(Oe3TextRole.Section),
                            color = colors.onSurface.oe3Color(),
                        )
                        Text(
                            text = stringResource(R.string.about_author_name),
                            style = Oe3Typography.style(Oe3TextRole.Section),
                            color = colors.primary.oe3Color(),
                        )
                    }
                }
                LinkBanner(
                    url = stringResource(R.string.about_author_github_url),
                    onClick = onOpenAuthor,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Oe3Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
                ) {
                    Oe3Button(
                        text = stringResource(R.string.about_btn_visit_github),
                        onClick = onOpenAuthor,
                        style = Oe3ButtonStyle.Pill,
                    )
                    Oe3Button(
                        text = stringResource(R.string.about_btn_copy_link),
                        onClick = onCopyAuthor,
                        style = Oe3ButtonStyle.Tonal,
                    )
                }
            }

            // 5. Project Repository Card
            Oe3Card {
                Text(
                    text = stringResource(R.string.about_project_card_title),
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                )
                Text(
                    text = stringResource(R.string.about_project_name),
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.primary.oe3Color(),
                    modifier = Modifier.padding(top = Oe3Spacing.sm),
                )
                Text(
                    text = "开源协议：GNU GPL v3.0 · 强开源保障",
                    style = Oe3Typography.style(Oe3TextRole.Meta),
                    color = colors.onSurfaceVariant.oe3Color(),
                    modifier = Modifier.padding(top = Oe3Spacing.xs),
                )
                LinkBanner(
                    url = stringResource(R.string.about_project_url),
                    onClick = onOpenProject,
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Oe3Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
                ) {
                    Oe3Button(
                        text = stringResource(R.string.about_btn_visit_project),
                        onClick = onOpenProject,
                        style = Oe3ButtonStyle.Pill,
                    )
                    Oe3Button(
                        text = stringResource(R.string.about_btn_copy_link),
                        onClick = onCopyProject,
                        style = Oe3ButtonStyle.Tonal,
                    )
                }
                Spacer(Modifier.size(Oe3Spacing.sm))
                Oe3Button(
                    text = stringResource(R.string.third_party_notices_title),
                    onClick = onOpenNotices,
                    style = Oe3ButtonStyle.Tonal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LinkBanner(
    url: String,
    onClick: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Oe3Spacing.md)
            .heightIn(min = Oe3Sizes.minTouchTarget)
            .clip(RoundedCornerShape(Oe3Shapes.md))
            .background(colors.surfaceHigh.oe3Color())
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Oe3Spacing.sm, vertical = Oe3Spacing.xs),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = url,
            style = Oe3Typography.style(Oe3TextRole.Meta),
            color = colors.primary.oe3Color(),
        )
    }
}
