package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.CompileBackend
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
 * [todayLaunch] seam runs, no platform fact is read, no manifest is opened, no
 * coordinator exists and no accelerator budget is reserved.
 *
 * While the flag is on the QEMU lane is the main lane (the cross-lane contract
 * is defined over QEMU, so the AVF main-instance strategy is not consulted)
 * and the assembly decides which compile backend, if any, may start. The QEMU
 * starter wraps the existing launcher unchanged — session registration, launch
 * guard and failure cleanup all stay in QemuInstanceLauncher; the readiness
 * probe observes the registered session's Bridge control channel. The budget
 * envelope is computed here as exactly what the two lanes request, so a
 * reservation is never refused by size and the only accelerator gate is the
 * AcceleratorDecision.
 *
 * PVM-09: the accelerator lane is a compile service with two startable
 * backends. [AccelBackends] carries both factories lazily, and the starter
 * handed to the coordinator reads the verdict the assembly just produced
 * ([AcceleratorLaneAssembly.Result.decisionBackend]) instead of re-deriving it
 * from the facts. A backend the dispatch cannot start is a fixed
 * [ACCEL_NO_BACKEND] failure, never a silent QEMU-only run.
 */
object AcceleratorLaunchDispatcher {
    /**
     * Fixed accelerator lane request. One vCPU: the pinned AVF SystemApi can
     * only express ONE_CPU topology (ReflectiveAvfVirtualMachineApi rejects
     * any other count), so the VM and the budget must agree. 2048 MiB memory
     * and a 16 GiB root volume are the declared accelerator-image envelope
     * until the image ticket provisions real sizes — both live here and are
     * derived, never re-stated at call sites.
     */
    val ACCEL_REQUEST: LaneRequest = LaneRequest(LaneId.ACCEL, 1, 2048L, 16L)

    /** Shipped assembly timing; identical to Config.currentBuild(). */
    const val QEMU_READY_TIMEOUT_MS: Long = 30_000L
    const val STOP_GRACE_MS: Long = 2_000L

    /**
     * Bound for the payload compiler's readiness handoff. DV-PVM-02a measured
     * 2.5 s from create to `payloadReady`, so 30 s is a generous ceiling for a
     * slower device, not a tuned value.
     */
    const val PAYLOAD_READY_TIMEOUT_MS: Long = 30_000L

    /**
     * The compiler identity this product's guest expects. The app ships an
     * arm64-v8a guest and both accelerator backends compile for it, so this is
     * a build-time declaration of the *requirement*, compared against what the
     * manifest (payload) or the installed image (custom VM) actually declares.
     * An identity that does not match is refused with the backend's mismatch
     * code rather than being run unverified.
     */
    const val GUEST_COMPILER_TRIPLE: String = "aarch64-unknown-linux-android"

    /** Fixed code when the verdict names a backend this dispatch cannot start. */
    const val ACCEL_NO_BACKEND: String = "ACCEL_NO_BACKEND"

    /** The existing QEMU launch seam; MainActivity binds the real launcher. */
    fun interface QemuLaunch {
        @Throws(IOException::class, InterruptedException::class)
        fun launch(params: InstanceStrategyLauncher.LaunchParameters): ExecutionSession
    }

    /**
     * The two startable compile backends, both built only when selected.
     *
     * Construction is deferred so an unarmed or flag-off launch never touches
     * a manifest, a VM API or an image path. The PVM factory is expected to
     * refuse with a fixed code when the payload is not installed; it is never
     * consulted unless the verdict already selected `PVM_PAYLOAD`.
     */
    class AccelBackends(
        private val customVm: () -> AcceleratorVmLauncher,
        private val pvmPayload: () -> PvmPayloadLaneStarter,
    ) {
        internal fun start(backend: CompileBackend) = when (backend) {
            CompileBackend.PVM_PAYLOAD -> pvmPayload().start()
            CompileBackend.CUSTOM_VM -> customVm().start()
            CompileBackend.HOST_NATIVE, CompileBackend.NONE ->
                throw LaneStartException(ACCEL_NO_BACKEND)
        }
    }

    /**
     * Today's outcome plus the coordinator when the flag routed the launch
     * through the assembly. [coordinator] is null while the flag is off; the
     * caller stops it once the QEMU session has exited. [backend] is the
     * verdict's backend, or [CompileBackend.NONE] when the lane was never in
     * the picture.
     */
    data class Dispatch(
        val outcome: InstanceStrategyLauncher.Outcome,
        val coordinator: DualLaneCoordinator?,
        val decisionJson: String,
        val backend: CompileBackend,
    )

    fun dispatch(
        acceleratorLaneEnabled: Boolean,
        params: InstanceStrategyLauncher.LaunchParameters,
        qemuLaunch: QemuLaunch,
        accelBackends: AccelBackends,
        facts: AcceleratorFactProvider,
        qemuSessionSource: Supplier<ExecutionSession?>,
        requiredCompilerTriple: String,
        clock: LaneClock,
        events: LaneEventSink,
        todayLaunch: () -> InstanceStrategyLauncher.Outcome,
    ): Dispatch {
        Objects.requireNonNull(params, "params")
        Objects.requireNonNull(qemuLaunch, "qemuLaunch")
        Objects.requireNonNull(accelBackends, "accelBackends")
        Objects.requireNonNull(facts, "facts")
        Objects.requireNonNull(qemuSessionSource, "qemuSessionSource")
        Objects.requireNonNull(clock, "clock")
        Objects.requireNonNull(events, "events")
        Objects.requireNonNull(todayLaunch, "todayLaunch")

        if (!acceleratorLaneEnabled) {
            // Zero side effects: no fact read, no manifest, no backend built.
            return Dispatch(
                Objects.requireNonNull(todayLaunch(), "todayLaunch"),
                null,
                "",
                CompileBackend.NONE,
            )
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

        // The verdict is produced by the assembly, so the accelerator starter
        // cannot be bound to a backend before it returns. The coordinator
        // starts the lanes after assembly, so this holder is always set first.
        val selected = AtomicReference(CompileBackend.NONE)

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
            } catch (failure: RuntimeException) {
                // Today's path lets unchecked launch failures reach the launch
                // failure handler unchanged; keep that exception face under the
                // flag by rethrowing the original object after the coordinator.
                qemuFailure.set(failure)
                throw LaneStartException("QEMU_START_FAILED")
            }
        }
        val accelStarter = LaneStarter { accelBackends.start(selected.get()) }

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
        selected.set(result.decisionBackend())
        result.coordinator().startAll()

        val session = launched.get() ?: run {
            val failure = qemuFailure.get()
            throw when (failure) {
                is IOException -> failure
                is InterruptedException -> failure
                is RuntimeException -> failure
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
            result.decisionBackend(),
        )
    }

    private fun gibibytes(bytes: Long): Long =
        if (bytes <= 0L) 1L else maxOf(1L, (bytes + (1L shl 30) - 1L) shr 30)
}
