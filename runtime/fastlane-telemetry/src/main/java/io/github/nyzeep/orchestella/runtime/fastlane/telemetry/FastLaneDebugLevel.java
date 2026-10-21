package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

/**
 * Microdroid debug level for the Fast Lane payload.
 *
 * <p>Production is {@link #NONE}. {@link #FULL} is a QA-only setting: it can
 * expose guest internals and therefore weakens the protection boundary. Every
 * event emitted under {@code FULL} carries {@code protectionWeakened:true} so a
 * log reader cannot mistake it for a production sample.
 */
public enum FastLaneDebugLevel {
    NONE(false),
    FULL(true);

    private final boolean weakensProtection;

    FastLaneDebugLevel(boolean weakensProtection) {
        this.weakensProtection = weakensProtection;
    }

    public boolean weakensProtection() {
        return weakensProtection;
    }
}
