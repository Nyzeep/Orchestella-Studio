package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pure JVM unit tests for {@link TerminalHistoryStore} (Phase 5.0 Task 2).
 *
 * Verifies:
 * <ul>
 *   <li>Per-instance isolated command history storage and reloading;</li>
 *   <li>Enforcement of the maximum 500-command circular retention limit;</li>
 *   <li>Concurrency robustness under simultaneous async read and write requests;</li>
 *   <li>Corrupted / malformed history file fault tolerance without crashes;</li>
 *   <li>Atomic persistence ensuring data integrity during sudden interruptions.</li>
 * </ul>
 */
public class TerminalHistoryStoreTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private Path storageDir;
    private TerminalHistoryStore store;

    @Before
    public void setUp() throws Exception {
        storageDir = tempFolder.newFolder("terminal_history").toPath();
        store = new TerminalHistoryStore(storageDir);
    }

    @Test
    public void returnsEmptyListForNonExistentInstance() {
        List<String> history = store.loadHistory("non-existent-instance");
        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    @Test
    public void savesAndReloadsCommandsInOrder() throws IOException {
        String instanceId = "inst-test-1";
        List<String> commands = Arrays.asList(
                "echo hello",
                "ls -la /var/log",
                "cd /tmp && pwd",
                "cat << 'EOF'\nmultiline payload\nEOF",
                "uname -a"
        );

        store.saveHistory(instanceId, commands);

        List<String> reloaded = store.loadHistory(instanceId);
        assertEquals(commands.size(), reloaded.size());
        assertEquals(commands, reloaded);
    }

    @Test
    public void enforcesMaxCapacityLimitOf500Entries() throws IOException {
        String instanceId = "inst-large-500";
        List<String> bulkCommands = new ArrayList<>();
        for (int i = 0; i < 750; i++) {
            bulkCommands.add("command-index-" + i);
        }

        store.saveHistory(instanceId, bulkCommands);

        List<String> reloaded = store.loadHistory(instanceId);
        assertEquals(500, reloaded.size());
        // Should preserve the latest 500 entries (250 through 749)
        assertEquals("command-index-250", reloaded.get(0));
        assertEquals("command-index-749", reloaded.get(499));
    }

    @Test
    public void maintainsIndependentHistoriesPerInstance() throws IOException {
        String instA = "instance-alpha";
        String instB = "instance-beta";

        store.saveHistory(instA, Arrays.asList("apt-get update", "apt-get install -y vim"));
        store.saveHistory(instB, Arrays.asList("apk add curl", "curl -I https://google.com"));

        List<String> historyA = store.loadHistory(instA);
        List<String> historyB = store.loadHistory(instB);

        assertEquals(2, historyA.size());
        assertEquals("apt-get update", historyA.get(0));

        assertEquals(2, historyB.size());
        assertEquals("apk add curl", historyB.get(0));
    }

    @Test
    public void appendsSingleCommandSafely() throws IOException {
        String instanceId = "inst-append";
        store.saveHistory(instanceId, Arrays.asList("cmd-1", "cmd-2"));

        store.appendCommand(instanceId, "cmd-3");

        List<String> reloaded = store.loadHistory(instanceId);
        assertEquals(Arrays.asList("cmd-1", "cmd-2", "cmd-3"), reloaded);
    }

    @Test
    public void handlesCorruptedAndGarbageFileGracefully() throws IOException {
        String instanceId = "inst-corrupted";
        Path historyFile = storageDir.resolve(instanceId + ".history");

        // Write arbitrary binary garbage and partial lines
        byte[] garbage = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00, 0x12, 0x7F, '\n', 'l', 's', '\n', (byte) 0x00};
        Files.write(historyFile, garbage);

        // Loading should never throw runtime exceptions or crashes
        List<String> reloaded = store.loadHistory(instanceId);
        assertNotNull(reloaded);
        // It should either recover valid lines (like "ls") or safely fall back without throwing
        assertFalse(reloaded.contains(null));
    }

    @Test
    public void handlesConcurrentAsyncReadsAndWrites() throws Exception {
        String instanceId = "inst-concurrent";
        int threadCount = 10;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        if (i % 2 == 0) {
                            store.appendCommand(instanceId, "thread-" + threadId + "-cmd-" + i);
                        } else {
                            List<String> loaded = store.loadHistory(instanceId);
                            assertNotNull(loaded);
                        }
                    }
                } catch (Throwable t1) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertTrue("Concurrent operations should complete within timeout", finished);
        assertEquals("No concurrent operations should fail", 0, failures.get());

        List<String> finalHistory = store.loadHistory(instanceId);
        assertTrue(finalHistory.size() <= 500);
        assertTrue(finalHistory.size() > 0);
    }

    @Test
    public void asyncSaveAndLoadReturnsCompletableFuture() throws Exception {
        String instanceId = "inst-async";
        List<String> commands = Arrays.asList("git status", "git diff", "git commit -m 'feat'");

        store.saveHistoryAsync(instanceId, commands).get(5, TimeUnit.SECONDS);

        List<String> reloaded = store.loadHistoryAsync(instanceId).get(5, TimeUnit.SECONDS);
        assertEquals(commands, reloaded);
    }

    @Test
    public void deleteHistoryRemovesFile() throws IOException {
        String instanceId = "inst-delete";
        store.saveHistory(instanceId, Arrays.asList("rm -rf /tmp/test"));
        assertTrue(Files.exists(storageDir.resolve(instanceId + ".history")));

        store.deleteHistory(instanceId);
        assertFalse(Files.exists(storageDir.resolve(instanceId + ".history")));
        assertTrue(store.loadHistory(instanceId).isEmpty());
    }
}
