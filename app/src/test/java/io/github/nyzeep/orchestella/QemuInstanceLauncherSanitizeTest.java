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
        assertEquals(0, QemuInstanceLauncher.sanitizeSpecForTargetBinary(null, false).length);
        assertEquals(0, QemuInstanceLauncher.sanitizeSpecForTargetBinary(new String[0], true).length);
    }

    // ---- G-06: GPU 生命周期接线 · 无 native 逐字节一致锁定 ----

    /**
     * The flag-on spec used by the G-06 lock tests: identical payload shape to
     * production {@code buildWithPasst} output where the GPU toggle emits
     * {@code virtio-gpu-gl-device}.
     */
    private static String[] gpuEnabledSpec() {
        return new String[]{
                "/path/to/libdroidlayer_qemu.so",
                "-machine", "virt,gic-version=3",
                "-m", "1024M",
                "-device", "virtio-gpu-gl-device",
                "-netdev", "stream,id=orchestella-net,server=off,addr.type=unix,addr.path=/x/passt.sock",
                "-device", "virtio-net-pci,netdev=orchestella-net",
                "-device", "virtio-serial-device"
        };
    }

    /**
     * Lock: without an active GPU context the new decision-aware pipeline must
     * produce the same bytes as the pre-G-06 silent downgrade (the legacy
     * signature stays the golden reference).
     */
    @Test
    public void gpuFallbackDowngradeMatchesLegacySanitizeByteForByte() {
        String[] legacy = QemuInstanceLauncher.sanitizeSpecForTargetBinary(gpuEnabledSpec());
        String[] fallback = QemuInstanceLauncher.sanitizeSpecForTargetBinary(gpuEnabledSpec(), false);
        assertArrayEquals(legacy, fallback);
    }

    /** Lock: with an ACTIVE GPU context the gl-device args survive untouched. */
    @Test
    public void gpuActivePipelineKeepsGlDeviceArgsByteExact() {
        assertArrayEquals(gpuEnabledSpec(),
                QemuInstanceLauncher.sanitizeSpecForTargetBinary(gpuEnabledSpec(), true));
    }

    /**
     * Mirrors the production assembly order (buildWithPasst → GPU decision →
     * display channel → audio channel) so the no-native byte-exact lock runs
     * against the same pipeline the launcher executes.
     */
    private static String[] assembleLaunchArgs(boolean gpuAccelerationEnabled) {
        io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig config =
                io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.builder()
                        .cpuCores(2)
                        .ramMegabytes(2048)
                        .gpuAccelerationEnabled(gpuAccelerationEnabled)
                        .build();
        String[] spec = io.github.nyzeep.orchestella.runtime.engineqemu.QemuLaunchSpec.buildWithPasst(
                "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
                "/data/runtime/Image",
                "/data/runtime/initramfs-embedded.cpio.gz",
                "/data/instances/i1/root-volume.raw",
                "droidlayer-control-i1",
                "droidlayer-terminal-i1",
                config,
                "38221",
                "orchestella.guest_ip=10.53.53.2 orchestella.guest_gw=10.53.53.1"
                        + " orchestella.guest_mask=255.255.255.0"
                        + " orchestella.guest_dns=10.53.53.1 orchestella.proxy_enabled=0");
        // gpuAccelerationActive=false is exactly what the launcher passes whenever
        // the GPU gate did not reach ACTIVE (flag off, native missing, init failed).
        String[] sanitized = QemuInstanceLauncher.sanitizeSpecForTargetBinary(spec, false);
        String[] withDisplay = io.github.nyzeep.orchestella.runtime.engineqemu.QemuLaunchSpec
                .withDisplayChannel(sanitized, "/data/display/display.sock");
        return io.github.nyzeep.orchestella.runtime.engineqemu.QemuLaunchSpec
                .withAudioChannel(withDisplay, "/data/audio/audio.sock");
    }

    /**
     * Lock: in a no-native environment (this checkout has no libvirglrenderer.so)
     * the flag-on pipeline output must equal the flag-off pipeline output
     * byte-for-byte — the GPU toggle adds no argument drift beyond the one
     * device token the fallback downgrades.
     */
    @Test
    public void fullPipelineWithoutNativeMatchesFlagOffSpecByteForByte() {
        String[] flagOff = assembleLaunchArgs(false);
        String[] flagOnFallback = assembleLaunchArgs(true);
        assertArrayEquals(flagOff, flagOnFallback);
    }

    /** Lock: the fallback pipeline still carries the 2D device and never the gl one. */
    @Test
    public void assembledFallbackArgsCarry2dDeviceOnly() {
        for (String arg : assembleLaunchArgs(true)) {
            if ("virtio-gpu-gl-device".equals(arg)) {
                org.junit.Assert.fail("fallback pipeline must not emit virtio-gpu-gl-device");
            }
        }
        boolean has2dDevice = false;
        for (String arg : assembleLaunchArgs(true)) {
            has2dDevice |= "virtio-gpu-device".equals(arg);
        }
        org.junit.Assert.assertTrue("2D device must stay wired", has2dDevice);
    }

    /**
     * Golden literal: the exact 55-argument array the pre-G-06 pipeline produced
     * for this launch payload (captured from the legacy sanitize path, which the
     * fallback output matches byte-for-byte). Guards against silent drift of the
     * no-native launch argument sequence.
     */
    private static final String[] GOLDEN_NO_NATIVE_LAUNCH_ARGS = {
            "/native/lib/arm64-v8a/libdroidlayer_qemu.so",
            "-machine",
            "virt,gic-version=3",
            "-accel",
            "tcg,thread=multi",
            "-cpu",
            "cortex-a57",
            "-smp",
            "2",
            "-m",
            "2048M",
            "-nodefaults",
            "-display",
            "none",
            "-monitor",
            "none",
            "-no-reboot",
            "-chardev",
            "socket,id=terminal,path=droidlayer-terminal-i1,abstract=on,server=on,wait=off",
            "-serial",
            "chardev:terminal",
            "-kernel",
            "/data/runtime/Image",
            "-initrd",
            "/data/runtime/initramfs-embedded.cpio.gz",
            "-append",
            "console=ttyAMA0 rdinit=/init panic=-1 droidlayer.root_device=/dev/vda"
                    + " droidlayer.input_tag=droidlayer-input"
                    + " droidlayer.bridge_device=/dev/vport0p1"
                    + " droidlayer.terminal_device=/dev/ttyAMA0"
                    + " orchestella.guest_ip=10.53.53.2 orchestella.guest_gw=10.53.53.1"
                    + " orchestella.guest_mask=255.255.255.0"
                    + " orchestella.guest_dns=10.53.53.1 orchestella.proxy_enabled=0",
            "-drive",
            "if=none,id=root,file=/data/instances/i1/root-volume.raw,format=raw,cache=writeback",
            "-device",
            "virtio-blk-device,drive=root",
            "-device",
            "virtio-gpu-device",
            "-netdev",
            "socket,id=orchestella-net,connect=127.0.0.1:38221",
            "-device",
            "virtio-net-device,netdev=orchestella-net",
            "-device",
            "virtio-serial-device",
            "-chardev",
            "socket,id=control,path=droidlayer-control-i1,abstract=on,server=on,wait=off",
            "-device",
            "virtserialport,chardev=control,name=droidlayer.bridge",
            "-chardev",
            "socket,id=netproxy,path=orchestella-proxy-i1,abstract=on,server=on,wait=off",
            "-device",
            "virtserialport,chardev=netproxy,name=orchestella.proxy",
            "-chardev",
            "socket,id=display,path=/data/display/display.sock,server=on,wait=off",
            "-device",
            "virtserialport,chardev=display,name=orchestella.display",
            "-chardev",
            "socket,id=audio,path=/data/audio/audio.sock,server=on,wait=off",
            "-device",
            "virtserialport,chardev=audio,name=orchestella.audio",
    };

    /** Lock: the fallback pipeline output equals the pre-G-06 golden bytes. */
    @Test
    public void fallbackPipelineMatchesPreG06GoldenLiteralByteForByte() {
        assertArrayEquals(GOLDEN_NO_NATIVE_LAUNCH_ARGS, assembleLaunchArgs(true));
        assertArrayEquals(GOLDEN_NO_NATIVE_LAUNCH_ARGS, assembleLaunchArgs(false));
    }
}
