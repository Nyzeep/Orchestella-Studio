package io.github.nyzeep.orchestella

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.ui.motion.OrchestellaMotion
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Generic Settings sub-page (T3 settings IA redesign, ADR-0006 follow-up).
 *
 * <p>The settings home page ([SettingsActivity]) is a section directory; each
 * section's content lives in one instance of this activity. There is no
 * Fragment: the section layout chosen by [EXTRA_SECTION] is inflated into the
 * shared [R.id.settings_sub_container] and wired here, one binder per section.
 *
 * <p>Theme integration follows the [AboutActivity] paradigm
 * ([ThemeApplier.prepare] before super.onCreate, [ThemeApplier.bind] after the
 * section is wired) so the custom wallpaper, corner radius and surface opacity
 * stay alive on sub-pages. Preference keys are untouched — only UI and
 * listeners moved here from [SettingsActivity].
 *
 * <p>Level-1 → level-2 transition: a scale+alpha approximation of a container
 * transform played on the section container (alpha/scale only, no layout
 * height animation), [OrchestellaMotion.DURATION_CONTAINER_TRANSFORM_MS] on
 * standard devices, the medium3 (350 ms) tier on low-RAM devices.
 */
class SettingsSubActivity : AppCompatActivity() {

    companion object {
        /** Intent extra selecting the section layout bound by this activity. */
        const val EXTRA_SECTION = "extra_settings_section"

        /** 「外观与主题」：8 选择按钮 + 5 滑条 + 终端光标偏好（并入）。 */
        const val SECTION_APPEARANCE = "appearance"

        /** 「镜像源与加速」：软件源策略 Spinner + 测速优选 + Docker 加速源。 */
        const val SECTION_MIRROR = "mirror"

        /** 「关于与合规」：版本 + 商标与合规声明（折叠块）。 */
        const val SECTION_ABOUT = "about"

        /** Container-transform approximation: entry scale (and alpha 0→1). */
        private const val TRANSFORM_START_SCALE = 0.96f

        /** Low-RAM tier duration: oe_motion_duration_medium3. */
        private const val TRANSFORM_LOW_TIER_MS = 350L

        fun intent(context: Context, section: String): Intent =
            Intent(context, SettingsSubActivity::class.java).putExtra(EXTRA_SECTION, section)
    }

    private lateinit var settingsManager: SettingsManager
    private lateinit var themeStore: ThemeStore

    /** Mirror speed-test probe runs off the main thread; shutdown in onDestroy. */
    private val backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val backgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        if (themeStore.persistBackgroundPermission(this, uri)) {
            themeStore.setBackgroundUri(uri.toString())
            refreshAppearance()
            Toast.makeText(this, "背景图片已更新", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "无法保留图片访问权限，请重新选择本地图片", Toast.LENGTH_LONG).show()
        }
    }

    private val terminalBackgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        if (themeStore.persistBackgroundPermission(this, uri)) {
            themeStore.setTerminalBackgroundUri(uri.toString())
            refreshAppearance()
            Toast.makeText(this, "终端背景图片已更新", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "无法保留图片访问权限，请重新选择本地图片", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_sub)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val rootCoordinator = findViewById<View>(R.id.settings_sub_root_coordinator)
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

        val toolbar = findViewById<MaterialToolbar>(R.id.settings_sub_toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.navigationContentDescription = "返回"

        settingsManager = SettingsManager(this)

        val container = findViewById<LinearLayout>(R.id.settings_sub_container)
        when (intent?.getStringExtra(EXTRA_SECTION)) {
            SECTION_APPEARANCE -> bindAppearance(container)
            SECTION_MIRROR -> bindMirror(container)
            SECTION_ABOUT -> bindAbout(container)
            // Sections land here as their migration commits land; anything
            // unknown must not linger as an empty shell.
            else -> {
                finish()
                return
            }
        }

        ThemeApplier.bind(this)
        playContainerTransformEntrance()
    }

    override fun onResume() {
        super.onResume()
        if (currentSection == SECTION_APPEARANCE) {
            refreshAppearance()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }

    /** Section currently bound (null before onCreate dispatch completes). */
    private var currentSection: String? = null

    // =====================================================================
    // 外观与主题（旧一级页「外观与主题」卡 + 「终端使用体验偏好」卡并入；
    // 偏好读写 key 不动，UI 与监听原样迁移）
    // =====================================================================

    private fun bindAppearance(container: LinearLayout) {
        currentSection = SECTION_APPEARANCE
        toolbarTitle(R.string.settings_section_appearance_title)
        layoutInflater.inflate(R.layout.layout_settings_section_appearance, container, true)
        themeStore = ThemeStore(this)
        setupTerminalSection()
        setupThemeSection()
        refreshAppearance()
    }

    private fun toolbarTitle(resId: Int) {
        findViewById<MaterialToolbar>(R.id.settings_sub_toolbar).setTitle(resId)
    }

    /**
     * Level-1 → level-2 transition: scale+alpha container-transform
     * approximation on the section container, pivoting at the top edge so the
     * page reads as growing out of the tapped directory row. Alpha/scale only
     * (no layout-height animation); ViewPropertyAnimator durations honour the
     * system animator scale, so reduced-motion setups collapse to instant.
     */
    private fun playContainerTransformEntrance() {
        val container = findViewById<View>(R.id.settings_sub_container) ?: return
        val lowRamDevice = (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
        container.pivotY = 0f
        container.scaleX = TRANSFORM_START_SCALE
        container.scaleY = TRANSFORM_START_SCALE
        container.alpha = 0f
        container.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(
                if (lowRamDevice) TRANSFORM_LOW_TIER_MS
                else OrchestellaMotion.DURATION_CONTAINER_TRANSFORM_MS.toLong()
            )
            .setInterpolator(PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f))
            .setListener(null)
            .start()
    }

    private fun refreshAppearance() {
        val theme = themeStore.resolve()
        findViewById<TextView>(R.id.txt_settings_theme_summary).text =
            "${theme.selectionLabel} · ${themeStore.mode().displayName} · 立即应用到全部页面"
        findViewById<MaterialButton>(R.id.btn_pick_theme_mode).text = "明暗模式：${themeStore.mode().displayName}"
        findViewById<TextView>(R.id.txt_settings_terminal_palette).text =
            "配色：${theme.displayName}\n等宽字体与 16 色 ANSI 调色板跟随主题"
        findViewById<MaterialButton>(R.id.btn_clear_background).isEnabled = themeStore.backgroundUri() != null
        findViewById<MaterialButton>(R.id.btn_clear_terminal_background)?.isEnabled =
            themeStore.terminalBackgroundUri() != null
        findViewById<MaterialButton>(R.id.btn_pick_corner_radius)?.text =
            "按钮圆角：" + when (themeStore.cornerRadiusDp()) {
                ThemeStore.DEFAULT_CORNER_RADIUS_DP -> "大圆角 ${ThemeStore.DEFAULT_CORNER_RADIUS_DP}dp（默认）"
                12 -> "中圆角 12dp"
                8 -> "小圆角 8dp"
                0 -> "直角 0dp（保留 XML 圆角）"
                else -> "自定义 ${themeStore.cornerRadiusDp()}dp"
            }
        ThemeApplier.bind(this)
    }

    private fun setupTerminalSection() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switch_settings_cursor_blink
        ) ?: return
        switch.isChecked = settingsManager.isTerminalCursorBlinkEnabled
        switch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isTerminalCursorBlinkEnabled = isChecked
        }
    }

    private fun setupThemeSection() {
        val btnPickTheme = findViewById<MaterialButton?>(R.id.btn_pick_theme)
        val btnPickAccent = findViewById<MaterialButton?>(R.id.btn_pick_accent)
        val btnPickBackground = findViewById<MaterialButton?>(R.id.btn_pick_background)
        val btnClearBackground = findViewById<MaterialButton?>(R.id.btn_clear_background)
        val fontLabel = findViewById<android.widget.TextView?>(R.id.tv_font_size_label)
        val fontSeek = findViewById<android.widget.SeekBar?>(R.id.seek_terminal_font)
        val spacingLabel = findViewById<android.widget.TextView?>(R.id.tv_letter_spacing_label)
        val spacingSeek = findViewById<android.widget.SeekBar?>(R.id.seek_letter_spacing)
        val lineLabel = findViewById<TextView>(R.id.tv_line_spacing_label)
        val lineSeek = findViewById<android.widget.SeekBar>(R.id.seek_terminal_line_spacing)
        if (btnPickTheme == null || fontSeek == null) return

        fun refreshLabels() {
            fontLabel?.text = "终端字号：${themeStore.terminalFontSizeSp()}sp"
            fontSeek.progress = ThemeColors.fontProgress(themeStore.terminalFontSizeSp())
            val em = themeStore.terminalLetterSpacingEm()
            spacingLabel?.text = "终端字间距：%.2fem".format(em)
            spacingSeek?.progress = (em * 100).toInt()
            lineLabel.text = "终端额外行距：%.1fpx".format(themeStore.terminalLineSpacingPx())
            lineSeek.progress = themeStore.terminalLineSpacingPx().toInt()
        }
        refreshLabels()

        findViewById<MaterialButton>(R.id.btn_pick_theme_mode).setOnClickListener {
            // FIXED is not a manual choice: a single-form palette selects it when picked.
            val modes = ThemeStore.Mode.entries.filter { it.isUserSelectable }
            val theme = themeStore.resolve()
            val builder = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("明暗模式")
            if (theme.isFixed) {
                // Read-only note, not a row: this palette owns its brightness, so nothing
                // here may look like it follows the system.
                builder.setMessage(
                    "当前主题「${theme.displayName}」自带明暗，不跟随系统。切换到普通主题后即可手动指定明暗模式。")
            }
            builder.setSingleChoiceItems(
                modes.map { it.displayName }.toTypedArray(),
                // Filtered list: ordinal would skip FIXED and select the wrong row.
                modes.indexOf(themeStore.mode()),
            ) { dialog, which ->
                themeStore.setMode(modes[which])
                dialog.dismiss()
                refreshAppearance()
            }
                .setNegativeButton("取消", null)
                .show().also(ThemeApplier::apply)
        }

        btnPickTheme.setOnClickListener {
            val slots = themeStore.customSlots()
            val customLabels = slots.mapIndexed { index, slot ->
                slot?.let { "${it.name} · ${if (it.isDark) "暗" else "亮"}" } ?: "自定义 ${index + 1}（未设置）"
            }
            val themes = ThemeCatalog.all
            val names = (customLabels + themes.map { it.selectionLabel }).toTypedArray()
            val resolvedId = themeStore.resolve().id
            val current = when {
                resolvedId.startsWith(CustomThemeSlot.ID_PREFIX) ->
                    resolvedId.removePrefix(CustomThemeSlot.ID_PREFIX).toIntOrNull()?.minus(1) ?: -1
                else -> slots.size + themes.indexOfFirst { it.id == resolvedId }
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择主题")
                .setSingleChoiceItems(names, current) { dialog, which ->
                    dialog.dismiss()
                    if (which < slots.size) {
                        if (slots[which] == null) {
                            showCustomThemeEditor(which)
                        } else {
                            themeStore.setThemeId("custom:${which + 1}")
                            refreshAppearance()
                            Toast.makeText(this, "主题：${slots[which]!!.name}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val theme = themes[which - slots.size]
                        themeStore.setThemeId(theme.id)
                        refreshLabels()
                        refreshAppearance()
                        Toast.makeText(this, "主题：${theme.selectionLabel}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show().also(ThemeApplier::apply)
        }

        findViewById<MaterialButton?>(R.id.btn_pick_corner_radius)?.setOnClickListener {
            val standard = listOf(
                "小圆角 8dp" to 8,
                "中圆角 12dp" to 12,
                "大圆角 ${ThemeStore.DEFAULT_CORNER_RADIUS_DP}dp（默认）" to ThemeStore.DEFAULT_CORNER_RADIUS_DP,
            )
            val saved = themeStore.cornerRadiusDp()
            // Legacy 0 (an explicit "square corners" pick) and any historic value stay in the
            // list, selected as-is: the dialog must never open at -1 nor silently rewrite them.
            val options = if (standard.any { it.second == saved }) standard else standard + (
                if (saved == 0) "直角 0dp（保留 XML 圆角）" to 0 else "自定义 ${saved}dp" to saved)
            val current = options.indexOfFirst { it.second == saved }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("全局按钮圆角")
                .setSingleChoiceItems(options.map { it.first }.toTypedArray(), current) { dialog, which ->
                    themeStore.setCornerRadiusDp(options[which].second)
                    refreshAppearance()
                    dialog.dismiss()
                    Toast.makeText(this, "圆角已更新：${options[which].first}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show().also(ThemeApplier::apply)
        }

        findViewById<MaterialButton?>(R.id.btn_pick_custom_theme)?.setOnClickListener {
            val slots = themeStore.customSlots()
            val labels = slots.mapIndexed { index, slot ->
                if (slot == null) "自定义 ${index + 1}（未设置）"
                else "${slot.name} · ${if (slot.isDark) "暗" else "亮"} · 主色 ${hexLabel(slot.primary)}"
            }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自定义配色（3 组）")
                .setItems(labels) { dialog, which ->
                    dialog.dismiss()
                    showCustomThemeEditor(which)
                }
                .setNegativeButton("取消", null)
                .show().also(ThemeApplier::apply)
        }

        btnPickAccent?.setOnClickListener {
            val accents = listOf(
                "紫" to 0xFFBD93F9.toInt(), "青" to 0xFF8BE9FD.toInt(), "粉" to 0xFFFF79C6.toInt(),
                "绿" to 0xFF50FA7B.toInt(), "蓝" to 0xFF61AFEF.toInt(), "橙" to 0xFFFE8019.toInt(),
                "黄" to 0xFFF1FA8C.toInt(), "红" to 0xFFFF5555.toInt(),
            )
            val names = accents.map { it.first }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("自定义强调色（按钮、导航与仪表）")
                .setItems(names) { dialog, which ->
                    themeStore.setCustomAccent(accents[which].second)
                    refreshAppearance()
                    dialog.dismiss()
                }
                .setNeutralButton("跟随主题") { _, _ ->
                    themeStore.setCustomAccent(null)
                    refreshAppearance()
                }
                .setNegativeButton("取消", null)
                .show().also(ThemeApplier::apply)
        }

        btnPickBackground?.setOnClickListener {
            backgroundPicker.launch(arrayOf("image/*"))
        }

        btnClearBackground?.setOnClickListener {
            themeStore.setBackgroundUri(null)
            refreshAppearance()
            Toast.makeText(this, "已恢复纯色背景", Toast.LENGTH_SHORT).show()
        }

        val scrimLabel = findViewById<TextView?>(R.id.tv_terminal_scrim_label)
        val scrimSeek = findViewById<android.widget.SeekBar?>(R.id.seek_terminal_scrim)
        findViewById<MaterialButton?>(R.id.btn_pick_terminal_background)?.setOnClickListener {
            terminalBackgroundPicker.launch(arrayOf("image/*"))
        }
        findViewById<MaterialButton?>(R.id.btn_clear_terminal_background)?.setOnClickListener {
            themeStore.setTerminalBackgroundUri(null)
            refreshAppearance()
            Toast.makeText(this, "终端已恢复纯色背景", Toast.LENGTH_SHORT).show()
        }
        scrimSeek?.max = 90
        scrimSeek?.progress = themeStore.terminalScrimAlpha() * 90 / 230
        scrimLabel?.text = "背景遮罩浓度：${scrimSeek?.progress ?: 0}%"
        scrimSeek?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                themeStore.setTerminalScrimAlpha(progress * 230 / 90)
                scrimLabel?.text = "背景遮罩浓度：${progress}%"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                Toast.makeText(this@SettingsSubActivity, "遮罩浓度已保存", Toast.LENGTH_SHORT).show()
            }
        })

        val opacityLabel = findViewById<TextView?>(R.id.tv_ui_surface_opacity_label)
        val opacitySeek = findViewById<android.widget.SeekBar?>(R.id.seek_ui_surface_opacity)
        opacitySeek?.max = SurfaceOpacity.MAX_PERCENT - SurfaceOpacity.MIN_PERCENT
        opacitySeek?.progress = themeStore.surfaceOpacityPercent() - SurfaceOpacity.MIN_PERCENT
        opacityLabel?.text = "界面表面不透明度：${themeStore.surfaceOpacityPercent()}%"
        opacitySeek?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val percent = progress + SurfaceOpacity.MIN_PERCENT
                themeStore.setUiSurfaceOpacityPercent(percent)
                opacityLabel?.text = "界面表面不透明度：$percent%"
                ThemeApplier.bind(this@SettingsSubActivity)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                Toast.makeText(this@SettingsSubActivity, "界面表面不透明度已应用", Toast.LENGTH_SHORT).show()
            }
        })

        fontSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val sp = ThemeColors.fontSize(progress)
                themeStore.setTerminalFontSizeSp(sp)
                fontLabel?.text = "终端字号：${themeStore.terminalFontSizeSp()}sp"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        spacingSeek?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val em = progress / 100f
                themeStore.setTerminalLetterSpacingEm(em)
                spacingLabel?.text = "终端字间距：%.2fem".format(em)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        lineSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                themeStore.setTerminalLineSpacingPx(progress.toFloat())
                lineLabel.text = "终端额外行距：${progress}px"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    /** Shared swatch board for the custom theme editor: neutrals plus common theme hues. */
    private val swatchPalette = listOf(
        "黑" to 0xFF000000.toInt(), "夜空" to 0xFF0D0E11.toInt(), "深灰" to 0xFF2E323B.toInt(),
        "灰" to 0xFF8E929B.toInt(), "浅灰" to 0xFFC9CDD4.toInt(), "米白" to 0xFFF5F4F0.toInt(),
        "白" to 0xFFFFFFFF.toInt(), "暗红" to 0xFF7F1D1D.toInt(), "红" to 0xFFD62828.toInt(),
        "玫瑰" to 0xFFF43F5E.toInt(), "橙" to 0xFFEA580C.toInt(), "琥珀" to 0xFFFDBB24.toInt(),
        "黄" to 0xFFFACC15.toInt(), "青柠" to 0xFF84CC16.toInt(), "绿" to 0xFF22C55E.toInt(),
        "翠绿" to 0xFF10B981.toInt(), "青" to 0xFF06B6D4.toInt(), "天蓝" to 0xFF38BDF8.toInt(),
        "蓝" to 0xFF0047AB.toInt(), "靛蓝" to 0xFF4F46E5.toInt(), "紫" to 0xFFBD93F9.toInt(),
        "洋红" to 0xFFE056FD.toInt(), "粉" to 0xFFFF79C6.toInt(), "棕" to 0xFF92400E.toInt(),
    )

    private fun hexLabel(color: Int): String = String.format("#%06X", color and 0x00FFFFFF)

    /** Slot editor: name + mode + eight color rows, each opening the shared swatch board. */
    private fun showCustomThemeEditor(slotIndex: Int) {
        val existing = themeStore.customSlots().getOrNull(slotIndex)
        val fallback = themeStore.resolve()
        var name = existing?.name ?: "自定义 ${slotIndex + 1}"
        var isDark = existing?.isDark ?: fallback.isDark
        var background = existing?.background ?: fallback.background
        var surface = existing?.surface ?: fallback.surface
        var primary = existing?.primary ?: fallback.primary
        var accent = existing?.accent ?: fallback.accent
        var text = existing?.text ?: fallback.text
        var textDim = existing?.textDim ?: fallback.textDim
        var terminalBackground = existing?.terminalBackground ?: fallback.terminalBackground
        var terminalForeground = existing?.terminalForeground ?: fallback.terminalForeground

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }
        val nameEdit = android.widget.EditText(this).apply {
            setText(name)
            hint = "主题名称"
            setSingleLine(true)
        }
        container.addView(nameEdit)

        val modeButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        fun repaintMode() {
            modeButton.text = "明暗：${if (isDark) "深色（暗色 ANSI）" else "浅色（亮色 ANSI）"}"
        }
        repaintMode()
        modeButton.isAllCaps = false
        modeButton.setOnClickListener {
            isDark = !isDark
            repaintMode()
        }
        container.addView(modeButton)

        fun addColorRow(label: String, initial: Int, onPick: (Int) -> Unit) {
            var current = initial
            val button = MaterialButton(this)
            button.isAllCaps = false
            fun repaint() {
                button.text = "$label：${hexLabel(current)}"
                button.setBackgroundColor(current)
                button.setTextColor(ThemeColors.onColor(current))
            }
            repaint()
            button.setOnClickListener {
                val names = swatchPalette.map { "${it.first} ${hexLabel(it.second)}" }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(names) { dialog, which ->
                        current = swatchPalette[which].second
                        onPick(current)
                        repaint()
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show().also(ThemeApplier::apply)
            }
            container.addView(button)
        }

        addColorRow("背景", background) { background = it }
        addColorRow("表面", surface) { surface = it }
        addColorRow("主色", primary) { primary = it }
        addColorRow("强调色", accent) { accent = it }
        addColorRow("正文", text) { text = it }
        addColorRow("次要文字", textDim) { textDim = it }
        addColorRow("终端背景", terminalBackground) { terminalBackground = it }
        addColorRow("终端前景", terminalForeground) { terminalForeground = it }

        val resetButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        resetButton.text = "重置此槽位"
        resetButton.isAllCaps = false
        resetButton.setOnClickListener {
            themeStore.setCustomSlot(slotIndex, null)
            refreshAppearance()
            Toast.makeText(this, "自定义 ${slotIndex + 1} 已重置", Toast.LENGTH_SHORT).show()
            currentEditorDialog?.dismiss()
        }
        container.addView(resetButton)

        val scroll = android.widget.ScrollView(this).apply { addView(container) }
        fun applySlot() {
            val slot = CustomThemeSlot(
                name = nameEdit.text.toString().ifBlank { "自定义 ${slotIndex + 1}" },
                isDark = isDark,
                background = background,
                surface = surface,
                primary = primary,
                accent = accent,
                text = text,
                textDim = textDim,
                terminalBackground = terminalBackground,
                terminalForeground = terminalForeground,
            )
            themeStore.setCustomSlot(slotIndex, slot)
            refreshAppearance()
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("编辑自定义主题 ${slotIndex + 1}")
            .setView(scroll)
            .setPositiveButton("应用") { _, _ ->
                applySlot()
                Toast.makeText(this, "自定义主题已保存", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("选为当前主题") { _, _ ->
                applySlot()
                themeStore.setThemeId("custom:${slotIndex + 1}")
                refreshAppearance()
                Toast.makeText(this, "主题：${nameEdit.text}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        currentEditorDialog = dialog
        dialog.show()
        ThemeApplier.apply(dialog)
    }

    private var currentEditorDialog: androidx.appcompat.app.AlertDialog? = null

    // =====================================================================
    // 镜像源与加速（旧一级页「镜像源与容器加速管理」卡迁入；偏好与探测
    // 行为不变，测速完成后的分节 stagger 重放落在子页容器上）
    // =====================================================================

    private fun bindMirror(container: LinearLayout) {
        currentSection = SECTION_MIRROR
        toolbarTitle(R.string.settings_section_mirror_title)
        layoutInflater.inflate(R.layout.layout_settings_section_mirror, container, true)
        setupMirrorSection()
    }

    private fun setupMirrorSection() {
        val spinnerMirrorSource = findViewById<android.widget.Spinner>(R.id.spinner_settings_mirror_source)
        val btnSpeedTest = findViewById<MaterialButton>(R.id.btn_settings_speed_test)
        val btnResetMirror = findViewById<MaterialButton>(R.id.btn_settings_reset_mirror)
        val txtSpeedTestResults = findViewById<TextView>(R.id.txt_settings_speed_test_results)

        ThemeApplier.setRole(btnSpeedTest, ThemeApplier.Role.PRIMARY_ACTION)
        ThemeApplier.setRole(btnResetMirror, ThemeApplier.Role.SECONDARY_ACTION)

        val sources = DistroMirrorProvisioner.MirrorSource.values()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources.map { it.displayName() }
        )
        spinnerMirrorSource.adapter = adapter
        spinnerMirrorSource.setSelection(0) // Default: MULTI_TIER

        btnSpeedTest.setOnClickListener {
            runMirrorSpeedTest(btnSpeedTest, spinnerMirrorSource, txtSpeedTestResults)
        }

        btnResetMirror.setOnClickListener {
            spinnerMirrorSource.setSelection(0)
            txtSpeedTestResults.text = "已恢复为默认多层级回退配置 (国内云源 -> 教育网 -> 官方源)"
            Toast.makeText(this, "已恢复默认镜像源策略", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runMirrorSpeedTest(
        btnSpeedTest: MaterialButton,
        spinnerMirrorSource: android.widget.Spinner,
        txtSpeedTestResults: TextView,
    ) {
        btnSpeedTest.isEnabled = false
        txtSpeedTestResults.text = "正在并发测速探测各镜像节点 (阿里云 / 清华 / USTC / 华为 / 腾讯 / 官方)..."

        backgroundExecutor.execute {
            val candidates = MirrorSpeedTester.defaultSystemCandidates()
            val results = MirrorSpeedTester.probeAll(candidates, 3000)

            runOnUiThread {
                btnSpeedTest.isEnabled = true
                val sb = StringBuilder()
                sb.append("实时测速结果 (响应时间从快到慢)：\n")
                for (r in results) {
                    val statusText = if (r.isSuccess) "${r.latencyMs()} ms ✓" else "连接超时 ✕"
                    sb.append("• ").append(r.candidate().name()).append("：").append(statusText).append("\n")
                }

                val fastest = MirrorSpeedTester.selectFastest(results, candidates.first())
                sb.append("\n建议优选：").append(fastest.name())
                txtSpeedTestResults.text = sb.toString().trim()

                // If fastest matches Aliyun or TUNA, update spinner accordingly
                val sources = DistroMirrorProvisioner.MirrorSource.values()
                if (fastest.name().contains("阿里")) {
                    val idx = sources.indexOf(DistroMirrorProvisioner.MirrorSource.ALIYUN)
                    if (idx >= 0) spinnerMirrorSource.setSelection(idx)
                } else if (fastest.name().contains("清华")) {
                    val idx = sources.indexOf(DistroMirrorProvisioner.MirrorSource.TUNA)
                    if (idx >= 0) spinnerMirrorSource.setSelection(idx)
                } else if (fastest.name().contains("中科大")) {
                    val idx = sources.indexOf(DistroMirrorProvisioner.MirrorSource.USTC)
                    if (idx >= 0) spinnerMirrorSource.setSelection(idx)
                }

                Toast.makeText(this@SettingsSubActivity, "测速完成，推荐节点：${fastest.name()}", Toast.LENGTH_SHORT).show()

                // T2 (2026-11-08) Lane motion consumption: a finished speed test is
                // the page's explicit data-refresh moment, so replay the section
                // stagger on the sub-page container. First-open motion is owned by
                // the container-transform entrance, so the two never double up.
                findViewById<ViewGroup?>(R.id.settings_sub_container)?.let {
                    OrchestellaMotion.playSectionStagger(it)
                }
            }
        }
    }

    // =====================================================================
    // 关于与合规（旧一级页「关于与官方商标声明」卡迁入；商标长文折叠为
    // 「商标与合规声明」expandSection 折叠块，默认收起）
    // =====================================================================

    private fun bindAbout(container: LinearLayout) {
        currentSection = SECTION_ABOUT
        toolbarTitle(R.string.settings_section_about_title)
        layoutInflater.inflate(R.layout.layout_settings_section_about, container, true)
        findViewById<TextView>(R.id.txt_settings_version)?.text = "版本：v${BuildConfig.VERSION_NAME}"
        setupTrademarkDisclosureSection()
    }

    /**
     * The trademark wall becomes a toggleable section via
     * [OrchestellaMotion.expandSection]/[collapseSection] — the same
     * vocabulary as the launch-fallback block on the settings home page.
     * Default state is collapsed (reference material, not a decision);
     * a generation counter stops a stale collapse-end action from GONE-ing
     * a section that was re-expanded meanwhile.
     */
    private fun setupTrademarkDisclosureSection() {
        val header = findViewById<View?>(R.id.layout_settings_trademark_header) ?: return
        val content = findViewById<View?>(R.id.layout_settings_trademark_content) ?: return
        val indicator = findViewById<TextView?>(R.id.txt_settings_trademark_indicator)
        var expanded = false
        var toggleGeneration = 0
        fun repaintIndicator() {
            indicator?.text = if (expanded) "▾" else "▸"
        }
        repaintIndicator()
        header.setOnClickListener {
            toggleGeneration++
            val generation = toggleGeneration
            expanded = !expanded
            if (expanded) {
                OrchestellaMotion.expandSection(content)
            } else {
                OrchestellaMotion.collapseSection(content) {
                    if (generation == toggleGeneration) content.visibility = View.GONE
                }
            }
            repaintIndicator()
        }
    }
}
