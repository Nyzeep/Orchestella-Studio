package io.github.nyzeep.orchestella.startup

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 锁定仪表盘快照的纯选择语义（主实例选择、变更指纹），保证主线程 IO 迁移后
 * 渲染输入与原实现逐字节一致。
 */
public final class DashboardSnapshotTest {

    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-11-07T08:00:00Z"), ZoneOffset.UTC)
    private lateinit var tempDir: Path
    private lateinit var store: InstanceLifecycle.FileStore
    private lateinit var controller: InstanceLifecycle.Controller

    @Before
    @Throws(IOException::class)
    fun setUp() {
        tempDir = Files.createTempDirectory("dashboard-snapshot-test-")
        store = InstanceLifecycle.FileStore(tempDir)
        controller = InstanceLifecycle.Controller(store, fixedClock)
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                .sorted(java.util.Comparator.reverseOrder<Path>())
                .forEach { p -> Files.deleteIfExists(p) }
        }
    }

    private fun createInstance(distro: String): InstanceLifecycle.Instance {
        val disk = tempDir.resolve("$distro.raw")
        Files.createFile(disk)
        return controller.create(InstanceLifecycle.DistroReference(distro, "12"), disk)
    }

    private fun toRunning(instance: InstanceLifecycle.Instance): InstanceLifecycle.Instance {
        controller.beginProvisioning(instance.id())
        controller.completeProvisioning(instance.id())
        return controller.start(instance.id())!!
    }

    @Test
    @Throws(IOException::class)
    fun pickPrimaryPrefersRunningThenLastActiveThenFirst() {
        val a = createInstance("alpine")
        val b = createInstance("debian")
        val c = createInstance("fedora")
        toRunning(c)
        // create/transition 返回的是当时的快照对象，选择语义以 store 现值为准。
        val runningState = store.list()
        assertEquals(c.id(), DashboardSnapshot.pickPrimary(runningState, b.id())!!.id())

        // 没有 RUNNING 时回到 lastActive。
        controller.forceStop(c.id())
        val stoppedState = store.list()
        assertEquals(b.id(), DashboardSnapshot.pickPrimary(stoppedState, b.id())!!.id())
        // 没有 lastActive 时回到首个。
        assertEquals(a.id(), DashboardSnapshot.pickPrimary(stoppedState, null)!!.id())
        // 空列表为 null。
        assertNull(DashboardSnapshot.pickPrimary(emptyList(), b.id()))
    }

    @Test
    @Throws(IOException::class)
    fun stateKeyTracksIdAndStateOnly() {
        val a = createInstance("alpine")
        val b = createInstance("debian")
        val key = DashboardSnapshot.stateKeyOf(listOf(a, b))
        assertTrue(key.contains(a.id()))
        assertTrue(key.contains(b.id()))
        assertTrue(key.contains(InstanceLifecycle.State.CREATED.name))

        // 状态变化必须改变指纹（刷新循环依赖它决定重渲染）。
        toRunning(a)
        val after = store.list()
        assertNotEquals(key, DashboardSnapshot.stateKeyOf(after))
    }

    @Test
    fun snapshotCarriesRenderInputs() {
        val profile = HostHardwareProfile(8, 16L shl 30, 10L shl 30, 512L shl 20, false)
        val snapshot = DashboardSnapshot(
            instances = emptyList(),
            stateKey = "",
            listFailed = false,
            primary = null,
            ssh = DashboardSnapshot.SshPrefetch(command = null),
            acceleratorLaneReady = true,
            hardwareProfile = profile,
            storageTotalBytes = 100L,
            storageFreeBytes = 40L,
        )
        // SshPrefetch 是哨兵：command 为 null 也表示“已取过”，与“未取过”（ssh=null）可区分。
        assertEquals(null, snapshot.ssh!!.command)
        assertTrue(snapshot.acceleratorLaneReady)
        assertEquals(100L, snapshot.storageTotalBytes)
        assertEquals(40L, snapshot.storageFreeBytes)
    }
}
