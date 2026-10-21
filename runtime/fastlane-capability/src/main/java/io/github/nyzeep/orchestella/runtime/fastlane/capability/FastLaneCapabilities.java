package io.github.nyzeep.orchestella.runtime.fastlane.capability;

import java.util.Objects;

/**
 * The single source of truth for what the protected Fast Lane offers.
 *
 * <p>This is a declaration, not an availability probe. {@link #v1()} is the
 * canonical contract and {@link #project(DeviceFacts)} narrows it to the
 * capabilities this device can actually provide. It never claims Full Linux:
 * no native network, package installation, Docker, systemd, or GPU.
 */
public final class FastLaneCapabilities {
    private final boolean terminal;
    private final boolean hardwareCpu;
    private final boolean encryptedStorage;
    private final boolean sealedToolbox;
    private final boolean tcpProxy;
    private final boolean exportImport;
    private final boolean distccOffload;
    private final boolean nativeNetwork;
    private final boolean nativePackageInstall;
    private final boolean docker;
    private final boolean systemd;
    private final boolean gpu;

    private FastLaneCapabilities(
            boolean terminal,
            boolean hardwareCpu,
            boolean encryptedStorage,
            boolean sealedToolbox,
            boolean tcpProxy,
            boolean exportImport,
            boolean distccOffload,
            boolean nativeNetwork,
            boolean nativePackageInstall,
            boolean docker,
            boolean systemd,
            boolean gpu) {
        this.terminal = terminal;
        this.hardwareCpu = hardwareCpu;
        this.encryptedStorage = encryptedStorage;
        this.sealedToolbox = sealedToolbox;
        this.tcpProxy = tcpProxy;
        this.exportImport = exportImport;
        this.distccOffload = distccOffload;
        this.nativeNetwork = nativeNetwork;
        this.nativePackageInstall = nativePackageInstall;
        this.docker = docker;
        this.systemd = systemd;
        this.gpu = gpu;
    }

    /** Canonical v1 matrix; every non-capability is explicit and false. */
    public static FastLaneCapabilities v1() {
        return new FastLaneCapabilities(
                true, true, true, true, true, true, true,
                false, false, false, false, false);
    }

    /** All-false matrix used when Fast Lane is not viable on this device. */
    public static FastLaneCapabilities none() {
        return new FastLaneCapabilities(
                false, false, false, false, false, false, false,
                false, false, false, false, false);
    }

    /**
     * Narrows {@link #v1()} to observed availability. Unknown is treated as
     * unavailable here; the router must already have refused to select FAST
     * when a required fact was unknown.
     */
    public FastLaneCapabilities project(DeviceFacts facts) {
        Objects.requireNonNull(facts, "facts");
        boolean viable = facts.avfPresent() == Support.YES
                && facts.protectedVmSupported() == Support.YES
                && facts.managePermissionGranted() == Support.YES
                && facts.hiddenApiBlocked() == Support.NO;
        if (!viable) {
            return none();
        }
        boolean storage = facts.encryptedStorageAvailable() == Support.YES;
        return new FastLaneCapabilities(
                true,
                true,
                storage,
                facts.sealedToolboxAvailable() == Support.YES,
                facts.tcpProxyAvailable() == Support.YES,
                storage,
                facts.distccOffloadAvailable() == Support.YES,
                false,
                false,
                false,
                false,
                false);
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean hardwareCpu() {
        return hardwareCpu;
    }

    public boolean encryptedStorage() {
        return encryptedStorage;
    }

    public boolean sealedToolbox() {
        return sealedToolbox;
    }

    public boolean tcpProxy() {
        return tcpProxy;
    }

    public boolean exportImport() {
        return exportImport;
    }

    public boolean distccOffload() {
        return distccOffload;
    }

    public boolean nativeNetwork() {
        return nativeNetwork;
    }

    public boolean nativePackageInstall() {
        return nativePackageInstall;
    }

    public boolean docker() {
        return docker;
    }

    public boolean systemd() {
        return systemd;
    }

    public boolean gpu() {
        return gpu;
    }

    /** Terminal-only display; this matrix never claims graphics. */
    public boolean terminalOnlyDisplay() {
        return terminal && !gpu;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FastLaneCapabilities that)) {
            return false;
        }
        return terminal == that.terminal
                && hardwareCpu == that.hardwareCpu
                && encryptedStorage == that.encryptedStorage
                && sealedToolbox == that.sealedToolbox
                && tcpProxy == that.tcpProxy
                && exportImport == that.exportImport
                && distccOffload == that.distccOffload
                && nativeNetwork == that.nativeNetwork
                && nativePackageInstall == that.nativePackageInstall
                && docker == that.docker
                && systemd == that.systemd
                && gpu == that.gpu;
    }

    @Override
    public int hashCode() {
        int result = terminal ? 1 : 0;
        result = 31 * result + (hardwareCpu ? 1 : 0);
        result = 31 * result + (encryptedStorage ? 1 : 0);
        result = 31 * result + (sealedToolbox ? 1 : 0);
        result = 31 * result + (tcpProxy ? 1 : 0);
        result = 31 * result + (exportImport ? 1 : 0);
        result = 31 * result + (distccOffload ? 1 : 0);
        result = 31 * result + (nativeNetwork ? 1 : 0);
        result = 31 * result + (nativePackageInstall ? 1 : 0);
        result = 31 * result + (docker ? 1 : 0);
        result = 31 * result + (systemd ? 1 : 0);
        result = 31 * result + (gpu ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "FastLaneCapabilities{terminal=" + terminal
                + ", hardwareCpu=" + hardwareCpu
                + ", encryptedStorage=" + encryptedStorage
                + ", sealedToolbox=" + sealedToolbox
                + ", tcpProxy=" + tcpProxy
                + ", exportImport=" + exportImport
                + ", distccOffload=" + distccOffload
                + ", nativeNetwork=" + nativeNetwork
                + ", nativePackageInstall=" + nativePackageInstall
                + ", docker=" + docker
                + ", systemd=" + systemd
                + ", gpu=" + gpu
                + '}';
    }
}
