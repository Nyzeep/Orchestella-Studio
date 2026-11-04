package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Controller;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.DistroReference;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Instance;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Contract for the directory-wide write lock across independently constructed Controllers.
 *
 * <p>Every Controller write path (create, delete, exportToArchive) must hold the Store's
 * {@code stateMonitor()} for the whole call, not just the Controller instance monitor. The
 * app constructs several Controllers over one state directory, so the Controller monitor
 * alone cannot serialize these writes.
 */
public final class InstanceLifecycleWriteLockContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);
    private static final DistroReference DISTRO = new DistroReference("debian", "13");

    @Test
    public void createOverloadsCallStoreSaveUnderTheDirectoryStateMonitor() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-create-");
        try {
            MonitorAssertingStore store = new MonitorAssertingStore(new FileStore(directory));
            Controller controller = new Controller(store, CLOCK);

            controller.create(DISTRO, directory.resolve("two-arg.raw"));
            controller.create(DISTRO, directory.resolve("three-arg.raw"),
                    io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig());
            controller.create(DISTRO, directory.resolve("four-arg.raw"),
                    io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig(),
                    null);

            assertEquals("every create overload reached store.save", 3, store.saveCalls.get());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void deleteCallsStoreDeleteUnderTheDirectoryStateMonitor() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-delete-");
        try {
            FileStore backing = new FileStore(directory);
            Controller setup = new Controller(backing, CLOCK);
            Instance instance = setup.create(DISTRO, directory.resolve("to-delete.raw"));
            Files.write(instance.rootVolume(), new byte[] {1, 2, 3});

            MonitorAssertingStore store = new MonitorAssertingStore(new FileStore(directory));
            new Controller(store, CLOCK).delete(instance.id());

            assertEquals("delete reached store.delete", 1, store.deleteCalls.get());
            assertEquals("instance is gone", 0, backing.list().size());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void exportToArchiveCallsStoreLoadUnderTheDirectoryStateMonitor() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-export-");
        try {
            FileStore backing = new FileStore(directory);
            Controller setup = new Controller(backing, CLOCK);
            Instance instance = setup.create(DISTRO, directory.resolve("to-export.raw"));
            Files.write(instance.rootVolume(), new byte[] {4, 5, 6, 7});

            MonitorAssertingStore store = new MonitorAssertingStore(new FileStore(directory));
            new Controller(store, CLOCK).exportToArchive(instance.id(), new ByteArrayOutputStream());

            assertEquals("exportToArchive reached store.load", 1, store.loadCalls.get());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void twoControllersCannotCreateConcurrently() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-create-race-");
        try {
            Race race = race(
                    directory,
                    Probe.SAVE,
                    controller -> controller.create(DISTRO, directory.resolve("race-a.raw")),
                    controller -> controller.create(DISTRO, directory.resolve("race-b.raw")));

            assertNoUnexpected(race);
            assertEquals("both creates succeeded", 2, race.successes.get());
            assertFalse(
                    "create must be serialized by the directory state monitor",
                    race.metAtBarrier.get());
            assertEquals(2, new FileStore(directory).list().size());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void twoControllersCannotDeleteConcurrently() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-delete-race-");
        try {
            Controller setup = new Controller(new FileStore(directory), CLOCK);
            Instance first = setup.create(DISTRO, directory.resolve("delete-a.raw"));
            Instance second = setup.create(DISTRO, directory.resolve("delete-b.raw"));
            Files.write(first.rootVolume(), new byte[] {1});
            Files.write(second.rootVolume(), new byte[] {2});

            Race race = race(
                    directory,
                    Probe.DELETE,
                    controller -> controller.delete(first.id()),
                    controller -> controller.delete(second.id()));

            assertNoUnexpected(race);
            assertEquals("both deletes succeeded", 2, race.successes.get());
            assertFalse(
                    "delete must be serialized by the directory state monitor",
                    race.metAtBarrier.get());
            assertEquals("every instance was purged", 0, new FileStore(directory).list().size());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void twoControllersCannotExportToArchiveConcurrently() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-export-race-");
        try {
            Controller setup = new Controller(new FileStore(directory), CLOCK);
            Instance first = setup.create(DISTRO, directory.resolve("export-a.raw"));
            Instance second = setup.create(DISTRO, directory.resolve("export-b.raw"));
            Files.write(first.rootVolume(), new byte[] {1, 2, 3});
            Files.write(second.rootVolume(), new byte[] {4, 5, 6});

            Race race = race(
                    directory,
                    Probe.LOAD,
                    controller -> controller.exportToArchive(first.id(), new ByteArrayOutputStream()),
                    controller -> controller.exportToArchive(second.id(), new ByteArrayOutputStream()));

            assertNoUnexpected(race);
            assertEquals("both exports succeeded", 2, race.successes.get());
            assertFalse(
                    "exportToArchive must be serialized by the directory state monitor",
                    race.metAtBarrier.get());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void concurrentCreatesAcrossControllersProduceDistinctLoadableInstances() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-create-hammer-");
        final int threads = 8;
        final int perThread = 5;
        try {
            List<Throwable> failures = new ArrayList<>();
            AtomicInteger created = new AtomicInteger();
            runOnThreads(threads, () -> {
                Controller controller = new Controller(new FileStore(directory), CLOCK);
                for (int index = 0; index < perThread; index++) {
                    Instance instance = controller.create(
                            DISTRO,
                            directory.resolve("hammer-" + Thread.currentThread().getName()
                                    + "-" + index + ".raw"));
                    Files.write(instance.rootVolume(), new byte[] {(byte) index, 9, 9});
                    controller.exportToArchive(instance.id(), new ByteArrayOutputStream());
                    created.incrementAndGet();
                }
            }, failures);

            assertEquals("no unexpected failure", List.of(), failures);
            List<Instance> listed = new FileStore(directory).list();
            assertEquals(threads * perThread, listed.size());
            Set<String> ids = new HashSet<>();
            for (Instance instance : listed) {
                ids.add(instance.id());
                assertEquals(InstanceLifecycle.State.CREATED, instance.state());
            }
            assertEquals("every instance id is unique", threads * perThread, ids.size());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void createExportDeleteHammerKeepsTheStoreConsistent() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-write-lock-hammer-");
        final int threads = 8;
        final int perThread = 6;
        try {
            List<Throwable> failures = new ArrayList<>();
            AtomicInteger created = new AtomicInteger();
            AtomicInteger deleted = new AtomicInteger();
            runOnThreads(threads, () -> {
                Controller controller = new Controller(new FileStore(directory), CLOCK);
                for (int index = 0; index < perThread; index++) {
                    Instance instance = controller.create(
                            DISTRO,
                            directory.resolve("cycle-" + Thread.currentThread().getName()
                                    + "-" + index + ".raw"));
                    created.incrementAndGet();
                    Files.write(instance.rootVolume(), new byte[] {(byte) index, 1, 2, 3});
                    controller.exportToArchive(instance.id(), new ByteArrayOutputStream());
                    controller.delete(instance.id());
                    deleted.incrementAndGet();
                }
            }, failures);

            assertEquals("no unexpected failure", List.of(), failures);
            assertEquals(threads * perThread, created.get());
            assertEquals("every created instance was deleted", created.get(), deleted.get());
            assertEquals("no metadata was left behind", 0, new FileStore(directory).list().size());
            try (var entries = Files.list(directory)) {
                assertEquals("no stray .tmp files were left behind", 0,
                        entries.filter(path -> path.getFileName().toString().endsWith(".tmp")).count());
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    // ---- helpers ----

    private static Race race(Path directory, Probe probe, Action firstAction, Action secondAction)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Controller firstController = new Controller(
                new BarrierStore(new FileStore(directory), barrier, probe), CLOCK);
        Controller secondController = new Controller(
                new BarrierStore(new FileStore(directory), barrier, probe), CLOCK);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Race race = new Race();
        Thread firstThread = new Thread(
                () -> run(race, ready, go, () -> firstAction.run(firstController)),
                "orchestella-write-race-first");
        Thread secondThread = new Thread(
                () -> run(race, ready, go, () -> secondAction.run(secondController)),
                "orchestella-write-race-second");
        firstThread.start();
        secondThread.start();
        assertTrue("both controllers reached the start gate", ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        firstThread.join(5_000);
        secondThread.join(5_000);
        assertTrue("first controller completed", !firstThread.isAlive());
        assertTrue("second controller completed", !secondThread.isAlive());
        return race;
    }

    private static void run(Race race, CountDownLatch ready, CountDownLatch go, ThrowingAction action) {
        ready.countDown();
        try {
            if (!go.await(5, TimeUnit.SECONDS)) {
                race.unexpected.add(new AssertionError("start gate timed out"));
                return;
            }
            action.run();
            race.successes.incrementAndGet();
        } catch (Throwable failure) {
            race.unexpected.add(failure);
        }
    }

    private static void assertNoUnexpected(Race race) {
        if (!race.unexpected.isEmpty()) {
            AssertionError failure = new AssertionError("unexpected failures");
            race.unexpected.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static void runOnThreads(int threads, ThrowingAction action, List<Throwable> failures)
            throws Exception {
        CyclicBarrier startGate = new CyclicBarrier(threads);
        List<Thread> workers = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread worker = new Thread(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    action.run();
                } catch (Throwable failure) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                }
            }, "orchestella-write-hammer-" + index);
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            worker.join(30_000);
            if (worker.isAlive()) {
                synchronized (failures) {
                    failures.add(new AssertionError("worker did not finish: " + worker.getName()));
                }
            }
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
    private interface Action {
        void run(Controller controller) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class Race {
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicBoolean metAtBarrier = new AtomicBoolean();
        private final List<Throwable> unexpected = new ArrayList<>();
    }

    private enum Probe {
        SAVE,
        LOAD,
        DELETE
    }

    /**
     * Forces both controllers to meet inside a store method when they are not serialized.
     * With the shared state monitor only one controller can enter the probed method; the
     * other waits for the lock and the barrier times out instead of tripping.
     */
    private static final class BarrierStore implements Store {
        private final FileStore delegate;
        private final CyclicBarrier barrier;
        private final Probe probe;
        private final AtomicBoolean metAtBarrier;

        private BarrierStore(
                FileStore delegate, CyclicBarrier barrier, Probe probe) {
            this(delegate, barrier, probe, new AtomicBoolean());
        }

        private BarrierStore(
                FileStore delegate,
                CyclicBarrier barrier,
                Probe probe,
                AtomicBoolean metAtBarrier) {
            this.delegate = delegate;
            this.barrier = barrier;
            this.probe = probe;
            this.metAtBarrier = metAtBarrier;
        }

        @Override
        public Object stateMonitor() {
            return delegate.stateMonitor();
        }

        @Override
        public void save(Instance instance) throws IOException {
            if (probe == Probe.SAVE) {
                awaitBarrier();
            }
            delegate.save(instance);
        }

        @Override
        public Instance load(String id) throws IOException {
            if (probe == Probe.LOAD) {
                awaitBarrier();
            }
            return delegate.load(id);
        }

        @Override
        public List<Instance> list() throws IOException {
            return delegate.list();
        }

        @Override
        public void delete(String id) throws IOException {
            if (probe == Probe.DELETE) {
                awaitBarrier();
            }
            delegate.delete(id);
        }

        private void awaitBarrier() {
            try {
                barrier.await(250, TimeUnit.MILLISECONDS);
                metAtBarrier.set(true);
            } catch (TimeoutException | BrokenBarrierException expected) {
                // Serialized controllers never meet at the barrier; the timeout is expected.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at the serialization probe", interrupted);
            }
        }
    }

    /** Records whether each store method is reached while the caller holds the state monitor. */
    private static final class MonitorAssertingStore implements Store {
        private final FileStore delegate;
        private final Object monitor = new Object();
        private final AtomicInteger saveCalls = new AtomicInteger();
        private final AtomicInteger loadCalls = new AtomicInteger();
        private final AtomicInteger deleteCalls = new AtomicInteger();

        private MonitorAssertingStore(FileStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object stateMonitor() {
            return monitor;
        }

        @Override
        public void save(Instance instance) throws IOException {
            assertTrue(
                    "create must call store.save while holding the directory state monitor",
                    Thread.holdsLock(monitor));
            saveCalls.incrementAndGet();
            delegate.save(instance);
        }

        @Override
        public Instance load(String id) throws IOException {
            assertTrue(
                    "exportToArchive must call store.load while holding the directory state monitor",
                    Thread.holdsLock(monitor));
            loadCalls.incrementAndGet();
            return delegate.load(id);
        }

        @Override
        public List<Instance> list() throws IOException {
            return delegate.list();
        }

        @Override
        public void delete(String id) throws IOException {
            assertTrue(
                    "delete must call store.delete while holding the directory state monitor",
                    Thread.holdsLock(monitor));
            deleteCalls.incrementAndGet();
            delegate.delete(id);
        }
    }
}
