package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Receives structured lane events. Implementations must not add payloads. */
@FunctionalInterface
public interface LaneEventSink {
    void emit(LaneEvent event);

    static LaneEventSink discarding() {
        return event -> { };
    }
}
