package io.github.nyzeep.orchestella

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import io.github.nyzeep.orchestella.ui.motion.OrchestellaMotion
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorDecision
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorMode
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorPolicy
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorReasonCode
import java.util.concurrent.Executors

/** Logcat tag for the accelerator-lane verdict (enum names only, no paths). */
private const val ACCEL_LANE_TAG = "AccelLane"

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
    private lateinit var switchStageTrace: MaterialSwitch
    private lateinit var switchAccelLane: MaterialSwitch
    private lateinit var txtAccelStatus: TextView

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
        setupMirrorSection()
        setupAvfSection()
        setupDiagnosticsSection()
        setupAcceleratorSection()
        setupFallbackExplanationSection()
    }

    override fun onResume() {
        super.onResume()
        setupAvfSection()
        setupAcceleratorSection()
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
        switchStageTrace = findViewById(R.id.switch_settings_stage_trace)

        findViewById<TextView>(R.id.txt_settings_version)?.text = "版本：v${BuildConfig.VERSION_NAME}"
        toolbar.navigationContentDescription = "返回"
        ThemeApplier.setRole(btnSpeedTest, ThemeApplier.Role.PRIMARY_ACTION)
        ThemeApplier.setRole(btnResetMirror, ThemeApplier.Role.SECONDARY_ACTION)

        // 分节目录（T3 settings IA redesign）：行点击进 SettingsSubActivity 对应节；
        // 「虚拟化与架构」摘要卡保留一级（决策项），不进目录。
        findViewById<View?>(R.id.row_settings_appearance)?.setOnClickListener {
            startActivity(SettingsSubActivity.intent(this, SettingsSubActivity.SECTION_APPEARANCE))
        }
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

                Toast.makeText(this@SettingsActivity, "测速完成，推荐节点：${fastest.name()}", Toast.LENGTH_SHORT).show()

                // T2 (2026-11-08) Lane motion consumption: a finished speed test is
                // the page's explicit data-refresh moment, so replay the section
                // stagger on settings_root_container. The first-open play is still
                // owned solely by the XML layoutAnimation — this code path never
                // runs in onCreate, so the two never double up.
                findViewById<ViewGroup?>(R.id.settings_root_container)?.let {
                    OrchestellaMotion.playSectionStagger(it)
                }
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
     * toggleable section via [OrchestellaMotion.expandSection]/[collapseSection],
     * exactly the hook point named in that KDoc. Default state stays expanded so
     * first-open behavior is unchanged; a generation counter stops a stale
     * collapse-end action from GONE-ing a section that was re-expanded meanwhile.
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

    /**
     * Ticket 27: launch stage diagnostics opt-in. The UI only reads/writes the
     * same SettingsManager key the launch pipeline consults; enabling here never
     * collects anything by itself — capture starts on the next launch attempt.
     */
    private fun setupDiagnosticsSection() {
        switchStageTrace.isChecked = settingsManager.isStageTraceDiagnosticsEnabled
        switchStageTrace.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isStageTraceDiagnosticsEnabled = isChecked
        }
    }

    /**
     * Accelerator lane status + runtime switch. Status mirrors the launch-time
     * decision: the build flag is a hard gate, the stored user value decides
     * otherwise, and an enabled lane is probed with the same facts + policy the
     * dispatcher consults (no separate source of truth).
     */
    private fun setupAcceleratorSection() {
        val switch = findViewById<MaterialSwitch>(R.id.switch_settings_accel_lane) ?: return
        val status = findViewById<TextView>(R.id.txt_settings_accel_status) ?: return
        switchAccelLane = switch
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
}
