package io.github.nyzeep.orchestella.ui.screens.hardware

import io.github.nyzeep.orchestella.BatteryFactProvider
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬件页状态映射契约（UI3-07）。
 *
 * 页面只消费 [HardwareUiState]；这些用例锁定摘要格式化、三环 0/50/100、
 * AVF 五状态文案、探针失败态与字段来源（稳定别名优先、无序列号类字段）。
 */
class HardwareUiStateTest {

    private val gb = HardwareTestFixtures.GIB

    @Test
    fun gigabytesUseBinaryUnitsAndOneDecimalOnlyWhenNeeded() {
        assertEquals("0 GB", HardwareFormat.gigabytes(0L))
        assertEquals("8 GB", HardwareFormat.gigabytes(8L * gb))
        assertEquals("128 GB", HardwareFormat.gigabytes(128L * gb))
        assertEquals("7.4 GB", HardwareFormat.gigabytes((7.4 * gb).toLong()))
        assertEquals("0 GB", HardwareFormat.gigabytes(-5L))
    }

    @Test
    fun percentIsClampedAndAlwaysCarriesTheSuffix() {
        assertEquals("0%", HardwareFormat.percent(0))
        assertEquals("50%", HardwareFormat.percent(50))
        assertEquals("100%", HardwareFormat.percent(100))
        assertEquals("0%", HardwareFormat.percent(-3))
        assertEquals("100%", HardwareFormat.percent(120))
    }

    @Test
    fun temperatureNeedsAFiniteReadingAndKeepsOneDecimal() {
        assertEquals("36.5 °C", HardwareFormat.temperatureCelsius(36.5f))
        assertEquals("0.0 °C", HardwareFormat.temperatureCelsius(0f))
        assertNull(HardwareFormat.temperatureCelsius(null))
        assertNull(HardwareFormat.temperatureCelsius(Float.NaN))
        assertNull(HardwareFormat.temperatureCelsius(Float.POSITIVE_INFINITY))
    }

    @Test
    fun memoryRingCoversZeroHalfAndFull() {
        val zero = HardwareTestFixtures.ready(hardware = HardwareTestFixtures.profile(8L * gb, 8L * gb))
        val half = HardwareTestFixtures.ready(hardware = HardwareTestFixtures.profile(8L * gb, 4L * gb))
        val full = HardwareTestFixtures.ready(hardware = HardwareTestFixtures.profile(8L * gb, 0L))

        assertEquals(0f, zero.rings.memory.fraction, 0.0001f)
        assertEquals(0, zero.rings.memory.percent)
        assertEquals(0.5f, half.rings.memory.fraction, 0.0001f)
        assertEquals(50, half.rings.memory.percent)
        assertEquals(1f, full.rings.memory.fraction, 0.0001f)
        assertEquals(100, full.rings.memory.percent)
    }

    @Test
    fun storageRingReusesSharedStorageFactsForZeroHalfAndFull() {
        val zero = HardwareTestFixtures.ready(
            storageFacts = StorageFacts(totalBytes = 100L * gb, freeBytes = 100L * gb),
        )
        val half = HardwareTestFixtures.ready(
            storageFacts = StorageFacts(totalBytes = 100L * gb, freeBytes = 50L * gb),
        )
        val full = HardwareTestFixtures.ready(
            storageFacts = StorageFacts(totalBytes = 100L * gb, freeBytes = 0L),
        )

        assertEquals(0f, zero.rings.storage.fraction, 0.0001f)
        assertEquals(0, zero.rings.storage.percent)
        assertEquals(0.5f, half.rings.storage.fraction, 0.0001f)
        assertEquals(50, half.rings.storage.percent)
        assertEquals(1f, full.rings.storage.fraction, 0.0001f)
        assertEquals(100, full.rings.storage.percent)
        // 共享类型自身仍是唯一口径：越界 free 由 StorageFacts 夹取。
        val clamped = StorageFacts(totalBytes = 100L * gb, freeBytes = 150L * gb)
        assertEquals(0, clamped.usedPercent)
    }

    @Test
    fun batteryRingCoversZeroHalfAndFullAndSurvivesMissingReadings() {
        val zero = HardwareTestFixtures.ready(batteryFacts = HardwareBatteryFacts(0, 0f))
        val half = HardwareTestFixtures.ready(batteryFacts = HardwareBatteryFacts(50, 32f))
        val full = HardwareTestFixtures.ready(batteryFacts = HardwareBatteryFacts(100, 41f))
        val missing = HardwareTestFixtures.ready(batteryFacts = HardwareBatteryFacts(null, null))

        assertEquals(0f, zero.rings.battery.fraction, 0.0001f)
        assertEquals(0, zero.rings.battery.percent)
        assertEquals(0.5f, half.rings.battery.fraction, 0.0001f)
        assertEquals(50, half.rings.battery.percent)
        assertEquals(1f, full.rings.battery.fraction, 0.0001f)
        assertEquals(100, full.rings.battery.percent)
        assertEquals(0f, missing.rings.battery.fraction, 0.0001f)
        assertNull(missing.rings.battery.percent)
        assertNull(missing.rings.battery.detail)
        assertNull(missing.summary.batteryPercent)
        assertNull(missing.summary.temperature)
    }

    /** UI-08：采集口径折出的事实直接点亮环弧与温度，不再是 0f / `—`。 */
    @Test
    fun collectedBatteryFactsDriveTheRingInsteadOfPlaceholders() {
        val facts = BatteryFactProvider.factsFrom(
            level = 76,
            scale = 100,
            temperatureTenths = 315,
            capacityPercent = 76,
        )
        val ready = HardwareTestFixtures.ready(batteryFacts = facts)

        assertEquals(0.76f, ready.rings.battery.fraction, 0.0001f)
        assertEquals(76, ready.rings.battery.percent)
        assertEquals("31.5 °C", ready.rings.battery.detail)
        assertEquals(76, ready.summary.batteryPercent)
        assertEquals("31.5 °C", ready.summary.temperature)
    }

    /** UI-08：插拔充电器改变的是广播里的 status，环模型必须随之带上产品状态。 */
    @Test
    fun chargingStateReachesTheBatteryRing() {
        val charging = BatteryFactProvider.factsFrom(
            level = 100,
            scale = 100,
            temperatureTenths = 300,
            capacityPercent = 100,
            status = android.os.BatteryManager.BATTERY_STATUS_CHARGING,
        )
        val ready = HardwareTestFixtures.ready(batteryFacts = charging)

        assertEquals(HardwareBatteryCharging.Charging, ready.rings.battery.charging)
        assertEquals("30.0 °C", ready.rings.battery.detail)
    }

    @Test
    fun avfMapsSevenEvaluatorStatusesOntoFiveProductStates() {
        assertEquals(5, HardwareAvfState.entries.size)
        assertEquals(5, HardwareAvfState.entries.map { it.labelRes }.toSet().size)
        assertEquals(5, HardwareAvfState.entries.map { it.fallbackReasonRes }.toSet().size)

        val mapped = AvfCapabilityEvaluator.AvfStatus.values().map { HardwareAvfState.of(it) }
        assertEquals(7, mapped.size)
        assertEquals(5, mapped.toSet().size)

        assertEquals(HardwareAvfState.Ready, HardwareAvfState.of(AvfCapabilityEvaluator.AvfStatus.READY))
        assertEquals(
            HardwareAvfState.NeedsAuthorization,
            HardwareAvfState.of(AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION),
        )
        assertEquals(
            HardwareAvfState.InterfaceBlocked,
            HardwareAvfState.of(AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED),
        )
        assertEquals(
            HardwareAvfState.InterfaceBlocked,
            HardwareAvfState.of(AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE),
        )
        assertEquals(
            HardwareAvfState.Unsupported,
            HardwareAvfState.of(AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED),
        )
        assertEquals(
            HardwareAvfState.Unsupported,
            HardwareAvfState.of(AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED),
        )
        assertEquals(
            HardwareAvfState.Unknown,
            HardwareAvfState.of(AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN),
        )
    }

    @Test
    fun onlyNeedsAuthorizationOffersTheAuthorizationEntry() {
        val offering = HardwareAvfState.entries.filter { it.offersAuthorization }
        assertEquals(listOf(HardwareAvfState.NeedsAuthorization), offering)
    }

    @Test
    fun probeFailureCarriesNoHardwareFacts() {
        val failed = HardwareUiState.Failed(R.string.ui3_hardware_probe_failed_body)
        assertEquals(R.string.ui3_hardware_probe_failed_body, failed.reasonRes)
        assertFalse(failed is HardwareUiState.Ready)

        val fieldNames = failed.javaClass.declaredFields.map { it.name }.toSet()
        assertFalse(fieldNames.contains("summary"))
        assertFalse(fieldNames.contains("rings"))
        assertFalse(fieldNames.contains("engines"))
        assertFalse(fieldNames.contains("details"))
    }

    @Test
    fun deviceNamePrefersTheStableAliasOverTheModel() {
        assertEquals("工作机", HardwareTestFixtures.ready().summary.deviceName)
        assertEquals(
            "Pixel 8",
            HardwareTestFixtures.ready(
                deviceFacts = HardwareTestFixtures.device.copy(alias = null),
            ).summary.deviceName,
        )
        assertEquals(
            "Pixel 8",
            HardwareTestFixtures.ready(
                deviceFacts = HardwareTestFixtures.device.copy(alias = "   "),
            ).summary.deviceName,
        )
        assertNull(
            HardwareTestFixtures.ready(
                deviceFacts = HardwareTestFixtures.device.copy(alias = null, model = " "),
            ).summary.deviceName,
        )
    }

    @Test
    fun summaryReadsVersionArchitectureCoresRamStorageBatteryAndTemperature() {
        val summary = HardwareTestFixtures.ready().summary
        assertEquals("15", summary.androidRelease)
        assertEquals(35, summary.apiLevel)
        assertEquals("arm64-v8a", summary.architecture)
        assertEquals(8, summary.cores)
        assertEquals("8 GB", summary.ramTotal)
        assertEquals("62 GB", summary.storageFree)
        assertEquals(76, summary.batteryPercent)
        assertEquals("36.5 °C", summary.temperature)
    }

    @Test
    fun engineCardKeepsQemuAndUmlStateAsText() {
        val idle = HardwareTestFixtures.ready(
            engineFacts = HardwareTestFixtures.engines(umlRunningWidth = 0, qemuAvailable = false),
        ).engines
        assertFalse(idle.qemuAvailable)
        assertEquals(R.string.ui3_hardware_qemu_unavailable, idle.qemuStatusRes)
        assertFalse(idle.uml.running)
        assertEquals(R.string.ui3_hardware_uml_idle, idle.uml.statusRes)
        assertNull(idle.uml.channelRes)

        val wired = HardwareTestFixtures.ready(
            engineFacts = HardwareTestFixtures.engines(umlRunningWidth = 2, umlChannelWired = true),
        ).engines
        assertTrue(wired.qemuAvailable)
        assertEquals(R.string.ui3_hardware_qemu_available, wired.qemuStatusRes)
        assertTrue(wired.uml.running)
        assertEquals(R.string.ui3_hardware_uml_running, wired.uml.statusRes)
        assertEquals(R.string.ui3_hardware_uml_wired, wired.uml.channelRes)

        val unwired = HardwareTestFixtures.ready(
            engineFacts = HardwareTestFixtures.engines(umlRunningWidth = 1, umlChannelWired = false),
        ).engines
        assertEquals(R.string.ui3_hardware_uml_unwired, unwired.uml.channelRes)
    }

    @Test
    fun avfKeepsTheEvaluatorReasonWhenItHasOne() {
        val ready = HardwareTestFixtures.ready(
            engineFacts = HardwareTestFixtures.engines(
                status = AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                reasons = listOf("android.permission.MANAGE_VIRTUAL_MACHINE：未授权"),
            ),
        )
        assertEquals(HardwareAvfState.NeedsAuthorization, ready.engines.avf.state)
        assertEquals(
            "android.permission.MANAGE_VIRTUAL_MACHINE：未授权",
            ready.engines.avf.reason,
        )
    }

    @Test
    fun fullParametersCarryEveryTicketField() {
        val labels = HardwareTestFixtures.ready().details
            .flatMap { it.rows }
            .map { it.labelRes }
            .toSet()

        listOf(
            R.string.ui3_hardware_detail_soc,
            R.string.ui3_hardware_detail_abi,
            R.string.ui3_hardware_detail_kernel,
            R.string.ui3_hardware_detail_screen,
            R.string.ui3_hardware_detail_gpu,
            R.string.ui3_hardware_detail_selinux,
            R.string.ui3_hardware_detail_avf_mode,
        ).forEach { required ->
            assertTrue("full parameters must include label $required", labels.contains(required))
        }
        // 同一标签不得重复（分组之间也不重复）。
        val allRows = HardwareTestFixtures.ready().details.flatMap { it.rows }
        assertEquals(allRows.size, allRows.map { it.labelRes }.toSet().size)
    }

    @Test
    fun detailsOmitUnknownFactsInsteadOfInventingThem() {
        val bare = HardwareTestFixtures.ready(
            deviceFacts = HardwareTestFixtures.device.copy(
                socModel = null,
                kernelVersion = null,
                screenSpec = null,
                gpuRenderer = null,
                selinuxStatus = null,
            ),
            engineFacts = HardwareTestFixtures.engines(reasons = emptyList()).copy(avfModeSummary = null),
        )
        val byLabel = bare.details.flatMap { it.rows }.associateBy { it.labelRes }

        assertNull(byLabel.getValue(R.string.ui3_hardware_detail_soc).value)
        assertNull(byLabel.getValue(R.string.ui3_hardware_detail_kernel).value)
        assertNull(byLabel.getValue(R.string.ui3_hardware_detail_screen).value)
        assertNull(byLabel.getValue(R.string.ui3_hardware_detail_gpu).value)
        assertNull(byLabel.getValue(R.string.ui3_hardware_detail_selinux).value)
        assertNull(byLabel.getValue(R.string.ui3_hardware_detail_avf_mode).value)
        // 已知读数仍然存在。
        assertNotNull(byLabel.getValue(R.string.ui3_hardware_detail_abi).value)
    }

    @Test
    fun missingStorageAndRamReadingsStayUnavailableInsteadOfZero() {
        val bare = HardwareTestFixtures.ready(
            hardware = HardwareTestFixtures.profile(totalBytes = 0L, availBytes = 0L),
            storageFacts = StorageFacts.None,
        )

        assertEquals(0f, bare.rings.memory.fraction, 0.0001f)
        assertNull(bare.rings.memory.percent)
        assertNull(bare.rings.memory.detail)
        assertNull(bare.summary.ramTotal)
        assertNull(bare.rings.storage.percent)
        assertNull(bare.rings.storage.detail)
        assertNull(bare.summary.storageFree)

        val rows = bare.details.flatMap { it.rows }.associateBy { it.labelRes }
        assertNull(rows.getValue(R.string.ui3_hardware_detail_ram_total).value)
        assertNull(rows.getValue(R.string.ui3_hardware_detail_ram_available).value)
        assertNull(rows.getValue(R.string.ui3_hardware_detail_storage_total).value)
        assertNull(rows.getValue(R.string.ui3_hardware_detail_storage_free).value)
    }

    @Test
    fun androidVersionValueStaysInStringResources() {
        val row = HardwareTestFixtures.ready().details
            .flatMap { it.rows }
            .associateBy { it.labelRes }
            .getValue(R.string.ui3_hardware_detail_android)

        val value = row.value as HardwareDetailValue.Template
        assertEquals(R.string.ui3_hardware_detail_android_value, value.res)
        assertEquals(listOf("15", "35"), value.args)
    }

    @Test
    fun androidVersionRowIsOmittedWhenUnreadable() {
        val row = HardwareTestFixtures.ready(
            deviceFacts = HardwareTestFixtures.device.copy(androidRelease = " ", sdkInt = 0),
        ).details
            .flatMap { it.rows }
            .associateBy { it.labelRes }
            .getValue(R.string.ui3_hardware_detail_android)

        assertNull(row.value)
    }

    @Test
    fun hardwareModelNeverAcceptsUniqueDeviceIdentifiers() {
        val fields = HardwareDeviceFacts::class.java.declaredFields.map { it.name.lowercase() }.toSet()
        listOf("serial", "imei", "androidid", "android_id", "mac", "fingerprint", "brand", "board", "device")
            .forEach { forbidden ->
                assertFalse("HardwareDeviceFacts must not carry $forbidden", fields.contains(forbidden))
            }
    }
}
