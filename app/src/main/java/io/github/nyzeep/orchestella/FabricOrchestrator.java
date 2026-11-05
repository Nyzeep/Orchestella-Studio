package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudgetLedger;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * B2: the fabric orchestration that consumes the C1 dispatch contract
 * ({@link FabricWidthDecision#route}) — compile work is routed across the
 * fabric faces so that wide-parallel (splittable make-shaped) work lands on
 * the native/UML faces and the QEMU-TCG face serves at most
 * {@value FabricWidthDecision#QEMU_TCG_ACCOUNTED_VCPUS}-way parallel work,
 * exactly the measured decision (the 92%-translation-tax face is never fed
 * wide work, and the dispatch-width priority is native &gt; UML &gt; QEMU).
 *
 * <p>The decision class is pure routing; this class is the orchestration
 * around it:
 *
 * <ul>
 *   <li><strong>Ledger-derived widths</strong> — the per-face widths handed to
 *   {@code route} are derived from the coordinator's {@link LaneBudgetLedger},
 *   never invented here: the QEMU face is the C1 accounting value the launch
 *   reserved (already clamped to the effective TCG width), the native face
 *   grows into the ledger headroom exactly as the pool's
 *   {@code Admission.ledgerBacked} allows, and the UML face width is supplied
 *   by the UML face wiring (C2 {@code ncpus=} budget; zero until wired). The
 *   sum of the face widths therefore always equals the ledger's core budget —
 *   the D-3 rule "task concurrency cap = the ledger's core sum" holds by
 *   construction, and an over-declaring UML supplier is clamped instead of
 *   oversubscribing the ledger.</li>
 *   <li><strong>Width accounting</strong> — every admitted grant is held as an
 *   outstanding width on its face until the work returns (or fails), so
 *   concurrent dispatches can never exceed a face's ledger-derived width even
 *   mid-flight. The accounting is orchestration-level; the ledger itself is
 *   not mutated — the orchestrator consumes the same read-only views the
 *   pool's admission consumes.</li>
 *   <li><strong>Feature gate</strong> — the caller passes the same double gate
 *   the launch consumed (build flag + user setting). While it is off,
 *   {@link #dispatch} answers the fixed {@link Code#GATE_DISABLED} code
 *   without reading the width source or touching any worker — zero side
 *   effects, the run stays today's run.</li>
 *   <li><strong>Per-face failure and eviction</strong> (fabric-route doc §4
 *   D-3) — a worker that fails or disconnects mid-dispatch evicts its face
 *   until an explicit rebuild path re-arms it; later dispatches route to the
 *   remaining faces, and a wide-parallel refusal then carries the C1 policy
 *   code {@code WIDE_PARALLEL_NO_FACE} (a deliberate policy refusal the
 *   caller answers with its existing local fallback, not an outage). The
 *   per-worker local-fallback thresholds themselves stay with the distcc
 *   client (the UML face is tuned separately); this class only removes the
 *   face from routing.</li>
 * </ul>
 *
 * <p><b>Wiring state (B2).</b> The orchestration is the consumption-ready seam
 * over the C1 decision; the face workers are injected and no product submitter
 * feeds it yet. The native face's connection-level dispatch exists
 * ({@code WorkerPoolDistccDispatcher}, the pool-backed bridge
 * {@code ChannelFactory}); the product TCP listener feeding it is recorded
 * DV-PVM-02b work, and the UML face's TCP worker access point is fabric-route
 * doc D-1 (a bridge change that goes through the ACR before it is built).
 * Nothing here changes today's single-task path: narrow (≤2) work routes
 * through {@code route} verbatim, and with the shipped zero-headroom ledger
 * envelope every verdict is exactly the C1-era routing.
 */
public final class FabricOrchestrator {

    /**
     * Orchestration-level fixed codes. Route-level refusals are not restated
     * here — they travel inside the {@link FabricWidthDecision.Dispatch}
     * verdict with their own fixed codes.
     */
    public enum Code {
        /**
         * The feature gate is off: nothing is read, nothing is granted, no
         * worker is touched. Distinct from every route refusal so the
         * orchestration log reads "gate off", never "no face existed".
         */
        GATE_DISABLED,
        /**
         * The admitted face's worker failed mid-dispatch; the grant was
         * released and the face was evicted until re-armed. The caller owns
         * the retry (its local fallback); the next dispatch routes to the
         * remaining faces.
         */
        FACE_FAILED
    }

    /**
     * One dispatch outcome, carrying exactly one of a route verdict or an
     * orchestration-level code. An admitted or route-refused dispatch carries
     * the {@link FabricWidthDecision.Dispatch} verbatim (its {@code face}
     * names the winner, its {@code rejectCode} names a policy refusal or an
     * outage); {@link Code} speaks only for what the orchestration itself
     * decided outside the route contract.
     */
    public record Outcome(FabricWidthDecision.Dispatch route, Code code) {
        public Outcome {
            if ((route == null) == (code == null)) {
                throw new IllegalArgumentException(
                        "an outcome carries exactly one of a route verdict or an orchestration code");
            }
        }

        /** True only when the route admitted the work and it was executed. */
        public boolean admitted() {
            return route != null && route.admitted();
        }
    }

    /**
     * One face's worker. The orchestrator hands it the admitted face, the
     * granted width and the opaque work item; how the face executes them (a
     * pool lease per connection, a UML TCP channel, guest-local compilation)
     * is the face's own seam. Implementations must be safe to call while the
     * orchestrator serves other dispatches.
     */
    @FunctionalInterface
    public interface FaceWorker {
        void execute(FabricWidthDecision.Face face, int grantedWidth, WorkItem item)
                throws FaceException;
    }

    /** Opaque work identity; the orchestrator routes it, never inspects it. */
    public interface WorkItem {
    }

    /**
     * A face worker failure. Any failure shape (channel refused, worker gone,
     * guest dead) collapses into this one checked type: the orchestrator's
     * answer is always the same — release the grant, evict the face, report
     * {@link Code#FACE_FAILED}.
     */
    public static final class FaceException extends Exception {
        private static final long serialVersionUID = 1L;

        public FaceException(String message) {
            super(message);
        }

        public FaceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The per-face budget widths, refreshed per dispatch and derived by the
     * caller from the ledger — the orchestrator never invents widths. A
     * missing or non-positive entry means the face has no budget this tick.
     */
    @FunctionalInterface
    public interface FaceWidthSource {
        Map<FabricWidthDecision.Face, Integer> budgetWidths();
    }

    /**
     * The production width derivation: the fabric's face widths read straight
     * off the coordinator's ledger so their sum is the ledger's core budget
     * (fabric-route doc §4 D-3). The QEMU face is the C1 accounting value the
     * launch reserved — {@code AcceleratorLaunchDispatcher} built it with
     * {@link FabricWidthDecision#qemuLaneBudgetCores} — and the native face is
     * the accelerator lane's own reservation (the payload worker's budget);
     * both are already inside the ledger snapshot, so pass the reservations,
     * never re-derivations. The QEMU face is clamped to the decision's
     * accounted width here as well, so it can never be dispatched wider than
     * the measured effective TCG width even if a caller over-declares. The
     * native face additionally grows into the ledger headroom
     * ({@code budget.cpuCores() - snapshot().cpuCores()}), the exact bound the
     * pool's {@code Admission.ledgerBacked} enforces, minus whatever the UML
     * face takes first; the UML face width is supplied by the UML face wiring
     * (C2 derives it from the ledger budget) and is clamped to the headroom so
     * an over-declaring supplier cannot oversubscribe the ledger.
     *
     * @param ledger the coordinator's ledger, read-only
     * @param qemuFaceWidth the QEMU lane's reserved concurrency budget (the
     *        C1 accounting value already counted in the ledger snapshot)
     * @param nativeFaceWidth the accelerator lane's reserved concurrency
     *        budget (the payload worker's, already counted in the snapshot)
     * @param umlFaceWidth the UML face's budget; zero until the UML face is
     *        wired (C2)
     */
    public static FaceWidthSource ledgerBackedWidths(
            LaneBudgetLedger ledger,
            int qemuFaceWidth,
            int nativeFaceWidth,
            IntSupplier umlFaceWidth) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(umlFaceWidth, "umlFaceWidth");
        int budgetCores = ledger.budget().cpuCores();
        if (qemuFaceWidth < 0 || qemuFaceWidth > budgetCores) {
            throw new IllegalArgumentException(
                    "qemuFaceWidth must be within the ledger budget, got: " + qemuFaceWidth);
        }
        if (nativeFaceWidth < 0 || nativeFaceWidth > budgetCores) {
            throw new IllegalArgumentException(
                    "nativeFaceWidth must be within the ledger budget, got: " + nativeFaceWidth);
        }
        return () -> {
            Map<FabricWidthDecision.Face, Integer> widths =
                    new EnumMap<>(FabricWidthDecision.Face.class);
            int headroom = Math.max(0, budgetCores - ledger.snapshot().cpuCores());
            int uml = Math.max(0, Math.min(umlFaceWidth.getAsInt(), headroom));
            widths.put(FabricWidthDecision.Face.UML, uml);
            widths.put(
                    FabricWidthDecision.Face.HOST_NATIVE, nativeFaceWidth + (headroom - uml));
            widths.put(
                    FabricWidthDecision.Face.QEMU_TCG,
                    Math.min(qemuFaceWidth, FabricWidthDecision.QEMU_TCG_ACCOUNTED_VCPUS));
            return widths;
        };
    }

    private final boolean fabricDispatchEnabled;
    private final FaceWidthSource widthSource;
    private final Map<FabricWidthDecision.Face, FaceWorker> workers;
    private final Map<FabricWidthDecision.Face, Integer> outstanding = new EnumMap<>(FabricWidthDecision.Face.class);
    private final java.util.Set<FabricWidthDecision.Face> evicted =
            java.util.EnumSet.noneOf(FabricWidthDecision.Face.class);

    /**
     * @param fabricDispatchEnabled the existing double gate (build flag + user
     *        setting) as the launch consumed it; off means every dispatch is a
     *        zero-side-effect {@link Code#GATE_DISABLED} answer
     * @param widthSource the ledger-derived face widths
     * @param workers the wired face workers; a face without a worker is
     *        unavailable regardless of its budget width
     */
    public FabricOrchestrator(
            boolean fabricDispatchEnabled,
            FaceWidthSource widthSource,
            Map<FabricWidthDecision.Face, FaceWorker> workers) {
        this.fabricDispatchEnabled = fabricDispatchEnabled;
        this.widthSource = Objects.requireNonNull(widthSource, "widthSource");
        Map<FabricWidthDecision.Face, FaceWorker> copy =
                new EnumMap<>(FabricWidthDecision.Face.class);
        copy.putAll(Objects.requireNonNull(workers, "workers"));
        this.workers = Collections.unmodifiableMap(copy);
    }

    /**
     * Routes one work item of the given requested parallelism across the
     * fabric. The route verdict is computed against the <em>available</em>
     * widths (budget minus outstanding, zero for unwired or evicted faces) and
     * an admitted grant is executed on its face's worker outside the
     * orchestration lock, so concurrent dispatches stay width-bounded without
     * serializing each other's work. A worker failure releases the grant,
     * evicts the face and answers {@link Code#FACE_FAILED}; the caller owns
     * the retry and its local fallback.
     */
    public Outcome dispatch(WorkItem item, int requestedParallelism) {
        Objects.requireNonNull(item, "item");
        FabricWidthDecision.Dispatch verdict;
        FaceWorker worker;
        synchronized (this) {
            if (!fabricDispatchEnabled) {
                return new Outcome(null, Code.GATE_DISABLED);
            }
            verdict = FabricWidthDecision.route(requestedParallelism, availableWidths());
            if (!verdict.admitted()) {
                return new Outcome(verdict, null);
            }
            // availableWidths() grants positive width only to a wired,
            // non-evicted face, so the worker is present for every admission.
            worker = workers.get(verdict.face());
            outstanding.merge(verdict.face(), verdict.width(), Integer::sum);
        }
        try {
            worker.execute(verdict.face(), verdict.width(), item);
        } catch (FaceException | RuntimeException failure) {
            synchronized (this) {
                evicted.add(verdict.face());
                release(verdict.face(), verdict.width());
            }
            return new Outcome(null, Code.FACE_FAILED);
        }
        synchronized (this) {
            release(verdict.face(), verdict.width());
        }
        return new Outcome(verdict, null);
    }

    /**
     * Removes a face from routing until it is re-armed: the rebuild path
     * (pool evict-and-rebuild, UML restart) calls this when it observes a
     * worker disconnect. In-flight grants on the face finish and release
     * normally; later dispatches route to the remaining faces.
     */
    public synchronized void evict(FabricWidthDecision.Face face) {
        evicted.add(Objects.requireNonNull(face, "face"));
    }

    /** Puts a rebuilt face back into routing. */
    public synchronized void arm(FabricWidthDecision.Face face) {
        evicted.remove(Objects.requireNonNull(face, "face"));
    }

    private EnumMap<FabricWidthDecision.Face, Integer> availableWidths() {
        Map<FabricWidthDecision.Face, Integer> budget = widthSource.budgetWidths();
        EnumMap<FabricWidthDecision.Face, Integer> available =
                new EnumMap<>(FabricWidthDecision.Face.class);
        for (FabricWidthDecision.Face face : FabricWidthDecision.Face.values()) {
            Integer budgetWidth = budget.get(face);
            int width = budgetWidth == null ? 0 : budgetWidth;
            if (workers.get(face) == null || evicted.contains(face)) {
                available.put(face, 0);
            } else {
                Integer granted = outstanding.get(face);
                available.put(face, Math.max(0, width - (granted == null ? 0 : granted)));
            }
        }
        return available;
    }

    private void release(FabricWidthDecision.Face face, int width) {
        Integer granted = outstanding.get(face);
        int remaining = (granted == null ? 0 : granted) - width;
        if (remaining > 0) {
            outstanding.put(face, remaining);
        } else {
            outstanding.remove(face);
        }
    }
}
