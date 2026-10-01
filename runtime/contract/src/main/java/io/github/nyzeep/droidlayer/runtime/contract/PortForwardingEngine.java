package io.github.nyzeep.droidlayer.runtime.contract;

import java.io.IOException;

/**
 * Strategy-neutral runtime engine seam for dynamic port forwarding reconciliation.
 *
 * <p>Execution engines (such as QEMU via QMP or AVF via vsock proxy) implement this seam
 * to dynamically apply or remove host forwarding rules without restarting the virtual machine.
 */
public interface PortForwardingEngine {
    /**
     * Dynamically adds and activates a port forwarding rule on the running hypervisor/engine.
     */
    void addRule(PortForwardingRule rule) throws IOException;

    /**
     * Dynamically removes and deactivates a port forwarding rule on the running hypervisor/engine.
     */
    void removeRule(PortForwardingRule rule) throws IOException;

    /**
     * Returns true if the underlying hypervisor/engine is currently active and can receive commands.
     */
    boolean isRunning();
}
