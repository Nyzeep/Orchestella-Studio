package io.github.nyzeep.orchestella.ui.screens.dashboard

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.InstanceCardUiModel
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.startup.DashboardSnapshot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工作站页状态映射契约（UI3-05）。
 *
 * 页面只消费 [DashboardUiState]；这些用例锁定四态、空态、存储百分比、
 * 运行中计数与「新页面不得复活旧壳元素」的结构性约束。
 */
class DashboardUiStateTest {

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }

    /** Fixed base plus an explicit tick, so `updatedAt` ordering is deterministic. */
    private class TickingClock(private val base: Instant) : Clock() {
        var tick: Long = 0

        override fun instant(): Instant = base.plusMillis(tick * 60_000L)
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private val clock = TickingClock(Instant.parse("2026-11-16T08:00:00Z"))
    private lateinit var tempDir: Path
    private lateinit var store: InstanceLifecycle.FileStore
    private lateinit var controller: InstanceLifecycle.Controller

    private val profile = HostHardwareProfile(
        8,
        8L * GB,
        6L * GB,
        2L * GB,
        false,
    )

    @Before
    @Throws(IOException::class)
    fun setUp() {
        tempDir = Files.createTempDirectory("dashboard-ui-state-test-")
        store = InstanceLifecycle.FileStore(tempDir)
        controller = InstanceLifecycle.Controller(store, clock)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder<Path>())
                .forEach { path -> Files.deleteIfExists(path) }
        }
    }

    private fun create(distro: String, cores: Int = 2): InstanceLifecycle.Instance {
        clock.tick += 1
        val disk = tempDir.resolve("$distro-$cores-${clock.tick}.raw")
        Files.createFile(disk)
        val hardware = VmHardwareConfig.builder()
            .cpuCores(cores)
            .ramMegabytes(2048)
            .diskCapacityBytes(8L * GB)
            .gpuAccelerationEnabled(false)
            .build()
        return controller.create(InstanceLifecycle.DistroReference(distro, "24.04"), disk, hardware)
    }

    private fun stopped(distro: String, cores: Int = 2): InstanceLifecycle.Instance {
        val created = create(distro, cores)
        controller.beginProvisioning(created.id())
        return controller.completeProvisioning(created.id())
    }

    private fun running(distro: String, cores: Int = 2): InstanceLifecycle.Instance {
        val stopped = stopped(distro, cores)
        return controller.start(stopped.id())
    }

    private fun snapshot(
        instances: List<InstanceLifecycle.Instance>,
        profile: HostHardwareProfile = this.profile,
        total: Long = 128L * GB,
        free: Long = 64L * GB,
    ): DashboardSnapshot = DashboardSnapshot(
        instances = instances,
        stateKey = DashboardSnapshot.stateKeyOf(instances),
        listFailed = false,
        primary = DashboardSnapshot.pickPrimary(instances, null),
        ssh = null,
        acceleratorLaneReady = false,
        hardwareProfile = profile,
        storageTotalBytes = total,
        storageFreeBytes = free,
    )

    private fun stateOf(
        snapshot: DashboardSnapshot,
        card: InstanceCardUiModel? = null,
        total: Long = snapshot.storageTotalBytes,
        free: Long = snapshot.storageFreeBytes,
    ): DashboardUiState = DashboardUiState.from(
        snapshot = snapshot,
        card = card,
        storage = StorageFacts(totalBytes = total, freeBytes = free),
    )

    @Test
    fun emptySnapshotKeepsStorageAndShowsPlaceholders() {
        val state = stateOf(
            snapshot(instances = emptyList(), total = 128L * GB, free = 96L * GB),
        )

        assertFalse(state.hasInstances)
        assertNull(state.primary)
        assertEquals(0, state.instanceCount)
        assertEquals(0, state.runningCount)

        // 存储环是空态下仍然真实的主视觉：128 GB 中已用 32 GB。
        assertEquals(25, state.storage.percent)
        assertEquals(32L * GB, state.storage.usedBytes)
        assertEquals(0.25f, state.storage.fraction, 0.0001f)

        // 指标退化为占位，不残留上一次实例的数据。
        assertFalse(state.cpu.enabled)
        assertEquals(0f, state.cpu.fraction, 0.0001f)
        assertEquals(0, state.cpu.instanceCores)
        assertFalse(state.ram.enabled)
        assertEquals(0f, state.ram.fraction, 0.0001f)
        assertTrue(state.recent.isEmpty())
    }

    @Test
    fun runningInstanceIsPrimaryWithReadableStatusAndTerminal() {
        val running = running("ubuntu", cores = 4)
        val other = stopped("debian")
        val state = stateOf(snapshot(listOf(running, other)))

        assertEquals(2, state.instanceCount)
        assertEquals(1, state.runningCount)
        assertEquals(running.id(), state.primary!!.id)
        assertEquals(DashboardStatus.Running, state.primary!!.status)
        assertEquals(DashboardStatusTone.Active, state.primary!!.status.tone)
        assertTrue(state.primary!!.canOpenTerminal)
        assertEquals("Ubuntu 24.04", state.primary!!.name)
        assertEquals(4, state.primary!!.cpuCores)
    }

    @Test
    fun stoppedInstanceKeepsTerminalClosed() {
        val stopped = stopped("debian")
        val state = stateOf(snapshot(listOf(stopped)))

        assertEquals(1, state.instanceCount)
        assertEquals(0, state.runningCount)
        assertEquals(DashboardStatus.Stopped, state.primary!!.status)
        assertEquals(DashboardStatusTone.Idle, state.primary!!.status.tone)
        assertFalse(state.primary!!.canOpenTerminal)
        assertTrue(state.cpu.enabled)
        assertTrue(state.ram.enabled)
    }

    @Test
    fun crashedInstanceReadsAsErrorAndCountsAsNotRunning() {
        val crashed = running("fedora")
        controller.recordCrash(crashed.id(), 137, tempDir.resolve("crash.log"))
        val state = stateOf(snapshot(store.list()))

        assertEquals(DashboardStatus.Crashed, state.primary!!.status)
        assertEquals(DashboardStatusTone.Error, state.primary!!.status.tone)
        assertFalse(state.primary!!.canOpenTerminal)
        assertEquals(0, state.runningCount)
        assertEquals(1, state.instanceCount)
    }

    @Test
    fun storagePercentIsExactAtZeroHalfAndFullThroughTheMapper() {
        val empty = emptyList<InstanceLifecycle.Instance>()

        val zero = stateOf(snapshot(empty, total = 128L * GB, free = 128L * GB))
        assertEquals(0, zero.storage.percent)
        assertEquals(0f, zero.storage.fraction, 0.0001f)
        assertEquals(0L, zero.storage.usedBytes)

        val half = stateOf(snapshot(empty, total = 128L * GB, free = 64L * GB))
        assertEquals(50, half.storage.percent)
        assertEquals(0.5f, half.storage.fraction, 0.0001f)
        assertEquals(64L * GB, half.storage.usedBytes)

        val full = stateOf(snapshot(empty, total = 128L * GB, free = 0L))
        assertEquals(100, full.storage.percent)
        assertEquals(1f, full.storage.fraction, 0.0001f)
        assertEquals(128L * GB, full.storage.usedBytes)
    }

    @Test
    fun storageClampsDegenerateAndOutOfRangeInput() {
        assertEquals(0, StorageFacts(totalBytes = 0L, freeBytes = 0L).usedPercent)
        assertEquals(0f, StorageFacts(totalBytes = 0L, freeBytes = 0L).fraction, 0.0001f)
        // free 大于 total（统计口径漂移）时不得出现负用量。
        assertEquals(0L, StorageFacts(totalBytes = 100L, freeBytes = 140L).usedBytes)
        assertEquals(0, StorageFacts(totalBytes = 100L, freeBytes = 140L).usedPercent)
        assertEquals(100, StorageFacts(totalBytes = 100L, freeBytes = -20L).usedPercent)
        assertEquals(1f, StorageFacts(totalBytes = 100L, freeBytes = -20L).fraction, 0.0001f)
        // 负总量同样退化到 0，而不是负环或 NaN。
        assertEquals(0L, StorageFacts(totalBytes = -1L, freeBytes = -5L).usedBytes)
        assertEquals(0, StorageFacts(totalBytes = -1L, freeBytes = -5L).usedPercent)
    }

    @Test
    fun storageComesFromSnapshotWithoutNewMeasurement() {
        val snapshot = snapshot(instances = emptyList(), total = 200L * GB, free = 50L * GB)
        val facts = StorageFacts.from(snapshot)

        assertEquals(200L * GB, facts.totalBytes)
        assertEquals(50L * GB, facts.freeBytes)
        assertEquals(75, facts.usedPercent)
    }

    @Test
    fun runningCountCountsOnlyRunningInstances() {
        val running = running("ubuntu")
        val stoppedA = stopped("debian")
        val stoppedB = stopped("fedora")
        val state = stateOf(snapshot(listOf(running, stoppedA, stoppedB)))

        assertEquals(3, state.instanceCount)
        assertEquals(1, state.runningCount)
    }

    @Test
    fun everyInstanceStateMapsToItsOwnNonEmptyChipLabel() {
        assertEquals(7, InstanceLifecycle.State.values().size)
        val labels = InstanceLifecycle.State.values().map { DashboardStatus.of(it) }

        assertEquals(7, labels.size)
        assertEquals(labels.size, labels.map { it.labelRes }.toSet().size)
        labels.forEach { status ->
            assertNotEquals("status ${status.name} needs a string resource", 0, status.labelRes)
        }
        // 终端入口只对运行中实例开放，其余状态靠文字而非颜色表达。
        assertEquals(
            listOf(DashboardStatus.Running),
            labels.filter { it.canOpenTerminal },
        )
    }

    @Test
    fun everyStatusCarriesItsSemanticTone() {
        val expected = mapOf(
            InstanceLifecycle.State.RUNNING to DashboardStatusTone.Active,
            InstanceLifecycle.State.PROVISIONING to DashboardStatusTone.Busy,
            InstanceLifecycle.State.STOPPED to DashboardStatusTone.Idle,
            InstanceLifecycle.State.SUSPENDED to DashboardStatusTone.Idle,
            InstanceLifecycle.State.INTERRUPTED to DashboardStatusTone.Error,
            InstanceLifecycle.State.CRASHED to DashboardStatusTone.Error,
            InstanceLifecycle.State.CREATED to DashboardStatusTone.Idle,
        )

        expected.forEach { (state, tone) ->
            assertEquals("tone for $state", tone, DashboardStatus.of(state).tone)
        }
    }

    @Test
    fun metricsUseHostCapacityAndInstanceAllocation() {
        val running = running("ubuntu", cores = 4)
        val state = stateOf(snapshot(listOf(running)))

        assertTrue(state.cpu.enabled)
        assertEquals(4, state.cpu.instanceCores)
        assertEquals(8, state.cpu.hostCores)
        assertEquals(0.5f, state.cpu.fraction, 0.0001f)

        // 8 GB 总量、6 GB 可用 → 已用 2 GB。
        assertTrue(state.ram.enabled)
        assertEquals(2L * GB, state.ram.usedBytes)
        assertEquals(8L * GB, state.ram.totalBytes)
        assertEquals(0.25f, state.ram.fraction, 0.0001f)
    }

    @Test
    fun cpuFractionClampsWhenTheInstanceAsksForMoreCoresThanTheHostReports() {
        val running = running("ubuntu", cores = 4)
        val smallHost = HostHardwareProfile(2, 8L * GB, 6L * GB, 2L * GB, false)
        val state = stateOf(snapshot(listOf(running), profile = smallHost))

        assertEquals(1f, state.cpu.fraction, 0.0001f)
    }

    @Test
    fun ramFractionSurvivesAZeroTotalAndAvailableAboveTotal() {
        val running = running("ubuntu")

        val zeroRam = HostHardwareProfile(4, 0L, 0L, 0L, true)
        val zero = stateOf(snapshot(listOf(running), profile = zeroRam))
        assertEquals(0L, zero.ram.usedBytes)
        assertEquals(0f, zero.ram.fraction, 0.0001f)
        assertTrue(zero.ram.enabled)

        // 可用量大于总量属于统计漂移；夹取后已用为 0，不能出现负值。
        val overAvailable = HostHardwareProfile(4, 8L * GB, 12L * GB, 2L * GB, false)
        val over = stateOf(snapshot(listOf(running), profile = overAvailable))
        assertEquals(0L, over.ram.usedBytes)
        assertEquals(0f, over.ram.fraction, 0.0001f)
    }

    @Test
    fun recentListExcludesPrimaryAndCapsAtThreeNewest() {
        val primary = running("alpine")
        val first = stopped("debian")
        val second = stopped("fedora")
        val third = stopped("opensuse")
        val fourth = stopped("rocky")

        val state = stateOf(snapshot(store.list()))

        assertEquals(primary.id(), state.primary!!.id)
        assertEquals(DashboardUiState.MAX_RECENT, state.recent.size)
        assertEquals(
            listOf(fourth.id(), third.id(), second.id()),
            state.recent.map { it.id },
        )
        assertFalse(state.recent.map { it.id }.contains(first.id()))
        assertEquals(DashboardStatus.Stopped, state.recent.first().status)
    }

    @Test
    fun cardForAnotherInstanceCannotOverrideThePrimaryRow() {
        val primary = running("ubuntu", cores = 4)
        val other = stopped("debian", cores = 6)
        val staleCard = InstanceCardUiModel.from(other, "AVF 硬件虚拟化")

        val state = stateOf(snapshot(listOf(primary, other)), card = staleCard)

        assertEquals(primary.id(), state.primary!!.id)
        assertEquals(DashboardStatus.Running, state.primary!!.status)
        assertNull(state.primary!!.engineLabel)
        // 硬件事实取自主实例，而不是那张 6 核的旧卡。
        assertEquals(4, state.primary!!.cpuCores)
        assertTrue(state.primary!!.canOpenTerminal)
    }

    @Test
    fun staleCardForTheSameInstanceCannotKeepTheTerminalOpenAfterAStop() {
        val instance = running("ubuntu", cores = 4)
        val runningCard = InstanceCardUiModel.from(instance, "QEMU 软件虚拟化")
        assertTrue(runningCard.canOpenTerminal)

        controller.forceStop(instance.id())
        val state = stateOf(snapshot(store.list()), card = runningCard)

        assertEquals(DashboardStatus.Stopped, state.primary!!.status)
        assertFalse(state.primary!!.canOpenTerminal)
        assertEquals(4, state.primary!!.cpuCores)
    }

    @Test
    fun cardForThePrimarySuppliesEngineLabelAndTerminalRule() {
        val primary = running("ubuntu")
        val card = InstanceCardUiModel.from(primary, "AVF 硬件虚拟化")
        val state = DashboardUiState.from(
            snapshot = snapshot(listOf(primary)),
            card = card,
            storage = StorageFacts(totalBytes = 128L * GB, freeBytes = 64L * GB),
            engineLabel = "AVF 硬件虚拟化",
        )

        assertEquals("AVF 硬件虚拟化", state.primary!!.engineLabel)
        assertTrue(state.primary!!.canOpenTerminal)
    }

    @Test
    fun blankEngineLabelCollapsesToNull() {
        val primary = running("ubuntu")
        val blank = DashboardUiState.from(
            snapshot = snapshot(listOf(primary)),
            card = null,
            storage = StorageFacts(totalBytes = 128L * GB, freeBytes = 64L * GB),
            engineLabel = "   ",
        )

        assertNull(blank.primary!!.engineLabel)
    }

    @Test
    fun distroNameComesFromTheIconCatalogue() {
        val primary = stopped("opensuse")
        val state = stateOf(snapshot(listOf(primary)))

        assertEquals("openSUSE 24.04", state.primary!!.name)
        assertEquals("opensuse", state.primary!!.distroId)
    }

    @Test
    fun unknownDistroFallsBackToATitleCasedId() {
        val primary = stopped("gentoo")
        val state = stateOf(snapshot(listOf(primary)))

        assertEquals("Gentoo 24.04", state.primary!!.name)
    }

    @Test
    fun stateCarriesNoBatteryTemperatureOrLegacyChromeFields() {
        val forbidden = listOf("battery", "temperature", "telemetry", "pageNumber", "ssh", "engineChoice")
        val carriers = listOf(
            DashboardUiState::class.java,
            DashboardInstanceUi::class.java,
            DashboardStorageUi::class.java,
            DashboardCpuUi::class.java,
            DashboardRamUi::class.java,
            DashboardRecentUi::class.java,
            StorageFacts::class.java,
        )
        carriers.forEach { type ->
            type.declaredFields
                .filterNot { it.isSynthetic }
                .forEach { field ->
                    forbidden.forEach { token ->
                        assertFalse(
                            "${type.simpleName}.${field.name} must not carry legacy field '$token'",
                            field.name.contains(token, ignoreCase = true),
                        )
                    }
                }
        }
    }

    @Test
    fun statusVocabularyCoversEveryLifecycleStateExactlyOnce() {
        assertEquals(7, DashboardStatus.values().size)
        val mapped = InstanceLifecycle.State.values().map { DashboardStatus.of(it) }
        assertEquals(InstanceLifecycle.State.values().size, mapped.size)
        assertEquals(DashboardStatus.values().toSet(), mapped.toSet())
    }
}
