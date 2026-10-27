package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Controller;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.DistroReference;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Instance;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.State;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Store;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Contract for root-volume mutation while another Controller touches the same instance.
 *
 * <p>{@code resizeRootVolume} rewrites the disk image, so it must hold the same directory-wide
 * monitor as the lifecycle transitions. Without it a second Controller can start (or copy) the
 * instance between the resize's state check and its metadata write, and the resize then persists
 * its stale snapshot over the newer state.
 *
 * <p>Both cases are made deterministic with store-level gates: the resizing controller takes its
 * metadata snapshot and then parks inside {@code load} until the peer has had its chance. With the
 * shared monitor the peer cannot get that far, so the gate times out and the operations serialize.
 */
public final class ConcurrentResizeContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);
    private static final long OLD_SIZE_BYTES = 1024L * 1024L;
    private static final long NEW_SIZE_BYTES = 4L * 1024L * 1024L;
    private static final long GATE_TIMEOUT_MILLIS = 1000L;
    private static final StorageContract.StorageSpaceChecker PLENTY_OF_SPACE =
            path -> 1024L * 1024L * 1024L;

    @Test
    public void resizeAndStartKeepStateAndVolumeConsistent() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-concurrent-resize-start-");
        try {
            Path volume = directory.resolve("shared.raw");
            createVolume(volume, OLD_SIZE_BYTES);
            Instance instance = createStopped(new Controller(new FileStore(directory), CLOCK), volume);

            CountDownLatch resizeLoaded = new CountDownLatch(1);
            CountDownLatch startFinished = new CountDownLatch(1);
            AtomicReference<State> startedAs = new AtomicReference<>();
            AtomicInteger resizeRejections = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Controller resizer = new Controller(
                    new ResizeGateStore(new FileStore(directory), resizeLoaded, startFinished), CLOCK);
            Controller starter = new Controller(new FileStore(directory), CLOCK);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Thread resizeThread = worker("orchestella-resize", ready, go, unexpected, () -> {
                try {
                    resizer.resizeRootVolume(instance.id(), NEW_SIZE_BYTES, PLENTY_OF_SPACE);
                } catch (IllegalStateException rejected) {
                    resizeRejections.incrementAndGet();
                }
            });
            Thread startThread = worker("orchestella-start", ready, go, unexpected, () -> {
                try {
                    // The starting controller may only reach the instance once the resize has
                    // taken its metadata snapshot.
                    awaitQuietly(resizeLoaded, GATE_TIMEOUT_MILLIS * 5);
                    startedAs.set(starter.start(instance.id()).state());
                } finally {
                    startFinished.countDown();
                }
            });
            runBoth(resizeThread, startThread, ready, go);

            assertNoUnexpected(unexpected.get());
            assertEquals("the resize must be accepted while the instance is STOPPED", 0, resizeRejections.get());
            assertEquals("start() must report RUNNING", State.RUNNING, startedAs.get());

            Instance persisted = new FileStore(directory).load(instance.id());
            long persistedSize = Files.size(volume);
            assertEquals(
                    "start() must stay durable: a concurrent resize must not overwrite the RUNNING state",
                    State.RUNNING,
                    persisted.state());
            assertEquals(
                    "a RUNNING instance must expose the completely resized volume, never the old or a partial size",
                    NEW_SIZE_BYTES,
                    persistedSize);
            assertTrue(
                    "volume size must be the old or the requested size, never an intermediate value",
                    persistedSize == OLD_SIZE_BYTES || persistedSize == NEW_SIZE_BYTES);
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void resizeAndCloneNeverOverlapOnTheVolume() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-concurrent-resize-clone-");
        try {
            Path volume = directory.resolve("shared.raw");
            Path cloneVolume = directory.resolve("clone.raw");
            createVolume(volume, OLD_SIZE_BYTES);
            Instance instance = createStopped(new Controller(new FileStore(directory), CLOCK), volume);

            VolumeWindow window = new VolumeWindow();
            AtomicReference<Instance> cloned = new AtomicReference<>();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Controller resizer = new Controller(
                    new ContentGateStore(new FileStore(directory), window, true), CLOCK);
            Controller cloner = new Controller(
                    new ContentGateStore(new FileStore(directory), window, false), CLOCK);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            Thread resizeThread = worker("orchestella-resize", ready, go, unexpected,
                    () -> resizer.resizeRootVolume(instance.id(), NEW_SIZE_BYTES, PLENTY_OF_SPACE));
            Thread cloneThread = worker("orchestella-clone", ready, go, unexpected, () -> {
                awaitQuietly(window.resizeLoaded, GATE_TIMEOUT_MILLIS * 5);
                cloned.set(cloner.cloneInstance(instance.id(), cloneVolume));
            });
            runBoth(resizeThread, cloneThread, ready, go);

            assertNull(
                    "another Controller touched the root volume inside resizeRootVolume's snapshot-to-save window: "
                            + window.violation.get(),
                    window.violation.get());
            assertNoUnexpected(unexpected.get());
            assertEquals(
                    "the resize must complete against the STOPPED source",
                    NEW_SIZE_BYTES,
                    Files.size(volume));
            assertEquals(
                    "the clone must copy the fully resized volume, never the old or a partial size",
                    NEW_SIZE_BYTES,
                    Files.size(cloneVolume));
            assertEquals("a clone starts in CREATED", State.CREATED, cloned.get().state());
            assertEquals(
                    "the cloned metadata must be readable",
                    State.CREATED,
                    new FileStore(directory).load(cloned.get().id()).state());
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void runBoth(
            Thread first, Thread second, CountDownLatch ready, CountDownLatch go) throws Exception {
        first.start();
        second.start();
        assertTrue("both controllers reached the start gate", ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        first.join(10_000);
        second.join(10_000);
        assertTrue("first controller completed", !first.isAlive());
        assertTrue("second controller completed", !second.isAlive());
    }

    private static Thread worker(
            String name,
            CountDownLatch ready,
            CountDownLatch go,
            AtomicReference<Throwable> unexpected,
            ThrowingAction action) {
        return new Thread(() -> {
            ready.countDown();
            try {
                if (!go.await(5, TimeUnit.SECONDS)) {
                    unexpected.compareAndSet(null, new AssertionError("start gate timed out"));
                    return;
                }
                action.run();
            } catch (Throwable failure) {
                unexpected.compareAndSet(null, failure);
            }
        }, name);
    }

    private static void assertNoUnexpected(Throwable failure) {
        if (failure != null) {
            throw new AssertionError("unexpected failure", failure);
        }
    }

    private static void awaitQuietly(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating the race", interrupted);
        }
    }

    private static Instance createStopped(Controller controller, Path volume) throws Exception {
        Instance instance = controller.create(new DistroReference("debian", "13"), volume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        return instance;
    }

    private static void createVolume(Path volume, long sizeBytes) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(volume.toFile(), "rw")) {
            file.setLength(sizeBytes);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    /**
     * Holds the resizing controller inside {@code load} after it took its metadata snapshot, so a
     * start on another controller has the whole window between snapshot and save to slip in.
     */
    private static final class ResizeGateStore implements Store {
        private final Store delegate;
        private final CountDownLatch resizeLoaded;
        private final CountDownLatch peerFinished;

        private ResizeGateStore(Store delegate, CountDownLatch resizeLoaded, CountDownLatch peerFinished) {
            this.delegate = delegate;
            this.resizeLoaded = resizeLoaded;
            this.peerFinished = peerFinished;
        }

        @Override
        public Object stateMonitor() {
            return delegate.stateMonitor();
        }

        @Override
        public void save(Instance instance) throws IOException {
            delegate.save(instance);
        }

        @Override
        public Instance load(String id) throws IOException {
            Instance loaded = delegate.load(id);
            resizeLoaded.countDown();
            awaitQuietly(peerFinished, GATE_TIMEOUT_MILLIS);
            return loaded;
        }

        @Override
        public List<Instance> list() throws IOException {
            return delegate.list();
        }

        @Override
        public void delete(String id) throws IOException {
            delegate.delete(id);
        }
    }

    /** Shared observation of the resize's snapshot-to-save window on one root volume. */
    private static final class VolumeWindow {
        private final AtomicInteger mutating = new AtomicInteger();
        private final AtomicReference<String> violation = new AtomicReference<>();
        private final CountDownLatch resizeLoaded = new CountDownLatch(1);
        private final CountDownLatch peerEntered = new CountDownLatch(1);
    }

    /**
     * Opening the window after the resize took its snapshot and recording any peer that reaches the
     * volume while the window is open. With the directory-wide monitor the peer cannot reach it.
     */
    private static final class ContentGateStore implements Store {
        private final Store delegate;
        private final VolumeWindow window;
        private final boolean resizing;

        private ContentGateStore(Store delegate, VolumeWindow window, boolean resizing) {
            this.delegate = delegate;
            this.window = window;
            this.resizing = resizing;
        }

        @Override
        public Object stateMonitor() {
            return delegate.stateMonitor();
        }

        @Override
        public Instance load(String id) throws IOException {
            if (resizing) {
                Instance loaded = delegate.load(id);
                window.mutating.incrementAndGet();
                window.resizeLoaded.countDown();
                awaitQuietly(window.peerEntered, GATE_TIMEOUT_MILLIS);
                return loaded;
            }
            if (window.mutating.get() > 0) {
                window.violation.compareAndSet(null, "read the source metadata of " + id);
            }
            window.peerEntered.countDown();
            return delegate.load(id);
        }

        @Override
        public void save(Instance instance) throws IOException {
            try {
                if (!resizing && window.mutating.get() > 0) {
                    window.violation.compareAndSet(null, "wrote while resizing");
                }
                delegate.save(instance);
            } finally {
                if (resizing) {
                    window.mutating.decrementAndGet();
                }
            }
        }

        @Override
        public List<Instance> list() throws IOException {
            return delegate.list();
        }

        @Override
        public void delete(String id) throws IOException {
            delegate.delete(id);
        }
    }
}
