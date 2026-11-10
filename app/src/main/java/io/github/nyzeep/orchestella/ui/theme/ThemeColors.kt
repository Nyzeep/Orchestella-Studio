package io.github.nyzeep.orchestella.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Android-free color and preference rules shared by chrome and tests. */
object ThemeColors {
    enum class Role { BACKGROUND, SURFACE, PRIMARY, ACCENT, TEXT, MUTED, OUTLINE }

    /**
     * The card surface ladder (F-lane 三档归档, made runtime-visible for
     * MaterialCardView by the 2026-11-11 U1 round — the applier previously
     * flattened every tier to one surface):
     * background → CARD_WELL → CARD_PRIMARY → CARD_AUXILIARY (raised controls).
     *
     * - [CARD_PRIMARY]: decision/identity/form cards — the plain card surface
     *   (bg_oe_card tier). Paints exactly the pre-registry fill.
     * - [CARD_WELL]: secondary data-readout cards — one tonal step down toward
     *   the page ([surfaceLow]), matching the well_ LinearLayout banner treatment
     *   so both card shapes speak the same ladder.
     * - [CARD_AUXILIARY]: auxiliary info raised a whisper above the card surface
     *   ([surfaceHigh], the surfaceContainerHigh tactic). Defined for future
     *   registration; no layout consumer yet this round.
     *
     * Assignment is registration, not name-sniffing arms: cards land here either
     * through ThemeApplier.setSurfaceTier (explicit) or the id-keyed
     * [surfaceTierFor] archive table (declarative). Unregistered cards keep the
     * single-surface behavior byte-identically — that fallback is the regression
     * contract, so the danger zone and id-less cards ride it unchanged.
     */
    enum class SurfaceTier { CARD_PRIMARY, CARD_WELL, CARD_AUXILIARY }

    /**
     * The 2026-11-11 U1 三档归档 for every id-bearing MaterialCardView, keyed by
     * the existing layout id (zero renames — the id contract stays intact; the
     * table only READS it). Id-less cards and the semantic danger zone stay
     * unregistered on purpose: the fallback gives them the plain card surface,
     * which is exactly the tier where those are classified.
     */
    private val cardSurfaceTiers: Map<String, SurfaceTier> = mapOf(
        // activity_instance_detail: 一级决策卡 + 二级数据卡（F 注释归档）
        "card_header_dashboard" to SurfaceTier.CARD_PRIMARY,
        "card_detail_ssh_connect" to SurfaceTier.CARD_WELL,
        "card_detail_specs" to SurfaceTier.CARD_WELL,
        "card_hardware_specs_editor" to SurfaceTier.CARD_WELL,
        "card_storage_manager" to SurfaceTier.CARD_WELL,
        "card_network_port_forwarding" to SurfaceTier.CARD_WELL,
        // "card_danger_zone": 语义特例（红描边），不参与三档归一 → 不注册。
        // activity_settings: 全部为决策/表单区卡（一级）
        "card_settings_mirrors" to SurfaceTier.CARD_PRIMARY,
        "card_settings_virtualization" to SurfaceTier.CARD_PRIMARY,
        "card_settings_terminal" to SurfaceTier.CARD_PRIMARY,
        "card_settings_about" to SurfaceTier.CARD_PRIMARY,
        "card_settings_theme" to SurfaceTier.CARD_PRIMARY,
        "card_settings_accelerator" to SurfaceTier.CARD_PRIMARY,
        "card_settings_diagnostics" to SurfaceTier.CARD_PRIMARY,
        // activity_setup_wizard: 读数卡 well 档，选择/配置卡 card 档
        "card_wizard_avf_detection" to SurfaceTier.CARD_WELL,
        "card_distro_selector" to SurfaceTier.CARD_PRIMARY,
        "card_storage_config" to SurfaceTier.CARD_PRIMARY,
        "card_network_diag" to SurfaceTier.CARD_WELL,
        "card_mirror_select" to SurfaceTier.CARD_PRIMARY,
        "card_hardware_quota" to SurfaceTier.CARD_PRIMARY,
        "card_startup_tasks" to SurfaceTier.CARD_PRIMARY,
        "card_spec_summary" to SurfaceTier.CARD_WELL,
        "card_download_panel" to SurfaceTier.CARD_PRIMARY,
        // 列表/条目卡：实例卡一级决策（F 注释），端口转发规则条目二级数据（F 注释）
        "instance_card" to SurfaceTier.CARD_PRIMARY,
        "card_pf_rule" to SurfaceTier.CARD_WELL,
        // activity_about（走查报告清单外，按「一级决策=card、二级数据=well」判）：
        // 版本/更新卡是纯状态读数 → well；介绍/看板娘/作者/仓库四卡无 id，
        // 未注册 → fallback 即 card 档旧行为，视觉零变化。
        "card_version_update" to SurfaceTier.CARD_WELL,
    )

    /** Exact-id registry lookup (no prefix sniffing); null = unregistered fallback. */
    fun surfaceTierFor(id: String): SurfaceTier? = cardSurfaceTiers[id]

    /**
     * Card fill for a registered surface tier. Pure derivation so the JVM tier
     * tests can pin the ladder per theme without a view tree:
     *
     * - null / CARD_PRIMARY: the plain (already opacity-resolved) card surface —
     *   the exact pre-registry fill, so unregistered views are byte-identical.
     * - CARD_WELL: the [surfaceLow] step, same tactic as the well_ LinearLayout
     *   banners (page → well → card ladder).
     * - CARD_AUXILIARY: the [surfaceHigh] step above the card
     *   (surfaceContainerHigh tactic, same register as in-card text fields).
     */
    fun cardSurfaceFill(tier: SurfaceTier?, theme: AppTheme, effectiveSurface: Int): Int = when (tier) {
        SurfaceTier.CARD_WELL -> surfaceLow(theme.background, effectiveSurface)
        SurfaceTier.CARD_AUXILIARY -> surfaceHigh(effectiveSurface, theme.text)
        SurfaceTier.CARD_PRIMARY, null -> effectiveSurface
    }

    fun color(theme: AppTheme, role: Role): Int = when (role) {
        Role.BACKGROUND -> theme.background
        Role.SURFACE -> theme.surface
        Role.PRIMARY -> theme.primary
        Role.ACCENT -> theme.accent
        Role.TEXT -> theme.text
        Role.MUTED -> readable(theme.textDim, theme.surface)
        Role.OUTLINE -> blend(theme.surface, theme.text, 0.28f)
    }

    /**
     * Hairline stroke for card edges (Material 3 "outlineVariant" tactic): one low
     * mix step of the theme's own ink over its surface, so the border separates the
     * card from the page without the heavy neutral-grey band a full-strength outline
     * paints on light palettes. Both endpoints are theme roles, therefore every
     * catalog theme and every custom slot inherits an on-palette hairline.
     */
    fun hairline(theme: AppTheme): Int = blend(theme.surface, theme.text, 0.12f)

    /**
     * Edge stroke for ghost (secondary/outline) buttons: the theme primary pulled
     * toward the surface. Tinting the edge with the action hue keeps enabled ghost
     * buttons reading as intentional controls instead of flat grey blocks.
     */
    fun actionEdge(theme: AppTheme): Int = blend(theme.surface, theme.primary, 0.40f)

    /**
     * Disabled content ink: clearly dimmed against the surface yet still legible
     * (~4:1 contrast), so a disabled control reads "inactive by design" instead of
     * "broken". Disabled controls are WCAG-contrast-exempt, but the content must
     * stay recognizable.
     */
    fun disabledInk(theme: AppTheme): Int = blend(theme.surface, theme.text, 0.62f)

    /**
     * Disabled container fill: a whisper of the enabled fill over the surface
     * (the ~40%-opacity-of-enabled tactic, resolved opaquely so translucency
     * never lets the page background bleed through the button shape).
     */
    fun disabledFill(theme: AppTheme, fill: Int): Int = blend(theme.surface, fill, 0.14f)

    /**
     * Surface tier "low" — the well/banner step directly above the page
     * background (Material 3's tonal surfaceContainerLow tactic: emphasis by
     * tone, never by shadow). Half a blend-step from the page color toward the
     * card surface, so every catalog theme and custom slot inherits an
     * on-palette ladder. Sources:
     * https://m3.material.io/styles/color/roles
     * https://m3.material.io/blog/tone-based-surface-color-m3
     */
    fun surfaceLow(theme: AppTheme): Int = blend(theme.background, theme.surface, 0.5f)

    /** Overload for opacity-resolved inputs: page and card colors resolved by the caller. */
    fun surfaceLow(pageColor: Int, surfaceColor: Int): Int = blend(pageColor, surfaceColor, 0.5f)

    /**
     * Surface tier "high" — controls sitting ON a card (text fields, menus,
     * popups) raised one whisper of ink above their container
     * (surfaceContainerHigh tactic). A lighter step than [hairline] so fills
     * and strokes stay in separate visual registers.
     */
    fun surfaceHigh(theme: AppTheme): Int = blend(theme.surface, theme.text, 0.06f)

    /** Overload for opacity-resolved inputs. */
    fun surfaceHigh(surfaceColor: Int, ink: Int): Int = blend(surfaceColor, ink, 0.06f)

    /**
     * Pressed fill of a stateful control: the M3 state-layer tactic — ~10% of
     * the on-color washed over the container, replacing the flat "pressed looks
     * identical to rest" gap the hardcoded-tint buttons had. Sources:
     * https://m3.material.io/foundations/interactive-components/states
     */
    fun pressedFill(fill: Int, ink: Int): Int = blend(fill, ink, 0.10f)

    /** Prefer black/white by measured contrast; amber and pastel buttons need dark ink. */
    fun onColor(background: Int): Int =
        if (contrast(0xFF000000.toInt(), background) >= contrast(0xFFFFFFFF.toInt(), background)) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

    fun readable(foreground: Int, background: Int, minimum: Double = 4.5): Int {
        val opaque = foreground or 0xFF000000.toInt()
        if (contrast(opaque, background) >= minimum) return opaque
        val target = onColor(background)
        for (step in 1..20) {
            val candidate = blend(opaque, target, step / 20f)
            if (contrast(candidate, background) >= minimum) return candidate
        }
        return target
    }

    fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    fun blend(first: Int, second: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int =
            (((first ushr shift and 255) * (1f - f)) + ((second ushr shift and 255) * f)).toInt()
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Source roles are captured once, before replacing legacy hardcoded colors. */
    fun textRole(source: Int): Role {
        val rgb = source and 0x00FFFFFF
        return when (rgb) {
            0xD62828, 0x0047AB, 0xBD93F9, 0x007CFF, 0x00A1FF, 0xCBA6F7 -> Role.PRIMARY
            0xFDBB24, 0x8BE9FD, 0x00F6FF, 0xFFFF79, 0xFF79C6 -> Role.ACCENT
            0x8E929B, 0x9399B2, 0x6272A4, 0x8B949E, 0xA19E9A, 0x6B7280,
            0x9CA3AF, 0xAAAAAA, 0x888888, 0xCCCCCC -> Role.MUTED
            else -> if (isNeutral(rgb) && luminance(source) in 0.08..0.65) Role.MUTED else Role.TEXT
        }
    }

    fun backgroundRole(source: Int): Role {
        val rgb = source and 0x00FFFFFF
        return when (rgb) {
            0x0D0E11, 0x0C1017, 0x282A36, 0x07080A -> Role.BACKGROUND
            // Audit S3 blind spots: legacy card/surface tints pinned to SURFACE so
            // they no longer ride on the neutral-luminance heuristic band.
            0x121318, 0x0B0C0F, 0x0C0D10, 0x0D0E12, 0x0E0F14, 0x1F2026, 0x1E2028 -> Role.SURFACE
            // Session 17: theConstructivist dashboard XML fill family. #16171D doubles as
            // the light theme's text ink, so these must resolve to SURFACE explicitly
            // (see isLegacySurfaceHex for the inference-order guard).
            0x16171D, 0x1A1C22, 0x0C0D11 -> Role.SURFACE
            0xF5F4F0, 0xF8F8F2, 0xFFFFFF -> Role.TEXT
            0x2E323B, 0x333333 -> Role.OUTLINE
            else -> when (val role = textRole(source)) {
                Role.PRIMARY, Role.ACCENT -> role
                else -> Role.SURFACE
            }
        }
    }

    /**
     * True for the legacy dark-surface hex family used across dashboard XML drawables.
     * [0x16171D][0x16171D] collides with light themes' text ink, so background-role
     * inference must consult this set before any exact "bg == theme.text" match.
     */
    fun isLegacySurfaceHex(argb: Int): Boolean =
        (argb and 0x00FFFFFF) in legacySurfaceHexes

    private val legacySurfaceHexes = setOf(
        0x16171D, 0x1A1C22, 0x0C0D11, 0x121318, 0x0B0C0F,
        0x0C0D10, 0x0D0E12, 0x0E0F14, 0x1F2026, 0x1E2028,
    )

    /** Only this dashboard wrapper inherits its outer page's wallpaper; cards still paint surfaces. */
    fun isWallpaperContentWrapper(viewName: String, ancestorName: String): Boolean =
        viewName == "root_container" && ancestorName == "root_coordinator"

    // --- Name contracts (2026-11-11 U1: single source, table-tested) ---
    // The legacy id-name sniffing moved here from ThemeApplier so the contracts
    // are JVM-testable from one place. The applier now treats them strictly as
    // FALLBACK: registered views (Role / SurfaceTier) bypass these predicates,
    // and the id-keyed surface registry above carries the card tier semantics.

    /** Wells are page-level banners/strips: ids prefixed "well_" take the surfaceLow tier. */
    fun isWellId(id: String): Boolean = id.startsWith("well_")

    /** Cards whose stroke carries meaning (the danger zone's red ring) keep it. */
    fun isSemanticStrokeName(name: String): Boolean = name.contains("danger")

    /** Brand artwork never gets tinted (mascot/distro/logo/qr and friends). */
    fun isArtworkName(name: String): Boolean =
        listOf("distro", "logo", "brand", "qr", "screenshot", "preview_image").any(name::contains)

    /** Status badges keep their palette-independent ink when the theme repaints. */
    fun isStatusName(name: String): Boolean = listOf(
        "status_badge", "avf_badge", "badge_cfg_zone", "ram_safety_zone", "storage_warning",
        "txt_storage_warning", "net_test_result", "download_status", "runtime_badge", "fps_ping",
    ).any(name::contains)

    fun fontProgress(sp: Int): Int = sp.coerceIn(10, 22) - 10
    fun fontSize(progress: Int): Int = 10 + progress.coerceIn(0, 12)
    fun boundedSpacing(value: Float, maximum: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, maximum) else 0f

    /**
     * Monotonic corner-radius merge: the token may only grow existing geometry.
     *
     * Session 25 moved [ThemeStore.DEFAULT_CORNER_RADIUS_DP] from 0 to 16, so an
     * unconditional assignment would flatten pill status badges, circular icon
     * bases and already-large cards. Rules: a token of 0 keeps the existing
     * geometry untouched (pre-session-25 behavior), an existing radius larger
     * than the token wins, otherwise the token applies.
     */
    fun clampCornerRadius(existingPx: Float, tokenPx: Float): Float = when {
        tokenPx <= 0f -> existingPx
        existingPx.isFinite() && existingPx > tokenPx -> existingPx
        else -> tokenPx
    }

    private fun isNeutral(rgb: Int): Boolean {
        val channels = listOf(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255)
        return channels.max() - channels.min() < 48
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = (color ushr shift and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
