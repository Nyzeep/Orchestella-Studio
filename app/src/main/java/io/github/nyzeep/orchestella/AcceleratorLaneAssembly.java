package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorDecision;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorMode;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorPolicy;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.CompileBackend;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStarter;
import java.util.Objects;
import java.util.Optional;

/**
 * AX-08: the one place where the accelerator lane is assembled for production.
 *
 * <p>This is the only {@code new DualLaneCoordinator(...)} outside tests, and
 * the only place that decides whether the ACCEL lane may exist at all. Three
 * rules are enforced here, not by call sites:
 *
 * <ol>
 *   <li>The feature flag is off by default. While off nothing reads platform
 *       facts, the accelerator lane is never started, and no accelerator budget
 *       is reserved — the run is exactly today's QEMU-only run.</li>
 *   <li>When the flag is on, {@link AcceleratorPolicy} still decides. Only
 *       {@link AcceleratorMode#QEMU_PLUS_ACCEL} arms the lane; every
 *       {@code QEMU_ONLY} verdict leaves the budget unavailable so a stray
 *       start attempt is rejected instead of silently running unmeasured.</li>
 *   <li>The protected pool shares this coordinator's ledger through
 *       {@link DualLaneCoordinator#admission()}; its capacity stays zero unless
 *       a caller deliberately builds a different pool config.</li>
 * </ol>
 *
 * <p>The assembly does not launch anything by itself: {@link Inputs} carries
 * the two {@link LaneStarter}s supplied by the existing launch chain, so no VM
 * is created here and nothing about default product behaviour changes.
 */
public final class AcceleratorLaneAssembly {
    private AcceleratorLaneAssembly() {
    }

    /** Bounded assembly configuration; the flag defaults to off. */
    public record Config(
            boolean acceleratorLaneEnabled,
            String requiredCompilerTriple,
            long qemuReadyTimeoutMs,
            long stopGraceMs) {

        public Config {
            requiredCompilerTriple = requiredCompilerTriple == null
                    ? ""
                    : requiredCompilerTriple.trim();
            if (qemuReadyTimeoutMs < 0L || stopGraceMs < 0L) {
                throw new IllegalArgumentException("timeouts must be non-negative");
            }
        }

        /** The shipped default: accelerator lane off, no triple required yet. */
        public static Config disabled() {
            return new Config(false, "", 30_000L, 2_000L);
        }

        /**
         * Reads the build-time flag. Off unless the build explicitly opted in,
         * and the required triple is empty until a caller supplies the QEMU
         * guest's compiler identity, which refuses the lane rather than
         * guessing.
         */
        public static Config currentBuild() {
            return new Config(BuildConfig.ACCELERATOR_LANE_ENABLED, "", 30_000L, 2_000L);
        }

        public Config withRequiredCompilerTriple(String triple) {
            return new Config(acceleratorLaneEnabled, triple, qemuReadyTimeoutMs, stopGraceMs);
        }

        DualLaneCoordinator.Config coordinatorConfig() {
            return new DualLaneCoordinator.Config(qemuReadyTimeoutMs, stopGraceMs);
        }
    }

    /** Everything the assembly needs; all VM creation stays outside. */
    public record Inputs(
            LaneBudget hostBudget,
            LaneRequest qemuRequest,
            LaneRequest accelRequest,
            LaneStarter qemuStarter,
            LaneStarter accelStarter,
            LaneClock clock,
            LaneEventSink events,
            AcceleratorFactProvider facts) {

        public Inputs {
            Objects.requireNonNull(hostBudget, "hostBudget");
            Objects.requireNonNull(qemuRequest, "qemuRequest");
            Objects.requireNonNull(accelRequest, "accelRequest");
            Objects.requireNonNull(qemuStarter, "qemuStarter");
            Objects.requireNonNull(accelStarter, "accelStarter");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(events, "events");
            Objects.requireNonNull(facts, "facts");
        }
    }

    /**
     * The assembled pair. {@code decision} is empty when the flag kept the
     * accelerator lane out of the picture entirely; that is not a policy
     * verdict and carries no reason code.
     */
    public record Result(DualLaneCoordinator coordinator, Optional<AcceleratorDecision> decision) {
        public Result {
            Objects.requireNonNull(coordinator, "coordinator");
            Objects.requireNonNull(decision, "decision");
        }

        public boolean acceleratorArmed() {
            return decision
                    .map(verdict -> verdict.mode() == AcceleratorMode.QEMU_PLUS_ACCEL)
                    .orElse(false);
        }

        /**
         * The backend the verdict selected, or {@link CompileBackend#NONE}
         * when the lane is off or the decision refused every backend. The
         * dispatcher reads this to choose which starter to run; it never
         * re-derives the verdict from the raw facts.
         */
        public CompileBackend decisionBackend() {
            return decision.map(AcceleratorDecision::backend).orElse(CompileBackend.NONE);
        }

        /** Empty string while the flag is off; the decision JSON otherwise. */
        public String decisionJson() {
            return decision.map(AcceleratorDecision::toJson).orElse("");
        }
    }

    /**
     * Builds the coordinator. With the flag off this reads no platform facts and
     * forces the accelerator budget to unavailable, so the ACCEL reservation is
     * rejected before any starter runs.
     */
    public static Result assemble(Config config, Inputs inputs) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(inputs, "inputs");

        if (!config.acceleratorLaneEnabled()) {
            return new Result(coordinator(withoutAccelerator(inputs.hostBudget()), config, inputs),
                    Optional.empty());
        }

        AcceleratorFacts facts;
        try {
            facts = inputs.facts().read();
        } catch (RuntimeException failure) {
            // An unreadable fact source must not crash a launch; it refuses the lane.
            facts = AcceleratorFacts.unknown();
        }
        AcceleratorDecision decision =
                AcceleratorPolicy.decide(facts, config.requiredCompilerTriple());
        boolean armed = decision.mode() == AcceleratorMode.QEMU_PLUS_ACCEL;
        return new Result(
                coordinator(withAccelerator(inputs.hostBudget(), armed), config, inputs),
                Optional.of(decision));
    }

    /** QEMU lane adapter with an explicit readiness probe. */
    public static LaneRuntime qemuLane(RuntimeOwnedSession session, QemuReadinessProbe probe) {
        Objects.requireNonNull(probe, "probe");
        return new QemuLaneRuntime(session, probe);
    }

    /** Accelerator lane adapter over the Fast Lane session that owns its stop. */
    public static LaneRuntime accelLane(FastLaneSession session) {
        return new ProtectedLaneRuntime(session);
    }

    /**
     * PL-01 wiring: the pool shares the coordinator's ledger and keeps the
     * shipped capacity of zero, so an unconfigured pool only ever refuses.
     */
    public static ProtectedWarmPool protectedPool(
            FastLaneVirtualMachineApi api,
            ProtectedWarmPool.SpecFactory specFactory,
            Result result) {
        Objects.requireNonNull(result, "result");
        return new ProtectedWarmPool(
                api,
                specFactory,
                ProtectedWarmPool.Config.defaults(),
                java.time.Clock.systemUTC(),
                result.coordinator().admission());
    }

    /**
     * PVM-06 wiring: the compile worker pool shares this coordinator's ledger
     * through {@link WorkerPool.Admission#ledgerBacked} (read-only, the pool
     * never reserves in it) so growth is bounded by the same budget the lanes
     * were admitted under. The capacity comes from the caller's config and the
     * shipped default stays zero — an unconfigured pool only ever refuses
     * deterministically, which is decision map §8.4 unchanged. Dispatch goes
     * through {@link WorkerPoolDistccDispatcher}, the pool-backed
     * {@code DistccTcpVsockBridge.ChannelFactory}.
     */
    public static ProtectedWorkerPool workerPool(
            FastLaneVirtualMachineApi api,
            ProtectedWorkerPool.WorkerSpecFactory specFactory,
            Result result,
            ProtectedWorkerPool.Config config) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(config, "config");
        return new ProtectedWorkerPool(
                api,
                specFactory,
                config,
                java.time.Clock.systemUTC(),
                WorkerPool.Admission.ledgerBacked(result.coordinator().ledger()));
    }

    /**
     * B2 wiring: the fabric orchestration that consumes the C1 dispatch
     * contract over this coordinator's ledger. The face widths are derived by
     * {@link FabricOrchestrator#ledgerBackedWidths} — the QEMU face is the C1
     * accounting value the launch reserved ({@code AcceleratorLaunchDispatcher}
     * built the qemuRequest with {@code FabricWidthDecision.qemuLaneBudgetCores}),
     * the native face is the accelerator lane's own reservation plus the
     * ledger headroom the pool's {@code ledgerBacked} admission allows, and
     * the UML face width comes from the UML face wiring (zero until C2 lands
     * the {@code ncpus=} budget), so the face widths always sum to the
     * ledger's core budget. The gate is the same double gate the launch
     * consumed; while it is off every dispatch is a zero-side-effect refusal
     * and today's run is unchanged. The face workers are injected: no product
     * submitter feeds the orchestration yet (the native face's listener is
     * recorded DV-PVM-02b work, the UML face's TCP access point is
     * fabric-route doc D-1, ACR-bound).
     */
    public static FabricOrchestrator fabricOrchestrator(
            boolean fabricDispatchEnabled,
            Result result,
            int qemuFaceWidth,
            int nativeFaceWidth,
            java.util.function.IntSupplier umlFaceWidth,
            java.util.Map<FabricWidthDecision.Face, FabricOrchestrator.FaceWorker> workers) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(umlFaceWidth, "umlFaceWidth");
        Objects.requireNonNull(workers, "workers");
        return new FabricOrchestrator(
                fabricDispatchEnabled,
                FabricOrchestrator.ledgerBackedWidths(
                        result.coordinator().ledger(),
                        qemuFaceWidth,
                        nativeFaceWidth,
                        umlFaceWidth),
                workers);
    }

    /**
     * C lane (width thermostat, item 4): the fabric orchestration wired over a
     * live {@link UmlFaceLauncher} — the UML face's width supply and its
     * worker come from the face itself, never from a caller's constant. The
     * face's {@link UmlFaceLauncher#widthSupplier()} feeds the slot's
     * {@code umlFaceWidth}: the kernel's effective SMP width while the face is
     * running, healthy and channel-wired ({@code ncpus=} ≥ 2 takes N, the
     * host-1 shape takes 1 — the device round's verdict, supplied per device
     * capability via {@link UmlFaceLauncher.LaunchParams#forDeviceCapability}),
     * and 0 while the face is down. {@link FabricOrchestrator#ledgerBackedWidths}
     * then clamps that supply to the ledger headroom, so the thermostat stays
     * the ledger: a wide supply cannot oversubscribe, and the sum of the three
     * face widths still equals the ledger's core budget (the invariant the
     * locked fabric tests hold).
     *
     * <p>The UML worker is the face's own {@link UmlFaceLauncher#faceWorker()}
     * (its fixed {@code uml-face-not-running} / {@code uml-face-compile-channel-unwired}
     * details ride the orchestrator's eviction contract); a caller's map that
     * already carries a {@link FabricWidthDecision.Face#UML} entry is refused —
     * the UML worker comes from the face, not from the caller.
     */
    public static FabricOrchestrator fabricOrchestrator(
            boolean fabricDispatchEnabled,
            Result result,
            int qemuFaceWidth,
            int nativeFaceWidth,
            UmlFaceLauncher umlFace,
            java.util.Map<FabricWidthDecision.Face, FabricOrchestrator.FaceWorker> otherWorkers) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(umlFace, "umlFace");
        Objects.requireNonNull(otherWorkers, "otherWorkers");
        java.util.Map<FabricWidthDecision.Face, FabricOrchestrator.FaceWorker> workers =
                new java.util.EnumMap<>(FabricWidthDecision.Face.class);
        workers.putAll(otherWorkers);
        if (workers.containsKey(FabricWidthDecision.Face.UML)) {
            throw new IllegalArgumentException(
                    "the UML face worker comes from the launcher, not the caller's map");
        }
        workers.put(FabricWidthDecision.Face.UML, umlFace.faceWorker());
        return fabricOrchestrator(
                fabricDispatchEnabled, result, qemuFaceWidth, nativeFaceWidth,
                umlFace.widthSupplier(), workers);
    }

    /**
     * B2 follow-up ② wiring: the UML compile-worker face's product lifecycle
     * manager. The face runs the UML kernel exactly the way the research
     * prototype proved it on device ({@code research/uml-runner}) — the
     * kernel image exec'd from the app's own {@code nativeLibraryDir}, the
     * hostfs rootfs channel, the {@code ncpus=} SMP knob — and its two seams
     * plug straight into {@link #fabricOrchestrator}: the face's
     * {@link UmlFaceLauncher#widthSupplier()} is the slot's
     * {@code umlFaceWidth} (the kernel's effective SMP width, clamped by the
     * slot to the ledger headroom, 0 while the face is down), and
     * {@link UmlFaceLauncher#faceWorker()} goes into the workers map under
     * {@link FabricWidthDecision.Face#UML}.
     *
     * <p>Rules this slot enforces, not call sites:
     * <ol>
     *   <li>The gate is the same double gate the launch consumed (build flag
     *   + user setting); while it is off, {@link UmlFaceLauncher#start} is a
     *   zero-side-effect fixed-code refusal — no asset is read, no kernel is
     *   spawned, no keep-alive is touched. No new flag exists.</li>
     *   <li>{@code keepAlive} is required and never a silent no-op: the
     *   foreground service + wake lock pair is the measured anti-freeze
     *   prerequisite (the device round froze a background face's whole
     *   process group; the protected form held 60 minutes of residency).</li>
     *   <li>Missing kernel assets degrade to the fixed {@code MISSING_ASSET}
     *   code with width 0 — the face never fakes a running state. The
     *   checksum-level reconciliation of the placed assets (the device
     *   window's manifest vs. what actually landed) is
     *   {@link UmlKernelAssets#verify}; the start gate itself keeps the
     *   historical existence precheck unchanged.</li>
     * </ol>
     *
     * <p>{@code launchParams} is the device window's tuning interface
     * (guest memory, the {@code ncpus} width, verbatim extra kernel args).
     * The launch shape follows the build flag (ACR 2026-11-12 D4 wiring):
     * {@link UmlFaceLauncher#createWithCurrentFastProfile} reads {@code
     * BuildConfig.UML_FAST_PROFILE}, so a build that opted in
     * ({@code -Porchestella.uml.fastProfile=true}) assembles the §4
     * built-in-initramfs fast face — the ACR D4 default width for that shape
     * is 1 while the face is up — and the flag-off build assembles the exact
     * byte-identical runner shape as before. The compile channel stays
     * unwired here (fabric-route doc D-1 is ACR-bound): a flag-off face
     * reads width 0 until the channel lands, and a fast face's unroutability
     * rides the worker's fixed channel-unwired refusal. The face's start/stop
     * remains the wiring owner's lifecycle decision (after {@code startAll},
     * like the other face reservations); nothing is spawned by assembly alone.
     */
    public static UmlFaceLauncher umlFace(
            boolean umlFaceEnabled,
            UmlFaceLauncher.Environment environment,
            UmlFaceLauncher.LaunchParams launchParams,
            UmlFaceLauncher.KeepAlive keepAlive) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(launchParams, "launchParams");
        Objects.requireNonNull(keepAlive, "keepAlive");
        return UmlFaceLauncher.createWithCurrentFastProfile(
                umlFaceEnabled, environment, launchParams, keepAlive);
    }

    private static DualLaneCoordinator coordinator(
            LaneBudget budget, Config config, Inputs inputs) {
        return new DualLaneCoordinator(
                budget,
                inputs.qemuRequest(),
                inputs.accelRequest(),
                inputs.qemuStarter(),
                inputs.accelStarter(),
                config.coordinatorConfig(),
                inputs.clock(),
                inputs.events());
    }

    /** The assembly, not the caller, owns whether the accelerator may run. */
    private static LaneBudget withoutAccelerator(LaneBudget budget) {
        return withAccelerator(budget, false);
    }

    private static LaneBudget withAccelerator(LaneBudget budget, boolean available) {
        return new LaneBudget(
                budget.cpuCores(), budget.ramMiB(), budget.diskGiB(), available);
    }
}
