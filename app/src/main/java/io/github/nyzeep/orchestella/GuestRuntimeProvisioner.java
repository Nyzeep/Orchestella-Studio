package io.github.nyzeep.orchestella;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/** Guest-only baseline entrypoint; package installation and readiness belong to the guest. */
public final class GuestRuntimeProvisioner {
    public static final String ROOT_VOLUME_CREATION_SIGNATURE = "ORCHESTELLA_EMPTY_ROOT_VOLUME_V2\n";
    public static final String SCRIPT_VERSION = "# orchestella-guest-runtime-version=2";

    private GuestRuntimeProvisioner() {}

    /** Always installs the required baseline; upgrading existing packages is a separate choice. */
    public static String firstBootScript(String distroId, boolean autoUpdate) {
        String id = Objects.requireNonNull(distroId, "distroId").trim().toLowerCase(Locale.ROOT);
        switch (id) {
            case "debian": case "ubuntu": case "alpine": case "arch":
            case "fedora": case "almalinux": case "rocky": case "opensuse":
                break;
            default:
                throw new IllegalArgumentException("unsupported guest provisioning distro: " + id);
        }
        return "#!/bin/sh\n" + SCRIPT_VERSION + "\n"
                + "# Mandatory baseline is independent of the optional distribution upgrade.\n"
                + "export ORCHESTELLA_EXPECTED_DISTRO='" + id + "'\n"
                + "export ORCHESTELLA_AUTO_UPDATE=" + (autoUpdate ? "1" : "0") + "\n"
                + "if [ -x /usr/lib/orchestella/busybox ]; then\n"
                + "    exec /usr/lib/orchestella/busybox sh /usr/sbin/orchestella-guest-provision \"$@\"\n"
                + "fi\n"
                + "exec /usr/sbin/orchestella-guest-provision \"$@\"\n";
    }

    public static byte[] firstBootBytes(String distroId, boolean autoUpdate) {
        return firstBootScript(distroId, autoUpdate).getBytes(StandardCharsets.UTF_8);
    }
}
