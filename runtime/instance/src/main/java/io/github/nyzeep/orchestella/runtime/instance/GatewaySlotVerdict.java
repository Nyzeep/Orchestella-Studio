package io.github.nyzeep.orchestella.runtime.instance;

/**
 * Outcome of inspecting the derived gateway port and Unix API path of one instance.
 *
 * <p>The port itself is never an identity: a bound port only proves that <em>something</em> is
 * listening, not that the listener belongs to this instance. Every constant below therefore states
 * both the condition under which it holds and the action it permits, so that a caller can never
 * read "the port is taken" as "our previous gateway is still running".
 *
 * <p>See {@link GatewaySlotArbiter} for the decision order.
 */
public enum GatewaySlotVerdict {

    /**
     * The port and Unix API path are both available, and the evidence is readable.
     *
     * <p>Permitted action: start a new gateway. A process whose command line merely looks like a
     * gateway of this instance does not matter here — an idle port is idle regardless of history.
     */
    FREE,

    /**
     * At least one endpoint is occupied and an observed process carries the instance's identity
     * in its command line.
     *
     * <p>Permitted action: report the owner. Reclaiming an owned socket additionally requires
     * confirmed process exit.
     */
    ORPHAN_OF_THIS_INSTANCE,

    /**
     * An endpoint is occupied and the snapshot provides no matching process identity.
     *
     * <p>Permitted action: preserve the endpoints and report the conflict.
     */
    HELD_BY_UNKNOWN,

    /**
     * The probe itself did not complete, for example because the process table could not be read.
     *
     * <p>Permitted action: none, and in particular this must not be degraded to {@link #FREE}: a
     * failed probe says nothing about whether the port is bound.
     */
    EVIDENCE_UNAVAILABLE
}
