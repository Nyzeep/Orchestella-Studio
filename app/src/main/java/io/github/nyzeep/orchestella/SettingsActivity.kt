package io.github.nyzeep.orchestella

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import androidx.activity.result.contract.ActivityResultContracts
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import java.util.concurrent.Executors

/**
 * Secondary Settings & Control Center Activity (ADR-0006, Phase 5.0 R4).
 *
 * <p>Aligned with the Instance Detail Console UI/UX standards:
 * <ul>
 *   <li>Comprehensive Multi-tier Mirror Management & One-click parallel latency probe;</li>
 *   <li>Docker registry-mirrors overview & reset;</li>
 *   <li>Realtime AVF Hardware virtualization probe & Graceful fallback documentation;</li>
 *   <li>Terminal cursor blink preferences;</li>
 *   <li>Official Linux distro trademarks & compliance credits.</li>
 * </ul>
 */
class SettingsActivity : AppCompatActivity() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private lateinit var settingsManager: SettingsManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var spinnerMirrorSource: Spinner
    private lateinit var btnSpeedTest: MaterialButton
    private lateinit var btnResetMirror: MaterialButton
    private lateinit var txtSpeedTestResults: TextView
    private lateinit var txtDockerMirrors: TextView
    private lateinit var txtAvfBadge: TextView
    private lateinit var txtAvfDetails: TextView
    private lateinit var switchCursorBlink: MaterialSwitch
    private lateinit var themeStore: ThemeStore
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

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val rootCoordinator = findViewById<View>(R.id.settings_root_coordinator)
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

        settingsManager = SettingsManager(this)
        initViews()
        themeStore = ThemeStore(this)
        setupMirrorSection()
        setupAvfSection()
        setupTerminalSection()
        setupThemeSection()
        refreshAppearance()
    }

    private fun refreshAppearance() {
        val theme = themeStore.resolve()
        findViewById<TextView>(R.id.txt_settings_theme_summary).text =
            "${theme.selectionLabel} · ${themeStore.mode().displayName} · 立即应用到全部页面"
        findViewById<MaterialButton>(R.id.btn_pick_theme_mode).text = "明暗模式：${themeStore.mode().displayName}"
        findViewById<TextView>(R.id.txt_settings_terminal_palette).text =
            "配色：${theme.displayName}\n等宽字体与 16 色 ANSI 调色板跟随主题"
        findViewById<MaterialButton>(R.id.btn_clear_background).isEnabled = themeStore.backgroundUri() != null
        ThemeApplier.bind(this)
    }

    override fun onResume() {
        super.onResume()
        if (::themeStore.isInitialized) {
            refreshAppearance()
            setupAvfSection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.settings_toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        spinnerMirrorSource = findViewById(R.id.spinner_settings_mirror_source)
        btnSpeedTest = findViewById(R.id.btn_settings_speed_test)
        btnResetMirror = findViewById(R.id.btn_settings_reset_mirror)
        txtSpeedTestResults = findViewById(R.id.txt_settings_speed_test_results)
        txtDockerMirrors = findViewById(R.id.txt_settings_docker_mirrors)
        txtAvfBadge = findViewById(R.id.txt_settings_avf_badge)
        txtAvfDetails = findViewById(R.id.txt_settings_avf_details)
        findViewById<MaterialButton>(R.id.btn_settings_shizuku).setOnClickListener {
            ShizukuClientDialog().show(supportFragmentManager, "shizuku-client")
        }
        switchCursorBlink = findViewById(R.id.switch_settings_cursor_blink)

        findViewById<TextView>(R.id.txt_settings_version)?.text = "版本：v${BuildConfig.VERSION_NAME}"
        toolbar.navigationContentDescription = "返回"
        ThemeApplier.setRole(btnSpeedTest, ThemeApplier.Role.PRIMARY_ACTION)
        ThemeApplier.setRole(btnResetMirror, ThemeApplier.Role.SECONDARY_ACTION)
        ThemeApplier.setRole(findViewById(R.id.btn_pick_theme_mode), ThemeApplier.Role.SECONDARY_ACTION)
        ThemeApplier.setRole(findViewById(R.id.btn_clear_background), ThemeApplier.Role.SECONDARY_ACTION)
    }

    private fun setupMirrorSection() {
        val sources = DistroMirrorProvisioner.MirrorSource.values()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources.map { it.displayName() }
        )
        spinnerMirrorSource.adapter = adapter
        spinnerMirrorSource.setSelection(0) // Default: MULTI_TIER

        btnSpeedTest.setOnClickListener {
            runMirrorSpeedTest()
        }

        btnResetMirror.setOnClickListener {
            spinnerMirrorSource.setSelection(0)
            txtSpeedTestResults.text = "已恢复为默认多层级回退配置 (国内云源 -> 教育网 -> 官方源)"
            Toast.makeText(this, "已恢复默认镜像源策略", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runMirrorSpeedTest() {
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
                sb.append("\n⚡ 建议优选：").append(fastest.name())
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

                Toast.makeText(this@SettingsActivity, "测速完成，推荐节点：${fastest.name()}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAvfSection() {
        val facts = AvfHostFactsProbe.probe(this)
        val readiness = AvfCapabilityEvaluator.evaluate(facts)
        findViewById<MaterialButton>(R.id.btn_settings_shizuku).visibility =
            if (readiness.wizardEligible()) View.VISIBLE else View.GONE
        val factsSummary = """
            • 硬件平台：${facts.socModel() ?: facts.deviceModel() ?: "ARM64 设备"}
            • Android 版本：API ${facts.sdkInt()}
            • AVF feature：${if (facts.virtualizationFrameworkFeature()) "已声明" else "未声明"}
            • 虚拟化属性：${facts.virtualizationProperty()?.takeIf { it.isNotBlank() } ?: "无法读取或未提供"}
            • VM 模式：${AvfHostFactsProbe.modeSummary(facts.modeFacts())}
            • 平台访问：${AvfHostFactsProbe.platformSummary(facts.platformFacts())}
            • MANAGE_VIRTUAL_MACHINE：${AvfHostFactsProbe.permissionLabel(facts.manageVirtualMachinePermissionState())}
            • USE_CUSTOM_VIRTUAL_MACHINE：${AvfHostFactsProbe.permissionLabel(facts.useCustomVirtualMachinePermissionState())}
        """.trimIndent()
        val launchNotice = getString(R.string.avf_preflight_boundary)

        when (readiness.status()) {
            AvfCapabilityEvaluator.AvfStatus.READY -> {
                txtAvfBadge.text = "硬件虚拟化权限预检满足"
                txtAvfBadge.setTextColor(getColor(R.color.zone_green))
                txtAvfDetails.text = "$factsSummary\n• 预检：声明与权限条件满足\n$launchNotice"
            }
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> {
                txtAvfBadge.text = "AVF 尚未授权"
                txtAvfBadge.setTextColor(getColor(R.color.zone_yellow))
                txtAvfDetails.text = "$factsSummary\n• 预检：${readiness.reasons().joinToString("；")}\n$launchNotice"
            }
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED -> {
                txtAvfBadge.text = "AVF 模式不支持"
                txtAvfBadge.setTextColor(getColor(R.color.zone_red))
                txtAvfDetails.text = "$factsSummary\n• 预检原因：${readiness.reasons().joinToString("；")}\n• 授权不会改变 VM 模式；当前使用 QEMU\n$launchNotice"
            }
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> {
                txtAvfBadge.text = "AVF 模式未验证"
                txtAvfBadge.setTextColor(getColor(R.color.zone_yellow))
                txtAvfDetails.text = "$factsSummary\n• 预检原因：${readiness.reasons().joinToString("；")}\n• 授权不会改变 VM 模式；当前使用 QEMU\n$launchNotice"
            }
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED -> {
                txtAvfBadge.text = "AVF 平台接口受限"
                txtAvfBadge.setTextColor(getColor(R.color.zone_red))
                txtAvfDetails.text = "$factsSummary\n• 预检原因：${readiness.reasons().joinToString("；")}\n• 这是系统隐藏 API 限制，不是权限问题；当前使用 QEMU\n$launchNotice"
            }
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> {
                txtAvfBadge.text = "AVF 平台接口缺失"
                txtAvfBadge.setTextColor(getColor(R.color.zone_red))
                txtAvfDetails.text = "$factsSummary\n• 预检原因：${readiness.reasons().joinToString("；")}\n• 当前系统未提供该平台接口；当前使用 QEMU\n$launchNotice"
            }
            AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED -> {
                txtAvfBadge.text = "AVF 预检未满足"
                txtAvfBadge.setTextColor(getColor(R.color.zone_red))
                val reasons = readiness.reasons().joinToString("；")
                txtAvfDetails.text = "$factsSummary\n• 预检原因：$reasons\n• 启动路径：QEMU\n$launchNotice"
            }
        }
    }

    private fun setupTerminalSection() {
        switchCursorBlink.isChecked = settingsManager.isTerminalCursorBlinkEnabled
        switchCursorBlink.setOnCheckedChangeListener { _, isChecked ->
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
            val modes = ThemeStore.Mode.entries
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("明暗模式")
                .setSingleChoiceItems(modes.map { it.displayName }.toTypedArray(), themeStore.mode().ordinal) { dialog, which ->
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
}
