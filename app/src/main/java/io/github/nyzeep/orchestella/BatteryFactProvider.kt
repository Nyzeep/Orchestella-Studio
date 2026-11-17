package io.github.nyzeep.orchestella

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareBatteryFacts
import kotlin.math.roundToInt

/**
 * 电池读数采集（UI-08）。
 *
 * 硬件页的圆环与温度只消费 [HardwareBatteryFacts]，没有任何自己的探测；
 * 本对象是这些事实的唯一来源。两个入参口径：
 *
 * - **电量**：先取 [BatteryManager.BATTERY_PROPERTY_CAPACITY]（系统电量服务给出的
 *   整数百分比）；不可用（`null` 或负数，见 `BatteryManager` 的未知哨兵）时回退
 *   `ACTION_BATTERY_CHANGED` 的 `EXTRA_LEVEL / EXTRA_SCALE`。
 * - **温度**：`ACTION_BATTERY_CHANGED` 的 `EXTRA_TEMPERATURE`，单位是 0.1 °C，这里 ÷10。
 *   缺失即 `null`（页面显示 `—`），绝不写 0。
 *
 * 解析口径集中在纯函数 [factsFrom] 里：不碰 Context、不碰 Intent，JVM 单测可覆盖。
 */
object BatteryFactProvider {

    /** 采集一次当前读数；调用方负责在主线程调用并缓存结果。 */
    fun read(context: Context): HardwareBatteryFacts =
        factsFromIntent(stickyBatteryIntent(context), capacityPercent(context))

    /**
     * 纯换算：把广播/属性读到的原始值折成 [HardwareBatteryFacts]。
     *
     * - `capacityPercent` 合法（0..100）时优先；
     * - 否则用 `level / scale`（`scale <= 0` 视为不可用，不抛异常）；
     * - `temperatureTenths` 为 0.1 °C 单位；`null`、[Int.MIN_VALUE] 与 0 都按缺失处理——
     *   0 是 AOSP `BatteryService` 在温度读不到时的缺省值，显示 `0.0 °C` 会把
     *   「没有读数」伪装成真实读数。负值是真实的低温读数，照常保留。
     */
    fun factsFrom(
        level: Int?,
        scale: Int?,
        temperatureTenths: Int?,
        capacityPercent: Int?,
    ): HardwareBatteryFacts = HardwareBatteryFacts(
        percent = percentFrom(level, scale, capacityPercent),
        temperatureCelsius = temperatureCelsius(temperatureTenths),
    )

    /** 从一次 `ACTION_BATTERY_CHANGED` 里取原始值；`intent` 为 null 时各字段按缺失处理。 */
    fun factsFromIntent(intent: Intent?, capacityPercent: Int?): HardwareBatteryFacts {
        if (intent == null) {
            return factsFrom(level = null, scale = null, temperatureTenths = null, capacityPercent = capacityPercent)
        }
        return factsFrom(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, UNKNOWN_INT).takeIf { it != UNKNOWN_INT },
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, UNKNOWN_INT).takeIf { it != UNKNOWN_INT },
            temperatureTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN_INT)
                .takeIf { it != UNKNOWN_INT },
            capacityPercent = capacityPercent,
        )
    }

    /**
     * [BatteryManager.BATTERY_PROPERTY_CAPACITY] 的读数；未知/不可用返回 null。
     *
     * 该属性按系统电量服务的预估返回 0..100；负数（含 `Integer.MIN_VALUE`）代表未知。
     */
    fun capacityPercent(context: Context): Int? = try {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (failure: RuntimeException) {
        // 厂商实现的电量服务可能直接抛 SecurityException / IllegalArgumentException。
        null
    }

    /** 一次性读取 `ACTION_BATTERY_CHANGED` 的胶水广播（`registerReceiver(null, …)` 语义）。 */
    fun stickyBatteryIntent(context: Context): Intent? = try {
        ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    } catch (failure: RuntimeException) {
        null
    }

    private const val UNKNOWN_INT = Int.MIN_VALUE

    private fun percentFrom(level: Int?, scale: Int?, capacityPercent: Int?): Int? {
        if (capacityPercent != null && capacityPercent in 0..100) {
            return capacityPercent
        }
        if (level == null || scale == null || scale <= 0 || level < 0) {
            return null
        }
        return (level.toDouble() * 100.0 / scale.toDouble()).roundToInt().coerceIn(0, 100)
    }

    private fun temperatureCelsius(temperatureTenths: Int?): Float? {
        val tenths = temperatureTenths ?: return null
        if (tenths == UNKNOWN_INT || tenths == 0) {
            return null
        }
        return tenths / 10f
    }
}
