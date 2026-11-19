package io.github.nyzeep.orchestella.runtime.engineqemu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * DW-01 JVM tests for the QEMU device-model probe. Hermetic: the real QEMU
 * binary is never exec'd — a fake {@link QemuDeviceModelProbe.CommandRunner}
 * supplies the stdout the packaged binary would produce from
 * {@code -device help}.
 *
 * <p>Contract under test:
 * <ul>
 *   <li>stdout containing {@code virtio-gpu-gl-device} → SUPPORTED (the
 *       ticket's decision rule: the token appearing in the device list means
 *       the model is usable);</li>
 *   <li>any failure (spawn error, timeout, interrupt, null/blank path) →
 *       UNSUPPORTED — fail-closed, see {@link QemuDeviceModelProbe};</li>
 *   <li>the process-wide cache computes once per path and
 *       {@link QemuDeviceModelProbe#cachedOutcome} reports PENDING before it.</li>
 * </ul>
 */
public final class QemuDeviceModelProbeTest {

    @After
    public void restoreDefaultRunnerAndCache() {
        QemuDeviceModelProbe.useRunnerForTest(null);
        QemuDeviceModelProbe.resetForTest();
    }

    /** Runner whose stdout is fixed; counts executions for the cache assertions. */
    private static final class FakeRunner implements QemuDeviceModelProbe.CommandRunner {
        final String stdout;
        final boolean throwOnRun;
        final AtomicInteger runs = new AtomicInteger();

        FakeRunner(String stdout) {
            this(stdout, false);
        }

        FakeRunner(String stdout, boolean throwOnRun) {
            this.stdout = stdout;
            this.throwOnRun = throwOnRun;
        }

        @Override public String run(String[] command, long timeoutMs) throws IOException {
            runs.incrementAndGet();
            if (throwOnRun) {
                throw new IOException("synthetic exec failure");
            }
            assertEquals("the probe must ask -device help",
                    3, command.length);
            assertEquals("-device", command[1]);
            assertEquals("help", command[2]);
            assertTrue("the probe must bound the wait", timeoutMs > 0);
            return stdout;
        }
    }

    @Test
    public void stdoutListingTheGlDeviceModelIsSupported() {
        String deviceList = "Supported devices:\n"
                + "virtio-blk-device\n"
                + "virtio-gpu-device\n"
                + "virtio-gpu-gl-device\n"
                + "virtio-net-device\n";
        assertEquals(QemuDeviceModelProbe.Outcome.SUPPORTED,
                QemuDeviceModelProbe.probeUncached(
                        "/native/lib/libdroidlayer_qemu.so",
                        new FakeRunner(deviceList), 5000L));
    }

    @Test
    public void quotedDeviceListFormatIsAlsoSupported() {
        // Older QEMU -device ? lists entries as name "device".
        String deviceList = "name \"virtio-gpu-device\"\n"
                + "name \"virtio-gpu-gl-device\"\n";
        assertEquals(QemuDeviceModelProbe.Outcome.SUPPORTED,
                QemuDeviceModelProbe.probeUncached(
                        "/native/lib/libdroidlayer_qemu.so",
                        new FakeRunner(deviceList), 5000L));
    }

    @Test
    public void stdoutWithoutTheGlDeviceModelIsUnsupported() {
        String deviceList = "Supported devices:\n"
                + "virtio-blk-device\n"
                + "virtio-gpu-device\n"
                + "virtio-net-device\n";
        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeUncached(
                        "/native/lib/libdroidlayer_qemu.so",
                        new FakeRunner(deviceList), 5000L));
    }

    @Test
    public void glPciVariantAloneDoesNotSatisfyTheProbe() {
        // The launch args emit exactly virtio-gpu-gl-device; a binary that
        // only lists the PCI variant cannot boot that argument, so the probe
        // must not read it as support.
        String deviceList = "Supported devices:\n"
                + "virtio-gpu-device\n"
                + "virtio-gpu-gl-pci\n";
        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeUncached(
                        "/native/lib/libdroidlayer_qemu.so",
                        new FakeRunner(deviceList), 5000L));
    }

    @Test
    public void spawnFailureFailClosedToUnsupported() {
        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeUncached(
                        "/native/lib/libdroidlayer_qemu.so",
                        new FakeRunner("", true), 5000L));
    }

    @Test
    public void nullAndBlankPathsFailClosedWithoutExecuting() {
        FakeRunner runner = new FakeRunner("virtio-gpu-gl-device");
        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeUncached(null, runner, 5000L));
        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeUncached("   ", runner, 5000L));
        assertEquals("blank paths must never reach the runner",
                0, runner.runs.get());
    }

    @Test
    public void timeoutShapedFailureFailClosedToUnsupported() {
        // The default runner turns a timeout into an IOException; the pure
        // probe treats it exactly like any other failure (fail-closed).
        QemuDeviceModelProbe.CommandRunner timingOut = (command, timeoutMs) -> {
            throw new IOException("synthetic probe timeout after " + timeoutMs + " ms");
        };
        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeUncached(
                        "/native/lib/libdroidlayer_qemu.so", timingOut, 5000L));
    }

    @Test
    public void defaultRunnerIsTheRealProcessImplementation() {
        QemuDeviceModelProbe.useRunnerForTest(null);
        assertSame(QemuDeviceModelProbe.defaultRunner(),
                QemuDeviceModelProbe.activeRunnerForTest());
        QemuDeviceModelProbe.CommandRunner fake = new FakeRunner("virtio-gpu-gl-device");
        QemuDeviceModelProbe.useRunnerForTest(fake);
        assertSame("the test seam must replace the default runner",
                fake, QemuDeviceModelProbe.activeRunnerForTest());
    }

    @Test
    public void processWideCacheComputesOncePerPath() {
        FakeRunner runner = new FakeRunner("virtio-gpu-gl-device");
        QemuDeviceModelProbe.useRunnerForTest(runner);
        String path = "/native/lib/libdroidlayer_qemu.so";

        assertEquals(QemuDeviceModelProbe.Outcome.PENDING,
                QemuDeviceModelProbe.cachedOutcome(path));
        assertEquals(QemuDeviceModelProbe.Outcome.SUPPORTED,
                QemuDeviceModelProbe.probeOutcome(path));
        assertEquals(QemuDeviceModelProbe.Outcome.SUPPORTED,
                QemuDeviceModelProbe.probeOutcome(path));
        assertEquals(QemuDeviceModelProbe.Outcome.SUPPORTED,
                QemuDeviceModelProbe.cachedOutcome(path));
        assertEquals("the exec must happen exactly once per process",
                1, runner.runs.get());
    }

    @Test
    public void unsupportedVerdictIsCachedToo() {
        FakeRunner runner = new FakeRunner("virtio-gpu-device only");
        QemuDeviceModelProbe.useRunnerForTest(runner);
        String path = "/native/lib/libdroidlayer_qemu.so";

        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeOutcome(path));
        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeOutcome(path));
        assertEquals(1, runner.runs.get());
    }

    @Test
    public void differentPathsProbeSeparately() {
        FakeRunner runner = new FakeRunner("virtio-gpu-device only");
        QemuDeviceModelProbe.useRunnerForTest(runner);

        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeOutcome("/a/libdroidlayer_qemu.so"));
        // Force a different stdout for the second path.
        QemuDeviceModelProbe.useRunnerForTest(new FakeRunner("virtio-gpu-gl-device"));
        assertEquals(QemuDeviceModelProbe.Outcome.SUPPORTED,
                QemuDeviceModelProbe.probeOutcome("/b/libdroidlayer_qemu.so"));
        // Single-entry cache: the last probed path wins, so path A reads back
        // as "not yet probed" (PENDING must be read conservatively).
        assertEquals(QemuDeviceModelProbe.Outcome.PENDING,
                QemuDeviceModelProbe.cachedOutcome("/a/libdroidlayer_qemu.so"));
    }

    @Test
    public void blankPathNeverEntersTheCache() {
        FakeRunner runner = new FakeRunner("virtio-gpu-gl-device");
        QemuDeviceModelProbe.useRunnerForTest(runner);

        assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.probeOutcome(null));
        assertEquals("a blank path can never be probed, so the cache read is fail-closed too",
                QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                QemuDeviceModelProbe.cachedOutcome(null));
        assertEquals(0, runner.runs.get());
    }

    @Test
    public void resetForTestReturnsTheCacheToPending() {
        QemuDeviceModelProbe.useRunnerForTest(new FakeRunner("virtio-gpu-gl-device"));
        String path = "/native/lib/libdroidlayer_qemu.so";
        assertEquals(QemuDeviceModelProbe.Outcome.SUPPORTED,
                QemuDeviceModelProbe.probeOutcome(path));

        QemuDeviceModelProbe.resetForTest();
        assertEquals(QemuDeviceModelProbe.Outcome.PENDING,
                QemuDeviceModelProbe.cachedOutcome(path));
    }

    @Test
    public void interruptedRunnerFailsClosedAndPreservesTheInterruptFlag() throws Exception {
        QemuDeviceModelProbe.CommandRunner interrupting = (command, timeoutMs) -> {
            throw new InterruptedException("synthetic probe interruption");
        };
        Thread caller = new Thread(() -> {
            assertEquals(QemuDeviceModelProbe.Outcome.UNSUPPORTED,
                    QemuDeviceModelProbe.probeUncached(
                            "/native/lib/libdroidlayer_qemu.so", interrupting, 5000L));
        });
        caller.start();
        caller.join(2000L);
        assertFalse(caller.isAlive());
        assertTrue("the probe must restore the caller's interrupt flag, never swallow it",
                caller.isInterrupted());
    }
}
