package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.PvmCompilerManifest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.util.Optional
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/**
 * Deep module owning the Full Linux instance launch orchestration that used to
 * live inline in [MainActivity].launchInstance: admission (attach fast path,
 * orphan reconciliation, launch-guard generation), provisioning inputs
 * (mirror/firstboot assembly), strategy + accelerator dispatch, the guest-exit
 * watcher thread and the failure cleanup sequence (ADR-0020 cleanup bridge,
 * foreground-service stop, metadata decisions).
 *
 * <p>The module is pure JVM: every Android-typed resource it needs arrives as a
 * narrow injected port — the foreground-service handle, the settings reads, the
 * copy/resource-string resolution, the log instrumentation, the cleanup bridge
 * and the AVF/QEMU/custom-VM/PVM launch seams. UI feedback flows through
 * [LaunchUi]: this coordinator decides when to surface what (including the
 * generation guards that gate every late UI update), the Activity decides how
 * it is presented. `launch` returns a fixed [LaunchOutcome] terminal state for
 * callers that need the synchronous result.
 *
 * <p>Behavioral contract (kept verbatim from the extracted implementation):
 * the attach fast path, the guard-refused path, foreground service
 * start×2/stop×2 call sites and arguments, `takeLastCleanupResult` consumed
 * exactly once per failed launch, the watcher daemon thread with its
 * WAIT_FAILED/STALE guards, and the double generation gate
 * ([InstanceLaunchGuard.isLatest] + registry identity) before every success or
 * exit UI update.
 *
 * <p>Lane A (2026-11-13): the ride-along UML face ([UmlFaceLane]) arms once
 * per successful flag-on dispatch with the same double-gate value the dispatch
 * consumed, and disarms at the three accelerator-coordinator close points
 * (watcher exit, watcher exception, launch failure). A gate-off dispatch
 * returns a null coordinator and never touches the face lane at all.
 */
class InstanceLaunchCoordinator(
    private val store: InstanceLifecycle.FileStore,
    private val settings: LaunchSettings,
    private val foreground: ForegroundServiceHandle,
    private val ui: LaunchUi,
    private val copy: LaunchCopy,
    private val diagnostics: LaunchDiagnostics,
    private val cleanupBridge: CleanupBridge,
    private val filesDir: File,
    private val performanceBadge: PerformanceBadgeSource,
    private val preferAvf: BooleanSupplier,
    private val avfLaunch: InstanceStrategyLauncher.AvfLaunch,
    private val qemuLaunch: InstanceStrategyLauncher.QemuLaunch,
    private val customVmBackend: CustomVmBackend,
    private val pvmPayloadBackend: PvmPayloadBackend,
    private val factsSource: FactsSource,
    private val payloadManifestSource: PayloadManifestSource,
    private val laneEventSink: LaneEventSink,
    private val laneClock: LaneClock,
    private val clock: Clock,
    private val orphanReconciler: Runnable,
    private val umlFaceLane: UmlFaceLane,
) {

    /** Settings values the orchestration consumes (SettingsManager handle). */
    interface LaunchSettings {
        fun setLastActiveInstanceId(instanceId: String)
        fun batteryPolicy(): BatteryPolicy
        fun isAcceleratorLaneEnabled(): Boolean
    }

    /** Foreground service binding seam (OrchestellaForegroundService handle). */
    interface ForegroundServiceHandle {
        fun start(
            instanceId: String,
            distroTitle: String,
            performanceBadge: String,
            batteryPolicy: BatteryPolicy,
            launchGeneration: String?,
        )
        fun stop(instanceId: String, generation: String?)
    }

    /**
     * UI presentation seam. The typed methods must run on the UI thread;
     * [runOnUi] schedules that (Activity.runOnUiThread). The coordinator keeps
     * every decision of when/what, including the generation guards.
     */
    interface LaunchUi {
        fun runOnUi(update: Runnable)
        /** Reset the create buttons to the device-derived default enablement. */
        fun resetCreateButtons()
        /** Write the launch status line. */
        fun showStatus(message: String)
        /** Attach presentation: "已连接到运行中的实例" + terminal handoff. */
        fun showAttached(instance: InstanceLifecycle.Instance)
        /** Guard-refused presentation: "实例正在启动，请稍后再试". */
        fun showLaunchBusyStatus()
        /** Success presentation: open the terminal for this navigation payload. */
        fun showLaunchSuccess(navigation: LaunchNavigation)
        /** Loud failure presentation: status line + long toast with the same text. */
        fun surfaceLaunchFailure(message: String)
        /** Repaint the instance list. */
        fun showInstances()
    }

    /** Copy/resource-string resolution seam (ErrorCopy + launch status strings). */
    interface LaunchCopy {
        fun detailMessage(failure: Throwable): String
        fun guestExitedStatus(): String
        fun launchStartedHardwareStatus(): String
        fun launchDegradedToSoftwareStatus(reason: String): String
    }

    /** Instrumentation seam for the launch-path log call sites. */
    interface LaunchDiagnostics {
        fun logAcceleratorSnapshot(line: String)
        fun logLaunchFailure(failureType: String)
    }

    /**
     * Cleanup-outcome bridge (ADR-0020): the failed launch consumes the most
     * recent QEMU cleanup result exactly once; null when there is none.
     */
    fun interface CleanupBridge {
        fun takeLastCleanupResult(instanceId: String): RuntimeProcessSupervisor.StopResult?
    }

    /** Deferred custom-VM (accelerator image + AVF engine) backend factory. */
    fun interface CustomVmBackend {
        fun create(instanceId: String): AcceleratorVmLauncher
    }

    /** Deferred PVM payload lane factory; refuses with a fixed code when absent. */
    fun interface PvmPayloadBackend {
        fun create(instanceId: String): PvmPayloadLaneStarter
    }

    /** Per-launch accelerator facts provider source (Android edge supplies it). */
    fun interface FactsSource {
        fun create(): AcceleratorFactProvider
    }

    /** Per-launch payload-manifest read for the compiler identity snapshot. */
    fun interface PayloadManifestSource {
        fun read(): Optional<PvmCompilerManifest>
    }

    /**
     * Ride-along UML face lane port (Lane A, 2026-11-13): the launch chain
     * arms the UML compile-worker face through the assembly's
     * spawner-injecting `umlFace` slot after a successful dispatch, and
     * disarms it exactly where the accelerator coordinator closes (the
     * watcher's exit and exception paths, plus the launch failure path). The
     * gate is the same double-gate value the dispatch consumed. Implementations
     * must be honest: a refused start (GATE_DISABLED / MISSING_ASSET /
     * SPAWN_FAILED) never fakes a state — width stays 0, no keep-alive is
     * touched, and at most one face is ever bound at a time.
     */
    interface UmlFaceLane {
        /** Arms the face; answers the armed launcher, or null when declined/refused. */
        fun arm(instanceId: String, gateEnabled: Boolean): UmlFaceLauncher?

        /** Stops the face and releases its status; idempotent, mirrors [close]. */
        fun disarm(instanceId: String)

        /**
         * Gate 1 (compile bridge, ACR 2026-11-13 方案 A): publishes the
         * launch's fabric orchestrator to the lane — the compile-bridge
         * submitter dispatches through it until [disarm]. Default no-op: lanes
         * without a compile bridge ignore it.
         */
        fun bindFabric(orchestrator: FabricOrchestrator?) {}
    }

    /** Performance badge source for the first foreground-service start. */
    fun interface PerformanceBadgeSource {
        fun current(): String
    }

    /** Fixed terminal states of one coordinated launch attempt. */
    sealed interface LaunchOutcome {
        /** A live session already owns the instance; the UI was pointed at it. */
        object Attached : LaunchOutcome

        /** Another launch holds the admission slot; the UI was told to wait. */
        object Busy : LaunchOutcome

        /** Dispatch finished, watcher armed, success UI posted. */
        data class Launched(
            val navigation: LaunchNavigation,
            val hardwareAccelerated: Boolean,
            val degradationReason: String?,
        ) : LaunchOutcome

        /** Launch failed; cleanup, metadata and failure UI were handled. */
        data class Failed(val failure: Throwable) : LaunchOutcome
    }

    /** TerminalActivity navigation payload for a successful launch. */
    data class LaunchNavigation(
        val bridgeToken: String,
        val instanceId: String,
        val hostname: String,
        val defaultUser: String,
    )

    fun launch(instance: InstanceLifecycle.Instance): LaunchOutcome {
        // A live session means the VM is already running: launching again would fork a
        // second QEMU that fails to bind the instance's sockets AND unregister the live
        // session in the failure path (observed on-device as "无法输入指令"). Attach instead.
        if (ExecutionSessionRegistry.get(instance.id()) != null) {
            ui.runOnUi {
                ui.resetCreateButtons()
                ui.showAttached(instance)
            }
            return LaunchOutcome.Attached
        }
        orphanReconciler.run()
        val controller = InstanceLifecycle.Controller(store, clock)
        var running = false
        var launchGeneration: String? = null
        var acceleratorCoordinator: DualLaneCoordinator? = null
        try {
            DistroResourceRequirements.requireBootRam(instance.distro().id(), instance.hardwareConfig().ramMegabytes())
            launchGeneration = InstanceLaunchGuard.begin(instance.id())
            if (launchGeneration == null) {
                ui.runOnUi {
                    ui.showLaunchBusyStatus()
                    ui.resetCreateButtons()
                }
                return LaunchOutcome.Busy
            }
            InstanceLaunchSequence.beforeLaunch(controller, instance)
            running = true
            val distroTitle = instance.distro().id().replaceFirstChar { it.uppercase() } + " " + instance.distro().version()
            val badge = performanceBadge.current()
            settings.setLastActiveInstanceId(instance.id())
            foreground.start(instance.id(), distroTitle, badge, settings.batteryPolicy(), launchGeneration)
            val rootfsFileName = DistroCatalogRegistry.getInstance().get(instance.distro().id())?.rootfsFileName()
                ?: if (instance.distro().id() == "alpine") "alpine-minirootfs-3.24.1-aarch64.tar.gz"
                else "${instance.distro().id()}-rootfs.tar.xz"
            val mirrorConfigFile = filesDir.resolve("instances").resolve("${instance.id()}.mirror")
            val firstbootFile = filesDir.resolve("instances").resolve("${instance.id()}.firstboot")
            val mirrorConfigName = DistroMirrorProvisioner.getConfigFileName(instance.distro().id())
            val mirrorBytes = if (mirrorConfigFile.exists()) Files.readAllBytes(mirrorConfigFile.toPath()) else null
            val firstbootBytes = if (firstbootFile.exists()) {
                Files.readAllBytes(firstbootFile.toPath())
            } else FirstBootProvisionPipeline.generateScriptForDistro(instance.distro().id())
                .toByteArray(Charsets.UTF_8)

            val provisionPayload = FirstBootProvisionPipeline.ProvisionPayload(
                mirrorConfigName,
                mirrorBytes,
                firstbootBytes
            )

            val parameters = InstanceStrategyLauncher.LaunchParameters(
                instance.id(),
                instance.bridgeToken(),
                rootfsFileName,
                instance.rootVolume().toString(),
                filesDir.resolve("runtime").resolve(instance.id()).absolutePath,
                filesDir.resolve("distro-inputs").absolutePath,
                instance.guestPassword(),
                instance.hardwareConfig(),
                provisionPayload,
                instance.hostname(),
                instance.defaultUser(),
                instance.hasSudo()
            )
            val strategyLauncher = InstanceStrategyLauncher(
                avfLaunch,
                qemuLaunch,
            ) { failure ->
                AvfDegradationLog(
                    filesDir.resolve("avf-degradations.txt").toPath(),
                ).record(failure)
            }
            // Runtime switch on the accelerator decision point: off keeps
            // today's launch path silently (no toast, no fact reads).
            // The build flag stays a hard gate so a build without the
            // accelerator components never assembles the lane. The ride-along
            // UML face consumes this exact same double-gate value below.
            val laneGate = settings.isAcceleratorLaneEnabled() && BuildConfig.ACCELERATOR_LANE_ENABLED
            val dispatchResult = AcceleratorLaunchDispatcher.dispatch(
                laneGate,
                parameters,
                { params -> qemuLaunch.launch(params) },
                AcceleratorLaunchDispatcher.AccelBackends(
                    customVm = { customVmBackend.create(instance.id()) },
                    pvmPayload = { pvmPayloadBackend.create(instance.id()) },
                ),
                factsSource.create(),
                { ExecutionSessionRegistry.get(instance.id()) },
                AcceleratorLaunchDispatcher.GUEST_COMPILER_TRIPLE,
                laneClock,
                laneEventSink,
            ) { strategyLauncher.launch(parameters, preferAvf.asBoolean) }
            acceleratorCoordinator = dispatchResult.coordinator
            if (dispatchResult.coordinator != null) {
                diagnostics.logAcceleratorSnapshot(
                    "backend=${dispatchResult.backend} ${dispatchResult.decisionJson}",
                )
                // PVM-09: the declared compiler identity next to the
                // decision. The decision JSON stays a stable contract, so
                // this observability lives in the log; the handshake-
                // measured version joins when compile work lands in
                // DV-PVM-02b. Flag-on only: this read must not happen
                // while the lane is off.
                val payload = payloadManifestSource.read()
                diagnostics.logAcceleratorSnapshot(
                    if (payload.isPresent) {
                        val manifest = payload.get()
                        "payload-compiler present=true" +
                            " version=${manifest.compilerVersion()}" +
                            " triple=${manifest.targetTriple()}"
                    } else {
                        "payload-compiler present=false"
                    },
                )
                armRideAlongUmlFace(instance.id(), laneGate, dispatchResult)
            }
            val outcome = dispatchResult.outcome
            val session = outcome.session
            val sessionGeneration = ExecutionSessionRegistry.generation(instance.id(), session)
                ?: throw IOException("运行会话已变化")
            Thread {
                try {
                    val exit = InstanceLaunchSequence.awaitGuestExit(controller, instance.id(), session) {
                        foreground.stop(instance.id(), sessionGeneration)
                    }
                    // The guest session is over (or no longer ours): the
                    // accelerator lane must not outlive the launch that
                    // armed it. A WAIT_FAILED keeps both lanes untouched.
                    if (exit.status() != InstanceLaunchSequence.ExitStatus.WAIT_FAILED) {
                        acceleratorCoordinator?.close()
                        umlFaceLane.disarm(instance.id())
                    }
                    if (exit.status() != InstanceLaunchSequence.ExitStatus.STALE) ui.runOnUi {
                        if (!InstanceLaunchGuard.isLatest(instance.id(), sessionGeneration)) return@runOnUi
                        ui.showStatus(
                            when (exit.status()) {
                                InstanceLaunchSequence.ExitStatus.NORMAL -> copy.guestExitedStatus()
                                InstanceLaunchSequence.ExitStatus.CRASHED ->
                                    "实例异常退出（退出码 ${exit.exitCode()}），可查看诊断并冷恢复"
                                InstanceLaunchSequence.ExitStatus.STOP_UNCONFIRMED ->
                                    "停止未确认：运行时未在时限内停止，实例仍保留，请检查运行日志"
                                else -> "无法确认虚拟机退出，已保留运行会话，请检查运行状态"
                            }
                        )
                        ui.resetCreateButtons()
                        ui.showInstances()
                    }
                } catch (failure: Exception) {
                    // The watcher only throws after the guest was observed
                    // exiting, so the QEMU runtime is already gone; the
                    // accelerator lane must not survive it unattended.
                    acceleratorCoordinator?.close()
                    umlFaceLane.disarm(instance.id())
                    ui.runOnUi {
                        if (!InstanceLaunchGuard.isLatest(instance.id(), sessionGeneration)) return@runOnUi
                        ui.showStatus(copy.detailMessage(failure))
                    }
                }
            }.apply { isDaemon = true }.start()
            foreground.start(instance.id(), distroTitle,
                if (outcome.hardwareAccelerated()) "AVF 硬件虚拟化" else "QEMU 软件虚拟化",
                settings.batteryPolicy(), sessionGeneration)
            ui.runOnUi {
                if (!InstanceLaunchGuard.isLatest(instance.id(), sessionGeneration)
                    || ExecutionSessionRegistry.get(instance.id()) !== session) return@runOnUi
                if (outcome.hardwareAccelerated()) {
                    ui.showStatus(copy.launchStartedHardwareStatus())
                } else if (outcome.degradationReason != null) {
                    ui.showStatus(copy.launchDegradedToSoftwareStatus(outcome.degradationReason))
                }
                ui.showLaunchSuccess(launchNavigation(instance))
            }
            return LaunchOutcome.Launched(launchNavigation(instance), outcome.hardwareAccelerated(), outcome.degradationReason)
        } catch (failure: Throwable) {
            val failedGeneration = launchGeneration
            // A failed launch may have left a runtime whose stop was not
            // confirmed; keep RUNNING metadata and the foreground binding in
            // that case instead of pretending the instance crashed.
            acceleratorCoordinator?.close()
            umlFaceLane.disarm(instance.id())
            val cleanupResult = cleanupBridge.takeLastCleanupResult(instance.id())
            try {
                InstanceLaunchSequence.afterLaunchFailure(
                    controller, instance.id(), failedGeneration, running, cleanupResult
                ) {
                    foreground.stop(instance.id(), failedGeneration)
                }
            } catch (_: Exception) {
                // A metadata/observer failure is not evidence of VM death; retain its owner.
            }
            val message = copy.detailMessage(failure)
            ui.runOnUi {
                if (failedGeneration != null && !InstanceLaunchGuard.isLatest(instance.id(), failedGeneration)) return@runOnUi
                ui.surfaceLaunchFailure(message)
                diagnostics.logLaunchFailure(failure.javaClass.simpleName)
                ui.resetCreateButtons()
                ui.showInstances()
            }
            return LaunchOutcome.Failed(failure)
        }
    }

    /**
     * The ride-along UML face window (Lane A, 2026-11-13): a successful
     * flag-on dispatch arms the face through the assembly slot with the same
     * double-gate value the dispatch consumed, then constructs the fabric
     * orchestration over it — the three-face ledger snapshot is logged once,
     * and the orchestrator is published to the lane (Gate 1 compile bridge:
     * the submitter dispatches through it until disarm; a no-op on a flag-off
     * build). No dispatch loop runs here — work enters only through the
     * submitter's listener, which nothing connects to until the compile
     * bridge's guest side is up (Gate 2). A refused arm
     * (GATE_DISABLED / MISSING_ASSET / SPAWN_FAILED / backend declined) is an
     * honest log line with width 0 and zero side effects; the disarm rides
     * the accelerator coordinator's three close points below.
     */
    private fun armRideAlongUmlFace(
        instanceId: String,
        laneGate: Boolean,
        dispatchResult: AcceleratorLaunchDispatcher.Dispatch,
    ) {
        val assemblyResult = dispatchResult.assemblyResult
        val umlFace = umlFaceLane.arm(instanceId, laneGate)
        if (umlFace == null || assemblyResult == null) {
            return
        }
        AcceleratorLaneAssembly.fabricOrchestrator(
            laneGate,
            assemblyResult,
            dispatchResult.qemuFaceWidth,
            dispatchResult.nativeFaceWidth,
            umlFace,
            emptyMap(),
        ).also {
            // Gate 1 (compile bridge): the lane's submitter dispatches through
            // this launch's orchestrator until disarm — a no-op on a flag-off
            // build or a lane without the compile bridge.
            umlFaceLane.bindFabric(it)
            val widths = FabricOrchestrator.ledgerBackedWidths(
                assemblyResult.coordinator().ledger(),
                dispatchResult.qemuFaceWidth,
                dispatchResult.nativeFaceWidth,
                umlFace.widthSupplier(),
            ).budgetWidths()
            diagnostics.logAcceleratorSnapshot(
                "fabric orchestrator constructed (no submitter yet): " +
                    "host-native=${widths[FabricWidthDecision.Face.HOST_NATIVE]}" +
                    " uml=${widths[FabricWidthDecision.Face.UML]}" +
                    " qemu=${widths[FabricWidthDecision.Face.QEMU_TCG]}" +
                    " sum=${widths.values.sum()}" +
                    " ledger-budget=${assemblyResult.coordinator().ledger().budget().cpuCores()}",
            )
        }
    }

    private fun launchNavigation(instance: InstanceLifecycle.Instance) = LaunchNavigation(
        bridgeToken = instance.bridgeToken(),
        instanceId = instance.id(),
        hostname = instance.hostname(),
        defaultUser = instance.defaultUser(),
    )
}
