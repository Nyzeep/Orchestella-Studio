package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

/** Which side of the accelerator channel this endpoint plays. */
public enum Axp1Role {
    HOST(1),
    GUEST(2);

    private final int wireCode;

    Axp1Role(int wireCode) {
        this.wireCode = wireCode;
    }

    public int wireCode() {
        return wireCode;
    }

    static Axp1Role fromWire(int code) {
        for (Axp1Role candidate : values()) {
            if (candidate.wireCode == code) {
                return candidate;
            }
        }
        return null;
    }
}
