package io.github.nyzeep.orchestella;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Versioned, bounded JSONL sink for production stage traces (ticket 27).
 *
 * <p>Bounds and policy, all explicit:
 *
 * <ul>
 *   <li>The active file {@code stage-trace-v1.jsonl} starts with one header
 *       line declaring the schema name and {@code schema_version}.</li>
 *   <li>When the active file would exceed {@code maxFileBytes}, it is rotated
 *       to {@code <name>.1} (replacing the previous archive) and a fresh file
 *       with a new header starts. Exactly {@code archivesKept} archives are
 *       retained; older archives are deleted, so the directory never grows
 *       without bound.</li>
 *   <li>An existing active file is only appended to when its first line is
 *       exactly the header this sink would write for its own schema version.
 *       Anything else — including a ticket 22 offline synthetic trace — is an
 *       explicit refusal: the file is preserved untouched, nothing is
 *       reinterpreted, and every write returns {@code false} so the recorder
 *       retires with {@code SINK_FAILED}.</li>
 *   <li>Every failure is reported as a {@code false} return; this sink never
 *       throws, keeps no queue, spawns no thread and never retries.</li>
 * </ul>
 *
 * <p>Pure JVM: the directory is only touched from {@link #writeLine(String)},
 * so a disabled configuration can assert zero file creation by never calling
 * it.
 */
public final class StageTraceFileSink {
    /** Active trace file name; the version suffix changes with the schema. */
    public static final String FILE_NAME = "stage-trace-v1.jsonl";
    /** Default active-file cap before rotation (128 KiB). */
    public static final long DEFAULT_MAX_FILE_BYTES = 128L * 1024L;
    /** Default number of rotated archives retained alongside the active file. */
    public static final int DEFAULT_ARCHIVES_KEPT = 1;

    private final Path directory;
    private final String fileName;
    private final long maxFileBytes;
    private final int archivesKept;
    private final String schemaName;
    private final int schemaVersion;
    private boolean headerChecked;
    private boolean refused;
    private long currentSize;

    public StageTraceFileSink(Path directory, String fileName, long maxFileBytes,
            int archivesKept, String schemaName, int schemaVersion) {
        this.directory = directory;
        this.fileName = fileName;
        this.maxFileBytes = maxFileBytes;
        this.archivesKept = archivesKept;
        this.schemaName = schemaName;
        this.schemaVersion = schemaVersion;
    }

    /** Production sink for the v1 trace schema in the app's private storage. */
    public static StageTraceFileSink defaultV1(Path directory) {
        return new StageTraceFileSink(directory, FILE_NAME, DEFAULT_MAX_FILE_BYTES,
                DEFAULT_ARCHIVES_KEPT, ProductionStageRecorder.SCHEMA_NAME,
                ProductionStageRecorder.SCHEMA_VERSION);
    }

    /**
     * Appends one complete line, writing the schema header first on a fresh
     * file. Returns {@code false} on any failure or after an explicit refusal;
     * never throws.
     */
    public synchronized boolean writeLine(String line) {
        if (line == null || refused) return false;
        try {
            Files.createDirectories(directory);
            Path active = directory.resolve(fileName);
            if (!headerChecked) {
                if (Files.exists(active) && Files.size(active) > 0) {
                    if (!headerMatches(readFirstLine(active))) {
                        // Explicit refusal: preserve the foreign or stale file,
                        // never reinterpret it silently.
                        refused = true;
                        return false;
                    }
                    currentSize = Files.size(active);
                } else {
                    byte[] header = (headerText() + "\n").getBytes(StandardCharsets.UTF_8);
                    Files.write(active, header, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    currentSize = header.length;
                }
                headerChecked = true;
            }
            byte[] payload = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (currentSize + payload.length > maxFileBytes) {
                rotate(active);
            }
            Files.write(active, payload, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            currentSize += payload.length;
            return true;
        } catch (IOException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** True when the sink refused its target file and preserved it untouched. */
    public synchronized boolean refusedForeignFile() {
        return refused;
    }

    private String headerText() {
        return "{\"schema_version\":" + schemaVersion + ",\"schema\":\"" + schemaName
                + "\",\"kind\":\"file_header\"}";
    }

    private boolean headerMatches(String firstLine) {
        return firstLine != null && firstLine.equals(headerText());
    }

    private static String readFirstLine(Path file) throws IOException {
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return reader.readLine();
        }
    }

    private void rotate(Path active) throws IOException {
        for (int index = archivesKept; index >= 1; index--) {
            Path target = directory.resolve(fileName + "." + index);
            Files.deleteIfExists(target);
            Path source = index == 1 ? active : directory.resolve(fileName + "." + (index - 1));
            if (Files.exists(source)) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        byte[] header = (headerText() + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(active, header, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        currentSize = header.length;
    }
}
