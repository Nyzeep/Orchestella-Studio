package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class PersistentTerminalSessionPoolTest {

    private PersistentTerminalSessionPool pool;

    @Before
    public void setUp() {
        pool = new PersistentTerminalSessionPool();
    }

    @After
    public void tearDown() {
        if (pool != null) {
            pool.clear();
        }
    }

    private static final class FakeExecutionSession implements ExecutionSession {
        private final InputStream termOut;
        private final OutputStream termIn;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        FakeExecutionSession(InputStream termOut, OutputStream termIn) {
            this.termOut = termOut;
            this.termIn = termIn;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public OutputStream terminalInput() {
            return termIn;
        }

        @Override
        public InputStream terminalOutput() {
            return termOut;
        }

        @Override
        public OutputStream controlInput() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream controlOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() {
            closed.set(true);
            try {
                termIn.close();
            } catch (IOException ignored) {
            }
            try {
                termOut.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Test
    public void testAttachDetachConcurrentSafetyAndTranscriptReplay() throws Exception {
        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut);
        ByteArrayOutputStream inputSink = new ByteArrayOutputStream();

        FakeExecutionSession session = new FakeExecutionSession(pipeIn, inputSink);
        PersistentTerminalSessionPool.SessionHolder holder = pool.register("test-vm-1", session);

        // Feed some initial shell prompt output
        pipeOut.write("root@orchestella:~$ \n".getBytes(StandardCharsets.UTF_8));
        pipeOut.flush();
        Thread.sleep(50);

        // First attachment
        List<String> receivedChunks = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> listener = receivedChunks::add;
        String replay = holder.attach(listener);

        assertTrue("Replay should contain prompt", replay.contains("root@orchestella:~$"));

        // Feed ongoing output
        pipeOut.write("echo hello world\n".getBytes(StandardCharsets.UTF_8));
        pipeOut.flush();
        Thread.sleep(50);

        assertFalse(receivedChunks.isEmpty());

        // Detach listener (simulating Activity onStop/onDestroy)
        holder.detach(listener);
        assertEquals(0, holder.subscriberCount());

        // Background session keeps receiving output while detached!
        pipeOut.write("background output while detached\n".getBytes(StandardCharsets.UTF_8));
        pipeOut.flush();
        Thread.sleep(50);

        // Re-attach second Activity
        List<String> secondChunks = Collections.synchronizedList(new ArrayList<>());
        String secondReplay = holder.attach(secondChunks::add);

        assertTrue("Second re-attach must contain background output",
                secondReplay.contains("background output while detached"));

        // Send input bytes
        holder.sendBytes("ls -la\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("ls -la\n", inputSink.toString(StandardCharsets.UTF_8));

        holder.close();
        assertTrue(session.closed.get());
    }

    @Test public void utf8DecoderPreservesCharactersSplitAtEveryByte() throws Exception {
        byte[] bytes = "中文😀é\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream split = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(1, length));
            }
        };
        PersistentTerminalSessionPool.SessionHolder holder = pool.register("utf8-vm",
                new FakeExecutionSession(split, new ByteArrayOutputStream()));
        CountDownLatch ended = new CountDownLatch(1);
        holder.setSessionEndListener(id -> ended.countDown());
        assertTrue(ended.await(3, TimeUnit.SECONDS));
        assertEquals("中文😀é\r\n", holder.getTranscript());
        assertFalse(holder.getTranscript().contains("\ufffd"));
    }

    @Test public void persistentScreenSurvivesTruncatedRawHistoryAndRespondsWhileDetached() throws Exception {
        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut, 32768);
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        PersistentTerminalSessionPool.SessionHolder holder = new PersistentTerminalSessionPool.SessionHolder(
                "persistent-vm", new FakeExecutionSession(pipeIn, input), 64);
        try {
            pipeOut.write("shell$ \u001b[?1049h\u001b[31m\u001b[?1h".getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 50; i++)
                pipeOut.write(("\u001b[Hframe-" + i + "\u001b[K").getBytes(StandardCharsets.UTF_8));
            pipeOut.write("\u001b[3;4H\u001b[6n".getBytes(StandardCharsets.UTF_8));
            pipeOut.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!input.toString(StandardCharsets.UTF_8).contains("\u001b[3;4R") && System.nanoTime() < deadline)
                Thread.sleep(2);
            assertTrue("Background DSR must be answered without a View", input.toString(StandardCharsets.UTF_8).contains("\u001b[3;4R"));
            assertFalse(holder.getTranscript().contains("1049h"));
            assertTrue(holder.getScreenModel().isAlternateScreen());
            assertTrue(holder.getScreenModel().isApplicationCursorKeys());
            assertEquals(2, holder.getScreenModel().cursorRow());
            assertEquals(3, holder.getScreenModel().cursorColumn());
            assertTrue(holder.getScreenModel().snapshot().text().startsWith("frame-49"));
            Consumer<String> listener = chunk -> {};
            holder.attach(listener); holder.detach(listener); holder.attach(listener);
            assertTrue(holder.getScreenModel().isAlternateScreen());
            pipeOut.write("\u001b[?1049l".getBytes(StandardCharsets.UTF_8));
            pipeOut.flush();
            while (holder.getScreenModel().isAlternateScreen() && System.nanoTime() < deadline) Thread.sleep(2);
            assertFalse(holder.getScreenModel().isAlternateScreen());
            assertTrue(holder.getScreenModel().snapshot().text().startsWith("shell$"));
        } finally { holder.close(); pipeOut.close(); }
    }

    @Test public void endListenerRegisteredAfterEofIsNotLostAndDeadStreamRejectsInput() throws Exception {
        PersistentTerminalSessionPool.SessionHolder holder = pool.register("ended-vm",
                new FakeExecutionSession(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        CountDownLatch first = new CountDownLatch(1);
        holder.setSessionEndListener(id -> first.countDown());
        assertTrue(first.await(3, TimeUnit.SECONDS));
        CountDownLatch second = new CountDownLatch(1);
        holder.setSessionEndListener(id -> second.countDown());
        assertTrue(second.await(1, TimeUnit.SECONDS));
        try {
            holder.sendBytes(new byte[]{'x'});
            org.junit.Assert.fail("Ended stream accepted input");
        } catch (IOException expected) { }
    }

    @Test
    public void conditionalUnregisterKeepsReplacementAndClosesOnlyTheExpectedOwnerOnce() throws Exception {
        FakeExecutionSession old = new FakeExecutionSession(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        FakeExecutionSession replacement = new FakeExecutionSession(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        pool.register("generation-vm", old);
        pool.register("generation-vm", replacement);
        assertFalse(pool.unregister("generation-vm", old));
        org.junit.Assert.assertSame(replacement, pool.getSession("generation-vm"));
        assertFalse(replacement.closed.get());
        assertTrue(pool.unregister("generation-vm", replacement));
        assertFalse(pool.unregister("generation-vm", replacement));
        assertTrue(replacement.closed.get());
        assertNull(pool.getSession("generation-vm"));
    }

    @Test
    public void ownerRetirementNotifiesAfterTerminalEofSoUiCanReconcileConfirmedExit() throws Exception {
        FakeExecutionSession session = new FakeExecutionSession(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        PersistentTerminalSessionPool.SessionHolder holder = pool.register("eof-then-owner-exit", session);
        CountDownLatch terminalEnded = new CountDownLatch(1);
        CountDownLatch ownerRetired = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
        holder.setSessionEndListener(id -> {
            if (events.incrementAndGet() == 1) terminalEnded.countDown();
            else if (pool.getSession(id) == null) ownerRetired.countDown();
        });
        assertTrue(terminalEnded.await(2, TimeUnit.SECONDS));
        assertTrue(holder.hasOwner());
        assertTrue(pool.unregister("eof-then-owner-exit", session));
        assertTrue("The confirmed owner exit must trigger a second reconciliation", ownerRetired.await(2, TimeUnit.SECONDS));
        assertEquals(2, events.get());
    }

    @Test
    public void testRingBufferEnforcesBoundaries() {
        TerminalRingBuffer buffer = new TerminalRingBuffer(100);
        buffer.append("1234567890");
        assertEquals(10, buffer.length());
        assertEquals("1234567890", buffer.snapshot());

        // Append 100 characters
        String hundred = "a".repeat(100);
        buffer.append(hundred);
        assertEquals(100, buffer.length());
        assertEquals(hundred, buffer.snapshot());

        // Append 20 characters: oldest 20 must be evicted
        buffer.append("bbbbbbbbbbbbbbbbbbbb");
        assertEquals(100, buffer.length());
        assertTrue(buffer.snapshot().endsWith("bbbbbbbbbbbbbbbbbbbb"));
        assertEquals("a".repeat(80) + "b".repeat(20), buffer.snapshot());
    }

    @Test
    public void testConcurrentAttachDetachStress() throws Exception {
        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut);
        FakeExecutionSession session = new FakeExecutionSession(pipeIn, new ByteArrayOutputStream());
        PersistentTerminalSessionPool.SessionHolder holder = pool.register("stress-vm", session);

        int threads = 8;
        int iterations = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        Consumer<String> l = chunk -> {};
                        holder.attach(l);
                        holder.detach(l);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Simultaneously pump data
        new Thread(() -> {
            try {
                for (int k = 0; k < 50; k++) {
                    pipeOut.write(("log " + k + "\n").getBytes(StandardCharsets.UTF_8));
                    pipeOut.flush();
                    Thread.sleep(5);
                }
            } catch (Exception ignored) {
            }
        }).start();

        boolean finished = latch.await(5, TimeUnit.SECONDS);
        assertTrue("Concurrent attach/detach must not deadlock", finished);
        executor.shutdown();
        holder.close();
    }

    @Test
    public void testSessionEndListenerFiresOnStreamEof() throws Exception {
        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut);
        FakeExecutionSession session = new FakeExecutionSession(pipeIn, new ByteArrayOutputStream());
        PersistentTerminalSessionPool.SessionHolder holder = pool.register("eof-vm", session);

        CountDownLatch endLatch = new CountDownLatch(1);
        List<String> endedIds = Collections.synchronizedList(new ArrayList<>());
        holder.setSessionEndListener(id -> {
            endedIds.add(id);
            endLatch.countDown();
        });

        pipeOut.write("booting\n".getBytes(StandardCharsets.UTF_8));
        pipeOut.flush();
        Thread.sleep(30);
        pipeOut.close(); // guest exit: pump hits EOF

        assertTrue("Session end listener must fire on stream EOF", endLatch.await(3, TimeUnit.SECONDS));
        assertEquals(1, endedIds.size());
        assertEquals("eof-vm", endedIds.get(0));
        assertFalse("Holder must report dead after EOF", holder.isAlive());
    }

    @Test
    public void testAttachSnapshotAndDeliveryAreDisjoint() throws Exception {
        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut);
        FakeExecutionSession session = new FakeExecutionSession(pipeIn, new ByteArrayOutputStream());
        PersistentTerminalSessionPool.SessionHolder holder = pool.register("atomic-vm", session);

        List<String> delivered = Collections.synchronizedList(new ArrayList<>());
        List<String> inconsistencies = Collections.synchronizedList(new ArrayList<>());
        int attachRounds = 60;
        for (int round = 0; round < attachRounds; round++) {
            pipeOut.write(("chunk-" + round + "\n").getBytes(StandardCharsets.UTF_8));
            pipeOut.flush();
            Thread.sleep(2);
            List<String> local = Collections.synchronizedList(new ArrayList<>());
            String replay = holder.attach(local::add);
            // The replayed snapshot must never contain a chunk that is ALSO delivered to
            // the freshly attached listener, and every delivered chunk must be new.
            for (String chunk : local) {
                if (replay.contains(chunk)) {
                    inconsistencies.add("duplicate chunk " + chunk + " in replay at round " + round);
                }
            }
            holder.detach(local::add);
        }
        assertTrue("Attach/deliver overlap detected: " + inconsistencies, inconsistencies.isEmpty());
        holder.close();
    }

    @Test
    public void registerReplacementStopsPreviousRuntimeBeforePublishingNewOwner() throws Exception {
        FakeOwnedSession old = new FakeOwnedSession();
        FakeOwnedSession replacement = new FakeOwnedSession();
        pool.register("owned-replace", old);

        PersistentTerminalSessionPool.SessionHolder holder =
                pool.register("owned-replace", replacement);

        assertEquals("the old owner must be stopped before the new holder is published",
                1, old.stops.get());
        assertSame(replacement, pool.getSession("owned-replace"));
        assertSame(replacement, holder.rawSession());
    }

    @Test
    public void registerRefusesReplacementWhenPreviousStopIsUnconfirmed() throws Exception {
        FakeOwnedSession old = new FakeOwnedSession();
        old.stopResult = RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
        FakeOwnedSession replacement = new FakeOwnedSession();
        pool.register("owned-blocked", old);

        try {
            pool.register("owned-blocked", replacement);
            org.junit.Assert.fail("an unconfirmed owner must block a second owner");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("did not confirm"));
        }
        assertSame(old, pool.getSession("owned-blocked"));
        assertEquals(1, old.stops.get());
        assertEquals("the replacement must not have been started", 0, replacement.stops.get());
    }

    @Test
    public void unregisterStopsOwnedRuntimeAndKeepsUnconfirmedOwner() throws Exception {
        FakeOwnedSession confirmed = new FakeOwnedSession();
        pool.register("owned-unregister", confirmed);
        assertTrue(pool.unregister("owned-unregister", confirmed));
        assertEquals(1, confirmed.stops.get());
        assertNull(pool.getSession("owned-unregister"));

        FakeOwnedSession unconfirmed = new FakeOwnedSession();
        unconfirmed.stopResult = RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
        pool.register("owned-unconfirmed", unconfirmed);
        assertFalse(pool.unregister("owned-unconfirmed", unconfirmed));
        assertSame("an unconfirmed owner must stay registered",
                unconfirmed, pool.getSession("owned-unconfirmed"));
    }

    @Test
    public void clearStopsEveryConfirmedRuntimeOwner() throws Exception {
        FakeOwnedSession first = new FakeOwnedSession();
        FakeOwnedSession second = new FakeOwnedSession();
        pool.register("clear-a", first);
        pool.register("clear-b", second);

        pool.clear();

        assertEquals(1, first.stops.get());
        assertEquals(1, second.stops.get());
        assertNull(pool.getSession("clear-a"));
        assertNull(pool.getSession("clear-b"));
    }

    @Test
    public void unregisterByIdStopsOwnedRuntime() throws Exception {
        FakeOwnedSession session = new FakeOwnedSession();
        pool.register("owned-by-id", session);
        pool.unregister("owned-by-id");
        assertEquals(1, session.stops.get());
        assertNull(pool.getSession("owned-by-id"));
    }

    @Test
    public void clearKeepsUnconfirmedOwner() throws Exception {
        FakeOwnedSession unconfirmed = new FakeOwnedSession();
        unconfirmed.stopResult = RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
        pool.register("clear-unconfirmed", unconfirmed);

        pool.clear();

        assertSame("an unconfirmed owner must survive clear()",
                unconfirmed, pool.getSession("clear-unconfirmed"));
    }

    @Test
    public void provisioningMarkerSplitAcrossOneByteChunksInvokesSinkOnceOnThePumpThread() throws Exception {
        byte[] stream = ("boot log\n"
                + "DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=ubuntu phase=rootfs runtime=pending\n")
                .getBytes(StandardCharsets.UTF_8);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        List<String> callbackThreads = Collections.synchronizedList(new ArrayList<>());
        PersistentTerminalSessionPool.SessionHolder holder = pool.register(
                "provisioning-vm",
                new FakeExecutionSession(new ChunkedInputStream(stream, 1), new ByteArrayOutputStream()),
                (instanceId, markerVersion) -> {
                    assertEquals("provisioning-vm", instanceId);
                    assertEquals(1, markerVersion);
                    calls.incrementAndGet();
                    callbackThreads.add(Thread.currentThread().getName());
                });
        holder.setSessionEndListener(id -> ended.countDown());

        assertTrue(ended.await(5, TimeUnit.SECONDS));
        assertEquals("the sink must fire exactly once", 1, calls.get());
        assertEquals(1, callbackThreads.size());
        assertTrue("the sink must run on the pump thread: " + callbackThreads.get(0),
                callbackThreads.get(0).startsWith("pty-term-pump-"));
    }

    @Test
    public void streamEndingBeforeTheMarkerInvokesNoSink() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PersistentTerminalSessionPool.SessionHolder holder = pool.register(
                "unprovisioned-vm",
                new FakeExecutionSession(
                        new ByteArrayInputStream("boot log\nDROIDLAYER_PROVISIONING_COMPLETE version=10\n"
                                .getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayOutputStream()),
                (instanceId, markerVersion) -> calls.incrementAndGet());
        CountDownLatch ended = new CountDownLatch(1);
        holder.setSessionEndListener(id -> ended.countDown());

        assertTrue(ended.await(4, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
    }

    @Test
    public void markerEndingTheStreamWithoutANewlineStillInvokesTheSinkOnce() throws Exception {
        CountDownLatch provisioned = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PersistentTerminalSessionPool.SessionHolder holder = pool.register(
                "truncated-vm",
                new FakeExecutionSession(
                        new ByteArrayInputStream(("boot\nDROIDLAYER_PROVISIONING_COMPLETE version=1")
                                .getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayOutputStream()),
                (instanceId, markerVersion) -> {
                    calls.incrementAndGet();
                    provisioned.countDown();
                });

        assertTrue(provisioned.await(4, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
    }

    private static final class ChunkedInputStream extends InputStream {
        private final byte[] bytes;
        private final int chunkSize;
        private int offset;

        ChunkedInputStream(byte[] bytes, int chunkSize) {
            this.bytes = bytes;
            this.chunkSize = chunkSize;
        }

        @Override
        public synchronized int read(byte[] target, int targetOffset, int length) {
            if (offset >= bytes.length) {
                return -1;
            }
            int count = Math.min(Math.min(chunkSize, length), bytes.length - offset);
            System.arraycopy(bytes, offset, target, targetOffset, count);
            offset += count;
            return count;
        }

        @Override
        public int read() {
            return offset >= bytes.length ? -1 : bytes[offset++] & 0xff;
        }
    }

    @Test(timeout = 10_000)
    public void slowStopOnOneInstanceDoesNotBlockRegisterOnAnother() throws Exception {
        FakeOwnedSession previous = new FakeOwnedSession();
        previous.stopEntered = new CountDownLatch(1);
        previous.stopGate = new CountDownLatch(1);
        pool.register("owned-slow", previous);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistentTerminalSessionPool.SessionHolder> replacing = executor.submit(
                    () -> pool.register("owned-slow", new FakeOwnedSession()));
            assertTrue("the slow runtime stop must have started",
                    previous.stopEntered.await(2, TimeUnit.SECONDS));

            Future<PersistentTerminalSessionPool.SessionHolder> other = executor.submit(
                    () -> pool.register("owned-fast", new FakeOwnedSession()));
            assertEquals("register on another instance must not wait for a slow stop",
                    "owned-fast", other.get(1, TimeUnit.SECONDS).instanceId());

            previous.stopGate.countDown();
            assertSame("the replacement is published only after the stop confirms",
                    replacing.get(2, TimeUnit.SECONDS).rawSession(), pool.getSession("owned-slow"));
        } finally {
            previous.stopGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10_000)
    public void concurrentStopNeverPublishesASecondOwnerAndDelayedUnregisterCannotRemoveTheReplacement()
            throws Exception {
        FakeOwnedSession old = new FakeOwnedSession();
        old.stopEntered = new CountDownLatch(1);
        old.stopGate = new CountDownLatch(1);
        pool.register("owned-race", old);
        FakeOwnedSession replacement = new FakeOwnedSession();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistentTerminalSessionPool.SessionHolder> replacing = executor.submit(
                    () -> pool.register("owned-race", replacement));
            assertTrue("the old owner stop must have started",
                    old.stopEntered.await(2, TimeUnit.SECONDS));
            assertSame("the old holder stays the only published owner while stopping",
                    old, pool.getSession("owned-race"));

            Future<Boolean> delayed = executor.submit(() -> pool.unregister("owned-race", old));
            old.stopGate.countDown();

            assertSame("the replacement must be published after the confirmed stop",
                    replacement, replacing.get(2, TimeUnit.SECONDS).rawSession());
            assertFalse("a delayed unregister must not remove the replacement",
                    delayed.get(2, TimeUnit.SECONDS));
            assertSame(replacement, pool.getSession("owned-race"));
            assertEquals("the replacement must not be stopped by the delayed owner",
                    0, replacement.stops.get());
        } finally {
            old.stopGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10_000)
    public void requestStopFailsFastWhileAnotherThreadStopsTheSameInstance() throws Exception {
        FakeOwnedSession old = new FakeOwnedSession();
        old.stopEntered = new CountDownLatch(1);
        old.stopGate = new CountDownLatch(1);
        pool.register("owned-in-flight", old);
        FakeOwnedSession replacement = new FakeOwnedSession();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistentTerminalSessionPool.SessionHolder> replacing = executor.submit(
                    () -> pool.register("owned-in-flight", replacement));
            assertTrue("the old owner stop must have started", old.stopEntered.await(2, TimeUnit.SECONDS));

            AtomicBoolean actionRan = new AtomicBoolean();
            long started = System.nanoTime();
            assertFalse("a stop already in flight must reject a second power action",
                    pool.requestStop("owned-in-flight", old,
                            PersistentTerminalSessionPool.StopIntent.FORCE, () -> actionRan.set(true)));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            // The old implementation held the transition lock across the bounded
            // stop, so this call blocked until the gate below was released.
            assertTrue("requestStop must not wait for the transition owner: " + elapsedMs + " ms",
                    elapsedMs < 1000L);
            assertFalse("the rejected request must not run its action", actionRan.get());
            assertSame("the old holder stays published while its stop is in flight",
                    old, pool.getSession("owned-in-flight"));

            old.stopGate.countDown();
            assertSame("the replacement is published after the stop confirms",
                    replacement, replacing.get(2, TimeUnit.SECONDS).rawSession());
            assertSame(replacement, pool.getSession("owned-in-flight"));
        } finally {
            old.stopGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10_000)
    public void exitSideEffectsDoNotRunWhileTheOwnerIsStopping() throws Exception {
        FakeOwnedSession old = new FakeOwnedSession();
        old.stopEntered = new CountDownLatch(1);
        old.stopGate = new CountDownLatch(1);
        pool.register("owned-exit-race", old);

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<Boolean> retiring = executor.submit(() -> pool.unregister("owned-exit-race", old));
            assertTrue(old.stopEntered.await(2, TimeUnit.SECONDS));

            AtomicBoolean actionRan = new AtomicBoolean();
            assertFalse("exit side effects must wait for the retirement owner",
                    pool.completeExit("owned-exit-race", old, () -> actionRan.set(true)));
            assertFalse(actionRan.get());
            assertSame(old, pool.getSession("owned-exit-race"));

            old.stopGate.countDown();
            assertTrue(retiring.get(2, TimeUnit.SECONDS));
        } finally {
            old.stopGate.countDown();
            executor.shutdownNow();
        }
    }

    @Test(timeout = 10_000)
    public void unconfirmedStopDuringReplacementKeepsTheExistingHolder() throws Exception {
        FakeOwnedSession old = new FakeOwnedSession();
        old.stopResult = RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
        old.stopEntered = new CountDownLatch(1);
        old.stopGate = new CountDownLatch(1);
        pool.register("owned-unconfirmed-race", old);

        ExecutorService executor = Executors.newFixedThreadPool(1);
        try {
            Future<PersistentTerminalSessionPool.SessionHolder> replacing = executor.submit(
                    () -> pool.register("owned-unconfirmed-race", new FakeOwnedSession()));
            assertTrue(old.stopEntered.await(2, TimeUnit.SECONDS));
            old.stopGate.countDown();
            try {
                replacing.get(2, TimeUnit.SECONDS);
                org.junit.Assert.fail("an unconfirmed owner must block the replacement");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }
            assertSame("an unconfirmed stop must keep the existing holder",
                    old, pool.getSession("owned-unconfirmed-race"));
        } finally {
            old.stopGate.countDown();
            executor.shutdownNow();
        }
    }

    private static final class FakeOwnedSession implements RuntimeOwnedSession {
        volatile RuntimeProcessSupervisor.StopResult stopResult =
                RuntimeProcessSupervisor.StopResult.CONFIRMED;
        final AtomicInteger stops = new AtomicInteger();
        volatile boolean alive = true;
        volatile CountDownLatch stopEntered;
        volatile CountDownLatch stopGate;

        @Override public int waitFor() { return 0; }
        @Override public OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void close() { }

        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            stops.incrementAndGet();
            CountDownLatch entered = stopEntered;
            if (entered != null) entered.countDown();
            CountDownLatch gate = stopGate;
            if (gate != null) {
                boolean interrupted = false;
                while (true) {
                    try {
                        gate.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            if (stopResult != RuntimeProcessSupervisor.StopResult.UNCONFIRMED) alive = false;
            return stopResult;
        }

        @Override public boolean isRuntimeAlive() { return alive; }
    }
}
