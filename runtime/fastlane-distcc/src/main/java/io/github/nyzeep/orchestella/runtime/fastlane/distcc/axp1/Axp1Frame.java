package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import java.util.Objects;

/** One AXP1 frame. Payload bytes are copied on construction and on read. */
public final class Axp1Frame {
    public enum Type {
        HELLO(1),
        HELLO_ACK(2),
        DATA(3),
        EOF(4),
        RST(5),
        PING(6),
        PONG(7);

        private final int wireCode;

        Type(int wireCode) {
            this.wireCode = wireCode;
        }

        public int wireCode() {
            return wireCode;
        }

        static Type fromWire(int code) {
            for (Type candidate : values()) {
                if (candidate.wireCode == code) {
                    return candidate;
                }
            }
            return null;
        }
    }

    private final Type type;
    private final byte[] payload;

    private Axp1Frame(Type type, byte[] payload) {
        this.type = Objects.requireNonNull(type, "type");
        this.payload = payload.clone();
    }

    public static Axp1Frame of(Type type, byte[] payload) {
        return new Axp1Frame(type, payload == null ? new byte[0] : payload);
    }

    public static Axp1Frame empty(Type type) {
        return new Axp1Frame(type, new byte[0]);
    }

    public Type type() {
        return type;
    }

    public byte[] payload() {
        return payload.clone();
    }

    int payloadLength() {
        return payload.length;
    }
}
