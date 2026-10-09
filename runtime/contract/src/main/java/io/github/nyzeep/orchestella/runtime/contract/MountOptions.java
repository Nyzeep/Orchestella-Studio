package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral mount options for virtual filesystem shares.
 *
 * <p>Enforces ADR-0010 security boundary:
 * <ul>
 *   <li>Only read-only (ro) mode is permitted in the MVP. Any attempt to configure writable
 *       shares is physically rejected with {@link IllegalArgumentException}.</li>
 *   <li>Standardized 9p version ({@value #DEFAULT_VERSION}) and security model.</li>
 * </ul>
 */
public final class MountOptions {
    public static final String DEFAULT_VERSION = "9p2000.L";

    public enum SecurityModel {
        NONE("none"),
        PASSTHROUGH("passthrough"),
        MAPPED("mapped"),
        MAPPED_XATTR("mapped-xattr");

        private final String wireValue;

        SecurityModel(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    private final boolean readOnly;
    private final SecurityModel securityModel;
    private final String version;

    private MountOptions(boolean readOnly, SecurityModel securityModel, String version) {
        if (!readOnly) {
            throw new IllegalArgumentException(
                    "Read-write shared directory is rejected: ADR-0010 permits only read-only Downloads sharing");
        }
        this.readOnly = true;
        this.securityModel = Objects.requireNonNull(securityModel, "securityModel");
        this.version = Objects.requireNonNull(version, "version");
    }

    public static MountOptions readOnly() {
        return new Builder().build();
    }

    public static MountOptions readOnly(SecurityModel securityModel) {
        return new Builder().securityModel(securityModel).build();
    }

    public static MountOptions ro() {
        return readOnly();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public SecurityModel securityModel() {
        return securityModel;
    }

    public String version() {
        return version;
    }

    public String toGuestMountOptionsString() {
        return "trans=virtio,version=" + version + ",ro";
    }

    public static final class Builder {
        private boolean readOnly = true;
        private SecurityModel securityModel = SecurityModel.NONE;
        private String version = DEFAULT_VERSION;

        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        public Builder securityModel(SecurityModel securityModel) {
            this.securityModel = Objects.requireNonNull(securityModel, "securityModel");
            return this;
        }

        public Builder version(String version) {
            this.version = Objects.requireNonNull(version, "version");
            return this;
        }

        public MountOptions build() {
            return new MountOptions(readOnly, securityModel, version);
        }
    }
}
