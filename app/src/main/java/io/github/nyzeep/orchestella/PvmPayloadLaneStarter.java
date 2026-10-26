package io.github.nyzeep.orchestella;

import android.content.Context;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneDv01Contract;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.ReflectiveFastLaneApi;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.PvmCompilerManifest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * PVM_PAYLOAD lane starter: one protected Microdroid VM whose payload is the
 * PVM compiler described by {@link PvmCompilerManifest}.
 *
 * <p>The launch is the Fast Lane shape, not the AVF custom-image shape: the
 * payload binary, memory size and compiler identity all come from the
 * manifest, the topology is pinned to {@code ONE_CPU} (the manifest's
 * {@code vcpu_topology} is {@code one_cpu} by contract) and debug is always
 * off.
 *
 * <p><strong>Readiness, stated explicitly.</strong> {@code LaneStarter}
 * requires {@code start()} to return without waiting for readiness, and the
 * platform owns the signal: the AVF callback {@code onPayloadReady} flips
 * {@code FastLaneHandle#payloadReady()}, which {@code
 * ProtectedLaneRuntime#isReady()} exposes. The production gate for AXP1 is
 * therefore the coordinator's {@code DualLaneCoordinator#awaitAcceleratorReady(long)}
 * (AX-01): compile work calls it before connecting to the payload, a timeout
 * means "build locally", and QEMU is never touched either way.
 * {@link #awaitReady(LaneRuntime, long)} is the standalone bounded wait for
 * callers that hold a bare {@code LaneRuntime} without the coordinator
 * (device-validation drivers and tools); it is not a second production gate.
 *
 * <p>Stop is not implemented here. {@link ProtectedLaneRuntime#stop(long)}
 * already delegates to {@link FastLaneSession#stopRuntime(long)}, so this
 * starter's only job on the stop path is to have built that session;
 * {@link #stopGraceMs()} is the grace this lane was configured with, for the
 * owner that performs the stop.
 *
 * <p>Failures are fixed codes only: {@link #PAYLOAD_UNAVAILABLE_NO_PROTECTED}
 * when the platform cannot give us a protected VM,
 * {@link #PAYLOAD_COMPILER_MISMATCH} when the requested compiler identity is
 * not the payload's, and {@link #PAYLOAD_LOAD_FAILED} for every load-side
 * failure (verify, create, config, vsock, stop, or an unexpected runtime
 * failure). No exception text, path or token leaves this class.
 */
public final class PvmPayloadLaneStarter {
    /** Fixed code when protected virtualisation is not available to this app. */
    public static final String PAYLOAD_UNAVAILABLE_NO_PROTECTED = "PAYLOAD_UNAVAILABLE_NO_PROTECTED";

    /** Fixed code for every load-side failure, including a readiness timeout. */
    public static final String PAYLOAD_LOAD_FAILED = "PAYLOAD_LOAD_FAILED";

    /** Fixed code when the caller requires a different compiler identity. */
    public static final String PAYLOAD_COMPILER_MISMATCH = "PAYLOAD_COMPILER_MISMATCH";

    /** Ready, or one "load failed" verdict: a timeout and a dead VM are the same. */
    public enum Readiness {
        READY,
        LOAD_FAILED
    }

    /**
     * Production wiring: the manifest comes from the APK asset (the same
     * reader the facts use) and the VM comes from the reflective protected
     * API. Empty means this build ships no payload compiler — the same
     * conservative answer the facts give, so the policy has already refused
     * the lane before a caller could start it.
     */
    public static Optional<PvmPayloadLaneStarter> createIfInstalled(
            Context context,
            String instanceId,
            String requiredTargetTriple,
            long readyTimeoutMs,
            long stopGraceMs) {
        Objects.requireNonNull(context, "context");
        Context application = context.getApplicationContext();
        return AndroidAcceleratorFactProvider.readPayloadManifest(application)
                .map(manifest -> new PvmPayloadLaneStarter(
                        instanceId,
                        manifest,
                        requiredTargetTriple,
                        new ReflectiveFastLaneApi(application, application.getClassLoader()),
                        readyTimeoutMs,
                        stopGraceMs));
    }

    /**
     * Smallest honest encrypted-storage size for this lane: 1 MiB.
     *
     * <p>The compiler payload reads its libraries from the APK and writes no
     * persistent state, so it needs no workspace volume. The platform still
     * requires a positive size for the encrypted store, so the smallest
     * defensible value is declared rather than the 256 MiB the QA payload uses
     * for its scratch space.
     */
    private static final long MINIMAL_ENCRYPTED_STORAGE_BYTES = 1024L * 1024L;

    /** Bounded readiness poll; small enough to notice readiness promptly. */
    private static final long READY_POLL_MS = 25L;

    private final String instanceId;
    private final PvmCompilerManifest manifest;
    private final String requiredTargetTriple;
    private final FastLaneVirtualMachineApi api;
    private final long readyTimeoutMs;
    private final long stopGraceMs;

    /**
     * @param instanceId platform VM name; the platform allows 64 characters
     * @param manifest the compiler payload's build-time declaration
     * @param requiredTargetTriple empty means "do not compare triples"
     * @param readyTimeoutMs default bound for {@link #awaitReady(LaneRuntime)}
     * @param stopGraceMs grace the lane owner passes to {@code stop}
     */
    public PvmPayloadLaneStarter(
            String instanceId,
            PvmCompilerManifest manifest,
            String requiredTargetTriple,
            FastLaneVirtualMachineApi api,
            long readyTimeoutMs,
            long stopGraceMs) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.requiredTargetTriple = Objects.requireNonNull(requiredTargetTriple, "requiredTargetTriple");
        this.api = Objects.requireNonNull(api, "api");
        if (readyTimeoutMs < 0L || stopGraceMs < 0L) {
            throw new IllegalArgumentException("timeouts must be non-negative");
        }
        this.readyTimeoutMs = readyTimeoutMs;
        this.stopGraceMs = stopGraceMs;
    }

    /**
     * Creates and runs the protected payload VM. Never waits for readiness;
     * see the class comment for why the wait lives in {@link #awaitReady}.
     */
    public LaneRuntime start() throws LaneStartException {
        // Triple preflight before any platform call: a payload that cannot
        // serve this QEMU guest is refused without spending a VM.
        if (!requiredTargetTriple.isEmpty()
                && !requiredTargetTriple.equals(manifest.targetTriple())) {
            throw new LaneStartException(PAYLOAD_COMPILER_MISMATCH);
        }
        FastLaneLaunchSpec spec = launchSpec();
        try {
            FastLaneHandle handle = api.start(spec);
            return new ProtectedLaneRuntime(new FastLaneSession(api, handle, spec));
        } catch (FastLaneException failure) {
            throw new LaneStartException(mapStartFailure(failure.reason()));
        } catch (RuntimeException failure) {
            throw new LaneStartException(PAYLOAD_LOAD_FAILED);
        }
    }

    /** Bounded wait using the timeout this starter was configured with. */
    public Readiness awaitReady(LaneRuntime lane) {
        return awaitReady(lane, readyTimeoutMs);
    }

    /**
     * Standalone bounded wait for {@code onPayloadReady}; the production gate
     * is {@code DualLaneCoordinator#awaitAcceleratorReady(long)} (see the
     * class comment). Returns {@link Readiness#READY}
     * only when the lane reports ready while still alive; a dead VM, an
     * exhausted budget or an interrupt is {@link Readiness#LOAD_FAILED}.
     * Never sleeps longer than the remaining budget. Uses the system clock
     * rather than an injected {@code LaneClock}: this helper serves tools and
     * drivers, not the coordinator's injectable-clock world.
     */
    public Readiness awaitReady(LaneRuntime lane, long timeoutMs) {
        Objects.requireNonNull(lane, "lane");
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        while (true) {
            if (!lane.isAlive()) {
                return Readiness.LOAD_FAILED;
            }
            if (lane.isReady()) {
                return Readiness.READY;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return Readiness.LOAD_FAILED;
            }
            try {
                Thread.sleep(Math.max(1L,
                        Math.min(READY_POLL_MS, TimeUnit.NANOSECONDS.toMillis(remaining))));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Readiness.LOAD_FAILED;
            }
        }
    }

    /** Grace this lane's owner should pass to {@link LaneRuntime#stop(long)}. */
    public long stopGraceMs() {
        return stopGraceMs;
    }

    /**
     * The four vsock endpoints come from the payload's own contract. Only the
     * distcc port carries the compiler service; control, terminal and proxy are
     * declared because the guest {@code PayloadConfig} requires four distinct
     * ports, and this lane opens none of them during start.
     */
    private FastLaneLaunchSpec launchSpec() {
        return new FastLaneLaunchSpec(
                instanceId,
                manifest.payloadBinary(),
                manifest.memoryMiB(),
                FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                MINIMAL_ENCRYPTED_STORAGE_BYTES,
                FastLaneLaunchSpec.DebugLevel.NONE,
                FastLaneDv01Contract.DEFAULT_CONTROL_PORT,
                FastLaneDv01Contract.DEFAULT_TERMINAL_PORT,
                FastLaneDv01Contract.DEFAULT_PROXY_PORT,
                FastLaneDv01Contract.DEFAULT_DISTCC_PORT,
                // The compiler payload carries no FLN1 toolbox. Saying so with
                // an empty digest is honest; a placeholder hash would claim a
                // toolbox that does not exist.
                "");
    }

    /** Fixed-code mapping; an unknown reason is a load failure, never a guess. */
    private static String mapStartFailure(String reason) {
        return switch (reason) {
            case FastLaneException.PROTECTED_UNAVAILABLE,
                    FastLaneException.HIDDEN_API_BLOCKED,
                    FastLaneException.PERMISSION_DENIED -> PAYLOAD_UNAVAILABLE_NO_PROTECTED;
            case FastLaneException.PAYLOAD_VERIFY_FAILED,
                    FastLaneException.VM_CREATE_FAILED,
                    FastLaneException.CONFIG_INVALID,
                    FastLaneException.VSOCK_FAILED,
                    FastLaneException.STOP_UNCONFIRMED -> PAYLOAD_LOAD_FAILED;
            default -> PAYLOAD_LOAD_FAILED;
        };
    }
}
