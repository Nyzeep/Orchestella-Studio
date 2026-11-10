package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator

/**
 * Pure assembly of the step-1 AVF diagnostic block (2026-11-11 walkthrough W2).
 *
 * <p>The probe/evaluator chain stays untouched; only the string the wizard
 * shows is assembled here, so the four walkthrough defects are contract-locked
 * on the JVM:
 * <ul>
 *   <li>(a) kv tail printed twice — the platform summary already inlines the
 *       mode-facts tail (来源/actor/采样/ROM); the mode line is now folded into
 *       it so the tail is concatenated exactly once.</li>
 *   <li>(b) 采样=<bare epoch millis> — rewritten through an injected formatter
 *       to a human-readable local time.</li>
 *   <li>(c) long permission names wrapping mid-word (USE_C/USTOM) — zero-width
 *       break opportunities are inserted at underscore boundaries, keeping the
 *       recognizable token itself intact.</li>
 *   <li>(d) kv wall burying the storage task — the block renders collapsed by
 *       default behind a one-line conclusion ([summaryLine]/[CollapseState]),
 *       following the SettingsActivity expand-section vocabulary.</li>
 * </ul>
 */
object WizardAvfDiagnostics {

    /** Zero-width space: line-break opportunity that keeps copy/paste intact. */
    const val BREAK_OPPORTUNITY: String = "​"

    /** Mode-facts kv tail keys; the contract test locks each to exactly one occurrence. */
    private val KV_TAIL_KEYS = listOf("来源=", "actor=", "采样=", "ROM=")

    /**
     * Assembles the facts block shown in the (collapsed by default) detail area.
     *
     * <p>When a platform observation exists its summary already carries the
     * mode-facts tail, so the separate "VM 模式" line is omitted — that was the
     * duplicate concatenation seen on device. Legacy callers without a platform
     * observation keep the mode line.
     */
    fun factsSummary(
        avfFeatureDeclared: Boolean,
        virtualizationProperty: String?,
        platformSummary: String?,
        modeSummary: String,
        managePermissionLabel: String,
        customPermissionLabel: String,
        sampledAtFormatter: (Long) -> String
    ): String {
        val lines = buildList {
            add("AVF feature：" + if (avfFeatureDeclared) "已声明" else "未声明")
            add("虚拟化属性：" + (virtualizationProperty?.takeIf { it.isNotBlank() } ?: "无法读取或未提供"))
            if (platformSummary == null) {
                add("VM 模式：$modeSummary")
            } else {
                add("平台访问：$platformSummary")
            }
            add(breakablePermissionName("MANAGE_VIRTUAL_MACHINE") + "：" + managePermissionLabel)
            add(breakablePermissionName("USE_CUSTOM_VIRTUAL_MACHINE") + "：" + customPermissionLabel)
        }
        return humanizeSampledAt(lines.joinToString("\n"), sampledAtFormatter)
    }

    /**
     * W2(b): rewrite every `采样=<epoch millis>` token in a diagnostic summary
     * through [format], keeping the `采样=` key itself. Unparseable or
     * out-of-range tokens are left unchanged (conservative display, no
     * detection semantics involved).
     */
    fun humanizeSampledAt(text: String, format: (Long) -> String): String =
        SAMPLED_AT_REGEX.replace(text) { match ->
            match.groupValues[1].toLongOrNull()?.let { "采样=" + format(it) } ?: match.value
        }

    /**
     * W2(c): a bare permission name has no break opportunities and overflow
     * wraps mid-word. Insert a zero-width break opportunity after every
     * underscore so wrapping happens at segment boundaries while the visible
     * (and copyable) characters stay the exact permission token.
     */
    fun breakablePermissionName(permission: String): String =
        permission.replace("_", "_$BREAK_OPPORTUNITY")

    /**
     * W2(d): one-line conclusion for the always-visible summary row. The
     * wording mirrors the badge/status semantics already in the wizard card;
     * "向导继续可用 QEMU 路径" states the fallback instead of implying a defect.
     */
    fun summaryLine(status: AvfCapabilityEvaluator.AvfStatus): String = when (status) {
        AvfCapabilityEvaluator.AvfStatus.READY -> "AVF 就绪——硬件虚拟化路径可用。"
        AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> "AVF 尚未授权——向导继续可用 QEMU 路径。"
        AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED -> "设备未提供 AVF 非保护型 VM——当前使用 QEMU。"
        AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> "AVF 模式未验证——向导继续可用 QEMU 路径。"
        AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED -> "AVF 平台接口受限——当前使用 QEMU。"
        AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> "AVF 平台接口缺失——当前使用 QEMU。"
        else -> "AVF 预检未满足——向导继续可用 QEMU 路径。"
    }

    /**
     * W2(d): collapse state machine. Folded by default; every toggle bumps the
     * generation so a stale collapse-end callback cannot GONE a section that
     * was re-expanded meanwhile (same guard as SettingsActivity's section).
     */
    data class CollapseState(val expanded: Boolean = false, val generation: Int = 0)

    fun toggled(state: CollapseState): CollapseState =
        CollapseState(expanded = !state.expanded, generation = state.generation + 1)

    /** Expand-entry glyph, same vocabulary as the Settings section header. */
    fun expandIndicator(expanded: Boolean): String = if (expanded) "▾" else "▸"

    private val SAMPLED_AT_REGEX = Regex("采样=(\\d+)")
}
