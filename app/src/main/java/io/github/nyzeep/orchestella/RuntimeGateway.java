package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;

/** A host-side gateway process owned by one runtime supervisor. */
public interface RuntimeGateway {
    /** True while the gateway process has not exited. */
    boolean isAlive();

    /** Stops the gateway with a bounded wait; true means exit was confirmed. */
    boolean stop(long graceMs);

    /** The forwarding API for this gateway process, when it is usable. */
    default PortForwardingEngine.Live forwardingEngine() {
        return null;
    }
}
