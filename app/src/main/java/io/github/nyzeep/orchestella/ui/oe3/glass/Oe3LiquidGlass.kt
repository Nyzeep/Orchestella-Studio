package io.github.nyzeep.orchestella.ui.oe3.glass

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass

/**
 * Applies AGSL edge-bezel refraction onto the blurred glass backing layer on API 33+,
 * keeping foreground labels outside the shader pass so text stays crisp.
 *
 * W2-05：着色器实例经 [Oe3RuntimeShaderPool] 按会话缓存——组合/主题/尺寸变更
 * 重建 [Oe3LiquidGlass] 时不再重编译 AGSL，只取缓存实例。脏检查**模式**与
 * Breezy-Weather 的 `DayNightShaderWrapper`（LGPL-3.0，零代码复制，仅模式借鉴）
 * 同构：尺寸/主题/高亮参数变更时重建或复用，未变更走内存化的旧产物；本仓的
 * remember 键 `(shape, direction, isDark)` 就是脏检查输入，池化使「重建」的
 * 代价从重新编译降为取缓存实例。
 */
@Composable
internal fun rememberOe3Refraction(enabled: Boolean, shape: Shape, isDark: Boolean): Modifier {
    if (!enabled ||
        Build.VERSION.SDK_INT < Oe3GlassStrategy.MIN_REFRACTION_API ||
        !LocalView.current.isHardwareAccelerated
    ) {
        return Modifier
    }
    val direction = LocalLayoutDirection.current
    val refraction = remember(shape, direction, isDark) {
        Oe3LiquidGlass.create(shape, direction, isDark)
    } ?: return Modifier
    return Modifier.graphicsLayer { renderEffect = refraction.effectFor(size, this) }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class Oe3LiquidGlass private constructor(
    private val shader: RuntimeShader,
    private val shape: Shape,
    private val direction: LayoutDirection,
    private val isDark: Boolean,
) {
    private var layerSize = Size.Zero
    private var layerDensity = 0f
    private var effect: RenderEffect? = null
    private var unavailable = false

    fun effectFor(size: Size, density: Density): RenderEffect? {
        if (unavailable || !size.width.isFinite() || !size.height.isFinite() ||
            size.width < 1f || size.height < 1f
        ) {
            return null
        }
        if (layerSize == size && layerDensity == density.density) return effect
        layerSize = size
        layerDensity = density.density

        val outline = shape.createOutline(size, direction, density)
        val radii = when (outline) {
            is Outline.Rectangle -> null
            is Outline.Rounded -> outline.roundRect
            is Outline.Generic -> return null.also { effect = null }
        }
        val highlight = if (isDark) Oe3Glass.DARK_RIM_HIGHLIGHT_ALPHA else Oe3Glass.LIGHT_RIM_HIGHLIGHT_ALPHA
        // 池化契约的借用方义务在这里履行：每次构造 RenderEffect 前把**全部**
        // uniform 覆写一遍（共享实例上一次的暗色/亮色取值不会残留）。
        val rollOff = if (isDark) DARK_LUMINANCE_ROLL_OFF else LIGHT_LUMINANCE_ROLL_OFF
        val maxLuma = if (isDark) DARK_MAX_LUMA else LIGHT_MAX_LUMA
        effect = shaderOrNull {
            shader.setFloatUniform("extent", size.width, size.height)
            shader.setFloatUniform(
                "radii",
                radii?.topLeftCornerRadius?.x ?: 0f,
                radii?.topRightCornerRadius?.x ?: 0f,
                radii?.bottomLeftCornerRadius?.x ?: 0f,
                radii?.bottomRightCornerRadius?.x ?: 0f,
            )
            with(density) {
                shader.setFloatUniform("bezel", Oe3Glass.refractionWidth.toPx())
                shader.setFloatUniform("shift", Oe3Glass.refractionDisplacement.toPx())
                shader.setFloatUniform("rim", Oe3Glass.rimWidth.toPx())
            }
            shader.setFloatUniform("highlight", highlight * Oe3Glass.REFRACTION_HIGHLIGHT_GAIN)
            shader.setFloatUniform("luminanceRollOff", rollOff)
            shader.setFloatUniform("maxLuma", maxLuma)
            shader.setFloatUniform("aberration", if (EDGE_DISPERSION) 1f else 0f)
            AndroidRenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
        }
        unavailable = effect == null
        return effect
    }

    companion object {
        fun create(shape: Shape, direction: LayoutDirection, isDark: Boolean): Oe3LiquidGlass? =
            shaderOrNull {
                // 池命中 → 复用同一编译实例；池内 AGSL 编译失败已吞掉并返回
                // null → 这里按「折射不可用」退出，宿主回落无折射的 L1 模糊。
                val shader = Oe3RuntimeShaderPool.obtain(KEY_LENS_PROGRAM, LENS_PROGRAM)
                    ?: return@shaderOrNull null
                Oe3LiquidGlass(shader, shape, direction, isDark)
            }
    }
}

private inline fun <T> shaderOrNull(block: () -> T): T? = try {
    block()
} catch (failure: RuntimeException) {
    Log.w("Oe3Glass", "Refraction unavailable, falling back to L1 blur", failure)
    null
} catch (failure: LinkageError) {
    Log.w("Oe3Glass", "Native shader unavailable, falling back to L1 blur", failure)
    null
}

/**
 * LENS_PROGRAM 光学升级（W2-05 净室增量 A；公式借鉴 Glasmio
 * `NativeGlassRefraction.kt` / QuartzCore AIR 语义，净室重写）：
 *
 * 1. **圆弧位移剖面**：`1 − sqrt(u(2−u))`（u = depth/bezel 的四分之一圆弧）替代
 *    `1 − smoothstep(0, bezel, depth)`。smoothstep 在边缘处斜率非零，折射位移
 *    在最外缘突然衰减；圆弧剖面在最外缘（u=1）与中心（u=0）两端的斜率都为 0，
 *    位移过渡更像真实圆透镜。bezel==0 的守卫由既有早退保证：`depth ≥ bezel`
 *    在 bezel=0 时对任意 depth≥0 恒真，剖面里的除法不可达，分母不可能为 0。
 * 2. **luminanceRollOff / maxLuma 过曝压制**（混合高光前对采样色处理）：
 *    亮区按亮度线性压 gain，且向全色偏置（mix 权重 >1 外插）防灰化；随后
 *    maxLuma 顶棚把残余过曝的亮度压回上限。**恒等口径（审查轴二 P2-1 收窄）**：
 *    「亮色像素不变」只对 rollOff/maxLuma 两个子特性成立（亮色取值 rollOff=0、
 *    maxLuma=1：gain 分支不进入，顶棚分支对 luma≤1 恒不触发）；第 1 条的圆弧
 *    位移剖面是双主题共享路径——亮/暗 L2 的 bezel 边缘带像素都会变。当前金样
 *    仅暗色 L2（已随真实光学更新），亮色 L2 尚无金样钉定，后续补钉时以新
 *    剖面为准。
 * 3. **7-tap 色散开关**（uniform `aberration`，默认 0f 关闭保持单次
 *    `backdrop.eval`）：开启时沿法线方向错位采样 7 次，R/G/B 各自按三角形
 *    核权重（中心分别在 +1/0/−1 偏移处）取色后归一——光谱端通道的空间偏移
 *    模拟边缘色散。Kotlin 侧 [EDGE_DISPERSION] 默认 false，待真机设备窗验证
 *    帧预算裕量后再放开。数值（rollOff/maxLuma）为起始标定，真机设备窗复核。
 */
private const val LENS_PROGRAM = """
    uniform shader backdrop;
    uniform float2 extent;
    uniform float4 radii;
    uniform float bezel;
    uniform float shift;
    uniform float rim;
    uniform float highlight;
    uniform float luminanceRollOff;
    uniform float maxLuma;
    uniform float aberration;

    half4 main(float2 pixel) {
        float2 halfSize = extent * 0.5;
        float2 p = pixel - halfSize;
        float radius = p.y < 0.0
            ? (p.x < 0.0 ? radii.x : radii.y)
            : (p.x < 0.0 ? radii.z : radii.w);
        radius = min(radius, min(halfSize.x, halfSize.y));
        float2 q = abs(p) - halfSize + radius;
        float2 roundedCorner = max(q, 0.0);
        float distanceToEdge = length(roundedCorner) + min(max(q.x, q.y), 0.0) - radius;
        float depth = max(-distanceToEdge, 0.0);
        // 守卫完备性（圆弧剖面下仍成立）：bezel==0 时 depth>=0>=bezel 对所有
        // 像素恒真，下面剖面里的 `depth / bezel` 不可达，分母不会为 0。
        if (depth >= bezel) {
            return backdrop.eval(pixel);
        }

        float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
        float2 normal = dot(roundedCorner, roundedCorner) > 0.0001
            ? normalize(roundedCorner) * s
            : (q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y));
        // 圆弧位移剖面：u∈[0,1]，1 − sqrt(u(2−u)) 在两端斜率为 0（对比
        // smoothstep 在外缘仍有非零斜率）。
        float u = clamp(depth / bezel, 0.0, 1.0);
        float lens = 1.0 - clamp(sqrt(u * (2.0 - u)), 0.0, 1.0);
        float2 samplePoint = clamp(pixel - normal * shift * lens, float2(0.5), extent - 0.5);
        half4 sampleColor = backdrop.eval(samplePoint);
        // 7-tap 色散（默认关闭）：R/G/B 三角核中心各偏 +1/0/−1，按权归一。
        if (aberration > 0.0) {
            float2 axis = normal * shift * lens * aberration;
            half3 acc = half3(0.0);
            float3 wsum = float3(0.0);
            for (int i = 0; i < 7; i++) {
                float t = (float(i) - 3.0) / 3.0;
                half4 tap = backdrop.eval(clamp(samplePoint + axis * t, float2(0.5), extent - 0.5));
                float3 w = max(float3(0.0), float3(1.0) - abs(float3(t) - float3(1.0, 0.0, -1.0)));
                acc += tap.rgb * half3(w);
                wsum += w;
            }
            sampleColor = half4(half3(float3(acc) / wsum), sampleColor.a);
        }
        // 面色压制：亮度压 gain → 向全色偏置（外插防灰化）→ maxLuma 顶棚。
        float3 rgb = float3(sampleColor.rgb);
        float luminance = dot(rgb, float3(0.2126, 0.7152, 0.0722));
        if (luminanceRollOff > 0.0) {
            float gain = clamp(1.0 - luminance * luminanceRollOff, 0.0, 1.0);
            rgb = mix(float3(luminance * gain), rgb * gain, 1.0 + 0.3 * (1.0 - gain));
        }
        float lumaAfter = dot(rgb, float3(0.2126, 0.7152, 0.0722));
        if (lumaAfter > maxLuma) rgb *= maxLuma / lumaAfter;
        half4 graded = half4(half3(rgb), sampleColor.a);
        float incidence = max(0.0, dot(normal, float2(-0.6, -0.8)));
        half gloss = half(highlight * incidence * (1.0 - smoothstep(0.0, rim, depth)));
        return half4(mix(graded.rgb, half3(sampleColor.a), gloss), sampleColor.a);
    }
"""

private const val KEY_LENS_PROGRAM = "oe3.liquid-glass.lens-program"

/** 暗色面色压制标定（真机设备窗复核起始值）：亮区按亮度×0.4 线性压。 */
private const val DARK_LUMINANCE_ROLL_OFF = 0.4f

/** 亮色路径面色压制关闭（rollOff=0 → gain 分支不进，亮色像素数值等价现状）。 */
private const val LIGHT_LUMINANCE_ROLL_OFF = 0f

/** 暗色亮度顶棚：gain 与外插后的残余过曝压回 0.96。 */
private const val DARK_MAX_LUMA = 0.96f

/** 亮色路径顶棚不削（luma≤1 恒不触发，金样安全）。 */
private const val LIGHT_MAX_LUMA = 1f

/**
 * 7-tap 边缘色散开关：默认全程关闭（aberration 恒 0f，保持单次 backdrop.eval），
 * 待真机设备窗验证帧预算裕量后再放开。
 */
private const val EDGE_DISPERSION = false
