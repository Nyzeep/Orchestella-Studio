package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;

/**
 * Append-only degradation ledger for transparent AVF→QEMU fallbacks
 * (AC-3: "给出明确降级原因记录"). Deliberately a {@code .txt} file — the
 * project-wide evidence discipline forbids new {@code .log} files.
 */
public final class AvfDegradationLog {
    /** One recorded degradation event. */
    public static final class Record {
        private final long timestampMillis;
        private final String reason;
        private final String detail;

        Record(long timestampMillis, String reason, String detail) {
            this.timestampMillis = timestampMillis;
            this.reason = Objects.requireNonNull(reason, "reason");
            this.detail = detail == null ? "" : detail;
        }

        public long timestampMillis() {
            return timestampMillis;
        }

        public String reason() {
            return reason;
        }

        public String detail() {
            return detail;
        }

        public String toLine() {
            String oneLineDetail = detail.replace('\n', ' ').replace('\r', ' ');
            return timestampMillis + "|" + reason + "|" + oneLineDetail;
        }
    }

    private final Path file;

    public AvfDegradationLog(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public Path file() {
        return file;
    }

    /** Appends one record; returns it for immediate UI disclosure. */
    public synchronized Record record(AvfEngineException failure) throws IOException {
        Objects.requireNonNull(failure, "failure");
        Record record = new Record(
                System.currentTimeMillis(), failure.reason(), failure.getMessage());
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(
                file,
                (record.toLine() + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        return record;
    }
}
