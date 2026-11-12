package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.FabricOrchestrator.FaceException;
import io.github.nyzeep.orchestella.FabricOrchestrator.FaceWorker;
import io.github.nyzeep.orchestella.FabricWidthDecision.Face;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridge;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/**
 * Gate 1 (ACR 2026-11-13 方案 A, fabric-route doc D-1): the first fabric
 * submitter's contract. One accepted loopback connection = one
 * {@link FabricCompileItem} byte pipe dispatched into the
 * {@link FabricOrchestrator} with parallelism 1; the winning face's worker
 * pumps the pipe through its own channel. Locked here over a real listener and
 * a real orchestrator: the round trip through an echo face worker, the fixed
 * {@code FABRIC_NOT_ARMED} refusal, the gate-off and no-face refusals (the face
 * worker is never touched), the face-death eviction and re-arm path, and the
 * grant/release reconciliation (a held width refuses new work; a released one
 * serves again). The listener's lifecycle is idempotent start/stop, bound to
 * the face's arm/disarm by the wiring owner.
 */
public final class FabricCompileSubmitterTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    // --- the round trip: listener → dispatch → face worker → answer ------------------

    @Test(timeout = 10000)
    public void dispatchesOneAcceptedConnectionToTheFabricAndBack() throws Exception {
        EchoWorker umlWorker = new EchoWorker();
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        AtomicReference<FabricOrchestrator> bound = new AtomicReference<>(orchestrator(umlWorker));
        try (FabricCompileSubmitter submitter = new FabricCompileSubmitter(
                bound::get, 0, 4, 0, stats::add)) {
            submitter.start(LOOPBACK);
            try (Socket client = new Socket(LOOPBACK, submitter.port())) {
                client.setSoTimeout(3000);
                client.getOutputStream().write("compile-me".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();
                byte[] echoed = client.getInputStream().readAllBytes();
                assertEquals("compile-me", new String(echoed, StandardCharsets.UTF_8));
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.COMPLETED, stats.get(0).outcome());
            assertEquals("compile-me".length(), stats.get(0).bytesFromClient());
            assertEquals("compile-me".length(), stats.get(0).bytesToClient());
            assertEquals("the UML face won the route and executed the job",
                    List.of("UML:1"), umlWorker.executions);
        }
    }

    // --- the fixed refusal mapping ---------------------------------------------------

    @Test(timeout = 10000)
    public void dispatchWithNoOrchestratorCarriesTheNotArmedFixedCode() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        AtomicReference<FabricOrchestrator> bound = new AtomicReference<>();
        try (FabricCompileSubmitter submitter = new FabricCompileSubmitter(
                bound::get, 0, 4, 0, stats::add)) {
            submitter.start(LOOPBACK);
            try (Socket client = new Socket(LOOPBACK, submitter.port())) {
                client.setSoTimeout(3000);
                assertClosed(client);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.CHANNEL_FAILED, stats.get(0).outcome());
            assertEquals("the not-armed refusal rides open() itself, code preserved",
                    FabricCompileSubmitter.NOT_ARMED_REASON, stats.get(0).failureCode());
        }
    }

    @Test(timeout = 10000)
    public void gateOffDispatchFailsTheConnectionWithoutTouchingTheFace() throws Exception {
        EchoWorker umlWorker = new EchoWorker();
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        AtomicReference<FabricOrchestrator> bound = new AtomicReference<>(
                new FabricOrchestrator(false, widths(Face.UML, 1), workers(Face.UML, umlWorker)));
        try (FabricCompileSubmitter submitter = new FabricCompileSubmitter(
                bound::get, 0, 4, 0, stats::add)) {
            submitter.start(LOOPBACK);
            try (Socket client = new Socket(LOOPBACK, submitter.port())) {
                client.setSoTimeout(3000);
                assertClosed(client);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.CHANNEL_FAILED, stats.get(0).outcome());
            assertEquals("a gate-off run touches no face worker",
                    0, umlWorker.executions.size());
        }
    }

    @Test(timeout = 10000)
    public void noFaceAvailableRefusalFailsTheConnection() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        // Width is declared but the workers map is empty: a genuine outage.
        AtomicReference<FabricOrchestrator> bound = new AtomicReference<>(
                new FabricOrchestrator(true, widths(Face.UML, 1), workers()));
        try (FabricCompileSubmitter submitter = new FabricCompileSubmitter(
                bound::get, 0, 4, 0, stats::add)) {
            submitter.start(LOOPBACK);
            try (Socket client = new Socket(LOOPBACK, submitter.port())) {
                client.setSoTimeout(3000);
                assertClosed(client);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.CHANNEL_FAILED, stats.get(0).outcome());
        }
    }

    // --- face death: FACE_NOT_RUNNING → evict → re-armed ------------------------------

    @Test(timeout = 10000)
    public void faceDeathEvictsTheFaceAndReArmingRestoresTheRoute() throws Exception {
        // The worker's first execution dies with the launcher's fixed
        // FACE_NOT_RUNNING detail (the shape of a dispatch reaching a face that
        // stopped or died mid-flight); the rebuild path repairs it after.
        EchoWorker worker = new EchoWorker(
                new FaceException(UmlFaceLauncher.FACE_NOT_RUNNING_DETAIL));
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        AtomicReference<FabricOrchestrator> bound = new AtomicReference<>(orchestrator(worker));
        try (FabricCompileSubmitter submitter = new FabricCompileSubmitter(
                bound::get, 0, 4, 0, stats::add)) {
            submitter.start(LOOPBACK);

            // 1. The face dies mid-dispatch: the job fails, the face is evicted,
            //    the grant was released with the eviction.
            try (Socket client = new Socket(LOOPBACK, submitter.port())) {
                client.setSoTimeout(3000);
                assertClosed(client);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals("stats=" + stats,
                    DistccTcpVsockBridge.Outcome.CHANNEL_FAILED, stats.get(0).outcome());
            assertEquals("executions=" + worker.executions,
                    List.of("UML:1"), worker.executions);

            // 2. The evicted face is out of routing: the next job refuses
            //    without ever reaching the (already repaired) worker.
            try (Socket client = new Socket(LOOPBACK, submitter.port())) {
                client.setSoTimeout(3000);
                assertClosed(client);
            }
            waitFor(() -> stats.size() == 2, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.CHANNEL_FAILED, stats.get(1).outcome());
            assertEquals(1, worker.executions.size());

            // 3. The re-arm path (the face restarted) puts it back into routing
            //    and the repaired worker serves the next job.
            bound.get().arm(Face.UML);
            try (Socket client = new Socket(LOOPBACK, submitter.port())) {
                client.setSoTimeout(3000);
                client.getOutputStream().write("after-repair".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();
                byte[] echoed = client.getInputStream().readAllBytes();
                assertEquals("after-repair", new String(echoed, StandardCharsets.UTF_8));
            }
            waitFor(() -> stats.size() == 3, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.COMPLETED, stats.get(2).outcome());
            assertEquals(List.of("UML:1", "UML:1"), worker.executions);
        }
    }

    @Test(timeout = 15000)
    public void grantLedgerStaysBalancedUnderInFlightWork() throws Exception {
        // The width-1 face takes one in-flight job; the second connection must
        // refuse (no face width left), and once the first job's grant is
        // released the face serves again — the D-3 sum invariant at the
        // submitter level, no accounting leak either way.
        GatedEchoWorker worker = new GatedEchoWorker();
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        AtomicReference<FabricOrchestrator> bound = new AtomicReference<>(orchestrator(worker));
        try (FabricCompileSubmitter submitter = new FabricCompileSubmitter(
                bound::get, 0, 4, 0, stats::add)) {
            submitter.start(LOOPBACK);

            Socket inFlight = new Socket(LOOPBACK, submitter.port());
            inFlight.setSoTimeout(5000);
            // The held job's request is sent up front; the face worker gates on
            // the latch before pumping it back.
            inFlight.getOutputStream().write("held-job".getBytes(StandardCharsets.UTF_8));
            inFlight.getOutputStream().flush();
            inFlight.shutdownOutput();
            assertTrue("the first job must be admitted and held mid-flight",
                    worker.firstStarted.await(5, TimeUnit.SECONDS));

            try (Socket second = new Socket(LOOPBACK, submitter.port())) {
                second.setSoTimeout(3000);
                assertClosed(second);
            }
            waitFor(() -> stats.stream().anyMatch(
                    item -> item.outcome() == DistccTcpVsockBridge.Outcome.CHANNEL_FAILED), 3000);
            assertEquals("only the in-flight job ever reached the worker",
                    1, worker.executions.size());

            // Release the grant: the held job completes, and the face serves again.
            worker.release.countDown();
            byte[] echoed = inFlight.getInputStream().readAllBytes();
            assertEquals("held-job", new String(echoed, StandardCharsets.UTF_8));
            inFlight.close();
            waitFor(() -> stats.stream().anyMatch(
                    item -> item.outcome() == DistccTcpVsockBridge.Outcome.COMPLETED), 3000);

            try (Socket third = new Socket(LOOPBACK, submitter.port())) {
                third.setSoTimeout(3000);
                third.getOutputStream().write("again".getBytes(StandardCharsets.UTF_8));
                third.getOutputStream().flush();
                third.shutdownOutput();
                assertEquals("again",
                        new String(third.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    // --- the arm-disarm lifecycle binding ---------------------------------------------

    @Test(timeout = 10000)
    public void listenerLifecycleIsIdempotentStartAndStop() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        AtomicReference<FabricOrchestrator> bound = new AtomicReference<>(orchestrator(new EchoWorker()));
        FabricCompileSubmitter submitter = new FabricCompileSubmitter(
                bound::get, 0, 4, 0, stats::add);
        assertFalse(submitter.isRunning());
        assertThrows(IllegalStateException.class, submitter::port);

        submitter.start(LOOPBACK);
        submitter.start(LOOPBACK); // idempotent while running
        int port = submitter.port();
        assertTrue(port > 0);
        assertTrue(submitter.isRunning());

        submitter.close();
        submitter.close(); // idempotent
        assertFalse(submitter.isRunning());
        assertThrows(IllegalStateException.class, submitter::port);
    }

    // --- fixtures ----------------------------------------------------------------------

    private static FabricOrchestrator orchestrator(FaceWorker umlWorker) {
        return new FabricOrchestrator(true, widths(Face.UML, 1), workers(Face.UML, umlWorker));
    }

    private static FabricOrchestrator.FaceWidthSource widths(Face face, int width) {
        return () -> {
            Map<Face, Integer> map = new EnumMap<>(Face.class);
            map.put(face, width);
            return map;
        };
    }

    private static Map<Face, FaceWorker> workers(Object... pairs) {
        Map<Face, FaceWorker> map = new EnumMap<>(Face.class);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((Face) pairs[i], (FaceWorker) pairs[i + 1]);
        }
        return map;
    }

    private static void assertClosed(Socket client) throws IOException {
        try {
            assertEquals(-1, client.getInputStream().read());
        } catch (IOException expected) {
            // A reset is also a valid closed-client outcome.
        }
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue("condition not met within " + timeoutMs + "ms", condition.getAsBoolean());
    }

    /**
     * The UML face worker as an echo "distccd": pumps the item's byte pipe
     * back through the face's channel (here, identity). Records every
     * execution; optionally throws once — the model of a face that died
     * mid-dispatch before the rebuild path repairs it.
     */
    private static class EchoWorker implements FaceWorker {
        final List<String> executions = new CopyOnWriteArrayList<>();
        private final FaceException firstExecutionFailure;
        private final AtomicBoolean failed = new AtomicBoolean();

        EchoWorker() {
            this(null);
        }

        EchoWorker(FaceException firstExecutionFailure) {
            this.firstExecutionFailure = firstExecutionFailure;
        }

        @Override
        public void execute(Face face, int grantedWidth, FabricOrchestrator.WorkItem raw)
                throws FaceException {
            executions.add(face + ":" + grantedWidth);
            if (firstExecutionFailure != null && failed.compareAndSet(false, true)) {
                throw firstExecutionFailure;
            }
            pump(raw);
        }

        void pump(FabricOrchestrator.WorkItem raw) throws FaceException {
            FabricCompileItem item = (FabricCompileItem) raw;
            try {
                item.request().transferTo(item.response());
            } catch (IOException pumpFailure) {
                throw new FaceException("echo pump failed", pumpFailure);
            } finally {
                try {
                    item.response().close();
                } catch (IOException ignored) {
                    // The terminal outcome is already set.
                }
            }
        }
    }

    /** An echo worker whose first job is held mid-flight until released. */
    private static final class GatedEchoWorker extends EchoWorker {
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void execute(Face face, int grantedWidth, FabricOrchestrator.WorkItem raw)
                throws FaceException {
            executions.add(face + ":" + grantedWidth);
            if (firstStarted.getCount() > 0) {
                firstStarted.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new FaceException("test fixture: held job timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new FaceException("test fixture: held job interrupted");
                }
            }
            pump(raw);
        }
    }
}
