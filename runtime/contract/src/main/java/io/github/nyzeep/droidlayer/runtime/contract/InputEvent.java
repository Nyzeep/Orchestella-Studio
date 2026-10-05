package io.github.nyzeep.droidlayer.runtime.contract;

/**
 * Strategy-neutral base contract for input events flowing between the Android host and guest Linux desktop.
 *
 * <p>Conforms to ADR-0017 and Ticket 38. All events support compact text serialization constrained
 * to {@value #MAX_WIRE_LENGTH} characters for robust single-line bridge/channel transmission.
 */
public interface InputEvent {
    /** Maximum allowed characters for a single serialized event wire line. */
    int MAX_WIRE_LENGTH = 2048;

    /** Category of the input event. */
    enum Type {
        POINTER,
        TOUCH,
        KEY,
        TEXT_COMMIT
    }

    /**
     * Returns the type of this input event.
     *
     * @return event type
     */
    Type eventType();

    /**
     * Returns the event creation timestamp in nanoseconds.
     *
     * @return timestamp in nanoseconds
     */
    long timestampNs();

    /**
     * Serializes this event into a compact, single-line wire format ending with {@code \n}.
     *
     * @return serialized wire line
     * @throws IllegalArgumentException if the serialized payload exceeds {@value #MAX_WIRE_LENGTH} characters
     */
    String serialize();

    /**
     * Deserializes a wire line into a concrete {@link InputEvent}.
     *
     * @param wire serialized wire string
     * @return deserialized InputEvent
     * @throws IllegalArgumentException if the wire format is invalid or exceeds {@value #MAX_WIRE_LENGTH} characters
     */
    static InputEvent deserialize(String wire) {
        return InputEventSerialization.deserialize(wire);
    }
}
