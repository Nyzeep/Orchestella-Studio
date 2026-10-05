package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class InstanceCredentialsTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void perInstanceCredentialsAreRandomAndUnique() throws IOException {
        Path root = temporaryFolder.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        Path root1 = Files.createFile(temporaryFolder.getRoot().toPath().resolve("root1.raw"));
        Path root2 = Files.createFile(temporaryFolder.getRoot().toPath().resolve("root2.raw"));

        InstanceLifecycle.Instance inst1 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), root1);
        InstanceLifecycle.Instance inst2 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), root2);

        assertNotNull("guest password inst1 must not be null", inst1.guestPassword());
        assertNotNull("guest password inst2 must not be null", inst2.guestPassword());
        assertTrue("password length >= 12", inst1.guestPassword().length() >= 12);
        assertTrue("password length >= 12", inst2.guestPassword().length() >= 12);
        assertNotEquals("passwords must be distinct random credentials (ADR-0010)",
                inst1.guestPassword(), inst2.guestPassword());
    }

    @Test
    public void updatesGuestPasswordWithValidation() throws IOException {
        Path root = temporaryFolder.newFolder("instances-update").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        Path rootVolume = Files.createFile(temporaryFolder.getRoot().toPath().resolve("root.raw"));
        InstanceLifecycle.Instance inst = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);

        InstanceLifecycle.Instance updated = controller.updateGuestPassword(inst.id(), "StrongPassw0rd!");
        assertEquals("StrongPassw0rd!", updated.guestPassword());

        // Re-load from disk to verify persistence
        InstanceLifecycle.Instance loaded = store.load(inst.id());
        assertEquals("StrongPassw0rd!", loaded.guestPassword());

        // Rejects too short
        try {
            controller.updateGuestPassword(inst.id(), "short");
            fail("should reject short password");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("at least 8"));
        }

        // Rejects whitespace
        try {
            controller.updateGuestPassword(inst.id(), "pass with space");
            fail("should reject whitespace");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("whitespace"));
        }
    }
}
