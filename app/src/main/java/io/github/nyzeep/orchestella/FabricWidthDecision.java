package io.github.nyzeep.orchestella;

import java.util.Map;
import java.util.Objects;

/**
 * C1 fabric dispatch-width and vCPU accounting decision, landed as the fixed
 * orchestration semantics (not a tunable knob).
 *
 * <p>Two rules from the measured performance plan
 * ({@code docs/research/2026-11-05-performance-optimization-plan.zh-CN.md}
 * section C1, adopted from the 2026-11-07 device-round report §4.1 answers to
 * W-FAB-07/08):
 *
 * <ol>
 *   <li><strong>The QEMU-TCG engine face is accounted at
 *   {@value #QEMU_TCG_ACCOUNTED_VCPUS} vCPU, not at the guest's {@code -smp N}
 *   topology.</strong> Measured basis: the phone-host QEMU MTTCG scaling curve
 *   peaks at 2 vCPU (S(2)=1.60, E(2)=0.80, n=10, confirmed on two devices);
 *   S(4)=1.42 misses the {@code S(4)≥2.0 ∧ E(4)≥0.60} criterion and S(8)=1.12
 *   is worse than S(4) (backfire clause), with a median 92% translation tax.
 *   vCPUs beyond 2 add reservation weight without adding effective parallelism,
 *   so the lane's concurrency budget (the {@code LaneRequest.cpuCores}
 *   semantics) is clamped to the effective width. The launch topology itself is
 *   untouched — QemuTuning defaults stay as shipped.</li>
 *   <li><strong>Wide-parallel work (parallelism above
 *   {@value #QEMU_TCG_ACCOUNTED_VCPUS}) is never dispatched to the QEMU
 *   face</strong>, even when it is the only available face; the dispatch-width
 *   priority is {@code HOST_NATIVE > UML > QEMU_TCG}. Pushing splittable
 *   make-shaped work into the 92%-tax face wastes wall clock that the
 *   native/UML faces would have completed 2-6x faster (same-window readings,
 *   cross-fixture comparisons prohibited). The UML face is not wired into the
 *   product orchestration yet (B2); this decision is the contract it will
 *   consume.</li>
 * </ol>
 *
 * <p>This class is pure decision logic: fixed codes, no I/O, no device data.
 * The accounting rule is applied at the one call site that converts hardware
 * facts into a lane budget ({@link AcceleratorLaunchDispatcher}); the assembly
 * and the ledger keep taking requests as given.
 */
public final class FabricWidthDecision {
    /**
     * The measured effective width of the QEMU-TCG face on a phone host: both
     * the accounting cap for the lane's concurrency budget and the widest
     * parallelism this face may be dispatched.
     */
    public static final int QEMU_TCG_ACCOUNTED_VCPUS = 2;

    private FabricWidthDecision() {
    }

    /**
     * The QEMU lane's vCPU budget: the guest topology clamped to the measured
     * effective TCG width. A 1-vCPU guest stays at 1 (accounting never exceeds
     * the real topology); wider guests are accounted at
     * {@value #QEMU_TCG_ACCOUNTED_VCPUS}.
     *
     * @throws IllegalArgumentException when {@code hardwareCpuCores <= 0}
     */
    public static int qemuLaneBudgetCores(int hardwareCpuCores) {
        if (hardwareCpuCores <= 0) {
            throw new IllegalArgumentException(
                    "hardwareCpuCores must be positive, got: " + hardwareCpuCores);
        }
        return Math.min(hardwareCpuCores, QEMU_TCG_ACCOUNTED_VCPUS);
    }

    /**
     * A dispatch face of the compile fabric. Declaration order is the
     * dispatch-width priority from the decision: native, then UML, then
     * QEMU-TCG.
     */
    public enum Face {
        HOST_NATIVE, UML, QEMU_TCG
    }

    /** Fixed refusal codes; a refusal never carries free-form text. */
    public enum RejectCode {
        /** Non-positive parallelism; the request itself is malformed. */
        INVALID_REQUEST,
        /**
         * Wide-parallel work with no face left after the QEMU face was
         * excluded by the width decision — the QEMU face was available and the
         * decision refused it on purpose. Distinct from
         * {@link #NO_FACE_AVAILABLE} so the orchestration log shows a policy
         * refusal, not an outage.
         */
        WIDE_PARALLEL_NO_FACE,
        /** No face available at all. */
        NO_FACE_AVAILABLE
    }

    /**
     * One dispatch verdict, in the {@code Decision(allowed, rejectCode)} shape
     * the repo's admission seams use: an admission names the face, grants a
     * positive width and carries a null code; a refusal names the fixed code,
     * carries no face and grants zero.
     */
    public record Dispatch(Face face, int width, RejectCode rejectCode) {
        public Dispatch {
            if (face == null) {
                if (rejectCode == null) {
                    throw new IllegalArgumentException("a refusal needs a reject code");
                }
                if (width != 0) {
                    throw new IllegalArgumentException("a refusal grants no width");
                }
            } else {
                if (rejectCode != null) {
                    throw new IllegalArgumentException("an admission carries no reject code");
                }
                if (width <= 0) {
                    throw new IllegalArgumentException("an admission needs a positive width");
                }
            }
        }

        public boolean admitted() {
            return face != null;
        }
    }

    /**
     * Routes one compile work item of the given requested parallelism across
     * the fabric faces. {@code faceWidths} maps each face to its current
     * dispatchable width (a missing or non-positive width means the face is
     * unavailable); the caller derives widths from the ledger, never from this
     * class.
     *
     * <p>Rules, in order:
     * <ol>
     *   <li>{@code requestedParallelism <= 0} is {@link RejectCode#INVALID_REQUEST}.</li>
     *   <li>Wide-parallel work ({@code > QEMU_TCG_ACCOUNTED_VCPUS}) never sees
     *   the {@code QEMU_TCG} face.</li>
     *   <li>Among the remaining faces the first available one in priority
     *   order (native &gt; UML &gt; QEMU-TCG) wins, granted
     *   {@code min(parallelism, face width)}.</li>
     *   <li>Nothing available: {@link RejectCode#WIDE_PARALLEL_NO_FACE} when
     *   the QEMU face was available and excluded by rule 2,
     *   {@link RejectCode#NO_FACE_AVAILABLE} otherwise.</li>
     * </ol>
     */
    public static Dispatch route(int requestedParallelism, Map<Face, Integer> faceWidths) {
        Objects.requireNonNull(faceWidths, "faceWidths");
        if (requestedParallelism <= 0) {
            return new Dispatch(null, 0, RejectCode.INVALID_REQUEST);
        }
        boolean wide = requestedParallelism > QEMU_TCG_ACCOUNTED_VCPUS;
        if (wide && width(faceWidths, Face.QEMU_TCG) > 0) {
            // The QEMU face could serve narrow work but is excluded here on
            // purpose; if nothing else exists the log must say so.
            for (Face face : Face.values()) {
                if (face == Face.QEMU_TCG) {
                    continue;
                }
                int width = width(faceWidths, face);
                if (width > 0) {
                    return new Dispatch(face, Math.min(requestedParallelism, width), null);
                }
            }
            return new Dispatch(null, 0, RejectCode.WIDE_PARALLEL_NO_FACE);
        }
        for (Face face : Face.values()) {
            int width = width(faceWidths, face);
            if (width > 0) {
                return new Dispatch(face, Math.min(requestedParallelism, width), null);
            }
        }
        return new Dispatch(null, 0, RejectCode.NO_FACE_AVAILABLE);
    }

    private static int width(Map<Face, Integer> faceWidths, Face face) {
        Integer width = faceWidths.get(face);
        return width == null ? 0 : width;
    }
}
