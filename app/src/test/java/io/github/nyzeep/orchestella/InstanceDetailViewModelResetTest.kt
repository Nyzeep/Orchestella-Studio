package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.time.Clock

/**
 * 2026-11-09 批次3 E 工单（T4② 删除守卫）：真机走查发现「运行中断实例复位后
 * 删除按钮仍禁用」。根因：resetDeadlockState 走 reconcileAfterHostDeath(id,
 * STOPPED)，而该迁移对 INTERRUPTED/CRASHED 是 no-op（只处理 RUNNING/SUSPENDED
 * 的宿主死亡对账）——状态不变，canDelete 恒 false。
 *
 * 契约：对 INTERRUPTED 实例复位后必须真正落到 STOPPED，删除守卫解除；
 * 对陈旧 RUNNING（无会话）实例保留原对账路径迁移到 STOPPED。
 */
class InstanceDetailViewModelResetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var store: InstanceLifecycle.FileStore
    private lateinit var controller: InstanceLifecycle.Controller

    @Before
    fun setUp() {
        val dir: Path = temporaryFolder.newFolder("reset_instances").toPath()
        store = InstanceLifecycle.FileStore(dir)
        controller = InstanceLifecycle.Controller(store, Clock.systemUTC())
    }

    /** 复刻 2026-11-08 真机路径：启动后被宿主死亡对账为运行中断。 */
    private fun interruptInstance(): InstanceLifecycle.Instance {
        val instance = controller.create(
            InstanceLifecycle.DistroReference("debian", "13"),
            temporaryFolder.newFile("root-interrupted.raw").toPath()
        )
        InstanceLaunchSequence.beforeLaunch(controller, instance)
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state())
        controller.reconcileAfterHostDeath(instance.id())
        assertEquals(InstanceLifecycle.State.INTERRUPTED, store.load(instance.id()).state())
        return instance
    }

    private fun viewModel(): InstanceDetailViewModel =
        InstanceDetailViewModel(instanceStore = store, controller = controller)

    @Test
    fun interruptedInstanceResetActuallyReachesStoppedAndReleasesDeleteGuard() {
        val instance = interruptInstance()
        val viewModel = viewModel()
        val state = requireNotNull(viewModel.loadInstance(instance.id())) { "instance must load" }
        assertEquals(InstanceLifecycle.State.INTERRUPTED, state.state)
        assertTrue(state.canResetState)
        assertTrue("中断态删除按钮必须禁用", !state.canDelete)

        assertNotNull("复位必须成功", viewModel.resetDeadlockState())
        assertEquals(
            "复位后必须真正落盘为已停止（no-op 复位即本缺陷）",
            InstanceLifecycle.State.STOPPED,
            store.load(instance.id()).state()
        )
        val after = requireNotNull(viewModel.uiState) { "uiState must refresh" }
        assertEquals(InstanceLifecycle.State.STOPPED, after.state)
        assertTrue("复位后删除守卫必须解除", after.canDelete)
    }

    @Test
    fun staleRunningInstanceStillReconcilesToStopped() {
        val instance = controller.create(
            InstanceLifecycle.DistroReference("ubuntu", "24.04"),
            temporaryFolder.newFile("root-stale.raw").toPath()
        )
        InstanceLaunchSequence.beforeLaunch(controller, instance)
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state())

        val viewModel = viewModel()
        assertNotNull(viewModel.loadInstance(instance.id()))
        assertNotNull(viewModel.resetDeadlockState())
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state())
        assertTrue(requireNotNull(viewModel.uiState) { "uiState must refresh" }.canDelete)
    }
}
