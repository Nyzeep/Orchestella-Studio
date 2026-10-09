package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;

/**
 * Host-side launch lifecycle sequencing for one instance. The Full Linux
 * contract v0 only allows CREATED -> PROVISIONING -> STOPPED -> RUNNING ->
 * STOPPED, so a launch must drive the provisioning record before the RUNNING
 * transition, and an observed guest exit may only record RUNNING -> STOPPED.
 */
public final class InstanceLaunchSequence {
    private InstanceLaunchSequence() {}

    /**
     * Drives the normal pre-launch transitions: first boot records CREATED ->
     * PROVISIONING -> STOPPED (host-side provisioning of verified inputs), then
     * every boot takes the single RUNNING slot via STOPPED -> RUNNING.
     */
    public static void beforeLaunch(InstanceLifecycle.Controller controller,
            InstanceLifecycle.Instance instance) throws IOException {
        if (instance.state() == InstanceLifecycle.State.CREATED) {
            controller.beginProvisioning(instance.id());
            controller.completeProvisioning(instance.id());
        }
        controller.start(instance.id());
    }

    /** Records an observed normal guest-initiated stop: RUNNING -> STOPPED. */
    public static void afterGuestExit(InstanceLifecycle.Controller controller, String instanceId)
            throws IOException {
        controller.recordNormalGuestStop(instanceId);
    }
}
