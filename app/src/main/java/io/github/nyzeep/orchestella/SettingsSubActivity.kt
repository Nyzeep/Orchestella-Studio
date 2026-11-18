package io.github.nyzeep.orchestella

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.ui.motion.OrchestellaMotion
import io.github.nyzeep.orchestella.ui.screens.config.ConfigPreferences
import io.github.nyzeep.orchestella.ui.screens.config.ConfigSections
import io.github.nyzeep.orchestella.ui.screens.config.DefaultMirror
import io.github.nyzeep.orchestella.ui.screens.config.SectionMotion
import io.github.nyzeep.orchestella.ui.screens.config.SharedPreferencesConfigStore
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorDecision
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorMode
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorPolicy
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorReasonCode
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator

/** Logcat tag for the accelerator-lane verdict (enum names only, no paths). */
private const val ACCEL_LANE_TAG = "AccelLane"

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

        /** 「镜像源与加速」：软件源策略 Spinner + 测速优选 + Docker 加速源。 */
        const val SECTION_MIRROR = ConfigSections.MIRROR

        /** 「关于与合规」：版本 + 商标与合规声明（折叠块）。 */
        const val SECTION_ABOUT = ConfigSections.ABOUT

        /** 「高级」：pVM 编译加速（加速与性能）+ 启动阶段诊断（合并节）。 */
        const val SECTION_ADVANCED = ConfigSections.ADVANCED

        /** 「引擎与授权」：AVF 预检、QEMU 回退与 UML 门控摘要。 */
        const val SECTION_SYSTEM = ConfigSections.SYSTEM

        /** Container-transform approximation: entry scale (and alpha 0→1). */
        private const val TRANSFORM_START_SCALE = 0.96f

        /** Low-RAM tier duration (350ms, between short4 and medium1). */
        private const val TRANSFORM_LOW_TIER_MS = 350L

        /** Fade-only tier for the user's 「精简」 choice. */
        private const val TRANSFORM_REDUCED_MS = 120L

        fun intent(context: Context, section: String): Intent =
            Intent(context, SettingsSubActivity::class.java).putExtra(EXTRA_SECTION, section)
    }

    private lateinit var settingsManager: SettingsManager
    private lateinit var configPreferences: ConfigPreferences

    /** Engine/authorization page views (bound only for [SECTION_SYSTEM]). */
    private lateinit var txtAvfBadge: TextView
    private lateinit var txtAvfDetails: TextView

    /** Mirror speed-test probe runs off the main thread; shutdown in onDestroy. */
    private val backgroundExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Accelerator status row (advanced section); guarded by ::isInitialized in the probe. */
    private lateinit var txtAccelStatus: TextView

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
        configPreferences = ConfigPreferences(SharedPreferencesConfigStore(this))

        val container = findViewById<LinearLayout>(R.id.settings_sub_container)
        when (intent?.getStringExtra(EXTRA_SECTION)) {
            SECTION_MIRROR -> bindMirror(container)
            SECTION_ABOUT -> bindAbout(container)
            SECTION_ADVANCED -> bindAdvanced(container)
            SECTION_SYSTEM -> bindSystem(container)
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
        when (currentSection) {
            // Status mirrors the launch-time decision; re-probe on return so the
            // verdict never goes stale while the page is backgrounded.
            SECTION_ADVANCED -> setupAcceleratorSection()
            SECTION_SYSTEM -> setupSystemSection()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundExecutor.shutdown()
    }

    /** Section currently bound (null before onCreate dispatch completes). */
    private var currentSection: String? = null

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

        // The user's motion choice is a second signal on top of the system
        // animator scale: standard keeps the container transform, reduced keeps
        // only the fade, off snaps. ValueAnimator still collapses every tier to
        // 0 ms when the system "remove animations" setting is active.
        when (configPreferences.sectionMotion()) {
            SectionMotion.Standard -> {
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

            SectionMotion.Reduced -> {
                container.pivotY = 0f
                container.scaleX = 1f
                container.scaleY = 1f
                container.alpha = 0f
                container.animate()
                    .alpha(1f)
                    .setDuration(TRANSFORM_REDUCED_MS)
                    .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                    .setListener(null)
                    .start()
            }

            SectionMotion.Off -> {
                container.scaleX = 1f
                container.scaleY = 1f
                container.alpha = 1f
            }
        }
    }
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
        // Persisted default (UI3-04): the selected policy survives reopening the
        // page instead of resetting to MULTI_TIER every time. Unknown values fall
        // back to MULTI_TIER through ConfigPreferences.
        val savedMirror = configPreferences.defaultMirror()
        spinnerMirrorSource.setSelection(
            sources.indexOfFirst { it.name == savedMirror.key }.coerceAtLeast(0)
        )
        spinnerMirrorSource.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val picked = DefaultMirror.fromKey(sources[position].name)
                configPreferences.setDefaultMirror(picked)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        btnSpeedTest.setOnClickListener {
            runMirrorSpeedTest(btnSpeedTest, spinnerMirrorSource, txtSpeedTestResults)
        }

        btnResetMirror.setOnClickListener {
            configPreferences.setDefaultMirror(DefaultMirror.MultiTier)
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

    // =====================================================================
    // 引擎与授权（UI3-04）：原 SettingsActivity 一级页的 AVF 摘要卡与
    // 「启动验证与回退」折叠块整体迁入；探测逻辑与阈值不变，徽章与详情
    // id 保持，旧入口删除后配置页仍能两级内到达。
    // =====================================================================

    private fun bindSystem(container: LinearLayout) {
        currentSection = SECTION_SYSTEM
        toolbarTitle(R.string.config_system_section_title)
        layoutInflater.inflate(R.layout.layout_settings_section_system, container, true)
        txtAvfBadge = findViewById(R.id.txt_settings_avf_badge)
        txtAvfDetails = findViewById(R.id.txt_settings_avf_details)
        findViewById<MaterialButton>(R.id.btn_settings_shizuku).setOnClickListener {
            ShizukuClientDialog().show(supportFragmentManager, "shizuku-client")
        }
        setupSystemSection()
        setupFallbackExplanationSection()
    }

    private fun setupSystemSection() {
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
                // Session-18 W-B: the unsupported verdict is a capability
                // statement, not a device defect — the badge becomes a neutral
                // entry point to the compatibility explanation page instead of
                // a red dead end.
                txtAvfBadge.text = BadgeCopy.avfUnsupportedEntryLabel()
                txtAvfBadge.setTextColor(getColor(R.color.zone_neutral))
                txtAvfBadge.setOnClickListener {
                    startActivity(Intent(this, CompatExplanationActivity::class.java))
                }
                txtAvfDetails.text = "$factsSummary\n• 此功能依赖设备的硬件虚拟化能力，你的设备上不可用（不影响其他功能）\n• 点击上方徽章查看兼容性说明\n$launchNotice"
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

    /**
     * Lane B motion consumption: the "启动验证与回退" explanation card becomes a
     * toggleable section via [OrchestellaMotion.expandSection]/[collapseSection].
     * Default state stays expanded so first-open behavior is unchanged; a
     * generation counter stops a stale collapse-end action from GONE-ing a
     * section that was re-expanded meanwhile.
     */
    private fun setupFallbackExplanationSection() {
        val header = findViewById<View?>(R.id.layout_settings_fallback_header) ?: return
        val content = findViewById<View?>(R.id.layout_settings_fallback_content) ?: return
        val indicator = findViewById<TextView?>(R.id.txt_settings_fallback_expand_indicator)
        var expanded = true
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

    // =====================================================================
    // 高级（旧一级页「加速与性能」卡 + 「诊断与开发者选项」卡合并迁入；
    // pVM 开关与 stage trace 开关读写同一 SettingsManager key，探测逻辑
    // 原样迁移，onResume 重新探测）
    // =====================================================================

    private fun bindAdvanced(container: LinearLayout) {
        currentSection = SECTION_ADVANCED
        toolbarTitle(R.string.settings_section_advanced_title)
        layoutInflater.inflate(R.layout.layout_settings_section_advanced, container, true)
        setupAcceleratorSection()
        setupDiagnosticsSection()
    }

    /**
     * Accelerator lane status + runtime switch. Status mirrors the launch-time
     * decision: the build flag is a hard gate, the stored user value decides
     * otherwise, and an enabled lane is probed with the same facts + policy the
     * dispatcher consults (no separate source of truth).
     */
    private fun setupAcceleratorSection() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switch_settings_accel_lane
        ) ?: return
        val status = findViewById<TextView>(R.id.txt_settings_accel_status) ?: return
        txtAccelStatus = status

        val buildEnabled = BuildConfig.ACCELERATOR_LANE_ENABLED
        val userEnabled = settingsManager.isAcceleratorLaneEnabled()
        // Assign state before the listener so a re-run never re-persists.
        switch.isChecked = userEnabled && buildEnabled
        switch.isEnabled = buildEnabled
        when {
            !buildEnabled -> {
                txtAccelStatus.text = "pVM 编译加速：不可用（构建未含加速组件）"
                txtAccelStatus.setTextColor(getColor(R.color.zone_yellow))
            }
            !userEnabled -> {
                txtAccelStatus.text = "pVM 编译加速：已停用（你在设置中关闭）"
                txtAccelStatus.setTextColor(getColor(R.color.zone_red))
            }
            else -> probeAcceleratorLane()
        }
        switch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.setAcceleratorLaneEnabled(isChecked)
            if (isChecked) probeAcceleratorLane() else {
                txtAccelStatus.text = "pVM 编译加速：已停用（你在设置中关闭）"
                txtAccelStatus.setTextColor(getColor(R.color.zone_red))
            }
        }
    }

    /** Best-effort lane availability probe; identical source to the dispatch verdict. */
    private fun probeAcceleratorLane() {
        if (!::txtAccelStatus.isInitialized) return
        txtAccelStatus.text = "pVM 编译加速：已启用（检测车道…）"
        txtAccelStatus.setTextColor(getColor(R.color.zone_yellow))
        txtAccelStatus.setOnClickListener(null)
        txtAccelStatus.isClickable = false
        backgroundExecutor.execute {
            val resolution = AccelExtensionResolver(this).resolve()
            val laneVerdict = try {
                val facts = AndroidAcceleratorFactProvider.create(this).read()
                AcceleratorPolicy.decide(
                    facts, AcceleratorLaunchDispatcher.GUEST_COMPILER_TRIPLE
                )
            } catch (_: RuntimeException) {
                null
            }
            val laneUsable = laneVerdict?.mode() == AcceleratorMode.QEMU_PLUS_ACCEL
            // The verdict's own JSON contract (enum names only, never paths or
            // device identifiers): a refused lane must name its reason here,
            // because the UI can only explain what the probe observed.
            Log.i(
                ACCEL_LANE_TAG,
                "extension=${resolution.decision.status} lane=${laneVerdict?.toJson() ?: "unavailable"}"
            )
            runOnUiThread {
                if (!::txtAccelStatus.isInitialized) return@runOnUiThread
                when {
                    laneUsable -> {
                        txtAccelStatus.text = "pVM 编译加速：已启用（车道可用，实例启动时生效）"
                        txtAccelStatus.setTextColor(getColor(R.color.zone_green))
                    }
                    resolution.decision.status == AccelExtensionRules.Status.EXTENSION_MISSING -> {
                        // Two-package split: the honest "not installed" state
                        // with a direct guide, instead of a generic fallback.
                        txtAccelStatus.text = "pVM 编译加速：未安装 pVM 加速扩展（点此查看安装引导）"
                        txtAccelStatus.setTextColor(getColor(R.color.zone_neutral))
                        txtAccelStatus.setOnClickListener {
                            openAcceleratorExtensionGuide()
                        }
                        txtAccelStatus.isClickable = true
                    }
                    else -> {
                        // Extension problems are the user's to fix, so they
                        // outrank the platform verdict; once the extension is
                        // ready, the policy's refusal reason is the only
                        // explanation left (and the only honest one).
                        val reason = if (resolution.decision.status != AccelExtensionRules.Status.OK) {
                            resolution.decision.detail
                        } else {
                            acceleratorReasonText(laneVerdict)
                                ?: resolution.decision.detail
                        }
                        txtAccelStatus.text = "pVM 编译加速：已启用（$reason，实例启动时回退普通编译）"
                        txtAccelStatus.setTextColor(getColor(R.color.zone_yellow))
                    }
                }
            }
        }
    }

    /**
     * One path-free sentence for a refused lane, in the policy's own
     * vocabulary. Returns null when the verdict is not a refusal, so the
     * caller falls back to the extension-side detail instead of inventing a
     * cause. Unmapped codes keep their enum name — never a guessed story.
     */
    private fun acceleratorReasonText(decision: AcceleratorDecision?): String? {
        val code = decision?.reasonCode() ?: return null
        return when (code) {
            AcceleratorReasonCode.ACCEL_AVAILABLE -> null
            AcceleratorReasonCode.PAYLOAD_UNAVAILABLE_NO_PROTECTED ->
                "系统未提供受保护虚拟机能力"
            AcceleratorReasonCode.PAYLOAD_IMAGE_MISSING -> "加速载荷缺失"
            AcceleratorReasonCode.PAYLOAD_COMPILER_MISMATCH ->
                "加速载荷与 guest 编译器不匹配"
            AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED ->
                "系统不支持非保护型虚拟机"
            AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND ->
                "本机未提供可用的加速后端（若尚未授权硬件虚拟化，请先完成 AVF 授权）"
            AcceleratorReasonCode.ACCEL_IMAGE_MISSING -> "加速镜像缺失"
            AcceleratorReasonCode.ACCEL_VSOCK_UNAVAILABLE -> "系统未提供 vsock 通道"
            AcceleratorReasonCode.COMPILER_MISMATCH ->
                "编译器版本与加速镜像不匹配"
            AcceleratorReasonCode.THERMAL_BUDGET -> "当前温度或电量不允许加速"
            else -> code.name
        }
    }

    /**
     * D2: the extension guide points at the repository Releases page (the
     * distribution channel for the standalone extension APK). Replaced by a
     * stable in-app URL when the release round lands.
     */
    private fun openAcceleratorExtensionGuide() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(ReleaseSelector.RELEASES_PAGE))
            )
        } catch (_: RuntimeException) {
            Toast.makeText(this, "未找到可用的浏览器，请手动访问仓库 Releases 页", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Ticket 27: launch stage diagnostics opt-in. The UI only reads/writes the
     * same SettingsManager key the launch pipeline consults; enabling here never
     * collects anything by itself — capture starts on the next launch attempt.
     */
    private fun setupDiagnosticsSection() {
        val switch = findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
            R.id.switch_settings_stage_trace
        ) ?: return
        switch.isChecked = settingsManager.isStageTraceDiagnosticsEnabled
        switch.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isStageTraceDiagnosticsEnabled = isChecked
        }
    }
}
