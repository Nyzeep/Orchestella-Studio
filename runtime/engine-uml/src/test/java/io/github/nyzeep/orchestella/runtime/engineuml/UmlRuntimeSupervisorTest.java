package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class UmlRuntimeSupervisorTest {

    @Test
    public void enforcesStopReapReleaseOrderAndAllowsRootReuseAfterConfirmedStop() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        Path rootPath = Paths.get("build/test-uml-root-order").toAbsolutePath();
        UmlRootReservationRegistry.Reservation reservation =
                registry.acquire(rootPath, "inst-1", "gen-1");

        FakeProcess process = new FakeProcess(4242L, 0, true, events);
        process.exitOnDestroy = true;

        UmlRuntimeSupervisor supervisor = new UmlRuntimeSupervisor(
                process,
                () -> events.add("close-bridge"),
                () -> events.add("release-resources"),
                registry,
                reservation,
                150L,
                250L,
                null);

        UmlRuntimeSupervisor.StopEvidence evidence = supervisor.stop(150L);
        assertEquals(UmlRuntimeSupervisor.StopResult.CONFIRMED, evidence.result());
        assertEquals("confirmed", evidence.stage());
        assertEquals(4242L, evidence.pid());
        assertEquals(0, evidence.exitCode());
        assertTrue(evidence.reaped());
        assertFalse(evidence.forced());
        assertTrue(evidence.channelsReleased());
        assertTrue(evidence.resourcesReleased());
        assertTrue(evidence.rootReleased());

        int destroyIndex = events.indexOf("destroy");
        int reapIndex = events.indexOf("waitFor-reaped");
        int closeChannelsIndex = events.indexOf("close-process-channels");
        int closeBridgeIndex = events.indexOf("close-bridge");
        int releaseResourcesIndex = events.indexOf("release-resources");
        assertTrue(destroyIndex >= 0);
        assertTrue(reapIndex > destroyIndex);
        assertTrue(closeBridgeIndex > reapIndex);
        assertTrue(closeChannelsIndex > reapIndex);
        assertTrue(releaseResourcesIndex > closeChannelsIndex);

        // Reservation must be released only after confirmed stop.
        UmlRootReservationRegistry.Reservation second =
                registry.acquire(rootPath, "inst-1", "gen-2");
        assertNotNull(second);
        registry.release(second);

        // Repeat stop is idempotent and does not re-release resources.
        int countBefore = events.size();
        UmlRuntimeSupervisor.StopEvidence repeat = supervisor.stop(150L);
        assertEquals(UmlRuntimeSupervisor.StopResult.CONFIRMED, repeat.result());
        assertEquals(countBefore, events.size());
    }

    @Test
    public void unconfirmedStopRetainsRootReservationUntilRetryReapsProcess() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        Path rootPath = Paths.get("build/test-uml-root-unconfirmed").toAbsolutePath();
        UmlRootReservationRegistry.Reservation reservation =
                registry.acquire(rootPath, "inst-2", "gen-1");

        FakeProcess stubborn = new FakeProcess(9001L, 137, true, events);
        stubborn.exitOnDestroy = false;
        stubborn.exitOnDestroyForcibly = false;

        UmlRuntimeSupervisor supervisor = new UmlRuntimeSupervisor(
                stubborn,
                () -> events.add("close-bridge"),
                () -> events.add("release-resources"),
                registry,
                reservation,
                50L,
                250L,
                null);

        UmlRuntimeSupervisor.StopEvidence first = supervisor.stop(30L);
        assertEquals(UmlRuntimeSupervisor.StopResult.UNCONFIRMED, first.result());
        assertEquals("force-wait", first.stage());
        assertFalse(first.reaped());
        assertTrue(first.forced());
        assertFalse(first.resourcesReleased());
        assertFalse(first.rootReleased());
        assertFalse(events.contains("release-resources"));

        // Must refuse re-launch with the same root while stop is unconfirmed.
        try {
            registry.acquire(rootPath, "inst-2", "gen-2");
            fail("expected RootVolumeBusyException while stop is unconfirmed");
        } catch (UmlRootReservationRegistry.RootVolumeBusyException expected) {
            assertTrue(expected.getMessage().contains("inst-2"));
        }

        // Now the process actually dies on the OS side; retry reaps and releases root.
        stubborn.markExited(137);
        UmlRuntimeSupervisor.StopEvidence retry = supervisor.stop(30L);
        assertEquals(UmlRuntimeSupervisor.StopResult.CONFIRMED, retry.result());
        assertTrue(retry.reaped());
        assertEquals(137, retry.exitCode());
        assertTrue(retry.resourcesReleased());
        assertTrue(retry.rootReleased());
        assertTrue(events.contains("release-resources"));

        UmlRootReservationRegistry.Reservation next =
                registry.acquire(rootPath, "inst-2", "gen-2");
        registry.release(next);
    }

    @Test
    public void eofSignalAloneDoesNotReleaseAliveProcessAndCoalescesConcurrentStops() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        Path rootPath = Paths.get("build/test-uml-root-coalesce").toAbsolutePath();
        UmlRootReservationRegistry.Reservation reservation =
                registry.acquire(rootPath, "inst-3", "gen-1");

        FakeProcess process = new FakeProcess(5150L, 0, true, events);
        process.exitOnDestroy = false;
        process.exitOnDestroyForcibly = true;
        AtomicInteger releaseCount = new AtomicInteger();

        UmlRuntimeSupervisor supervisor = new UmlRuntimeSupervisor(
                process,
                () -> events.add("close-bridge"),
                () -> {
                    releaseCount.incrementAndGet();
                    events.add("release-resources");
                },
                registry,
                reservation,
                40L,
                250L,
                null);

        // EOF while process is still alive does not fake exit or release root.
        supervisor.signalTerminalEof();
        assertTrue(process.isAlive());
        assertEquals(0, releaseCount.get());
        try {
            registry.acquire(rootPath, "inst-3", "gen-2");
            fail("expected root still busy while process is alive after EOF");
        } catch (UmlRootReservationRegistry.RootVolumeBusyException expected) {
            // expected
        }

        // Concurrent user stop, launch cancel, and watcher stop coalesce to one stop owner.
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch gate = new CountDownLatch(1);
        Future<UmlRuntimeSupervisor.StopEvidence> f1 = pool.submit(() -> {
            gate.await();
            return supervisor.stop(30L);
        });
        Future<UmlRuntimeSupervisor.StopEvidence> f2 = pool.submit(() -> {
            gate.await();
            return supervisor.cancelLaunch(30L);
        });
        Future<UmlRuntimeSupervisor.StopEvidence> f3 = pool.submit(() -> {
            gate.await();
            return supervisor.stopFromWatcher(30L);
        });
        gate.countDown();
        UmlRuntimeSupervisor.StopEvidence e1 = f1.get(5, TimeUnit.SECONDS);
        UmlRuntimeSupervisor.StopEvidence e2 = f2.get(5, TimeUnit.SECONDS);
        UmlRuntimeSupervisor.StopEvidence e3 = f3.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(UmlRuntimeSupervisor.StopResult.CONFIRMED, e1.result());
        assertEquals(e1.result(), e2.result());
        assertEquals(e1.result(), e3.result());
        assertEquals(1, releaseCount.get());
    }

    @Test
    public void hungChannelCloseFailsSafeAsUnconfirmedAndRetainsRootReservation() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        Path rootPath = Paths.get("build/test-uml-root-hung").toAbsolutePath();
        UmlRootReservationRegistry.Reservation reservation =
                registry.acquire(rootPath, "inst-4", "gen-1");

        FakeProcess process = new FakeProcess(777L, 0, false, events);
        CountDownLatch hangLatch = new CountDownLatch(1);
        UmlRuntimeSupervisor supervisor = new UmlRuntimeSupervisor(
                process,
                () -> {
                    try {
                        hangLatch.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                },
                () -> events.add("release-resources"),
                registry,
                reservation,
                40L,
                40L,
                null);

        UmlRuntimeSupervisor.StopEvidence evidence = supervisor.stop(20L);
        assertEquals(UmlRuntimeSupervisor.StopResult.UNCONFIRMED, evidence.result());
        assertEquals("channels", evidence.stage());
        assertTrue(evidence.reaped());
        assertFalse(evidence.channelsReleased());
        assertFalse(evidence.rootReleased());
        hangLatch.countDown();
    }

    private static final class FakeProcess implements UmlProcessHandle {
        private final long pid;
        private final AtomicBoolean alive;
        private final AtomicInteger exitCode;
        private final List<String> events;
        private final CountDownLatch exitLatch;
        volatile boolean exitOnDestroy;
        volatile boolean exitOnDestroyForcibly;

        FakeProcess(long pid, int initialCode, boolean initiallyAlive, List<String> events) {
            this.pid = pid;
            this.exitCode = new AtomicInteger(initialCode);
            this.alive = new AtomicBoolean(initiallyAlive);
            this.events = events;
            this.exitLatch = new CountDownLatch(initiallyAlive ? 1 : 0);
        }

        void markExited(int code) {
            exitCode.set(code);
            if (alive.compareAndSet(true, false)) {
                events.add("waitFor-reaped");
            }
            exitLatch.countDown();
        }

        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive.get(); }

        @Override
        public int waitFor() throws InterruptedException {
            exitLatch.await();
            return exitCode.get();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitLatch.await(timeout, unit);
        }

        @Override
        public void destroy() {
            events.add("destroy");
            if (exitOnDestroy) {
                markExited(exitCode.get());
            }
        }

        @Override
        public void destroyForcibly() {
            events.add("destroyForcibly");
            if (exitOnDestroyForcibly) {
                markExited(exitCode.get());
            }
        }

        @Override public OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public boolean resizeTerminal(int columns, int rows) { return true; }

        @Override
        public void closeChannels() {
            events.add("close-process-channels");
        }
    }
}
