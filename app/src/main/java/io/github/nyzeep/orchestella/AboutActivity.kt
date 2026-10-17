package io.github.nyzeep.orchestella

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Secondary Activity: About Orchestella Studio & Author Profile.
 *
 * <p>Provides author profile (Nyzeep) with GitHub avatar, repository details,
 * license notice, and dynamic version display.
 */
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
    private val avatarLoadStarted = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val rootCoordinator = findViewById<View>(R.id.about_root_coordinator)
        ViewCompat.setOnApplyWindowInsetsListener(rootCoordinator) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                statusBarInsets.left,
                statusBarInsets.top,
                statusBarInsets.right,
                navBarInsets.bottom
            )
            insets
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.about_toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val txtVersion = findViewById<TextView>(R.id.txt_about_version)
        txtVersion.text = "版本：v${BuildConfig.VERSION_NAME} · Phase 5.0"

        val avatarView = findViewById<ImageView>(R.id.img_author_avatar)
        val avatarPlaceholder = findViewById<TextView>(R.id.txt_avatar_placeholder)
        ThemeApplier.setRole(avatarView, ThemeApplier.Role.PRESERVE)
        ThemeApplier.setRole(avatarPlaceholder, ThemeApplier.Role.PRESERVE)
        val cached = File(filesDir, AVATAR_CACHE_FILE)
        val cachedBitmap = if (cached.isFile) decodeAvatar(cached) else null
        if (cachedBitmap != null) {
            showAvatar(avatarView, avatarPlaceholder, cachedBitmap)
        } else {
            loadAvatar(avatarView, avatarPlaceholder)
        }
        avatarView.setOnClickListener { openUrl(AUTHOR_GITHUB_URL) }

        findViewById<View>(R.id.btn_visit_author_github).setOnClickListener {
            openUrl(AUTHOR_GITHUB_URL)
        }
        findViewById<View>(R.id.txt_author_github_url).setOnClickListener {
            openUrl(AUTHOR_GITHUB_URL)
        }
        findViewById<View>(R.id.btn_copy_author_github).setOnClickListener {
            copyToClipboard(AUTHOR_GITHUB_URL, getString(R.string.about_link_copied))
        }

        findViewById<View>(R.id.btn_visit_project_repo).setOnClickListener {
            openUrl(PROJECT_REPO_URL)
        }
        findViewById<View>(R.id.txt_project_url).setOnClickListener {
            openUrl(PROJECT_REPO_URL)
        }
        findViewById<View>(R.id.btn_copy_project_repo).setOnClickListener {
            copyToClipboard(PROJECT_REPO_URL, getString(R.string.about_link_copied))
        }

        findViewById<MaterialButton>(R.id.btn_third_party_notices).setOnClickListener {
            val notice = try {
                assets.open("third-party-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: java.io.IOException) {
                getString(R.string.third_party_notices_unavailable)
            }
            val text = TextView(this).apply {
                this.text = notice
                setTextIsSelectable(true)
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val scroll = android.widget.ScrollView(this).apply { addView(text) }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.third_party_notices_title)
                .setView(scroll)
                .setPositiveButton(R.string.credentials_close, null)
                .show().also(ThemeApplier::apply)
        }

        val btnCheckUpdates = findViewById<MaterialButton>(R.id.btn_check_updates)
        val updateChannel = GitHubUpdateChecker.channelForVersion(BuildConfig.VERSION_NAME).label()
        btnCheckUpdates?.text = "立即检测更新 · $updateChannel"
        btnCheckUpdates?.setOnClickListener {
            btnCheckUpdates.isEnabled = false
            btnCheckUpdates.text = "正在检测新版本 · $updateChannel..."
            val settings = SettingsManager(this)
            val checker = GitHubUpdateChecker(settings)
            Executors.newSingleThreadExecutor().execute {
                val result = checker.checkForUpdate(BuildConfig.VERSION_NAME, true)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    btnCheckUpdates.isEnabled = true
                    btnCheckUpdates.text = "立即检测更新 · $updateChannel"
                    when (result.status()) {
                        GitHubUpdateChecker.Status.UPDATE_AVAILABLE -> {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("发现新版本 (${result.latestVersion()})")
                                .setMessage("$updateChannel\n当前安装版本：v${BuildConfig.VERSION_NAME}\n最新可用版本：${result.latestVersion()}\n\n更新日志：\n${result.releaseNotes() ?: "无详细说明"}")
                                .setPositiveButton("前往下载") { _, _ ->
                                    openUrl(result.releaseUrl() ?: "$PROJECT_REPO_URL/releases")
                                }
                                .setNegativeButton("稍后再说", null)
                                .show()
                        }
                        GitHubUpdateChecker.Status.UP_TO_DATE -> {
                            Toast.makeText(this, "$updateChannel：当前已是最新版本 (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("检查更新结果")
                                .setMessage("$updateChannel\n" + (result.errorMessage() ?: "无法获取版本信息") + "\n\n您可以直接前往 GitHub 仓库 Releases 页面手动查看与获取最新构建。")
                                .setPositiveButton("访问 Releases 页面") { _, _ ->
                                    openUrl("$PROJECT_REPO_URL/releases")
                                }
                                .setNegativeButton("知道了", null)
                                .show()
                        }
                    }
                }
            }
        }
        ThemeApplier.bind(this)
    }

    private fun loadAvatar(avatarView: ImageView, placeholder: TextView) {
        if (!avatarLoadStarted.compareAndSet(false, true)) {
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
                }
            }
            runOnUiThread {
                if (bitmap != null && !isFinishing && !isDestroyed) {
                    showAvatar(avatarView, placeholder, bitmap)
                }
            }
        }
    }

    private fun decodeAvatar(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun showAvatar(avatarView: ImageView, placeholder: TextView, bitmap: Bitmap) {
        placeholder.visibility = View.GONE
        avatarView.setImageDrawable(BitmapDrawable(resources, circular(bitmap)))
    }

    private fun circular(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height).coerceAtLeast(1)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val clip = Path().apply { addOval(rect, Path.Direction.CW) }
        canvas.clipPath(clip)
        val left = (size - source.width) / 2f
        val top = (size - source.height) / 2f
        canvas.drawBitmap(source, left, top, paint)
        return output
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            copyToClipboard(url, "无法唤起浏览器，已将链接复制到剪贴板")
        }
    }

    private fun copyToClipboard(text: String, successMessage: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URL", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show()
    }
}
