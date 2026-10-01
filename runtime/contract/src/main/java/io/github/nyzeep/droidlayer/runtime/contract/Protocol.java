package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Locale;
import java.util.Objects;

/**
 * Strategy-neutral transport layer protocol for port forwarding rules (ADR-0008).
 */
public enum Protocol {
    TCP,
    UDP;

    /**
     * Returns the lowercase wire protocol representation ("tcp", "udp").
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a wire protocol string case-insensitively.
     */
    public static Protocol fromWire(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("wire protocol must not be null");
        }
        String trimmed = wire.trim().toUpperCase(Locale.ROOT);
        try {
            return Protocol.valueOf(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported protocol: " + wire);
        }
    }
}
