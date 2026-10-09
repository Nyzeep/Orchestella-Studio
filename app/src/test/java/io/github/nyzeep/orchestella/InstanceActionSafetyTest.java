package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import org.junit.Test;

/**
 * Pure JVM unit tests for InstanceActionSafety circuit-breakers (Ticket 52 / US-23).
 */
public final class InstanceActionSafetyTest {

    @Test
    public void deleteIsAllowedOnlyWhenPoweredDownOrCrashed() {
        assertTrue(InstanceActionSafety.canDelete(InstanceLifecycle.State.STOPPED));
        assertTrue(InstanceActionSafety.canDelete(InstanceLifecycle.State.CREATED));
        assertTrue(InstanceActionSafety.canDelete(InstanceLifecycle.State.CRASHED));

        assertFalse(InstanceActionSafety.canDelete(InstanceLifecycle.State.RUNNING));
        assertFalse(InstanceActionSafety.canDelete(InstanceLifecycle.State.PROVISIONING));
    }

    @Test
    public void requireCanDeleteThrowsWhenRunning() {
        try {
            InstanceActionSafety.requireCanDelete(InstanceLifecycle.State.RUNNING);
            fail("Expected IllegalStateException when deleting RUNNING instance");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot delete"));
        }
    }

    @Test
    public void requireCanDeleteThrowsWhenProvisioning() {
        try {
            InstanceActionSafety.requireCanDelete(InstanceLifecycle.State.PROVISIONING);
            fail("Expected IllegalStateException when deleting PROVISIONING instance");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot delete"));
        }
    }

    @Test
    public void requireCanDeleteSucceedsWhenStopped() {
        InstanceActionSafety.requireCanDelete(InstanceLifecycle.State.STOPPED);
        InstanceActionSafety.requireCanDelete(InstanceLifecycle.State.CREATED);
        InstanceActionSafety.requireCanDelete(InstanceLifecycle.State.CRASHED);
    }

    @Test
    public void hardwareUpdateIsAllowedOnlyWhenStoppedOrCreated() {
        assertTrue(InstanceActionSafety.canUpdateHardware(InstanceLifecycle.State.STOPPED));
        assertTrue(InstanceActionSafety.canUpdateHardware(InstanceLifecycle.State.CREATED));

        assertFalse(InstanceActionSafety.canUpdateHardware(InstanceLifecycle.State.RUNNING));
        assertFalse(InstanceActionSafety.canUpdateHardware(InstanceLifecycle.State.PROVISIONING));
        assertFalse(InstanceActionSafety.canUpdateHardware(InstanceLifecycle.State.CRASHED));
    }

    @Test
    public void requireCanUpdateHardwareThrowsWhenRunning() {
        try {
            InstanceActionSafety.requireCanUpdateHardware(InstanceLifecycle.State.RUNNING);
            fail("Expected IllegalStateException when updating hardware while RUNNING");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot update hardware"));
        }
    }

    @Test
    public void requireCanUpdateHardwareSucceedsWhenStopped() {
        InstanceActionSafety.requireCanUpdateHardware(InstanceLifecycle.State.STOPPED);
        InstanceActionSafety.requireCanUpdateHardware(InstanceLifecycle.State.CREATED);
    }

    @Test
    public void cloneIsAllowedOnlyWhenStoppedOrCreated() {
        assertTrue(InstanceActionSafety.canClone(InstanceLifecycle.State.STOPPED));
        assertTrue(InstanceActionSafety.canClone(InstanceLifecycle.State.CREATED));

        assertFalse(InstanceActionSafety.canClone(InstanceLifecycle.State.RUNNING));
        assertFalse(InstanceActionSafety.canClone(InstanceLifecycle.State.PROVISIONING));
        assertFalse(InstanceActionSafety.canClone(InstanceLifecycle.State.CRASHED));
        assertFalse(InstanceActionSafety.canClone(InstanceLifecycle.State.INTERRUPTED));
    }

    @Test
    public void requireCanCloneThrowsWhenRunningOrProvisioning() {
        try {
            InstanceActionSafety.requireCanClone(InstanceLifecycle.State.RUNNING);
            fail("Expected IllegalStateException when cloning RUNNING instance");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot clone"));
        }

        try {
            InstanceActionSafety.requireCanClone(InstanceLifecycle.State.PROVISIONING);
            fail("Expected IllegalStateException when cloning PROVISIONING instance");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot clone"));
        }
    }

    @Test
    public void requireCanCloneSucceedsWhenStoppedOrCreated() {
        InstanceActionSafety.requireCanClone(InstanceLifecycle.State.STOPPED);
        InstanceActionSafety.requireCanClone(InstanceLifecycle.State.CREATED);
    }
}
