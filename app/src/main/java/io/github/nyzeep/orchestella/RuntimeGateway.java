package io.github.nyzeep.orchestella;

/** A host-side gateway process owned by one runtime supervisor. */
public interface RuntimeGateway {
    /** True while the gateway process has not exited. */
    boolean isAlive();

    /** Stops the gateway with a bounded wait; true means exit was confirmed. */
    boolean stop(long graceMs);
}
