package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Controller;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.DistroReference;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Instance;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.SingleRunningInstanceException;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.State;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.Store;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Contract for the single-RUNNING invariant across independently constructed Controllers.
 *
 * <p>Each Controller creates its own Store in production. The invariant therefore cannot rely on
 * Controller instance monitors alone; the Store must expose one monitor per canonical state
 * directory and the Controller must hold it across the whole check-and-transition sequence.
 */
public final class ConcurrentStartContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    @Test
    public void fileStoresOnTheSameDirectoryShareOneStateMonitor() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-store-monitor-");
        Path otherDirectory = Files.createTempDirectory("orchestella-store-monitor-other-");
        try {
            FileStore first = new FileStore(directory);
            FileStore second = new FileStore(directory.resolve("."));
            assertSame("same canonical directory shares the state monitor", first.stateMonitor(), second.stateMonitor());
            assertNotSame(
                    "different directories keep independent state monitors",
                    first.stateMonitor(),
                    new FileStore(otherDirectory).stateMonitor());
        } finally {
            deleteRecursively(directory);
            deleteRecursively(otherDirectory);
        }
    }

    @Test
    public void twoControllersCannotStartDifferentInstancesConcurrently() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-concurrent-start-");
        try {
            FileStore store = new FileStore(directory);
            Controller setup = new Controller(store, CLOCK);
            Instance first = createStopped(setup, directory, "first");
            Instance second = createStopped(setup, directory, "second");

            Race race = race(
                    directory,
                    controller -> controller.start(first.id()),
                    controller -> controller.start(second.id()));

            assertEquals("exactly one start succeeds", 1, race.successes.get());
            assertEquals("exactly one start is rejected", 1, race.rejections.get());
            assertNoUnexpected(race);
            assertEquals("exactly one RUNNING instance", 1L, runningCount(directory));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void twoControllersCannotResumeDifferentInstancesConcurrently() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-concurrent-resume-");
        try {
            FileStore store = new FileStore(directory);
            Controller setup = new Controller(store, CLOCK);
            Instance first = createStopped(setup, directory, "first");
            Instance second = createStopped(setup, directory, "second");
            setup.start(first.id());
            setup.suspend(first.id());
            setup.start(second.id());
            setup.suspend(second.id());

            Race race = race(
                    directory,
                    controller -> controller.resume(first.id()),
                    controller -> controller.resume(second.id()));

            assertEquals("exactly one resume succeeds", 1, race.successes.get());
            assertEquals("exactly one resume is rejected", 1, race.rejections.get());
            assertNoUnexpected(race);
            assertEquals("exactly one RUNNING instance", 1L, runningCount(directory));
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    public void twoControllersCannotColdRestoreDifferentInstancesConcurrently() throws Exception {
        Path directory = Files.createTempDirectory("orchestella-concurrent-restore-");
        try {
            FileStore store = new FileStore(directory);
            Controller setup = new Controller(store, CLOCK);
            Instance first = createStopped(setup, directory, "first");
            Instance second = createStopped(setup, directory, "second");
            setup.start(first.id());
            setup.reconcileAfterHostDeath(first.id());
            setup.start(second.id());
            setup.reconcileAfterHostDeath(second.id());

            Race race = race(
                    directory,
                    controller -> controller.coldRestore(first.id()),
                    controller -> controller.coldRestore(second.id()));

            assertEquals("exactly one cold restore succeeds", 1, race.successes.get());
            assertEquals("exactly one cold restore is rejected", 1, race.rejections.get());
            assertNoUnexpected(race);
            assertEquals("exactly one RUNNING instance", 1L, runningCount(directory));
        } finally {
            deleteRecursively(directory);
        }
    }

    private static Race race(Path directory, Action firstAction, Action secondAction) throws Exception {
        FileStore firstStore = new FileStore(directory);
        FileStore secondStore = new FileStore(directory);
        CyclicBarrier listBarrier = new CyclicBarrier(2);
        Controller firstController = new Controller(new BarrierStore(firstStore, listBarrier), CLOCK);
        Controller secondController = new Controller(new BarrierStore(secondStore, listBarrier), CLOCK);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Race race = new Race();
        Thread firstThread = new Thread(
                () -> run(race, ready, go, () -> firstAction.run(firstController)), "orchestella-race-first");
        Thread secondThread = new Thread(
                () -> run(race, ready, go, () -> secondAction.run(secondController)), "orchestella-race-second");
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
                race.unexpected.compareAndSet(null, new AssertionError("start gate timed out"));
                return;
            }
            action.run();
            race.successes.incrementAndGet();
        } catch (SingleRunningInstanceException expected) {
            race.rejections.incrementAndGet();
        } catch (Throwable failure) {
            race.unexpected.compareAndSet(null, failure);
        }
    }

    private static void assertNoUnexpected(Race race) {
        Throwable failure = race.unexpected.get();
        if (failure != null) {
            throw new AssertionError("unexpected failure", failure);
        }
    }

    private static Instance createStopped(Controller controller, Path directory, String name) throws Exception {
        Instance instance = controller.create(new DistroReference("debian", "13"), directory.resolve(name + ".raw"));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        return instance;
    }

    private static long runningCount(Path directory) throws Exception {
        return new FileStore(directory).list().stream()
                .filter(instance -> instance.state() == State.RUNNING)
                .count();
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
        private final AtomicInteger rejections = new AtomicInteger();
        private final AtomicReference<Throwable> unexpected = new AtomicReference<>();
    }

    /**
     * Forces both controllers to reach list() together when they are not serialized. With the
     * shared state monitor only one controller can reach list(); the other waits for the lock and
     * then observes the already-RUNNING instance after the barrier times out.
     */
    private static final class BarrierStore implements Store {
        private final Store delegate;
        private final CyclicBarrier barrier;

        private BarrierStore(Store delegate, CyclicBarrier barrier) {
            this.delegate = delegate;
            this.barrier = barrier;
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
            return delegate.load(id);
        }

        @Override
        public List<Instance> list() throws IOException {
            try {
                barrier.await(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | BrokenBarrierException ignored) {
                // Serialized controllers never meet at the barrier; the timeout is expected.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while coordinating list()", interrupted);
            }
            return delegate.list();
        }

        @Override
        public void delete(String id) throws IOException {
            delegate.delete(id);
        }
    }
}
