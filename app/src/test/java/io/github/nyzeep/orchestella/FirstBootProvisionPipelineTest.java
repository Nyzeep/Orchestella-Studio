package io.github.nyzeep.orchestella;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class FirstBootProvisionPipelineTest {
    @Test public void debianMirrorUsesManifestVersionAndDelegatesFailuresToGuest() {
        FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                "debian", "12", DistroMirrorProvisioner.MirrorSource.TUNA, true);
        assertTrue(payload.hasMirrorConfig());
        assertEquals("sources.list", payload.mirrorConfigFileName());
        String mirror = text(payload.mirrorConfigFileContent());
        assertTrue(mirror.contains("mirrors.tuna.tsinghua.edu.cn/debian/ bookworm"));
        assertTrue(mirror.contains("bookworm-security"));
        assertFalse(mirror.contains("trixie"));
        String script = text(payload.firstBootScript());
        assertTrue(script.contains(GuestRuntimeProvisioner.SCRIPT_VERSION));
        assertTrue(script.contains("ORCHESTELLA_EXPECTED_DISTRO='debian'"));
        assertTrue(script.contains("ORCHESTELLA_AUTO_UPDATE=1"));
        // exec propagates the synchronous guest install result; wrappers never pre-write success.
        assertTrue(script.contains("exec /usr/sbin/orchestella-guest-provision \"$@\""));
        assertFalse(script.contains("touch "));
        assertFalse(script.contains(".firstboot-done"));
        assertFalse(script.contains(" &"));
    }

    @Test public void fedoraMirrorRetainsPackageSignatureChecksAndBaselineEntrypoint() {
        FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                "fedora", "43", DistroMirrorProvisioner.MirrorSource.USTC, true);
        assertEquals("fedora.repo", payload.mirrorConfigFileName());
        String mirror = text(payload.mirrorConfigFileContent());
        assertTrue(mirror.contains("mirrors.ustc.edu.cn/fedora"));
        assertTrue(mirror.contains("$releasever/Everything/$basearch"));
        assertTrue(mirror.contains("gpgcheck=1"));
        assertFalse(mirror.contains("gpgcheck=0"));
        String script = text(payload.firstBootScript());
        assertTrue(script.contains("ORCHESTELLA_EXPECTED_DISTRO='fedora'"));
        assertTrue(script.contains("ORCHESTELLA_AUTO_UPDATE=1"));
        assertTrue(script.contains("orchestella-guest-provision"));
    }

    @Test public void optionalUpgradeDisabledStillProducesMandatoryBaseline() {
        FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                "ubuntu", "24.04", DistroMirrorProvisioner.MirrorSource.OFFICIAL, false);
        assertTrue(payload.hasFirstBootScript());
        assertNotNull(payload.firstBootScript());
        assertTrue(text(payload.firstBootScript()).contains("ORCHESTELLA_AUTO_UPDATE=0"));
        assertTrue(text(payload.firstBootScript()).contains("ORCHESTELLA_EXPECTED_DISTRO='ubuntu'"));
        assertTrue(text(payload.mirrorConfigFileContent()).contains("ports.ubuntu.com/ubuntu-ports/ noble"));
        assertFalse(text(payload.firstBootScript()).contains("status=ready"));
    }

    @Test public void alpineRepositoryUsesManifestMinorVersion() {
        FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                "alpine", "3.23.6", DistroMirrorProvisioner.MirrorSource.OFFICIAL, false);
        assertEquals("repositories", payload.mirrorConfigFileName());
        assertTrue(text(payload.mirrorConfigFileContent()).contains("/v3.23/main"));
        assertTrue(text(payload.mirrorConfigFileContent()).contains("/v3.23/community"));
        assertFalse(text(payload.mirrorConfigFileContent()).contains("/v3.24/"));
        assertTrue(payload.hasFirstBootScript());
    }

    @Test public void unsupportedDistroCannotInjectShellText() {
        try {
            FirstBootProvisionPipeline.generateScriptForDistro("ubuntu; touch /tmp/ready");
            fail("Unsupported distro must be rejected before producing a script");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported"));
        }
    }

    @Test public void defaultTemplateSupportsRecoveryWithoutDependingOnDamagedBinSh() {
        String script = FirstBootProvisionPipeline.generateScriptForDistro("debian");
        assertTrue(script.contains("/usr/lib/orchestella/busybox sh /usr/sbin/orchestella-guest-provision"));
        assertTrue(script.contains("ORCHESTELLA_AUTO_UPDATE=0"));
        assertFalse(script.contains("1234"));
    }

    private static String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }
}
