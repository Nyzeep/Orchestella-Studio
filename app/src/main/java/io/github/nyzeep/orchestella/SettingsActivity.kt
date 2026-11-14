package io.github.nyzeep.orchestella

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.ui.motion.OrchestellaMotion
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator

/**
 * Settings home page: section directory (ADR-0006, T3 settings IA redesign).
 *
 * <p>Level 1 is a section directory — the rows open per-section sub-pages
 * ([SettingsSubActivity]); the 「硬件虚拟化与运行架构」summary card stays here
 * because the AVF badge is a decision item, not a preference. Section
 * content and its listeners live in the sub-pages; preference keys are
 * untouched by the migration.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var txtAvfBadge: TextView
    private lateinit var txtAvfDetails: TextView

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

        initViews()
        setupAvfSection()
        setupFallbackExplanationSection()
        ThemeApplier.bind(this)
    }

    override fun onResume() {
        super.onResume()
        setupAvfSection()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.settings_toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        txtAvfBadge = findViewById(R.id.txt_settings_avf_badge)
        txtAvfDetails = findViewById(R.id.txt_settings_avf_details)
        findViewById<MaterialButton>(R.id.btn_settings_shizuku).setOnClickListener {
            ShizukuClientDialog().show(supportFragmentManager, "shizuku-client")
        }

        toolbar.navigationContentDescription = "返回"

        // 分节目录（T3 settings IA redesign）：行点击进 SettingsSubActivity 对应节；
        // 「虚拟化与架构」摘要卡保留一级（决策项），不进目录。
        findViewById<View?>(R.id.row_settings_mirror)?.setOnClickListener {
            startActivity(SettingsSubActivity.intent(this, SettingsSubActivity.SECTION_MIRROR))
        }
        findViewById<View?>(R.id.row_settings_appearance)?.setOnClickListener {
            startActivity(SettingsSubActivity.intent(this, SettingsSubActivity.SECTION_APPEARANCE))
        }
        findViewById<View?>(R.id.row_settings_about)?.setOnClickListener {
            startActivity(SettingsSubActivity.intent(this, SettingsSubActivity.SECTION_ABOUT))
        }
        findViewById<View?>(R.id.row_settings_advanced)?.setOnClickListener {
            startActivity(SettingsSubActivity.intent(this, SettingsSubActivity.SECTION_ADVANCED))
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
}
