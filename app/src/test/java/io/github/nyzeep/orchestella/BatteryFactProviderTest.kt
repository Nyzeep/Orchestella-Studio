package io.github.nyzeep.orchestella

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电池读数接线契约（UI-08）。
 *
 * 两条腿：
 * - **接线**：单测运行目录是 `app/`，直接读源码锁定 `MainActivity` 的
 *   `HardwareUiState.from(...)` 必须把采集结果作为 `battery =` 传入。不传时工厂默认
 *   `HardwareBatteryFacts()`，两字段皆 null → 环弧 `clamp(0f)` 不绘制、环内读数回落 `—`。
 * - **换算**：[BatteryFactProvider.factsFrom] 的纯函数口径（0.1 °C → °C、缺失温度不写 0、
 *   电量属性优先、`EXTRA_LEVEL/EXTRA_SCALE` 回退、异常 scale 不崩）。
 */
class BatteryFactProviderTest {

    private val mainActivity = File("src/main/java/io/github/nyzeep/orchestella/MainActivity.kt")

    @Test
    fun mainActivityPassesBatteryFactsIntoTheHardwareStateFactory() {
        assertTrue("missing MainActivity.kt: ${mainActivity.absolutePath}", mainActivity.isFile)
        val text = mainActivity.readText()

        val callStart = text.indexOf("HardwareUiState.from(")
        assertTrue("MainActivity must build the hardware state", callStart >= 0)
        val callEnd = text.indexOf("\n        )", callStart).takeIf { it > callStart }
            ?: (callStart + 800).coerceAtMost(text.length)
        val call = text.substring(callStart, callEnd)

        assertTrue(
            "HardwareUiState.from(...) must receive battery = <collected facts>, " +
                "otherwise the battery ring stays at 0f and readings stay null. Call:\n$call",
            Regex("""battery\s*=\s*\w""").containsMatchIn(call),
        )
    }

    @Test
    fun temperatureIsConvertedFromTenthsOfACelsius() {
        val facts = BatteryFactProvider.factsFrom(
            level = 76,
            scale = 100,
            temperatureTenths = 315,
            capacityPercent = null,
        )

        assertEquals(76, facts.percent)
        assertEquals(31.5f, facts.temperatureCelsius!!, 0.0001f)
    }

    @Test
    fun coldReadingsKeepTheirSign() {
        val facts = BatteryFactProvider.factsFrom(80, 100, -50, null)

        assertEquals(-5f, facts.temperatureCelsius!!, 0.0001f)
    }

    @Test
    fun missingTemperatureStaysNullInsteadOfZero() {
        assertNull(BatteryFactProvider.factsFrom(80, 100, null, null).temperatureCelsius)
        assertNull(BatteryFactProvider.factsFrom(80, 100, 0, null).temperatureCelsius)
        assertNull(BatteryFactProvider.factsFrom(80, 100, Int.MIN_VALUE, null).temperatureCelsius)
    }

    @Test
    fun levelOverScaleIsTheFallbackWhenCapacityIsUnavailable() {
        assertEquals(76, BatteryFactProvider.factsFrom(76, 100, null, null).percent)
        assertEquals(21, BatteryFactProvider.factsFrom(42, 200, null, null).percent)
        assertEquals(33, BatteryFactProvider.factsFrom(1, 3, null, null).percent)
    }

    @Test
    fun capacityPropertyWinsOverTheBroadcastLevel() {
        assertEquals(76, BatteryFactProvider.factsFrom(50, 100, null, 76).percent)
    }

    @Test
    fun unusableCapacityFallsBackToTheBroadcastLevel() {
        assertEquals(50, BatteryFactProvider.factsFrom(50, 100, null, -1).percent)
        assertEquals(50, BatteryFactProvider.factsFrom(50, 100, null, 120).percent)
    }

    @Test
    fun brokenScaleAndMissingLevelDegradeToNullInsteadOfThrowing() {
        assertNull(BatteryFactProvider.factsFrom(50, 0, null, null).percent)
        assertNull(BatteryFactProvider.factsFrom(50, -7, null, null).percent)
        assertNull(BatteryFactProvider.factsFrom(null, 100, null, null).percent)

        val empty = BatteryFactProvider.factsFrom(null, null, null, null)
        assertNull(empty.percent)
        assertNull(empty.temperatureCelsius)
    }

    @Test
    fun outOfScaleLevelIsClampedToAValidPercent() {
        assertEquals(100, BatteryFactProvider.factsFrom(120, 100, null, null).percent)
    }

    @Test
    fun absentBroadcastStillKeepsTheCapacityProperty() {
        assertEquals(76, BatteryFactProvider.factsFromIntent(null, 76).percent)
        assertNull(BatteryFactProvider.factsFromIntent(null, 76).temperatureCelsius)
    }
}
