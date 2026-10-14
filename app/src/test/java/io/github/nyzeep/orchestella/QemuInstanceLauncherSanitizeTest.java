package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QemuInstanceLauncherSanitizeTest {

    @Test
    public void testSanitizeSpecMapsGpuAndKeepsPasstNetworkPlane() {
        String[] original = new String[]{
                "/path/to/libdroidlayer_qemu.so",
                "-machine", "virt,gic-version=3",
                "-m", "1024M",
                "-device", "virtio-gpu-gl-device",
                "-netdev", "stream,id=orchestella-net,server=off,addr.type=unix,addr.path=/x/passt.sock",
                "-device", "virtio-net-pci,netdev=orchestella-net",
                "-device", "virtio-serial-device"
        };

        String[] sanitized = QemuInstanceLauncher.sanitizeSpecForTargetBinary(original);

        String[] expected = new String[]{
                "/path/to/libdroidlayer_qemu.so",
                "-machine", "virt,gic-version=3",
                "-m", "1024M",
                "-device", "virtio-gpu-device",
                "-netdev", "stream,id=orchestella-net,server=off,addr.type=unix,addr.path=/x/passt.sock",
                "-device", "virtio-net-pci,netdev=orchestella-net",
                "-device", "virtio-serial-device"
        };

        assertArrayEquals(expected, sanitized);
    }

    @Test
    public void failedLaunchReleasesGatewayAndAllowsTheSameOwnerToRetry() throws Exception {
        java.util.Set<String> gateways = new java.util.HashSet<>();
        String owner = "synthetic-instance";
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        java.io.IOException failure = new java.io.IOException("synthetic process factory failure");
        gateways.add(owner);
        try {
            QemuInstanceLauncher.completeLaunch(() -> { throw failure; }, () -> {
                cleanups.incrementAndGet();
                gateways.remove(owner);
            });
            org.junit.Assert.fail("failed process creation must propagate");
        } catch (java.io.IOException expected) {
            org.junit.Assert.assertSame(failure, expected);
        }
        assertEquals(1, cleanups.get());
        org.junit.Assert.assertTrue(gateways.isEmpty());
        org.junit.Assert.assertTrue(gateways.add(owner));
        assertEquals("published", QemuInstanceLauncher.completeLaunch(() -> "published", () -> {
            cleanups.incrementAndGet();
            gateways.remove(owner);
        }));
        assertEquals(1, cleanups.get());
        org.junit.Assert.assertTrue(gateways.contains(owner));
    }

    @Test
    public void interruptedAndInvalidLaunchesStillCleanUpWithoutMaskingTheFailure() throws Exception {
        java.util.concurrent.atomic.AtomicInteger cleanups = new java.util.concurrent.atomic.AtomicInteger();
        InterruptedException interrupted = new InterruptedException("synthetic interrupted launch");
        try {
            QemuInstanceLauncher.completeLaunch(() -> { throw interrupted; }, cleanups::incrementAndGet);
            org.junit.Assert.fail();
        } catch (InterruptedException expected) {
            org.junit.Assert.assertSame(interrupted, expected);
        }
        IllegalArgumentException invalid = new IllegalArgumentException("synthetic invalid display path");
        IllegalStateException cleanupFailure = new IllegalStateException("synthetic cleanup failure");
        try {
            QemuInstanceLauncher.completeLaunch(() -> { throw invalid; }, () -> {
                cleanups.incrementAndGet();
                throw cleanupFailure;
            });
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) {
            org.junit.Assert.assertSame(invalid, expected);
            org.junit.Assert.assertSame(cleanupFailure, expected.getSuppressed()[0]);
        }
        assertEquals(2, cleanups.get());
    }

    @Test(timeout = 3000L)
    public void blockedDisplayConnectClosesItsUnpublishedSocketOnceAtTheDeadline() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        FakeDisplaySocket socket = new FakeDisplaySocket(true);
        long started = System.nanoTime();
        try {
            QemuInstanceLauncher.openDisplayStreams(socket, executor, 50L);
            org.junit.Assert.fail("blocked display connection must time out");
        } catch (java.io.IOException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("timed out"));
            org.junit.Assert.assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started) < 1500L);
        } finally {
            executor.shutdownNow();
            org.junit.Assert.assertTrue(executor.awaitTermination(1L, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, socket.connectCalls.get());
        assertEquals(1, socket.closeCalls.get());
    }

    @Test(timeout = 3000L)
    public void cancelledQueuedDisplayConnectNeverOpensAndCannotPublishLateStreams() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch occupied = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        executor.submit(() -> {
            occupied.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        org.junit.Assert.assertTrue(occupied.await(1L, java.util.concurrent.TimeUnit.SECONDS));
        FakeDisplaySocket socket = new FakeDisplaySocket(false);
        try {
            QemuInstanceLauncher.openDisplayStreams(socket, executor, 30L);
            org.junit.Assert.fail("queued display connection must time out");
        } catch (java.io.IOException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("timed out"));
        } finally {
            release.countDown();
            executor.shutdown();
            org.junit.Assert.assertTrue(executor.awaitTermination(1L, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(0, socket.connectCalls.get());
        assertEquals(1, socket.closeCalls.get());
    }

    @Test(timeout = 3000L)
    public void successfulDisplayStreamsOwnTheSocketUntilExplicitClose() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        FakeDisplaySocket socket = new FakeDisplaySocket(false);
        try {
            io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel.Streams streams =
                    QemuInstanceLauncher.openDisplayStreams(socket, executor, 30L);
            assertEquals(0, socket.closeCalls.get());
            java.util.concurrent.CountDownLatch afterDeadline = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ScheduledExecutorService observer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            try {
                observer.schedule(afterDeadline::countDown, 60L, java.util.concurrent.TimeUnit.MILLISECONDS);
                org.junit.Assert.assertTrue(afterDeadline.await(1L, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, socket.closeCalls.get());
                streams.close();
                streams.close();
                assertEquals(1, socket.closeCalls.get());
            } finally { observer.shutdownNow(); }
        } finally {
            executor.shutdownNow();
            org.junit.Assert.assertTrue(executor.awaitTermination(1L, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 3000L)
    public void interruptedDisplayConnectClosesItsSocketAndPreservesTheInterrupt() throws Exception {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        FakeDisplaySocket socket = new FakeDisplaySocket(true);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                QemuInstanceLauncher.openDisplayStreams(socket, executor, 1000L);
                failure.set(new AssertionError("interrupted connection was published"));
            } catch (java.io.IOException expected) {
                if (!Thread.currentThread().isInterrupted()) failure.set(new AssertionError("interrupt was cleared"));
            }
        });
        try {
            caller.start();
            org.junit.Assert.assertTrue(socket.entered.await(1L, java.util.concurrent.TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(1000L);
            org.junit.Assert.assertFalse(caller.isAlive());
            org.junit.Assert.assertNull(failure.get());
        } finally {
            executor.shutdownNow();
            org.junit.Assert.assertTrue(executor.awaitTermination(1L, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(1, socket.closeCalls.get());
    }

    private static final class FakeDisplaySocket implements QemuInstanceLauncher.DisplaySocket {
        final java.util.concurrent.atomic.AtomicInteger connectCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger closeCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        final boolean blocked;
        FakeDisplaySocket(boolean blocked) { this.blocked = blocked; }
        @Override public void connect() {
            connectCalls.incrementAndGet();
            entered.countDown();
            if (blocked) {
                boolean interrupted = false;
                while (closed.getCount() != 0L) {
                    try { closed.await(); } catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        @Override public java.io.InputStream input() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream output() { return new java.io.ByteArrayOutputStream(); }
        @Override public void close() { closeCalls.incrementAndGet(); closed.countDown(); }
    }

    @Test
    public void testSanitizeSpecHandlesNullAndEmpty() {
        assertEquals(0, QemuInstanceLauncher.sanitizeSpecForTargetBinary(null).length);
        assertEquals(0, QemuInstanceLauncher.sanitizeSpecForTargetBinary(new String[0]).length);
    }
}
