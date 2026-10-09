package io.github.nyzeep.orchestella.runtime.engineavf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Archives the host-side evidence of one AVF session's abnormal end: the
 * observed exit status plus the reflective assembly notes, written to a text
 * file that {@code InstanceLifecycle} can bind as a crash diagnostic and the
 * user can export (ADR-0011: a VMM abnormal exit marks the instance and the
 * diagnostics stay retrievable). The QEMU strategy's equivalent source is the
 * VMM's own stderr; AVF sessions have no VMM log file, so the engine records
 * what it actually observed — nothing more, nothing invented.
 */
public final class AvfSessionDiagnostics {
    private AvfSessionDiagnostics() {}

    /**
     * Writes one diagnostics archive and returns its path.
     *
     * @param directory the per-instance diagnostics directory (created here)
     * @param instanceId the instance the session belonged to
     * @param exitStatus the status observed through {@code waitFor()}
     * @param observedEvents ordered host-side observations (assembly notes,
     *        channel endings); never rewritten or filtered
     */
    public static Path archive(
            Path directory, String instanceId, int exitStatus, List<String> observedEvents)
            throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(observedEvents, "observedEvents");
        Files.createDirectories(directory);
        String fileName = "avf-session-diagnostics-"
                + Long.toString(System.currentTimeMillis(), Character.MAX_RADIX) + ".txt";
        Path target = directory.resolve(fileName);
        StringBuilder report = new StringBuilder();
        report.append("ORCHESTELLA AVF SESSION DIAGNOSTICS\n");
        report.append("instance=").append(instanceId).append('\n');
        report.append("exit_status=").append(exitStatus).append('\n');
        report.append("archived_at=").append(Instant.now()).append('\n');
        report.append("--- observed events ---\n");
        for (String event : observedEvents) {
            report.append(event).append('\n');
        }
        Files.writeString(target, report.toString(), StandardCharsets.UTF_8);
        return target;
    }
}
