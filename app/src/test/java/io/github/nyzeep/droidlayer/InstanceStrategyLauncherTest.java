package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.engineavf.AvfEngineException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Observable transparent-fallback behavior of the strategy chain (AC-3). */
public final class InstanceStrategyLauncherTest {
    private static final InstanceStrategyLauncher.LaunchParameters PARAMETERS =
            new InstanceStrategyLauncher.LaunchParameters(
                    "instance-1", "token", "rootfs.tar.gz", "/root.raw", "/runtime", "/inputs");

    @Test
    public void prefersAvfAndSkipsQemuWhenHardwareLaunchSucceeds() throws Exception {
        AtomicInteger qemuCalls = new AtomicInteger();
        List<AvfEngineException> recorded = new ArrayList<>();
        InstanceStrategyLauncher launcher = new InstanceStrategyLauncher(
                parameters -> new AvfInstanceLauncher.AvfSession(null),
                parameters -> {
                    qemuCalls.incrementAndGet();
                    return null;
                },
                recorded::add);

        InstanceStrategyLauncher.Outcome outcome = launcher.launch(PARAMETERS, true);

        assertTrue(outcome.hardwareAccelerated());
        assertEquals(
                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION, outcome.strategy);
        assertNotNull(outcome.avfSession);
        assertNull(outcome.qemuSession);
        assertNull(outcome.degradationReason);
        assertEquals(0, qemuCalls.get());
        assertTrue(recorded.isEmpty());
    }

    @Test
    public void degradesToQemuAndRecordsTheReasonWhenAvfFails() throws Exception {
        AtomicInteger qemuCalls = new AtomicInteger();
        List<AvfEngineException> recorded = new ArrayList<>();
        AvfEngineException failure = new AvfEngineException(
                AvfEngineException.HARDWARE_UNSUPPORTED, "hardware refused non-protected VM");
        InstanceStrategyLauncher launcher = new InstanceStrategyLauncher(
                parameters -> {
                    throw failure;
                },
                parameters -> {
                    qemuCalls.incrementAndGet();
                    return new QemuInstanceLauncher.Session("control", "terminal", null);
                },
                recorded::add);

        InstanceStrategyLauncher.Outcome outcome = launcher.launch(PARAMETERS, true);

        assertTrue(!outcome.hardwareAccelerated());
        assertEquals(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, outcome.strategy);
        assertNull(outcome.avfSession);
        assertNotNull(outcome.qemuSession);
        assertEquals("control", outcome.qemuSession.controlSocketName);
        assertEquals(AvfEngineException.HARDWARE_UNSUPPORTED, outcome.degradationReason);
        assertTrue(outcome.degradationDetail.contains("hardware refused"));
        assertEquals(1, qemuCalls.get());
        assertEquals(1, recorded.size());
        assertEquals(AvfEngineException.HARDWARE_UNSUPPORTED, recorded.get(0).reason());
    }

    @Test
    public void softwarePreferredDevicesNeverAttemptAvf() throws Exception {
        AtomicInteger avfCalls = new AtomicInteger();
        List<AvfEngineException> recorded = new ArrayList<>();
        InstanceStrategyLauncher launcher = new InstanceStrategyLauncher(
                parameters -> {
                    avfCalls.incrementAndGet();
                    return null;
                },
                parameters -> new QemuInstanceLauncher.Session("control", "terminal", null),
                recorded::add);

        InstanceStrategyLauncher.Outcome outcome = launcher.launch(PARAMETERS, false);

        assertTrue(!outcome.hardwareAccelerated());
        assertNull(outcome.avfSession);
        assertNotNull(outcome.qemuSession);
        assertNull(outcome.degradationReason);
        assertEquals(0, avfCalls.get());
        assertTrue(recorded.isEmpty());
    }

    @Test
    public void sharedBootstrapFailuresAreNotSilentlyDegraded() {
        // An IOException comes from the shared asset bootstrap; it would fail
        // the QEMU path identically, so it must surface instead of degrading.
        InstanceStrategyLauncher launcher = new InstanceStrategyLauncher(
                parameters -> {
                    throw new IOException("root-volume-missing");
                },
                parameters -> null,
                failure -> {
                    throw new AssertionError("no degradation expected for shared failures");
                });
        try {
            launcher.launch(PARAMETERS, true);
            org.junit.Assert.fail("shared bootstrap failure must propagate");
        } catch (IOException expected) {
            assertEquals("root-volume-missing", expected.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            org.junit.Assert.fail("unexpected interrupt");
        }
    }
}
