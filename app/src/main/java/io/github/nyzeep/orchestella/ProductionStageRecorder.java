package io.github.nyzeep.orchestella;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Production stage event component (ticket 27): records one real launch's
 * progress over the same 16-gate stage vocabulary as the offline recorder
 * ({@code tools/device-validation/avf_stage_recorder.py}, ticket 22), with a
 * versioned, bounded event schema and an explicit retirement state machine.
 *
 * <p>Boundaries carried over from ticket 22 and made explicit for production:
 *
 * <ul>
 *   <li>One launch attempt owns one recorder. Every call carries the caller's
 *       {@link Identity}; a mismatched claim (for example a late watcher from a
 *       superseded generation) is rejected before any clock read or sink write
 *       and never mutates the current trace.</li>
 *   <li>Terminal states are final. A retired trace cannot be revived, so
 *       stop intents, owner exits and resource releases that arrive late stay
 *       out of a trace that already ended.</li>
 *   <li>The single time base is the injected monotonic nanosecond clock
 *       (production wires {@code SystemClock.elapsedRealtimeNanos()}). Clock
 *       values are only ever observed, never backfilled: each event carries the
 *       timestamp read at mark time, and a backwards clock retires the trace.
 *       Wall clocks, JVM clocks and guest uptime are never mixed in.</li>
 *   <li>Gate order is recorded as observed. Unlike the offline chain, the real
 *       production pipeline does not order preflight before inputs (the AVF
 *       admission may run before or after the shared bootstrap depending on
 *       strategy), so this component does not reject out-of-chain order; it
 *       records the true partial order and reports unobserved gates as missing
 *       instead of inventing them.</li>
 *   <li>No free-text payload exists in the API: stages, routes, stop facts and
 *       failures are fixed enums and the identity is charset-bounded, so raw
 *       exceptions, paths, tokens, keystrokes and device identifiers cannot
 *       enter an event.</li>
 *   <li>Sink I/O failure and capacity overflow retire the trace explicitly
 *       ({@code SINK_FAILED} / {@code CAPTURE_LIMIT}) with no retry, queue or
 *       worker thread; a failed sink never blocks its owner and never fakes a
 *       ready state.</li>
 * </ul>
 *
 * <p>The component is pure JVM: the clock and the sink are injected, so every
 * ordering, failure and bound contract is testable without Android. A disabled
 * recorder retains nothing, reads no clock and touches no sink.
 */
public final class ProductionStageRecorder {
    /** Version of this event schema; bumped only by an incompatible change. */
    public static final int SCHEMA_VERSION = 1;
    /** Schema identifier written into every line and into the sink file header. */
    public static final String SCHEMA_NAME = "orchestella_stage_trace";
    /** Provenance of production traces; synthetic offline traces stay separate. */
    public static final String PROVENANCE = "production";
    /** Maximum gate/observation events per trace (same magnitude as ticket 22's 16). */
    public static final int MAX_EVENTS = 16;
    /** Maximum serialized bytes per trace, including its end line (ticket 22 alignment). */
    public static final int MAX_TRACE_BYTES = 32768;
    /** Clock name recorded when production wires the Android monotonic clock. */
    public static final String CLOCK_ANDROID_ELAPSED_REALTIME_NS = "android_elapsed_realtime_ns";

    /** The 16 launch gates, named exactly as the ticket 22 offline chain. */
    public enum Stage {
        LAUNCH_REQUESTED("launch_requested"),
        PREFLIGHT_BEGIN("preflight_begin"),
        PREFLIGHT_END("preflight_end"),
        INPUTS_BEGIN("inputs_begin"),
        INPUTS_VERIFIED("inputs_verified"),
        MANAGER_BEGIN("manager_begin"),
        VM_CREATED("vm_created"),
        RUN_BEGIN("run_begin"),
        RUN_RETURNED("run_returned"),
        VM_RUNNING_OBSERVED("vm_running_observed"),
        VSOCK_CONNECTED("vsock_connected"),
        AGENT_AUTHENTICATED("agent_authenticated"),
        GUEST_COMMAND_OK("guest_command_ok"),
        SERVICES_READY("services_ready"),
        STOP_REQUESTED("stop_requested"),
        STOP_COMPLETED("stop_completed");

        private final String wire;

        Stage(String wire) { this.wire = wire; }

        /** Stable wire name shared with the offline recorder and validators. */
        public String wireName() { return wire; }

        static Stage fromWireName(String value) {
            for (Stage stage : values()) {
                if (stage.wire.equals(value)) return stage;
            }
            return null;
        }
    }

    /** Wiring label of a gate's source; a check, never a proof of authenticity. */
    public enum Route {
        OWNER("owner"),
        AUTHENTICATED_SINGLE_READER("authenticated_single_reader");

        private final String wire;

        Route(String wire) { this.wire = wire; }

        public String wireName() { return wire; }
    }

    /** Independent stop facts; none of them alone signs a normal stop. */
    public enum StopFact {
        GUEST_SHUTDOWN_ACKNOWLEDGED("guest_shutdown_acknowledged"),
        OWNED_VM_STOPPED("owned_vm_stopped"),
        OWNED_RESOURCES_RELEASED("owned_resources_released");

        private final String wire;

        StopFact(String wire) { this.wire = wire; }

        public String wireName() { return wire; }
    }

    /** Bounded failure vocabulary; no exception text or detail is carried. */
    public enum Failure {
        PERMISSION_DENIED,
        API_BLOCKED,
        CREATE_FAILED,
        RUN_FAILED,
        READINESS_FAILED,
        GUEST_COMMAND_FAILED,
        STOP_FAILED,
        TIMEOUT,
        UNKNOWN
    }

    /** Trace lifecycle states; everything after {@link #ACTIVE} is terminal. */
    public enum State { DISABLED, ACTIVE, FAILED, CANCELLED, FALLBACK, ENDED }

    /**
     * Launch identity bound to one real launch attempt: the owning instance,
     * the launch-scoped session, the launch-guard generation and the actual
     * execution strategy. Fields are charset-bounded app-internal aliases;
     * device serials, model names and user data are invalid by construction.
     */
    public static final class Identity {
        public final String ownerAlias;
        public final String sessionId;
        public final String generation;
        public final String vmAlias;

        private Identity(String ownerAlias, String sessionId, String generation, String vmAlias) {
            this.ownerAlias = ownerAlias;
            this.sessionId = sessionId;
            this.generation = generation;
            this.vmAlias = vmAlias;
        }

        /**
         * Returns an identity when every field is a bounded app-internal alias
         * ({@code [a-z0-9-]}), or {@code null} when any field is missing or
         * malformed. Callers must treat {@code null} as "cannot bind this
         * launch honestly" and record nothing rather than record a fake
         * identity.
         */
        public static Identity of(String ownerAlias, String sessionId, String generation,
                String vmAlias) {
            if (!isValidField(ownerAlias, 3) || !isValidField(sessionId, 3)
                    || !isValidField(generation, 1) || !isValidField(vmAlias, 3)) {
                return null;
            }
            return new Identity(ownerAlias, sessionId, generation, vmAlias);
        }

        private static boolean isValidField(String value, int minLength) {
            if (value == null || value.length() < minLength || value.length() > 64) return false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) return false;
            }
            return true;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Identity)) return false;
            Identity that = (Identity) other;
            return ownerAlias.equals(that.ownerAlias) && sessionId.equals(that.sessionId)
                    && generation.equals(that.generation) && vmAlias.equals(that.vmAlias);
        }

        @Override public int hashCode() {
            return Objects.hash(ownerAlias, sessionId, generation, vmAlias);
        }

        private String toJson() {
            return "{\"owner_alias\":\"" + escape(ownerAlias) + "\",\"session_id\":\""
                    + escape(sessionId) + "\",\"generation\":\"" + escape(generation)
                    + "\",\"vm_alias\":\"" + escape(vmAlias) + "\"}";
        }
    }

    /** Testable one-line destination; returns false to signal sink failure. */
    @FunctionalInterface
    public interface LineSink {
        boolean writeLine(String line);
    }

    /** Immutable readout of the trace's honest state for diagnostics. */
    public static final class Snapshot {
        public final State state;
        public final String reason;
        public final int eventCount;
        public final int pendingCount;
        public final long bytesWritten;
        public final Identity identity;
        public final List<String> observedGates;
        public final List<String> missingGates;
        public final List<String> stopFacts;
        public final String lastStage;
        public final String kernelObservation;
        public final String payloadCallback;
        public final int realReceiptCount;
        public final String deviceVerdict;

        Snapshot(State state, String reason, int eventCount, long bytesWritten, Identity identity,
                List<String> observedGates, List<String> missingGates, List<String> stopFacts,
                String lastStage) {
            this.state = state;
            this.reason = reason;
            this.eventCount = eventCount;
            this.pendingCount = 0;
            this.bytesWritten = bytesWritten;
            this.identity = identity;
            this.observedGates = List.copyOf(observedGates);
            this.missingGates = List.copyOf(missingGates);
            this.stopFacts = List.copyOf(stopFacts);
            this.lastStage = lastStage;
            this.kernelObservation = "not_observed";
            this.payloadCallback = "not_applicable";
            this.realReceiptCount = 0;
            this.deviceVerdict = "NOT_DETERMINED";
        }
    }

    private static final Set<Stage> AUTHENTICATED_ONLY_GATES = Set.of(
            Stage.AGENT_AUTHENTICATED, Stage.GUEST_COMMAND_OK, Stage.SERVICES_READY);

    private final Identity identity;
    private final LongSupplier clock;
    private final String clockName;
    private final LineSink sink;
    private final int maxEvents;
    private final int maxTraceBytes;
    private final Set<Stage> observed = new LinkedHashSet<>();
    private final Set<StopFact> stopFacts = new LinkedHashSet<>();
    private State state;
    private String reason = "NONE";
    private int eventCount;
    private long bytesWritten;
    private long lastNanos = -1L;
    private boolean endWritten;

    private ProductionStageRecorder() {
        this.identity = null;
        this.clock = null;
        this.clockName = "none";
        this.sink = null;
        this.maxEvents = 0;
        this.maxTraceBytes = 0;
        this.state = State.DISABLED;
    }

    private ProductionStageRecorder(Identity identity, LongSupplier clock, String clockName,
            LineSink sink, int maxEvents, int maxTraceBytes) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockName = clockName == null || clockName.isEmpty() ? "injected_monotonic_ns" : clockName;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.maxEvents = maxEvents;
        this.maxTraceBytes = maxTraceBytes;
        this.state = State.ACTIVE;
    }

    /** The disabled recorder: nothing retained, nothing observed, nothing written. */
    public static ProductionStageRecorder disabled() {
        return new ProductionStageRecorder();
    }

    /** Enabled recorder with the ticket-22-aligned default bounds (16 events / 32768 bytes). */
    public static ProductionStageRecorder enabled(Identity identity, LongSupplier clock,
            String clockName, LineSink sink) {
        return enabled(identity, clock, clockName, sink, MAX_EVENTS, MAX_TRACE_BYTES);
    }

    /** Enabled recorder with explicit bounds; both must stay within the ticket-22 magnitude. */
    public static ProductionStageRecorder enabled(Identity identity, LongSupplier clock,
            String clockName, LineSink sink, int maxEvents, int maxTraceBytes) {
        if (identity == null || clock == null || sink == null) {
            throw new IllegalArgumentException("enabled recorder requires identity, clock and sink");
        }
        if (maxEvents < 1 || maxEvents > MAX_EVENTS) throw new IllegalArgumentException("maxEvents");
        if (maxTraceBytes < 1 || maxTraceBytes > MAX_TRACE_BYTES) {
            throw new IllegalArgumentException("maxTraceBytes");
        }
        return new ProductionStageRecorder(identity, clock, clockName, sink, maxEvents, maxTraceBytes);
    }

    /** Records one gate on the owner route. Never throws. */
    public String mark(Stage stage, Identity claim) {
        return record(stage, Route.OWNER, claim);
    }

    /** Records one gate on the authenticated single-reader route. Never throws. */
    public String markAuthenticated(Stage stage, Identity claim) {
        return record(stage, Route.AUTHENTICATED_SINGLE_READER, claim);
    }

    private synchronized String record(Stage stage, Route route, Identity claim) {
        if (state == State.DISABLED) return "DISABLED";
        if (state != State.ACTIVE) return "RETIRED";
        if (!claims(claim)) return "IDENTITY_MISMATCH";
        if (stage == null) return retire("STAGE_REJECTED");
        Route expected = AUTHENTICATED_ONLY_GATES.contains(stage)
                ? Route.AUTHENTICATED_SINGLE_READER : Route.OWNER;
        if (route != expected) return retire("ROUTE_REJECTED");
        if (observed.contains(stage)) return "ALREADY_RECORDED";
        if (stage == Stage.STOP_COMPLETED && !stopFacts.containsAll(Set.of(StopFact.values()))) {
            return retire("STOP_UNCONFIRMED");
        }
        return writeEvent(stage.wireName(), "stage_event", route.wireName(), null);
    }

    /**
     * Retires the trace with a real failure as its terminal reason. The last
     * observed gate stays the honest last stage; nothing is filled in.
     */
    public String fail(Failure failure, Identity claim) {
        return terminate(failure, State.FAILED, claim);
    }

    /**
     * Retires the trace because this strategy attempt was abandoned for the
     * other strategy (AVF to QEMU fallback). The mapped failure code is kept as
     * the terminal reason so the fallback's cause stays readable.
     */
    public String fallback(Failure failure, Identity claim) {
        return terminate(failure, State.FALLBACK, claim);
    }

    /** Retires the trace as explicitly cancelled (superseded by a newer generation). */
    public String cancel(Identity claim) {
        return terminate(null, State.CANCELLED, claim);
    }

    private synchronized String terminate(Failure failure, State terminal, Identity claim) {
        if (state == State.DISABLED) return "DISABLED";
        if (state != State.ACTIVE) return "RETIRED";
        if (!claims(claim)) return "IDENTITY_MISMATCH";
        state = terminal;
        reason = failure == null ? terminal == State.CANCELLED ? "CANCELLED" : "NONE" : failure.name();
        // A failed end-line write never overwrites the real terminal reason.
        writeEndLine();
        return reason;
    }

    /** Observes one independent stop fact on the owner route. Never throws. */
    public String observeStop(StopFact fact, Identity claim) {
        return observe(fact, Route.OWNER, claim);
    }

    /** Observes the guest shutdown acknowledgement on the authenticated route. Never throws. */
    public String observeStopAuthenticated(StopFact fact, Identity claim) {
        return observe(fact, Route.AUTHENTICATED_SINGLE_READER, claim);
    }

    private synchronized String observe(StopFact fact, Route route, Identity claim) {
        if (state == State.DISABLED) return "DISABLED";
        if (state != State.ACTIVE) return "RETIRED";
        if (!claims(claim)) return "IDENTITY_MISMATCH";
        if (fact == null) return retire("STOP_UNCONFIRMED");
        Route expected = fact == StopFact.GUEST_SHUTDOWN_ACKNOWLEDGED
                ? Route.AUTHENTICATED_SINGLE_READER : Route.OWNER;
        if (route != expected) return retire("ROUTE_REJECTED");
        if (stopFacts.contains(fact)) return "ALREADY_RECORDED";
        stopFacts.add(fact);
        return "OBSERVED";
    }

    /**
     * Closes the trace with an end line carrying the real observed/missing
     * gates, the observed stop facts and the honest last stage. Idempotent; a
     * second call after any terminal state never rewrites.
     */
    public String finish(Identity claim) {
        synchronized (this) {
            if (state == State.DISABLED) return "DISABLED";
            if (state != State.ACTIVE) return "RETIRED";
            if (!claims(claim)) return "IDENTITY_MISMATCH";
            state = State.ENDED;
            reason = "NONE";
            writeEndLine();
            return "ENDED";
        }
    }

    /** Readout of the current honest state; never throws and carries no payload. */
    public synchronized Snapshot snapshot() {
        List<String> missing = new ArrayList<>();
        for (Stage stage : Stage.values()) {
            if (!observed.contains(stage)) missing.add(stage.wireName());
        }
        List<String> observedList = new ArrayList<>();
        for (Stage stage : observed) observedList.add(stage.wireName());
        List<String> factList = new ArrayList<>();
        for (StopFact fact : stopFacts) factList.add(fact.wireName());
        String lastStage = observedList.isEmpty() ? "not_observed"
                : observedList.get(observedList.size() - 1);
        return new Snapshot(state, reason, eventCount, bytesWritten, identity, observedList,
                missing, factList, lastStage);
    }

    private boolean claims(Identity claim) {
        return claim != null && claim.equals(identity);
    }

    private String retire(String code) {
        state = State.FAILED;
        reason = code;
        return code;
    }

    private void writeEndLine() {
        if (endWritten) return;
        endWritten = true;
        writeEvent(null, "trace_end", null, reason);
    }

    /**
     * Serializes and writes one line. The clock is read only here, once per
     * written line, at observation time — never backfilled. Capacity is checked
     * before the sink; a sink failure or an overflowing trace retires it. A
     * failure while writing the end line keeps the already-set terminal state
     * and reason: the real outcome outranks the write problem.
     */
    private String writeEvent(String stageWire, String kind, String routeWire, String endReason) {
        boolean endLine = stageWire == null;
        long nanos;
        try {
            nanos = clock.getAsLong();
        } catch (RuntimeException | LinkageError failure) {
            return endLine ? "CLOCK_REJECTED" : retire("CLOCK_REJECTED");
        }
        if (nanos < 0L || nanos < lastNanos) {
            return endLine ? "CLOCK_REJECTED" : retire("CLOCK_REJECTED");
        }
        if (kind.equals("stage_event") && eventCount + 1 > maxEvents) {
            return retire("CAPTURE_LIMIT");
        }
        StringBuilder json = new StringBuilder(256);
        json.append("{\"schema_version\":").append(SCHEMA_VERSION)
                .append(",\"schema\":\"").append(SCHEMA_NAME)
                .append("\",\"kind\":\"").append(kind)
                .append("\",\"provenance\":\"").append(PROVENANCE)
                .append("\",\"clock\":\"").append(escape(clockName))
                .append("\",\"identity\":").append(identity.toJson());
        if (stageWire != null) {
            json.append(",\"stage\":\"").append(stageWire)
                    .append("\",\"route\":\"").append(routeWire).append('"');
        } else {
            List<String> observedList = new ArrayList<>();
            for (Stage stage : observed) observedList.add(stage.wireName());
            List<String> missingList = new ArrayList<>();
            for (Stage stage : Stage.values()) {
                if (!observed.contains(stage)) missingList.add(stage.wireName());
            }
            List<String> factList = new ArrayList<>();
            for (StopFact fact : stopFacts) factList.add(fact.wireName());
            json.append(",\"state\":\"").append(state.name())
                    .append("\",\"reason\":\"").append(endReason == null ? "NONE" : endReason)
                    .append("\",\"last_stage\":\"")
                    .append(observedList.isEmpty() ? "not_observed"
                            : observedList.get(observedList.size() - 1))
                    .append("\",\"observed_gates\":").append(toJsonArray(observedList))
                    .append(",\"missing_gates\":").append(toJsonArray(missingList))
                    .append(",\"stop_facts\":").append(toJsonArray(factList));
        }
        json.append(",\"t_ns\":").append(nanos).append('}');
        String line = json.toString();
        int lineBytes = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
        if (bytesWritten + lineBytes > maxTraceBytes) {
            return endLine ? "CAPTURE_LIMIT" : retire("CAPTURE_LIMIT");
        }
        try {
            if (!sink.writeLine(line)) {
                return endLine ? "SINK_FAILED" : retire("SINK_FAILED");
            }
        } catch (RuntimeException | LinkageError failure) {
            return endLine ? "SINK_FAILED" : retire("SINK_FAILED");
        }
        bytesWritten += lineBytes;
        if (!endLine) {
            eventCount++;
            observed.add(Stage.fromWireName(stageWire));
        }
        lastNanos = nanos;
        return "RECORDED";
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(values.get(i))).append('"');
        }
        return json.append(']').toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }
}
