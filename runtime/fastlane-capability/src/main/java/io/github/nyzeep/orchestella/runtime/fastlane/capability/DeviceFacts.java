package io.github.nyzeep.orchestella.runtime.fastlane.capability;

import java.util.Objects;

/**
 * Immutable device observations consumed by {@link FastLaneRouter}.
 *
 * <p>Every capability is tri-state. A missing read is {@link Support#UNKNOWN},
 * not a silent yes; the router resolves unknown to the Full Lane.
 */
public final class DeviceFacts {
    private final Support avfPresent;
    private final Support protectedVmSupported;
    private final Support managePermissionGranted;
    private final Support hiddenApiBlocked;
    private final Support encryptedStorageAvailable;
    private final Support tcpProxyAvailable;
    private final Support sealedToolboxAvailable;
    private final Support distccOffloadAvailable;
    private final Support fullLaneAvailable;

    private DeviceFacts(Builder builder) {
        avfPresent = builder.avfPresent;
        protectedVmSupported = builder.protectedVmSupported;
        managePermissionGranted = builder.managePermissionGranted;
        hiddenApiBlocked = builder.hiddenApiBlocked;
        encryptedStorageAvailable = builder.encryptedStorageAvailable;
        tcpProxyAvailable = builder.tcpProxyAvailable;
        sealedToolboxAvailable = builder.sealedToolboxAvailable;
        distccOffloadAvailable = builder.distccOffloadAvailable;
        fullLaneAvailable = builder.fullLaneAvailable;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Nothing has been read yet. */
    public static DeviceFacts unknown() {
        return builder().build();
    }

    /** A device that only permits protected VMs: the Fast Lane target. */
    public static DeviceFacts protectedOnly() {
        return builder()
                .avfPresent(Support.YES)
                .protectedVmSupported(Support.YES)
                .managePermissionGranted(Support.YES)
                .hiddenApiBlocked(Support.NO)
                .encryptedStorageAvailable(Support.YES)
                .tcpProxyAvailable(Support.YES)
                .sealedToolboxAvailable(Support.YES)
                .distccOffloadAvailable(Support.YES)
                .fullLaneAvailable(Support.YES)
                .build();
    }

    public static DeviceFacts noAvf() {
        return builder()
                .avfPresent(Support.NO)
                .protectedVmSupported(Support.NO)
                .managePermissionGranted(Support.YES)
                .hiddenApiBlocked(Support.NO)
                .encryptedStorageAvailable(Support.NO)
                .tcpProxyAvailable(Support.NO)
                .sealedToolboxAvailable(Support.NO)
                .distccOffloadAvailable(Support.NO)
                .fullLaneAvailable(Support.YES)
                .build();
    }

    public static DeviceFacts hiddenApiBlockedDevice() {
        return protectedOnly().toBuilder().hiddenApiBlocked(Support.YES).build();
    }

    public static DeviceFacts permissionDenied() {
        return protectedOnly().toBuilder().managePermissionGranted(Support.NO).build();
    }

    public static DeviceFacts withoutEncryptedStorage() {
        return protectedOnly().toBuilder().encryptedStorageAvailable(Support.NO).build();
    }

    public static DeviceFacts withoutTcpProxy() {
        return protectedOnly().toBuilder().tcpProxyAvailable(Support.NO).build();
    }

    public static DeviceFacts withoutFullLane() {
        return protectedOnly().toBuilder().fullLaneAvailable(Support.NO).build();
    }

    public static DeviceFacts noAvfWithoutFullLane() {
        return noAvf().toBuilder().fullLaneAvailable(Support.NO).build();
    }

    /** Copies every observation into a fresh builder. */
    public Builder toBuilder() {
        return builder()
                .avfPresent(avfPresent)
                .protectedVmSupported(protectedVmSupported)
                .managePermissionGranted(managePermissionGranted)
                .hiddenApiBlocked(hiddenApiBlocked)
                .encryptedStorageAvailable(encryptedStorageAvailable)
                .tcpProxyAvailable(tcpProxyAvailable)
                .sealedToolboxAvailable(sealedToolboxAvailable)
                .distccOffloadAvailable(distccOffloadAvailable)
                .fullLaneAvailable(fullLaneAvailable);
    }

    public Support avfPresent() {
        return avfPresent;
    }

    public Support protectedVmSupported() {
        return protectedVmSupported;
    }

    public Support managePermissionGranted() {
        return managePermissionGranted;
    }

    public Support hiddenApiBlocked() {
        return hiddenApiBlocked;
    }

    public Support encryptedStorageAvailable() {
        return encryptedStorageAvailable;
    }

    public Support tcpProxyAvailable() {
        return tcpProxyAvailable;
    }

    public Support sealedToolboxAvailable() {
        return sealedToolboxAvailable;
    }

    public Support distccOffloadAvailable() {
        return distccOffloadAvailable;
    }

    public Support fullLaneAvailable() {
        return fullLaneAvailable;
    }

    /** Mutable builder; each setter returns this builder. */
    public static final class Builder {
        private Support avfPresent = Support.UNKNOWN;
        private Support protectedVmSupported = Support.UNKNOWN;
        private Support managePermissionGranted = Support.UNKNOWN;
        private Support hiddenApiBlocked = Support.UNKNOWN;
        private Support encryptedStorageAvailable = Support.UNKNOWN;
        private Support tcpProxyAvailable = Support.UNKNOWN;
        private Support sealedToolboxAvailable = Support.UNKNOWN;
        private Support distccOffloadAvailable = Support.UNKNOWN;
        private Support fullLaneAvailable = Support.UNKNOWN;

        public Builder avfPresent(Support value) {
            avfPresent = Objects.requireNonNull(value, "avfPresent");
            return this;
        }

        public Builder protectedVmSupported(Support value) {
            protectedVmSupported = Objects.requireNonNull(value, "protectedVmSupported");
            return this;
        }

        public Builder managePermissionGranted(Support value) {
            managePermissionGranted = Objects.requireNonNull(value, "managePermissionGranted");
            return this;
        }

        public Builder hiddenApiBlocked(Support value) {
            hiddenApiBlocked = Objects.requireNonNull(value, "hiddenApiBlocked");
            return this;
        }

        public Builder encryptedStorageAvailable(Support value) {
            encryptedStorageAvailable = Objects.requireNonNull(value, "encryptedStorageAvailable");
            return this;
        }

        public Builder tcpProxyAvailable(Support value) {
            tcpProxyAvailable = Objects.requireNonNull(value, "tcpProxyAvailable");
            return this;
        }

        public Builder sealedToolboxAvailable(Support value) {
            sealedToolboxAvailable = Objects.requireNonNull(value, "sealedToolboxAvailable");
            return this;
        }

        public Builder distccOffloadAvailable(Support value) {
            distccOffloadAvailable = Objects.requireNonNull(value, "distccOffloadAvailable");
            return this;
        }

        public Builder fullLaneAvailable(Support value) {
            fullLaneAvailable = Objects.requireNonNull(value, "fullLaneAvailable");
            return this;
        }

        public DeviceFacts build() {
            return new DeviceFacts(this);
        }
    }
}
