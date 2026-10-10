package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM unit tests for {@link MultiTerminalSessionManager} and {@link TerminalSessionInstance}.
 *
 * Covers:
 * 1. Multi-session creation, auto-increment IDs, title default & rename, and max limit protection;
 * 2. Concurrent multi-thread write isolation (Zero Cross-session Bleed);
 * 3. Graceful session closure, active session pointer failover, and cleanup.
 */
public class MultiTerminalSessionManagerTest {

    private MultiTerminalSessionManager manager;
    private final String testInstanceId = "inst-alpine-01";

    @Before
    public void setUp() {
        manager = new MultiTerminalSessionManager(5); // max 5 sessions for this test instance
    }

    @Test
    public void testSessionCreationAndIdIncrement() {
        TerminalSessionInstance session1 = manager.createSession(testInstanceId, "Bash 1");
        assertNotNull(session1);
        assertEquals("term-1", session1.getSessionId());
        assertEquals(testInstanceId, session1.getInstanceId());
        assertEquals("Bash 1", session1.getTitle());

        TerminalSessionInstance active = manager.getActiveSession(testInstanceId);
        assertNotNull(active);
        assertEquals(session1.getSessionId(), active.getSessionId());

        // Create second session with default title (passing null or empty)
        TerminalSessionInstance session2 = manager.createSession(testInstanceId, null);
        assertNotNull(session2);
        assertEquals("term-2", session2.getSessionId());
        assertEquals("终端 2", session2.getTitle());

        List<TerminalSessionInstance> list = manager.listSessions(testInstanceId);
        assertEquals(2, list.size());
        assertEquals("term-1", list.get(0).getSessionId());
        assertEquals("term-2", list.get(1).getSessionId());
    }

    @Test
    public void testMaxSessionLimitProtection() {
        // Limit is 5
        for (int i = 1; i <= 5; i++) {
            assertNotNull(manager.createSession(testInstanceId, "Session " + i));
        }
        assertEquals(5, manager.listSessions(testInstanceId).size());

        try {
            manager.createSession(testInstanceId, "Exceeding Session");
            fail("Expected IllegalStateException when exceeding max sessions limit");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Maximum terminal sessions limit reached"));
        }
    }

    @Test
    public void testRenameSession() {
        TerminalSessionInstance s1 = manager.createSession(testInstanceId, "Default Title");
        assertTrue(manager.renameSession(testInstanceId, s1.getSessionId(), "Kernel Build"));
        assertEquals("Kernel Build", s1.getTitle());

        // Rename non-existent session
        assertFalse(manager.renameSession(testInstanceId, "invalid-id", "New Name"));
    }

    @Test
    public void testSwitchActiveSession() {
        TerminalSessionInstance s1 = manager.createSession(testInstanceId, "Term 1");
        TerminalSessionInstance s2 = manager.createSession(testInstanceId, "Term 2");

        // First session created is active by default
        assertEquals(s1.getSessionId(), manager.getActiveSession(testInstanceId).getSessionId());

        // Switch to session 2
        assertTrue(manager.switchActiveSession(testInstanceId, s2.getSessionId()));
        assertEquals(s2.getSessionId(), manager.getActiveSession(testInstanceId).getSessionId());

        // Switch to non-existent session
        assertFalse(manager.switchActiveSession(testInstanceId, "non-existent"));
        assertEquals(s2.getSessionId(), manager.getActiveSession(testInstanceId).getSessionId());
    }

    @Test
    public void testConcurrentWritingIsolation() throws InterruptedException {
        TerminalSessionInstance s1 = manager.createSession(testInstanceId, "Stream 1");
        TerminalSessionInstance s2 = manager.createSession(testInstanceId, "Stream 2");
        TerminalSessionInstance s3 = manager.createSession(testInstanceId, "Stream 3");

        int threadCount = 3;
        int linesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        // Thread 1 writes exclusively to s1
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < linesPerThread; i++) {
                    s1.appendOutput("S1_DATA_" + i + "\n");
                }
            } catch (Exception ignored) {
            } finally {
                finishLatch.countDown();
            }
        });

        // Thread 2 writes exclusively to s2
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < linesPerThread; i++) {
                    s2.appendOutput("S2_DATA_" + i + "\n");
                }
            } catch (Exception ignored) {
            } finally {
                finishLatch.countDown();
            }
        });

        // Thread 3 writes exclusively to s3
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < linesPerThread; i++) {
                    s3.appendOutput("S3_DATA_" + i + "\n");
                }
            } catch (Exception ignored) {
            } finally {
                finishLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        String transcript1 = s1.getTranscript();
        String transcript2 = s2.getTranscript();
        String transcript3 = s3.getTranscript();

        // Strict isolation checks
        assertTrue("s1 should contain S1 data", transcript1.contains("S1_DATA_0"));
        assertTrue("s1 should contain S1 last data", transcript1.contains("S1_DATA_99"));
        assertFalse("s1 MUST NOT contain S2 data", transcript1.contains("S2_DATA_"));
        assertFalse("s1 MUST NOT contain S3 data", transcript1.contains("S3_DATA_"));

        assertTrue("s2 should contain S2 data", transcript2.contains("S2_DATA_0"));
        assertTrue("s2 should contain S2 last data", transcript2.contains("S2_DATA_99"));
        assertFalse("s2 MUST NOT contain S1 data", transcript2.contains("S1_DATA_"));
        assertFalse("s2 MUST NOT contain S3 data", transcript2.contains("S3_DATA_"));

        assertTrue("s3 should contain S3 data", transcript3.contains("S3_DATA_0"));
        assertTrue("s3 should contain S3 last data", transcript3.contains("S3_DATA_99"));
        assertFalse("s3 MUST NOT contain S1 data", transcript3.contains("S1_DATA_"));
        assertFalse("s3 MUST NOT contain S2 data", transcript3.contains("S2_DATA_"));
    }

    @Test
    public void testCloseActiveSessionSmoothFailover() {
        TerminalSessionInstance s1 = manager.createSession(testInstanceId, "Term 1");
        TerminalSessionInstance s2 = manager.createSession(testInstanceId, "Term 2");
        TerminalSessionInstance s3 = manager.createSession(testInstanceId, "Term 3");

        // Activate s2
        manager.switchActiveSession(testInstanceId, s2.getSessionId());
        assertEquals("term-2", manager.getActiveSession(testInstanceId).getSessionId());

        // Close s2 (active session)
        assertTrue(manager.closeSession(testInstanceId, s2.getSessionId()));
        assertFalse(s2.isActive());

        // Active pointer must smoothly failover to remaining session (e.g. s3 or s1)
        TerminalSessionInstance newActive = manager.getActiveSession(testInstanceId);
        assertNotNull(newActive);
        assertTrue(newActive.getSessionId().equals("term-3") || newActive.getSessionId().equals("term-1"));
        assertEquals(2, manager.listSessions(testInstanceId).size());

        // Close remaining sessions
        assertTrue(manager.closeSession(testInstanceId, "term-1"));
        assertTrue(manager.closeSession(testInstanceId, "term-3"));

        // No sessions left
        assertNull(manager.getActiveSession(testInstanceId));
        assertEquals(0, manager.listSessions(testInstanceId).size());

        // Closing non-existent session
        assertFalse(manager.closeSession(testInstanceId, "term-1"));
    }
}
