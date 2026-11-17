package io.github.nyzeep.orchestella.ui.oe3.glass

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * UI-06 分层降级的纯函数契约（v4 spec §1.7）：
 *
 * - [Oe3GlassStrategy.resolveTier] 按「全局开关 → 省电 → 低内存 → API → 设备
 *   档位缓存」的顺序落档；本波上限 L1，L2 枚举位保留但不可达（UI-06b）。
 * - [Oe3GlassStrategy.resolveLevel] 把用户模式（off/auto/always）与旧档位键的
 *   显式意图解析为请求风格；「null = 从未选择」与「显式 none = 明确关闭」是
 *   两个不同的输入。
 */
class Oe3GlassTierTest {

    // --- resolveTier：判定顺序矩阵 ---

    @Test
    fun userSwitchOffIsSolidBeforeAnythingElse() {
        assertEquals(
            GlassTier.Solid,
            Oe3GlassStrategy.resolveTier(
                level = Oe3GlassLevel.None,
                enabled = true,
                apiLevel = 35,
                isLowRamDevice = false,
                deviceCap = GlassDeviceCap.Unlimited,
            ),
        )
        // 显式关闭优先于省电/低内存等一切后续闸门。
        assertEquals(
            GlassTier.Solid,
            Oe3GlassStrategy.resolveTier(
                level = Oe3GlassLevel.None,
                enabled = false,
                apiLevel = 26,
                isLowRamDevice = true,
                deviceCap = GlassDeviceCap.L0,
            ),
        )
    }

    @Test
    fun powerSaveOrMissingHazeSourceDropsToL0() {
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(
                level = Oe3GlassLevel.Standard,
                enabled = false,
                apiLevel = 35,
                isLowRamDevice = false,
                deviceCap = GlassDeviceCap.Unlimited,
            ),
        )
    }

    @Test
    fun lowRamDevicesNeverReachTheBlurTier() {
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(
                level = Oe3GlassLevel.Standard,
                enabled = true,
                apiLevel = 35,
                isLowRamDevice = true,
                deviceCap = GlassDeviceCap.Unlimited,
            ),
        )
    }

    @Test
    fun belowApi31IsTheL0FakeGlassRecipe() {
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(
                level = Oe3GlassLevel.Standard,
                enabled = true,
                apiLevel = 30,
                isLowRamDevice = false,
                deviceCap = GlassDeviceCap.Unlimited,
            ),
        )
    }

    @Test
    fun api31AndAboveWithACleanCapReachesL1() {
        listOf(31, 32, 33, 35).forEach { api ->
            assertEquals(
                "api $api",
                GlassTier.L1,
                Oe3GlassStrategy.resolveTier(
                    level = Oe3GlassLevel.Standard,
                    enabled = true,
                    apiLevel = api,
                    isLowRamDevice = false,
                    deviceCap = GlassDeviceCap.Unlimited,
                ),
            )
        }
    }

    @Test
    fun theDeviceCapDowngradesTheBlurTierToL0() {
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(
                level = Oe3GlassLevel.Standard,
                enabled = true,
                apiLevel = 35,
                isLowRamDevice = false,
                deviceCap = GlassDeviceCap.L0,
            ),
        )
    }

    @Test
    fun l2IsReservedButUnreachableThisWave() {
        // UI-06b 接管前，任何生产输入都不得落到 L2（真机补测 research/04：
        // L1 的 P90 已达预算×0.7 边界，AGSL 折射无余量）。
        for (api in 31..36) {
            for (cap in GlassDeviceCap.entries) {
                for (level in Oe3GlassLevel.entries) {
                    val tier = Oe3GlassStrategy.resolveTier(
                        level = level,
                        enabled = level != Oe3GlassLevel.None,
                        apiLevel = api,
                        isLowRamDevice = false,
                        deviceCap = cap,
                    )
                    org.junit.Assert.assertNotEquals(GlassTier.L2, tier)
                }
            }
        }
    }

    // --- resolveLevel：用户模式 × 显式旧档位 ---

    @Test
    fun offModeWinsOverEverything() {
        for (chosen in arrayOf(null, "none", "standard", "ultra")) {
            assertEquals(
                Oe3GlassLevel.None,
                Oe3GlassStrategy.resolveLevel(GlassMode.Off, chosen),
            )
        }
    }

    @Test
    fun autoModeHonoursTheExplicitLegacyChoice() {
        // 从未写过旧键（null）→ 自动玻璃默认档。
        assertEquals(Oe3GlassLevel.Standard, Oe3GlassStrategy.resolveLevel(GlassMode.Auto, null))
        // 用户显式关闭 → 自动模式也必须保留关闭能力。
        assertEquals(Oe3GlassLevel.None, Oe3GlassStrategy.resolveLevel(GlassMode.Auto, "none"))
        assertEquals(Oe3GlassLevel.Standard, Oe3GlassStrategy.resolveLevel(GlassMode.Auto, "standard"))
        assertEquals(Oe3GlassLevel.Ultra, Oe3GlassStrategy.resolveLevel(GlassMode.Auto, "ultra"))
    }

    @Test
    fun alwaysModeForcesTheStrongestGlass() {
        assertEquals(Oe3GlassLevel.Ultra, Oe3GlassStrategy.resolveLevel(GlassMode.Always, null))
        // 显式选择过的风格在「始终」下保留；与 "none" 的矛盾以新模式为准。
        assertEquals(Oe3GlassLevel.Standard, Oe3GlassStrategy.resolveLevel(GlassMode.Always, "standard"))
        assertEquals(Oe3GlassLevel.Ultra, Oe3GlassStrategy.resolveLevel(GlassMode.Always, "ultra"))
        assertEquals(Oe3GlassLevel.Ultra, Oe3GlassStrategy.resolveLevel(GlassMode.Always, "none"))
    }

    // --- key 归一化 ---

    @Test
    fun modeKeysRoundTripAndUnknownFallsBackToAuto() {
        assertEquals(GlassMode.Auto, GlassMode.fromKey("auto"))
        assertEquals(GlassMode.Off, GlassMode.fromKey("off"))
        assertEquals(GlassMode.Always, GlassMode.fromKey("always"))
        assertEquals(GlassMode.Auto, GlassMode.fromKey(null))
        assertEquals(GlassMode.Auto, GlassMode.fromKey("bogus"))
    }

    @Test
    fun deviceCapKeysRoundTripAndUnknownFallsBackToUnlimited() {
        assertEquals(GlassDeviceCap.Unlimited, GlassDeviceCap.fromKey("unlimited"))
        assertEquals(GlassDeviceCap.L0, GlassDeviceCap.fromKey("l0"))
        assertEquals(GlassDeviceCap.Unlimited, GlassDeviceCap.fromKey(null))
        assertEquals(GlassDeviceCap.Unlimited, GlassDeviceCap.fromKey("bogus"))
    }

    @Test
    fun tierToRenderMappingStaysAlignedWithTheResolveContract() {
        // 阶梯与渲染路径的对齐：L0 == 回退配方（Fallback）、L1 == 模糊（Blurred）。
        // Oe3GlassSurface 的 when 分支依赖这组对应关系，两边漂移即出「半配置玻璃」。
        val theme = io.github.nyzeep.orchestella.ui.theme.ThemeCatalog.constructivistWorkstation
        val tokens = io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass.resolve(theme, Oe3GlassLevel.Standard)

        assertEquals(
            Oe3GlassRender.Fallback,
            Oe3GlassStrategy.resolve(level = Oe3GlassLevel.Standard, enabled = false, supportsRealBlur = true, tokens = tokens),
        )
        assertEquals(
            Oe3GlassRender.Blurred::class,
            Oe3GlassStrategy.resolve(level = Oe3GlassLevel.Standard, enabled = true, supportsRealBlur = true, tokens = tokens)::class,
        )
    }

    @Test
    fun glassStyleCarriesTheL0DecorationConstants() {
        val theme = io.github.nyzeep.orchestella.ui.theme.ThemeCatalog.constructivistWorkstation
        val tokens = io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass.resolve(theme, Oe3GlassLevel.Standard)
        val style = GlassStyle.of(tokens)
        // UI-06 L0 配方：surface@0.96 + 24dp scrim + 1dp 顶高光 + noise 0.02。
        assertEquals(0.96f, style.surfaceAlpha, 0.0001f)
        assertEquals(Oe3GlassFallback.SCRIM_HEIGHT, style.scrimHeight)
        assertEquals(Oe3GlassFallback.RIM_HEIGHT, style.rimHeight)
        assertEquals(0.02f, style.noiseAlpha, 0.0001f)
        // tint/fallback 直接承接 tokens（唯一数字所有者），不引入第二份权威。
        assertEquals(tokens.tint, style.tintArgb)
        assertEquals(tokens.fallbackTint, style.fallbackTintArgb)
    }
}
