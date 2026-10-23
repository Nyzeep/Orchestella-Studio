package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Observable lifecycle state of one lane. */
public enum LaneState {
    STOPPED,
    STARTING,
    READY,
    STOPPING,
    LOST,
    UNCONFIRMED,
    FAILED,
    DISABLED
}
