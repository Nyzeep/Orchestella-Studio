package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.ui.unit.Dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassTokens

/**
 * The draw path a glass surface takes for a level in the current environment
 * (spec §2.6, ticket UI3-10). Pure data, so the decision is JVM-testable and
 * the composable only renders.
 */
sealed interface Oe3GlassRender {

    /**
     * Opaque surface, no blur, no scrim: the [Oe3GlassLevel.None] level and
     * the pixel-stable default the shell shipped before the glass layer.
     */
    data object Solid : Oe3GlassRender

    /**
     * Real backdrop blur through Haze 1.x. The palette numbers come straight
     * from `Oe3Glass.resolve` — this class carries them, it does not own them.
     */
    data class Blurred(
        val blurRadius: Dp,
        val tintArgb: Int,
        val tintAlpha: Float,
        val backgroundArgb: Int,
        val fallbackTintArgb: Int,
    ) : Oe3GlassRender

    /**
     * Glass was requested but cannot render (API < 31, no RenderEffect, or
     * the layer was switched off): an almost-solid surface plus the 24dp top
     * scrim, so the chrome still reads as a surface instead of a black hole.
     */
    data object Fallback : Oe3GlassRender
}

/**
 * UI-06 分层降级阶梯（v4 spec §1.7，research/03 §A7）：
 *
 * - [Solid]：用户显式关闭，纯实色，与玻璃引入前的基线像素一致。
 * - [L0]：假玻璃配方（96% 表面 + 24dp 顶 scrim + 1dp 顶高光描边 + noise 0.02），
 *   API < 31、省电/减少动态、低内存设备、性能门降档时使用；无 GPU 模糊成本。
 * - [L1]：半分辨率 Haze 真模糊（tint α0.72/0.55），API 31–32 且性能门未降档。
 * - [L2]：L1 + API 33+ AGSL 边缘折射与高光边，API ≥ 33 且通过全部闸门。
 */
enum class GlassTier {
    Solid,
    L0,
    L1,
    L2,
    ;

    internal val samplesBackdrop: Boolean get() = this == L1 || this == L2
}

/**
 * UI-06 用户三档设置（Theme Studio 的玻璃模式）：关闭 / 自动 / 始终。
 *
 * - [Off]：用户显式关闭玻璃（保留的关闭能力，优先于一切）。
 * - [Auto]：默认档——按设备能力解析渲染档位（33+ → L2，31–32 → L1，<31 → L0），
 *   并尊重用户在旧档位选择里的显式关闭；从未选择过旧档位时给出自动玻璃（Standard）。
 * - [Always]：始终上最强玻璃（Ultra 风格），并绕过性能门的设备降档缓存——
 *   这是用户对「我就是要玻璃」的显式意志，硬闸（省电/低内存/API）仍然生效。
 */
enum class GlassMode(val key: String) {
    Off("off"),
    Auto("auto"),
    Always("always"),
    ;

    companion object {
        /** Maps ThemeStore's normalized `ui_glass_mode` value; unknown falls back to [Auto]. */
        fun fromKey(key: String?): GlassMode = entries.firstOrNull { it.key == key } ?: Auto
    }
}

/**
 * 性能门的设备级降档缓存（UI-06）：首 60 帧采样超预算时，把该设备持久化到
 * [L0]，之后每次启动都直接从 L0 开始，不再反复踩模糊。由 Activity 读写
 * ThemeStore 并经 CompositionLocal 下发；[Unlimited] 表示未降档。
 */
enum class GlassDeviceCap(val key: String) {
    Unlimited("unlimited"),
    L0("l0"),
    ;

    companion object {
        /** Maps ThemeStore's normalized `ui_glass_device_cap` value; unknown falls back to [Unlimited]. */
        fun fromKey(key: String?): GlassDeviceCap =
            entries.firstOrNull { it.key == key } ?: Unlimited
    }
}

/**
 * Branching only; the blur/tint numbers stay in `Oe3Glass.resolve` so there
 * is exactly one owner of the spec values.
 */
object Oe3GlassStrategy {

    /** First API level with `RenderEffect`, i.e. with real backdrop blur. */
    const val MIN_REAL_BLUR_API = 31

    /** First API level with AGSL `RuntimeShader` for L2 edge refraction. */
    const val MIN_REFRACTION_API = 33

    /**
     * [hasRenderEffect] defaults to the API check; callers that probe the
     * graphics stack directly can override it.
     */
    fun supportsRealBlur(apiLevel: Int, hasRenderEffect: Boolean = apiLevel >= MIN_REAL_BLUR_API): Boolean =
        hasRenderEffect

    fun resolve(
        level: Oe3GlassLevel,
        enabled: Boolean,
        supportsRealBlur: Boolean,
        tokens: Oe3GlassTokens,
    ): Oe3GlassRender = when {
        // Explicit "no glass" stays fully opaque in every environment: it is
        // the behavior-preserving default the shell goldens pin.
        level == Oe3GlassLevel.None -> Oe3GlassRender.Solid
        // Requested glass that cannot render: scrimmed fallback, never a raw
        // translucent tint over an unblurred backdrop.
        !enabled || !supportsRealBlur -> Oe3GlassRender.Fallback
        else -> Oe3GlassRender.Blurred(
            blurRadius = tokens.blurRadius,
            tintArgb = tokens.tint,
            tintAlpha = tokens.tintAlpha,
            backgroundArgb = tokens.tint,
            fallbackTintArgb = tokens.fallbackTint,
        )
    }

    /**
     * UI-06：把「用户模式 + 显式旧档位」解析为请求的玻璃风格档位。能力判定
     * （API/省电/低内存/性能门）不在这里——那是 [resolveTier] 的事，两层各管
     * 一段，风格层永远不知道设备快慢。
     *
     * [chosenLevelKey] 是旧键 `ui_glass_level` 的原始存储值；`null` 表示用户
     * 从未写过该键（区别于显式写过 `"none"`——后者是明确的关闭意志，必须保留）。
     */
    fun resolveLevel(mode: GlassMode, chosenLevelKey: String?): Oe3GlassLevel = when (mode) {
        GlassMode.Off -> Oe3GlassLevel.None
        GlassMode.Always -> when (chosenLevelKey) {
            "standard" -> Oe3GlassLevel.Standard
            "ultra" -> Oe3GlassLevel.Ultra
            // 始终 = 用户要最强玻璃；与旧档位 "none" 的矛盾以新模式为准。
            else -> Oe3GlassLevel.Ultra
        }
        GlassMode.Auto -> when (chosenLevelKey) {
            // 从未选择 → 自动玻璃默认档（Standard）：31+ 渲染 L1，<31 渲染 L0。
            null -> Oe3GlassLevel.Standard
            // 用户显式关闭 → 尊重（关闭能力不经自动模式抹掉）。
            "none" -> Oe3GlassLevel.None
            "standard" -> Oe3GlassLevel.Standard
            "ultra" -> Oe3GlassLevel.Ultra
            else -> Oe3GlassLevel.Standard
        }
    }

    /**
     * UI-06 能力判定：按「全局开关 → 省电/减少动态 → 低内存 → API → 设备档位
     * 缓存」的顺序把请求档位落到渲染阶梯上（31–32 → L1，33+ → L2）。
     *
     * [level] 是请求的玻璃风格（[resolveLevel] 的输出）；[enabled] 是运行期
     * 闸（非省电 && 有 haze 源）；[deviceCap] 是性能门持久化结果；
     * [reducedMotion] 为 true 时退回静态 [L0] 雾面以消除动态背景重采。
     */
    fun resolveTier(
        level: Oe3GlassLevel,
        enabled: Boolean,
        apiLevel: Int,
        isLowRamDevice: Boolean,
        deviceCap: GlassDeviceCap,
        reducedMotion: Boolean = false,
    ): GlassTier = when {
        // 全局开关：显式关闭与基线像素一致，不进任何玻璃配方。
        level == Oe3GlassLevel.None -> GlassTier.Solid
        // 省电/减少动态/缺 haze 源：降为假玻璃配方，不开模糊。
        !enabled || reducedMotion -> GlassTier.L0
        // 低内存设备：模糊背后的离屏采样不可负担，L0 配方保观感。
        isLowRamDevice -> GlassTier.L0
        // API 能力：31 以下没有 RenderEffect。
        apiLevel < MIN_REAL_BLUR_API -> GlassTier.L0
        // 设备档位缓存：性能门在该设备上判过不合格，直接从 L0 开始。
        deviceCap == GlassDeviceCap.L0 -> GlassTier.L0
        // API 33+：模糊 + 边缘 AGSL 折射（L2）。
        apiLevel >= MIN_REFRACTION_API -> GlassTier.L2
        // API 31–32：RenderEffect 半分辨率模糊（L1）。
        else -> GlassTier.L1
    }
}
