package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorDecision;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorMode;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorPolicy;
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
        return new QemuLaneRuntime(session, probe::isReady);
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
