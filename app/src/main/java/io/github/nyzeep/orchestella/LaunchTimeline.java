package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Records the ordered stages of one QEMU launch and appends one structured
 * JSONL record to {@code <runtimeDirectory>/launch-timeline.jsonl} when the
 * launch is finished (normally at stop confirmation). If that record carried an
 * UNCONFIRMED stop, one compensating record is appended when a retry settles.
 *
 * <p>The recorder is pure JVM: the clock and the line sink are injected, so the
 * ordering, elapsed-time and failure contracts are testable without Android.
 * It never accepts a bridge token, guest password, keystroke, user file or the
 * QEMU command line, and a failed write is swallowed after a diagnostic is
 * kept in memory so launch never fails because telemetry failed.
 */
public final class LaunchTimeline {
    public static final String FILE_NAME = "launch-timeline.jsonl";
    /**
     * Schema 2 adds {@code stopAttempt}/{@code stopFinal}: an UNCONFIRMED stop
     * is written as a diagnostic record, and a later confirmed retry appends a
     * compensating record the consumer can identify as the final one.
     */
    private static final int SCHEMA_VERSION = 2;

    /** Ordered launch stages; JSON renders them in this declaration order. */
    public enum Stage {
        LAUNCH_START,
        ASSETS_VERIFIED,
        INITRAMFS_BUILT,
        GATEWAY_READY,
        QEMU_STARTED,
        SESSION_REGISTERED,
        STOP_REQUESTED,
        STOP_CONFIRMED
    }

    /** Testable one-record destination. Implementations must append one complete line. */
    @FunctionalInterface
    public interface LineSink {
        void writeLine(String line) throws IOException;
    }

    private final String instanceId;
    private final String distroId;
    private final int guestMemoryMb;
    private final int vcpuCount;
    private final Clock clock;
    private final LineSink sink;
    private final Map<Stage, Long> elapsedMillis = new EnumMap<>(Stage.class);
    private final List<String> diagnostics = new ArrayList<>();

    private Instant startedAt;
    private Long rootfsBytes;
    private Long initramfsBytes;
    private StopResult stopResult;
    private int stopAttempt;
    private boolean finished;
    /** Result carried by the last successfully written line, if any. */
    private StopResult lastWrittenStopResult;
    /** A failed append is never retried; diagnostics keep the reason. */
    private boolean writeFailed;

    public LaunchTimeline(String instanceId, String distroId, int guestMemoryMb, int vcpuCount,
            Clock clock, LineSink sink) {
        this.instanceId = instanceId == null || instanceId.isEmpty() ? "unknown" : instanceId;
        this.distroId = distroId == null || distroId.isEmpty() ? "unknown" : distroId;
        this.guestMemoryMb = guestMemoryMb;
        this.vcpuCount = vcpuCount;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Opens the production recorder appending to the instance runtime directory. */
    public static LaunchTimeline open(Path runtimeDirectory, String instanceId, String distroId,
            int guestMemoryMb, int vcpuCount) {
        Objects.requireNonNull(runtimeDirectory, "runtimeDirectory");
        Path file = runtimeDirectory.resolve(FILE_NAME);
        return new LaunchTimeline(instanceId, distroId, guestMemoryMb, vcpuCount, Clock.systemUTC(),
                line -> {
                    Files.createDirectories(runtimeDirectory);
                    // Files.write keeps this usable on API 26 without desugaring java.nio.file additions.
                    Files.write(file, (line + "\n").getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                });
    }

    /**
     * Records a stage at most once. A stage marked before {@link Stage#LAUNCH_START}
     * is dropped with a diagnostic because no launch-relative elapsed time exists.
     */
    public synchronized void mark(Stage stage) {
        if (stage == null || elapsedMillis.containsKey(stage)) return;
        Instant now = clock.instant();
        if (stage == Stage.LAUNCH_START) {
            startedAt = now;
            elapsedMillis.put(stage, 0L);
            return;
        }
        if (startedAt == null) {
            diagnostics.add("mark-before-launch-start: " + stage.name());
            return;
        }
        long elapsed = Math.max(0L, Duration.between(startedAt, now).toMillis());
        elapsedMillis.put(stage, elapsed);
    }

    /** Records the embedded-initramfs stage with the rootfs input size in bytes. */
    public synchronized void markInitramfsBuilt(long rootfsSizeBytes) {
        markInitramfsBuilt(rootfsSizeBytes, -1L);
    }

    /**
     * Records the embedded-initramfs stage with both the rootfs input size and the
     * produced initramfs size. A negative initramfs size keeps the field unknown.
     */
    public synchronized void markInitramfsBuilt(long rootfsSizeBytes, long initramfsSizeBytes) {
        mark(Stage.INITRAMFS_BUILT);
        if (elapsedMillis.containsKey(Stage.INITRAMFS_BUILT) && rootfsBytes == null) {
            rootfsBytes = Math.max(0L, rootfsSizeBytes);
            if (initramfsSizeBytes >= 0L) {
                initramfsBytes = initramfsSizeBytes;
            }
        }
    }

    /**
     * Records one stop attempt. The stage keeps its first elapsed time while
     * {@code stopAttempt} counts every observed result, so a retry can be
     * distinguished from a duplicate observation.
     */
    public synchronized void markStopConfirmed(StopResult result) {
        mark(Stage.STOP_CONFIRMED);
        if (result != null && elapsedMillis.containsKey(Stage.STOP_CONFIRMED)) {
            stopAttempt++;
            stopResult = result;
        }
    }

    /**
     * Writes the launch record once. When the first record ended in
     * {@link StopResult#UNCONFIRMED} and a later attempt settled as CONFIRMED or
     * ALREADY_STOPPED, exactly one compensating record is appended with the
     * final result. Duplicate finishes never append. Returns false when nothing
     * was written or the sink failed; failures are diagnostic only and are
     * never retried.
     */
    public synchronized boolean finish() {
        boolean compensating = finished
                && lastWrittenStopResult == StopResult.UNCONFIRMED
                && (stopResult == StopResult.CONFIRMED || stopResult == StopResult.ALREADY_STOPPED);
        if (finished && !compensating) return false;
        if (writeFailed) return false;
        finished = true;
        final String line;
        try {
            line = toJsonLine();
        } catch (RuntimeException failure) {
            writeFailed = true;
            addWriteFailure(failure);
            return false;
        }
        try {
            sink.writeLine(line);
            lastWrittenStopResult = stopResult;
            return true;
        } catch (IOException | RuntimeException failure) {
            writeFailed = true;
            addWriteFailure(failure);
            return false;
        }
    }

    private void addWriteFailure(Exception failure) {
        String message = failure.getMessage();
        diagnostics.add("timeline-write-failed: " + failure.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message));
    }

    /** Canonical stage order; absent stages are simply omitted. */
    public synchronized List<Stage> stages() {
        return List.copyOf(elapsedMillis.keySet());
    }

    /** Elapsed milliseconds since launch start, or -1 when the stage is absent. */
    public synchronized long elapsedMs(Stage stage) {
        Long elapsed = elapsedMillis.get(stage);
        return elapsed == null ? -1L : elapsed;
    }

    public synchronized StopResult stopResult() {
        return stopResult;
    }

    /** Non-fatal recorder diagnostics, including dropped pre-start marks and write failures. */
    public synchronized List<String> diagnostics() {
        return Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    /** Serializes the current record as one JSON object without a trailing newline. */
    public synchronized String toJsonLine() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"schema\":").append(SCHEMA_VERSION)
                .append(",\"instanceId\":\"").append(escape(instanceId))
                .append("\",\"distroId\":\"").append(escape(distroId))
                .append("\",\"guestMemoryMb\":").append(guestMemoryMb)
                .append(",\"vcpuCount\":").append(vcpuCount)
                .append(",\"stages\":[");
        boolean first = true;
        for (Stage stage : Stage.values()) {
            Long elapsed = elapsedMillis.get(stage);
            if (elapsed == null) continue;
            if (!first) json.append(',');
            json.append("{\"stage\":\"").append(stage.name())
                    .append("\",\"elapsedMs\":").append(elapsed).append('}');
            first = false;
        }
        json.append("],\"rootfsBytes\":")
                .append(rootfsBytes == null ? "null" : rootfsBytes.toString())
                .append(",\"initramfsBytes\":")
                .append(initramfsBytes == null ? "null" : initramfsBytes.toString())
                .append(",\"credentialsOnly\":")
                .append(rootfsBytes == null ? "null" : Boolean.toString(rootfsBytes == 0L))
                .append(",\"stopResult\":")
                .append(stopResult == null ? "null" : "\"" + stopResult.name() + "\"")
                .append(",\"stopAttempt\":").append(stopAttempt)
                .append(",\"stopFinal\":")
                .append(stopResult == StopResult.CONFIRMED || stopResult == StopResult.ALREADY_STOPPED)
                .append(",\"diagnostics\":[");
        for (int i = 0; i < diagnostics.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(escape(diagnostics.get(i))).append('"');
        }
        json.append("]}");
        return json.toString();
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
