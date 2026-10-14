package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
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
    public void perInstanceCredentialsConformToBrandingAndUserConfig() throws IOException {
        Path root = temporaryFolder.newFolder("instances").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(root);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        Path root1 = Files.createFile(temporaryFolder.getRoot().toPath().resolve("root1.raw"));
        Path root2 = Files.createFile(temporaryFolder.getRoot().toPath().resolve("root2.raw"));

        InstanceLifecycle.Instance inst1 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), root1);

        String initialPassword = inst1.guestPassword();
        assertTrue("default password must have at least 24 characters", initialPassword.length() >= 24);
        assertTrue("default password must use guest-safe characters", initialPassword.matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]+"));
        assertFalse("default password must not be public", "1234".equals(initialPassword));
        assertEquals("orchestella", inst1.hostname());
        assertEquals("root", inst1.defaultUser());

        Path anotherRoot = Files.createFile(temporaryFolder.getRoot().toPath().resolve("another-default.raw"));
        InstanceLifecycle.Instance anotherDefault = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), anotherRoot);
        assertTrue("second default password must be strong and guest-safe",
                anotherDefault.guestPassword().matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{24,}"));
        assertFalse("new default instances must have independent passwords",
                initialPassword.equals(anotherDefault.guestPassword()));
        assertTrue("loading an instance must preserve its initial random password",
                initialPassword.equals(store.load(inst1.id()).guestPassword()));

        io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig customConfig =
                new io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig(
                        "customuser", "MySecretPass999", true, "customhost");
        InstanceLifecycle.Instance inst2 = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                root2,
                io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig(),
                customConfig);

        assertEquals("MySecretPass999", inst2.guestPassword());
        assertEquals("MySecretPass999", store.load(inst2.id()).guestPassword());
        assertTrue("creating a custom instance must not change the first instance password",
                initialPassword.equals(store.load(inst1.id()).guestPassword()));
        assertEquals("customhost", inst2.hostname());
        assertEquals("customuser", inst2.defaultUser());
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
