package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class InstanceLaunchSequenceTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void firstLaunchThenGuestStopThenRelaunchFollowTheNormalLifecycle() throws Exception {
        Path dir = temporaryFolder.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                dir.resolve("root-volume.raw"));

        InstanceLaunchSequence.beforeLaunch(controller, created);
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(created.id()).state());

        InstanceLaunchSequence.afterGuestExit(controller, created.id());
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(created.id()).state());

        InstanceLaunchSequence.beforeLaunch(controller, store.load(created.id()));
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(created.id()).state());
    }

    @Test
    public void beforeLaunchKeepsTheSingleRunningInstanceConstraint() throws Exception {
        Path dir = temporaryFolder.newFolder("single").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, Clock.systemUTC());
        InstanceLifecycle.Instance first = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                dir.resolve("first.raw"));
        InstanceLifecycle.Instance second = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                dir.resolve("second.raw"));

        InstanceLaunchSequence.beforeLaunch(controller, first);
        try {
            InstanceLaunchSequence.beforeLaunch(controller, second);
            fail("a second RUNNING instance must be rejected");
        } catch (InstanceLifecycle.SingleRunningInstanceException expected) {
            assertEquals(InstanceLifecycle.State.RUNNING, store.load(first.id()).state());
            assertEquals(InstanceLifecycle.State.STOPPED, store.load(second.id()).state());
        }
    }
}
