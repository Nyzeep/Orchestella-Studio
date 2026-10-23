package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

/** Fixed AXP1 error codes. The wire carries only the numeric code. */
public enum Axp1Error {
    VERSION_MISMATCH(1),
    AUTH_FAILED(2),
    FRAME_TOO_LARGE(3),
    PROTOCOL_ERROR(4),
    TIMEOUT(5),
    CLOSED(6);

    private final int wireCode;

    Axp1Error(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    /** Unknown codes are not forward-compatible; they become PROTOCOL_ERROR. */
    public static Axp1Error fromWire(int code) {
        for (Axp1Error candidate : values()) {
            if (candidate.wireCode == code) {
                return candidate;
            }
        }
        return PROTOCOL_ERROR;
    }
}
