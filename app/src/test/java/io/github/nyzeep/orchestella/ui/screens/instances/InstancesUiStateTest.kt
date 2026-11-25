package io.github.nyzeep.orchestella.ui.screens.instances

import io.github.nyzeep.orchestella.InstanceCardUiModel
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.StorageContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 实例页状态映射契约（UI3-06）。
 *
 * 页面只消费 [InstancesUiState]；这些用例锁定四状态分组、计数、每项动作
 * 可用性、空态、LazyColumn key 唯一，以及“不复活旧壳元素”的结构约束。
 * 卡片来自真实的 [InstanceLifecycle] 迁移，而不是手写的布尔组合，这样
 * `InstanceCardUiModel` 的既有规则一改就会在这里失败。
 */
class InstancesUiStateTest {

    private companion object {
        const val GB = 1024L * 1024L * 1024L
    }

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

    @Before
    @Throws(IOException::class)
    fun setUp() {
        tempDir = Files.createTempDirectory("instances-ui-state-test-")
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

    private fun created(distro: String): InstanceLifecycle.Instance {
        clock.tick += 1
        val disk = tempDir.resolve("$distro-${clock.tick}.raw")
        Files.createFile(disk)
        val hardware = VmHardwareConfig.builder()
            .cpuCores(4)
            .ramMegabytes(4096)
            .diskCapacityBytes(16L * GB)
            .gpuAccelerationEnabled(false)
            .build()
        return controller.create(InstanceLifecycle.DistroReference(distro, "24.04"), disk, hardware)
    }

    private fun provisioning(distro: String): InstanceLifecycle.Instance =
        controller.beginProvisioning(created(distro).id())

    private fun stopped(distro: String): InstanceLifecycle.Instance {
        val started = controller.beginProvisioning(created(distro).id())
        return controller.completeProvisioning(started.id())
    }

    private fun running(distro: String): InstanceLifecycle.Instance =
        controller.start(stopped(distro).id())

    private fun suspended(distro: String): InstanceLifecycle.Instance =
        controller.suspend(running(distro).id())

    private fun crashed(distro: String): InstanceLifecycle.Instance =
        controller.recordCrash(running(distro).id(), 137, tempDir.resolve("$distro-crash.log"))

    private fun interrupted(distro: String): InstanceLifecycle.Instance {
        val live = running(distro)
        controller.reconcileAfterHostDeath(live.id())
        return store.load(live.id())!!
    }

    private fun card(instance: InstanceLifecycle.Instance): InstanceCardUiModel =
        InstanceCardUiModel.from(instance, "QEMU 软件虚拟化")

    /** 七个状态各一份；先收敛非运行状态，最后才启动唯一允许的 RUNNING。 */
    private fun oneCardPerState(): List<InstanceCardUiModel> = listOf(
        card(created("alpine")),
        card(provisioning("debian")),
        card(stopped("fedora")),
        card(suspended("opensuse")),
        card(crashed("rocky")),
        card(interrupted("kali")),
        card(running("ubuntu")),
    )

    @Test
    fun emptyCardListHasNoGroupsAndNoItems() {
        val state = InstancesUiState.from(emptyList())

        assertFalse(state.hasInstances)
        assertEquals(0, state.totalCount)
        assertEquals(0, state.runningCount)
        assertTrue(state.groups.isEmpty())
        assertTrue(keysOf(state).isEmpty())
        assertNull(state.progress)
        assertFalse(state.isRefreshing)
    }

    @Test
    fun fourGroupsCoverEveryLifecycleStateInFixedOrder() {
        val state = InstancesUiState.from(oneCardPerState())

        assertEquals(
            listOf(
                InstanceGroup.Running,
                InstanceGroup.Provisioning,
                InstanceGroup.Stopped,
                InstanceGroup.Error,
            ),
            state.groups.map { it.kind },
        )
        // 已停止 = STOPPED + CREATED + SUSPENDED；异常 = CRASHED + INTERRUPTED。
        assertEquals(listOf(1, 1, 3, 2), state.groups.map { it.count })
        assertEquals(7, state.totalCount)
        assertEquals(1, state.runningCount)
        assertTrue(state.hasInstances)
    }

    @Test
    fun everyLifecycleStateMapsToItsGroupAndNonEmptyChipLabel() {
        assertEquals(7, InstanceLifecycle.State.values().size)
        val statuses = InstanceLifecycle.State.values().map { InstanceStatus.of(it) }

        assertEquals(InstanceLifecycle.State.values().size, statuses.size)
        assertEquals(statuses.size, statuses.map { it.labelRes }.toSet().size)
        statuses.forEach { status ->
            assertTrue("status ${status.name} needs a string resource", status.labelRes != 0)
        }
        assertEquals(
            mapOf(
                InstanceLifecycle.State.RUNNING to InstanceGroup.Running,
                InstanceLifecycle.State.PROVISIONING to InstanceGroup.Provisioning,
                InstanceLifecycle.State.STOPPED to InstanceGroup.Stopped,
                InstanceLifecycle.State.CREATED to InstanceGroup.Stopped,
                InstanceLifecycle.State.SUSPENDED to InstanceGroup.Stopped,
                InstanceLifecycle.State.CRASHED to InstanceGroup.Error,
                InstanceLifecycle.State.INTERRUPTED to InstanceGroup.Error,
            ),
            InstanceLifecycle.State.values().associateWith { InstanceStatus.of(it).group },
        )
    }

    @Test
    fun runningCardOffersTerminalButDisablesLaunch() {
        val row = InstancesUiState.from(listOf(card(running("ubuntu")))).groups.single().items.single()

        assertEquals(InstanceStatus.Running, row.status)
        assertTrue(row.canOpenTerminal)
        assertFalse(row.canLaunch)
        assertEquals(InstancePrimaryAction.Terminal, row.primaryAction)
        assertTrue(row.primaryEnabled)
        // 电源只对运行中/已挂起开放；冷备份必须等实例关闭。
        assertTrue(row.canPowerOff)
        assertFalse(row.canExport)
        assertFalse(row.canDelete)
        assertTrue(row.canShowLogs)
    }

    @Test
    fun stoppedCardOffersLaunchButDisablesTerminal() {
        val row = InstancesUiState.from(listOf(card(stopped("debian")))).groups.single().items.single()

        assertEquals(InstanceStatus.Stopped, row.status)
        assertFalse(row.canOpenTerminal)
        assertTrue(row.canLaunch)
        assertEquals(InstancePrimaryAction.Launch, row.primaryAction)
        assertTrue(row.primaryEnabled)
        assertFalse(row.canPowerOff)
        assertTrue(row.canExport)
        assertTrue(row.canDelete)
    }

    @Test
    fun provisioningCardDisablesBothPrimaryActions() {
        val row = InstancesUiState.from(listOf(card(provisioning("fedora")))).groups.single().items.single()

        assertEquals(InstanceStatus.Provisioning, row.status)
        assertFalse(row.canOpenTerminal)
        assertFalse(row.canLaunch)
        assertFalse(row.primaryEnabled)
        // PROVISIONING→STOPPED 是合法转移，电源可作为取消卡住的装配。
        assertTrue(row.canPowerOff)
        assertFalse(row.canExport)
        assertFalse(row.canDelete)
        assertTrue(row.canShowLogs)
    }

    @Test
    fun suspendedCardSitsInStoppedGroupAndOffersResume() {
        val row = InstancesUiState.from(listOf(card(suspended("opensuse")))).groups.single().items.single()

        assertEquals(InstanceStatus.Suspended, row.status)
        assertEquals(InstanceGroup.Stopped, row.status.group)
        assertFalse(row.canOpenTerminal)
        assertFalse(row.canLaunch)
        assertEquals(InstancePrimaryAction.Resume, row.primaryAction)
        assertTrue(row.primaryEnabled)
        // 挂起可以恢复，也可以强制关机，但不能再“启动”。
        assertTrue(row.canPowerOff)
        assertFalse(row.canExport)
        assertFalse(row.canDelete)
    }

    @Test
    fun crashedCardSitsInErrorGroupAndAllowsColdRestorePowerResetLogsAndDelete() {
        val row = InstancesUiState.from(listOf(card(crashed("rocky")))).groups.single().items.single()

        assertEquals(InstanceStatus.Crashed, row.status)
        assertEquals(InstanceGroup.Error, row.status.group)
        assertFalse(row.canOpenTerminal)
        assertTrue(row.canLaunch)
        assertEquals(InstancePrimaryAction.Launch, row.primaryAction)
        assertTrue(row.primaryEnabled)
        assertFalse(row.canLaunchDesktop)
        // CRASHED→STOPPED 是合法转移，电源用于复位。
        assertTrue(row.canPowerOff)
        assertTrue(row.canShowLogs)
        assertTrue(row.canExport)
        assertTrue(row.canDelete)
    }

    @Test
    fun interruptedCardCanRelaunchButCannotBeDeletedYet() {
        val row = InstancesUiState.from(listOf(card(interrupted("kali")))).groups.single().items.single()

        assertEquals(InstanceStatus.Interrupted, row.status)
        assertEquals(InstanceGroup.Error, row.status.group)
        assertTrue(row.canLaunch)
        assertFalse(row.canOpenTerminal)
        assertTrue(row.primaryEnabled)
        assertTrue(row.canPowerOff)
        assertTrue(row.canExport)
        assertFalse(row.canDelete)
    }

    @Test
    fun countsIgnoreDuplicateInstanceIdsSoEveryListKeyStaysUnique() {
        val runningCard = card(running("ubuntu"))
        val stoppedCard = card(stopped("debian"))

        val state = InstancesUiState.from(listOf(runningCard, runningCard, stoppedCard, stoppedCard))

        assertEquals(2, state.totalCount)
        assertEquals(1, state.runningCount)
        assertEquals(2, state.groups.sumOf { it.count })
        val keys = keysOf(state)
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(
            listOf("instances:group:running", runningCard.instanceId),
            keys.take(2),
        )
    }

    @Test
    fun listKeysCoverEveryGroupAndItemExactlyOnce() {
        val state = InstancesUiState.from(oneCardPerState())
        val keys = keysOf(state)

        assertEquals(4 + 7, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains(InstancesListKeys.group(InstanceGroup.Error)))
        assertTrue(keys.contains(InstancesListKeys.item(state.groups.last().items.last().id)))
    }

    @Test
    fun engineLabelIsTrimmedAndBlankCollapsesToNull() {
        val labelled = InstancesUiState.from(
            listOf(card(stopped("debian"))),
            engineLabel = "  QEMU 软件虚拟化  ",
        )
        assertEquals("QEMU 软件虚拟化", labelled.groups.single().items.single().engineLabel)

        val blank = InstancesUiState.from(
            listOf(card(stopped("debian"))),
            engineLabel = "   ",
        )
        assertNull(blank.groups.single().items.single().engineLabel)
    }

    @Test
    fun ipIsShownOnlyWhenTheCardCarriesARealAddress() {
        val base = card(stopped("debian"))

        val ipv4 = InstancesUiState.from(listOf(base.copy(ipAddress = "192.0.2.42")))
        assertEquals("192.0.2.42", ipv4.groups.single().items.single().ipAddress)

        val ipv6 = InstancesUiState.from(listOf(base.copy(ipAddress = "fd00::2")))
        assertEquals("fd00::2", ipv6.groups.single().items.single().ipAddress)

        // InstanceCardUiModel 在没有 guest IP 时写“离线/运行中”哨兵值，
        // 那不是地址，不得当作 IP 渲染。
        listOf("离线", "运行中", "", "   ").forEach { sentinel ->
            val row = InstancesUiState.from(listOf(base.copy(ipAddress = sentinel)))
                .groups.single().items.single()
            assertNull("'$sentinel' must not render as IP", row.ipAddress)
        }
    }

    @Test
    fun nameUsesTheDistroCatalogueAndVersion() {
        val row = InstancesUiState.from(listOf(card(stopped("opensuse"))))
            .groups.single().items.single()

        assertEquals("openSUSE 24.04", row.name)
        assertEquals("opensuse", row.distroId)
        assertTrue(row.specsText.contains("4 vCPU"))
        assertTrue(row.specsText.contains("4096 MB"))
    }

    @Test
    fun progressBannerNeedsCopyAndClampsFraction() {
        assertNull(InstancesProgressUi.of("   ", 0.4f))
        // 文案存在、进度未知：横幅仍显示，只是不画确定进度条。
        val unknown = InstancesProgressUi.of("装配中…", Float.NaN)
        assertNotNull(unknown)
        assertNull(unknown!!.fraction)

        val clampedHigh = InstancesProgressUi.of("装配中…", 1.4f)
        assertNotNull(clampedHigh)
        assertEquals(1f, clampedHigh!!.fraction!!, 0.0001f)

        val clampedLow = InstancesProgressUi.of("装配中…", -0.3f)
        assertEquals(0f, clampedLow!!.fraction!!, 0.0001f)

        assertNull(InstancesProgressUi.of("装配中…", null)!!.fraction)
    }

    /** 与屏幕相同的 key 来源：组头走 InstancesListKeys.group，条目就是实例 id。 */
    private fun keysOf(state: InstancesUiState): List<String> = state.groups.flatMap { group ->
        listOf(InstancesListKeys.group(group.kind)) + group.items.map { InstancesListKeys.item(it.id) }
    }

    @Test
    fun progressAndRefreshPassThroughTheMapper() {
        val progress = InstancesProgressUi.of("正在写入 rootfs…", 0.42f)
        val state = InstancesUiState.from(
            cards = listOf(card(provisioning("debian"))),
            progress = progress,
            isRefreshing = true,
        )

        assertEquals(progress, state.progress)
        assertTrue(state.isRefreshing)
    }

    @Test
    fun runningInstancePrefersLiveEngineLabelOverPersistedExplicitStrategyBadge() {
        val disk = tempDir.resolve("uml-fallback-alpine.raw")
        Files.createFile(disk)
        val hardware = VmHardwareConfig.builder()
            .cpuCores(1)
            .ramMegabytes(512)
            .diskCapacityBytes(8L * GB)
            .gpuAccelerationEnabled(false)
            .build()
        val created = controller.create(
            InstanceLifecycle.DistroReference("alpine", "3.22.2"),
            disk,
            hardware,
            InstanceUserConfig.defaultRoot(),
            ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                StorageContract.RootVolumeFormat.RAW_SPARSE,
            ),
        )
        controller.beginProvisioning(created.id())
        val stoppedInstance = controller.completeProvisioning(created.id())

        // Stopped keeps explicit strategy badge even if global fallback badge is passed.
        val stoppedRow = InstancesUiState.from(
            cards = listOf(InstanceCardUiModel.from(stoppedInstance, "QEMU 软件模拟")),
            engineLabel = "QEMU 软件模拟",
        ).groups.single().items.single()
        assertEquals(
            FullLinuxContract.CapabilityMatrix.userModeKernelBaseline().performanceBadge(),
            stoppedRow.engineLabel,
        )

        // Running (e.g., atomic QEMU fallback) must display the live session badge, not persisted UML.
        val liveQemuMatrix = FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithGraphics()
        val runningInstance = controller.start(stoppedInstance.id())
        val runningCard = InstanceCardUiModel.from(
            instance = runningInstance,
            strategyBadge = "QEMU 软件模拟",
            supportsDesktop = true,
            capabilityMatrix = liveQemuMatrix,
        )
        val runningRow = InstancesUiState.from(
            cards = listOf(runningCard),
            engineLabel = "QEMU 软件模拟",
        ).groups.single().items.single()
        assertEquals("QEMU 软件模拟", runningRow.engineLabel)
        assertTrue(runningCard.canLaunchDesktop)
    }
}
