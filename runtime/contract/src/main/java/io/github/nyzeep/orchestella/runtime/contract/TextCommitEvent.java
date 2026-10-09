package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral text commitment event carrying arbitrary Unicode strings (CJK characters, Emoji, symbols).
 *
 * <p>Conforms to ADR-0017 (Android IME passthrough bridge).
 */
public final class TextCommitEvent implements InputEvent {
    private final String text;
    private final long timestampNs;

    public TextCommitEvent(String text, long timestampNs) {
        this.text = Objects.requireNonNull(text, "text");
        this.timestampNs = timestampNs;
    }

    @Override
    public Type eventType() {
        return Type.TEXT_COMMIT;
    }

    public String text() {
        return text;
    }

    @Override
    public long timestampNs() {
        return timestampNs;
    }

    @Override
    public String serialize() {
        return InputEventSerialization.serialize(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TextCommitEvent)) {
            return false;
        }
        TextCommitEvent that = (TextCommitEvent) o;
        return timestampNs == that.timestampNs && Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, timestampNs);
    }

    @Override
    public String toString() {
        return "TextCommitEvent{"
                + "text='" + text + '\''
                + ", timestampNs=" + timestampNs
                + '}';
    }
}
