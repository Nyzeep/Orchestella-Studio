package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStarter
import java.io.IOException
import java.util.Objects
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/**
 * DV-ACC: the one call site that routes a real launch through the accelerator
 * assembly. The flag gate lives here so the shipped build keeps today's launch
 * path untouched, and the call site reads as one dispatch.
 *
 * While the flag is off the dispatch *is* the existing launch: the supplied
 * [todayLaunch] seam runs, no platform fact is read, no coordinator exists and
 * no accelerator budget is reserved.
 *
 * While the flag is on the QEMU lane is the main lane (the cross-lane contract
 * is defined over QEMU, so the AVF main-instance strategy is not consulted)
 * and the assembly decides whether the accelerator lane may start. The QEMU
 * starter wraps the existing launcher unchanged — session registration, launch
 * guard and failure cleanup all stay in QemuInstanceLauncher; the readiness
 * probe observes the registered session's Bridge control channel. The budget
 * envelope is computed here as exactly what the two lanes request, so a
 * reservation is never refused by size and the only accelerator gate is the
 * AcceleratorDecision.
 */
object AcceleratorLaunchDispatcher {
    /**
     * Fixed accelerator lane request: one vCPU (the pinned AVF SystemApi can
     * only express ONE_CPU), fixed memory and disk. Not caller-tunable.
     */
    val ACCEL_REQUEST: LaneRequest = LaneRequest(LaneId.ACCEL, 1, 2048L, 16L)

    /** Shipped assembly timing; identical to Config.currentBuild(). */
    const val QEMU_READY_TIMEOUT_MS: Long = 30_000L
    const val STOP_GRACE_MS: Long = 2_000L

    /** The existing QEMU launch seam; MainActivity binds the real launcher. */
    fun interface QemuLaunch {
        @Throws(IOException::class, InterruptedException::class)
        fun launch(params: InstanceStrategyLauncher.LaunchParameters): ExecutionSession
    }

    /**
     * Today's outcome plus the coordinator when the flag routed the launch
     * through the assembly. [coordinator] is null while the flag is off; the
     * caller stops it once the QEMU session has exited.
     */
    data class Dispatch(
        val outcome: InstanceStrategyLauncher.Outcome,
        val coordinator: DualLaneCoordinator?,
        val decisionJson: String,
    )

    fun dispatch(
        acceleratorLaneEnabled: Boolean,
        params: InstanceStrategyLauncher.LaunchParameters,
        qemuLaunch: QemuLaunch,
        accelLauncher: () -> AcceleratorVmLauncher,
        facts: AcceleratorFactProvider,
        qemuSessionSource: Supplier<ExecutionSession?>,
        requiredCompilerTriple: String,
        clock: LaneClock,
        events: LaneEventSink,
        todayLaunch: () -> InstanceStrategyLauncher.Outcome,
    ): Dispatch {
        Objects.requireNonNull(params, "params")
        Objects.requireNonNull(qemuLaunch, "qemuLaunch")
        Objects.requireNonNull(accelLauncher, "accelLauncher")
        Objects.requireNonNull(facts, "facts")
        Objects.requireNonNull(qemuSessionSource, "qemuSessionSource")
        Objects.requireNonNull(clock, "clock")
        Objects.requireNonNull(events, "events")
        Objects.requireNonNull(todayLaunch, "todayLaunch")

        if (!acceleratorLaneEnabled) {
            return Dispatch(Objects.requireNonNull(todayLaunch(), "todayLaunch"), null, "")
        }

        val hardware: VmHardwareConfig =
            Objects.requireNonNull(params.hardwareConfig, "hardwareConfig")
        val qemuRequest = LaneRequest(
            LaneId.QEMU,
            hardware.cpuCores(),
            hardware.ramMegabytes().toLong(),
            gibibytes(hardware.diskCapacityBytes()),
        )
        // The assembly root owns the budget: the envelope is exactly what the
        // two lanes request, so the only accelerator gate is the decision.
        val hostBudget = LaneBudget(
            qemuRequest.cpuCores + ACCEL_REQUEST.cpuCores,
            qemuRequest.ramMiB + ACCEL_REQUEST.ramMiB,
            qemuRequest.diskGiB + ACCEL_REQUEST.diskGiB,
            true,
        )

        val probe = QemuReadinessProbe.ofSession(qemuSessionSource)
        val launched = AtomicReference<ExecutionSession?>()
        val qemuFailure = AtomicReference<Exception?>()
        val qemuStarter = LaneStarter {
            try {
                val session = qemuLaunch.launch(params)
                if (session !is RuntimeOwnedSession) {
                    throw LaneStartException("QEMU_START_FAILED")
                }
                launched.set(session)
                AcceleratorLaneAssembly.qemuLane(session, probe)
            } catch (failure: IOException) {
                qemuFailure.set(failure)
                throw LaneStartException("QEMU_START_FAILED")
            } catch (failure: InterruptedException) {
                qemuFailure.set(failure)
                throw LaneStartException("QEMU_START_FAILED")
            }
        }
        val accelStarter = LaneStarter { accelLauncher().start() }

        val result = AcceleratorLaneAssembly.assemble(
            AcceleratorLaneAssembly.Config(
                true,
                requiredCompilerTriple,
                QEMU_READY_TIMEOUT_MS,
                STOP_GRACE_MS,
            ),
            AcceleratorLaneAssembly.Inputs(
                hostBudget,
                qemuRequest,
                ACCEL_REQUEST,
                qemuStarter,
                accelStarter,
                clock,
                events,
                facts,
            ),
        )
        result.coordinator().startAll()

        val session = launched.get() ?: run {
            val failure = qemuFailure.get()
            throw when (failure) {
                is IOException -> failure
                is InterruptedException -> failure
                else -> IOException("QEMU_START_FAILED")
            }
        }
        return Dispatch(
            InstanceStrategyLauncher.Outcome(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                session,
                null,
                null,
            ),
            result.coordinator(),
            result.decisionJson(),
        )
    }

    private fun gibibytes(bytes: Long): Long =
        if (bytes <= 0L) 1L else maxOf(1L, (bytes + (1L shl 30) - 1L) shr 30)
}
