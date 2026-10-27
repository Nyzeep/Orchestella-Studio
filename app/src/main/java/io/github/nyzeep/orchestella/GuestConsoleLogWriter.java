package io.github.nyzeep.orchestella;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * Appends guest console chunks to per-instance log files, rotating past
 * {@link #MAX_BYTES}. Deliberately free of Android imports: the writer is
 * invoked from the UI-adjacent output path, so the write itself runs on a
 * background executor (see {@code LogViewerActivity.appendGuestConsole}), and
 * the pure java.io/NIO body stays testable on the host JVM.
 */
public final class GuestConsoleLogWriter {

    /** Rotation threshold for one instance's console log (4 MB, mirrors the historical constant). */
    public static final long MAX_BYTES = 4L * 1024L * 1024L;

    /** Marker written to the fresh file after rotation. */
    public static final String ROTATION_MARKER = "…（日志超过 4MB，已从此处继续）\n";

    private GuestConsoleLogWriter() {
    }

    /** Appends one chunk; creates the log (and parent dirs) on first use; rotates a &gt;{@code maxBytes} log to a fresh file. */
    public static void append(File logsDir, String instanceId, String chunk) throws IOException {
        append(logsDir, instanceId, chunk, MAX_BYTES);
    }

    /** Same as {@link #append(File, String, String)} with an injectable rotation threshold for tests. */
    public static void append(File logsDir, String instanceId, String chunk, long maxBytes) throws IOException {
        if (logsDir == null || instanceId == null || instanceId.isEmpty()
                || chunk == null || chunk.isEmpty()) {
            return;
        }
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }
        File target = new File(logsDir, "console-" + instanceId + ".log");
        if (target.isFile() && target.length() > maxBytes) {
            target.delete();
            Files.write(target.toPath(), ROTATION_MARKER.getBytes(StandardCharsets.UTF_8));
        }
        Files.write(target.toPath(), chunk.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
