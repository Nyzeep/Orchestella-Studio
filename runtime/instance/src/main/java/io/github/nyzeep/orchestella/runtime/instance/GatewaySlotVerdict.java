package io.github.nyzeep.orchestella.runtime.instance;

/**
 * Outcome of inspecting the derived gateway port of one instance.
 *
 * <p>The port itself is never an identity: a bound port only proves that <em>something</em> is
 * listening, not that the listener belongs to this instance. Every constant below therefore states
 * both the condition under which it holds and the action it permits, so that a caller can never
 * read "the port is taken" as "our previous gateway is still running".
 *
 * <p>See {@link GatewaySlotArbiter} for the decision order and for the reason this type is not
 * wired into production.
 */
public enum GatewaySlotVerdict {

    /**
     * The port is not bound and the process table was readable.
     *
     * <p>Permitted action: start a new gateway. A process whose command line merely looks like a
     * gateway of this instance does not matter here — an idle port is idle regardless of history.
     */
    FREE,

    /**
     * The port is bound <em>and</em> some process in the snapshot carries the identity of this
     * instance, i.e. its command line contains {@code net-<instanceId>.yaml}.
     *
     * <p>Permitted action: reclaim the previous gateway of this instance is allowed in principle;
     * today the verdict is reported as a diagnostic only.
     */
    ORPHAN_OF_THIS_INSTANCE,

    /**
     * The port is bound but no process in the snapshot carries the identity of this instance, or
     * the snapshot is empty.
     *
     * <p>Permitted action: none. Reclaiming is forbidden because the listener may belong to another
     * instance whose QEMU dies with its gateway; the start must keep failing.
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
