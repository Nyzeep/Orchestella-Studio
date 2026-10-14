package io.github.nyzeep.orchestella.guestassets;

import io.github.nyzeep.orchestella.DistroMirrorProvisioner;
import io.github.nyzeep.orchestella.FirstBootProvisionPipeline;
import io.github.nyzeep.orchestella.GuestRuntimeProvisioner;
import java.nio.charset.StandardCharsets;

/** Offline, plain-JVM contract probe; compile with the three payload source classes. */
public final class GuestRuntimePayloadProbe {
    public static void main(String[] args) {
        int checks = 0;
        for (String distro : new String[] {"debian", "ubuntu", "alpine", "arch", "fedora", "almalinux", "rocky", "opensuse"}) {
            FirstBootProvisionPipeline.ProvisionPayload base = FirstBootProvisionPipeline.create(
                    distro, DistroMirrorProvisioner.MirrorSource.OFFICIAL, false);
            require(base.hasFirstBootScript(), distro + " baseline cannot be optional");
            String script = text(base.firstBootScript());
            require(script.contains("ORCHESTELLA_AUTO_UPDATE=0"), "upgrade choice");
            require(script.contains("ORCHESTELLA_EXPECTED_DISTRO='" + distro + "'"), "distro identity");
            require(script.contains("exec /usr/sbin/orchestella-guest-provision"), "guest failure propagation");
            require(!script.contains("touch ") && !script.contains("status=ready") && !script.contains(" &"), "no early success");
            checks += 5;
        }
        String debian = text(FirstBootProvisionPipeline.create("debian", "12",
                DistroMirrorProvisioner.MirrorSource.TUNA, true).mirrorConfigFileContent());
        require(debian.contains("bookworm-security") && !debian.contains("trixie"), "manifest Debian version"); checks++;
        String ubuntu = DistroMirrorProvisioner.generateMirrorConfig("ubuntu", "22.04", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        require(ubuntu.contains("ports.ubuntu.com/ubuntu-ports/ jammy") && !ubuntu.contains("archive.ubuntu.com"), "ARM Ubuntu mirror"); checks++;
        String arch = DistroMirrorProvisioner.generateMirrorConfig("arch", "latest", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        require(arch.contains("ca.us.mirror.archlinuxarm.org/$arch/$repo") && !arch.contains("$repo/os/$arch"), "ARM Arch layout"); checks++;
        String alpine = DistroMirrorProvisioner.generateMirrorConfig("alpine", "3.23.6", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        require(alpine.contains("/v3.23/main") && alpine.contains("/v3.23/community") && !alpine.contains("v3.24"), "Alpine minor version"); checks++;
        String leap = DistroMirrorProvisioner.generateMirrorConfig("opensuse", "16.0", DistroMirrorProvisioner.MirrorSource.MULTI_TIER);
        require(leap.contains("distribution/leap/16.0/repo/oss/") && !leap.contains("tumbleweed"), "Leap catalog version"); checks++;
        for (String distro : new String[] {"fedora", "almalinux", "rocky", "opensuse"}) {
            String repo = DistroMirrorProvisioner.generateMirrorConfig(distro, "latest", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
            require(repo.contains("gpgcheck=1") && !repo.contains("gpgcheck=0"), "RPM signatures " + distro); checks++;
        }
        require(GuestRuntimeProvisioner.ROOT_VOLUME_CREATION_SIGNATURE.getBytes(StandardCharsets.US_ASCII).length == 33,
                "root-volume creation signature bytes"); checks++;
        try {
            GuestRuntimeProvisioner.firstBootScript("debian;echo ready", false);
            throw new AssertionError("shell injection accepted");
        } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("GUEST_RUNTIME_PAYLOAD_PROBE_OK checks=" + checks);
    }
    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
