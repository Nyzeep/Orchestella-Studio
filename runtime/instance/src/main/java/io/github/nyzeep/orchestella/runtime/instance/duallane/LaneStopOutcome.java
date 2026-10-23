package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Strategy-neutral stop outcome. Adapters map their native enum onto this. */
public enum LaneStopOutcome {
    CONFIRMED,
    UNCONFIRMED,
    ALREADY_STOPPED
}
