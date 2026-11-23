package io.github.nyzeep.orchestella.runtime.contract;

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

    /** A session engine that can confirm which explicit user rules are listening. */
    interface Live extends PortForwardingEngine {
        void refresh() throws IOException;

        boolean isApplied(PortForwardingRule rule);

        default boolean supportsRule(PortForwardingRule rule) {
            return supportsIpv4LoopbackTcp(rule);
        }

        static boolean supportsIpv4LoopbackTcp(PortForwardingRule rule) {
            return rule.protocol() == Protocol.TCP
                    && PortForwardingRule.LOOPBACK_ADDRESS.equals(rule.bindAddress())
                    && !PortForwardingRule.isReservedHostPort(rule.hostPort());
        }

        /**
         * Changes an applied rule. A different endpoint must bind before the
         * old listener is retired. Changing the target without changing the
         * listener requires in-place support from the engine.
         */
        default void replaceRule(PortForwardingRule previous, PortForwardingRule replacement)
                throws IOException {
            if (previous.hasSameListener(replacement)) {
                if (previous.guestPort() == replacement.guestPort()) return;
                throw new ListenerRetargetUnsupported();
            }
            addRule(replacement);
            try {
                removeRule(previous);
            } catch (IOException failure) {
                try {
                    removeRule(replacement);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** The engine requires a different host endpoint to replace a live target safely. */
    final class ListenerRetargetUnsupported extends IOException {
        private static final long serialVersionUID = 1L;

        public ListenerRetargetUnsupported() {
            super("gateway cannot retarget the same listener");
        }
    }
}
