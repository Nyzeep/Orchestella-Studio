package io.github.nyzeep.orchestella.runtime.engineqemu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Contract tests for the bounded process seam used by the runtime stop path. */
public final class QemuProcessHandleSeamTest {

    @Test(timeout = 15_000)
    public void realProcessExposesBoundedWaitForcibleStopAndPid() throws Exception {
        QemuProcessLauncher launcher = new QemuProcessLauncher();
        QemuProcessLauncher.ProcessHandle handle = launcher.launch(
                new String[]{"/bin/sh", "-c", "sleep 30"}).handle();
        try {
            assertTrue("fresh process must be alive", handle.isAlive());
            assertTrue("real processes must expose a pid", handle.pid() > 0);
            assertFalse("bounded wait must not block until exit",
                    handle.waitFor(50, TimeUnit.MILLISECONDS));
            handle.destroyForcibly();
            assertTrue("forcible stop must converge", handle.waitFor(5, TimeUnit.SECONDS));
            assertFalse("exited process must not report alive", handle.isAlive());
        } finally {
            handle.destroyForcibly();
        }
    }

    @Test(timeout = 15_000)
    public void normalDestroyReachesWaitForWithoutTheUnboundedOverload() throws Exception {
        QemuProcessLauncher launcher = new QemuProcessLauncher();
        QemuProcessLauncher.ProcessHandle handle = launcher.launch(
                new String[]{"/bin/sh", "-c", "sleep 30"}).handle();
        handle.destroy();
        assertTrue("destroy must be observable through the bounded wait",
                handle.waitFor(5, TimeUnit.SECONDS));
    }
}
