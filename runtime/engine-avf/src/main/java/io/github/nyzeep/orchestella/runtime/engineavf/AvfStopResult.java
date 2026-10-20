package io.github.nyzeep.orchestella.runtime.engineavf;

/**
 * Bounded outcome of one host-side AVF stop. Only {@link #CONFIRMED} and
 * {@link #ALREADY_STOPPED} are evidence that the VM is gone; a platform call
 * that returned or threw without an observed STOPPED/DELETED status is
 * {@link #UNCONFIRMED}.
 */
public enum AvfStopResult {
    CONFIRMED,
    UNCONFIRMED,
    ALREADY_STOPPED
}
