package io.github.nyzeep.orchestella;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure JVM deep module for persisting and reloading terminal interactive command history.
 *
 * <p>Conforms to Phase 5.0 Task 2:
 * <ul>
 *   <li>Maintains per-instance isolated command history files;</li>
 *   <li>Enforces a maximum retention limit of {@value #MAX_HISTORY_ENTRIES} entries;</li>
 *   <li>Uses robust Base64-per-line encoding ensuring lossless multiline and binary safety;</li>
 *   <li>Tolerates corrupted / malformed entries without throwing unhandled exceptions;</li>
 *   <li>Provides asynchronous APIs for seamless Activity lifecycle and background persistence;</li>
 *   <li>Ensures crash-safe atomic filesystem writes via temporary file replacement.</li>
 * </ul>
 */
public class TerminalHistoryStore {
    private static final Logger LOGGER = Logger.getLogger(TerminalHistoryStore.class.getName());

    public static final int MAX_HISTORY_ENTRIES = 500;
    private static final String HISTORY_FILE_EXTENSION = ".history";
    private static final String TMP_FILE_EXTENSION = ".tmp";

    private final Path storageDirectory;
    private final ExecutorService ioExecutor;
    private final Map<String, ReentrantReadWriteLock> instanceLocks = new ConcurrentHashMap<>();

    public TerminalHistoryStore(Path storageDirectory) throws IOException {
        this(storageDirectory, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "terminal-history-io");
            t.setDaemon(true);
            return t;
        }));
    }

    public TerminalHistoryStore(Path storageDirectory, ExecutorService ioExecutor) throws IOException {
        this.storageDirectory = Objects.requireNonNull(storageDirectory, "storageDirectory").toAbsolutePath().normalize();
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        Files.createDirectories(this.storageDirectory);
    }

    private ReentrantReadWriteLock getLock(String instanceId) {
        return instanceLocks.computeIfAbsent(instanceId, id -> new ReentrantReadWriteLock());
    }

    private Path getHistoryPath(String instanceId) {
        String safeName = sanitizeInstanceId(instanceId);
        return storageDirectory.resolve(safeName + HISTORY_FILE_EXTENSION);
    }

    private static String sanitizeInstanceId(String instanceId) {
        if (instanceId == null || instanceId.trim().isEmpty()) {
            return "default";
        }
        return instanceId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Loads command history for the specified instance.
     * Returns an unmodifiable list of up to {@value #MAX_HISTORY_ENTRIES} commands.
     * Never returns null; on missing or corrupted files, returns an empty or salvaged list.
     */
    public List<String> loadHistory(String instanceId) {
        Path historyPath = getHistoryPath(instanceId);
        ReentrantReadWriteLock.ReadLock readLock = getLock(instanceId).readLock();
        readLock.lock();
        try {
            if (!Files.exists(historyPath)) {
                return Collections.emptyList();
            }

            List<String> commands = new ArrayList<>();
            try (BufferedReader reader = Files.newBufferedReader(historyPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        byte[] decodedBytes = Base64.getDecoder().decode(line);
                        String cmd = new String(decodedBytes, StandardCharsets.UTF_8);
                        commands.add(cmd);
                    } catch (IllegalArgumentException malformed) {
                        // Skip corrupted/non-base64 lines without crashing
                        LOGGER.log(Level.WARNING, "Skipping malformed command entry in " + historyPath, malformed);
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to read history file: " + historyPath, e);
                return Collections.emptyList();
            }

            if (commands.size() > MAX_HISTORY_ENTRIES) {
                commands = commands.subList(commands.size() - MAX_HISTORY_ENTRIES, commands.size());
            }

            return Collections.unmodifiableList(commands);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Atomically saves the complete list of history commands for the specified instance.
     * Caps entries at {@value #MAX_HISTORY_ENTRIES}, retaining the most recent commands.
     */
    public void saveHistory(String instanceId, List<String> history) throws IOException {
        if (history == null) {
            history = Collections.emptyList();
        }

        List<String> trimmedHistory;
        if (history.size() > MAX_HISTORY_ENTRIES) {
            trimmedHistory = history.subList(history.size() - MAX_HISTORY_ENTRIES, history.size());
        } else {
            trimmedHistory = new ArrayList<>(history);
        }

        Path historyPath = getHistoryPath(instanceId);
        Path tmpPath = storageDirectory.resolve(sanitizeInstanceId(instanceId) + TMP_FILE_EXTENSION + System.nanoTime());

        ReentrantReadWriteLock.WriteLock writeLock = getLock(instanceId).writeLock();
        writeLock.lock();
        try {
            Files.createDirectories(storageDirectory);
            try (BufferedWriter writer = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8)) {
                for (String cmd : trimmedHistory) {
                    if (cmd == null || cmd.isEmpty()) {
                        continue;
                    }
                    byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
                    String encoded = Base64.getEncoder().encodeToString(bytes);
                    writer.write(encoded);
                    writer.newLine();
                }
            }

            try {
                Files.move(tmpPath, historyPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmpPath, historyPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmpPath);
            } catch (IOException ignored) {
            }
            writeLock.unlock();
        }
    }

    /**
     * Appends a single command to the instance's history buffer, trimming overflow beyond 500.
     */
    public void appendCommand(String instanceId, String command) throws IOException {
        if (command == null || command.isEmpty()) {
            return;
        }
        ReentrantReadWriteLock.WriteLock writeLock = getLock(instanceId).writeLock();
        writeLock.lock();
        try {
            List<String> current = new ArrayList<>(loadHistory(instanceId));
            if (current.isEmpty() || !current.get(current.size() - 1).equals(command)) {
                current.add(command);
                saveHistory(instanceId, current);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Asynchronously saves history commands on the I/O thread pool.
     */
    public CompletableFuture<Void> saveHistoryAsync(String instanceId, List<String> history) {
        return CompletableFuture.runAsync(() -> {
            try {
                saveHistory(instanceId, history);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Async saveHistory failed for " + instanceId, e);
            }
        }, ioExecutor);
    }

    /**
     * Asynchronously loads history commands on the I/O thread pool.
     */
    public CompletableFuture<List<String>> loadHistoryAsync(String instanceId) {
        return CompletableFuture.supplyAsync(() -> loadHistory(instanceId), ioExecutor);
    }

    /**
     * Deletes the history file for the specified instance.
     */
    public void deleteHistory(String instanceId) throws IOException {
        Path historyPath = getHistoryPath(instanceId);
        ReentrantReadWriteLock.WriteLock writeLock = getLock(instanceId).writeLock();
        writeLock.lock();
        try {
            Files.deleteIfExists(historyPath);
        } finally {
            writeLock.unlock();
        }
    }
}
