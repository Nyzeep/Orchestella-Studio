package io.github.nyzeep.orchestella

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors

/**
 * Secondary Activity: About Orchestella Studio & Author Profile.
 *
 * <p>Provides author profile (Nyzeep) with GitHub link, repository details,
 * license notice, and dynamic version display.
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        const val AUTHOR_GITHUB_URL = "https://github.com/Nyzeep"
        const val PROJECT_REPO_URL = "https://github.com/Nyzeep/Orchestella-Studio"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyIfAvailable(this)
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
