package io.github.nyzeep.orchestella;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class FirstBootProvisionPipelineTest {

    @Test
    public void debianWithTunaMirrorAndAutoUpdate() {
        FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                "debian",
                DistroMirrorProvisioner.MirrorSource.TUNA,
                true
        );

        assertTrue(payload.hasMirrorConfig());
        assertEquals("sources.list", payload.mirrorConfigFileName());
        String mirrorText = new String(payload.mirrorConfigFileContent(), StandardCharsets.UTF_8);
        assertTrue(mirrorText.contains("mirrors.tuna.tsinghua.edu.cn"));

        assertTrue(payload.hasFirstBootScript());
        String script = new String(payload.firstBootScript(), StandardCharsets.UTF_8);
        assertTrue(script.contains("apt-get update && apt-get upgrade -y"));
        assertTrue(script.contains("timeout 300s"));
        assertTrue(script.contains(".firstboot-done"));
    }

    @Test
    public void alpineWithUstcMirrorAndAutoUpdate() {
        FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                "alpine",
                DistroMirrorProvisioner.MirrorSource.USTC,
                true
        );

        assertTrue(payload.hasMirrorConfig());
        assertEquals("repositories", payload.mirrorConfigFileName());
        String mirrorText = new String(payload.mirrorConfigFileContent(), StandardCharsets.UTF_8);
        assertTrue(mirrorText.contains("mirrors.ustc.edu.cn"));

        assertTrue(payload.hasFirstBootScript());
        String script = new String(payload.firstBootScript(), StandardCharsets.UTF_8);
        assertTrue(script.contains("apk update && apk upgrade"));
        assertTrue(script.contains("timeout 120s"));
    }

    @Test
    public void autoUpdateDisabledProducesNoScript() {
        FirstBootProvisionPipeline.ProvisionPayload payload = FirstBootProvisionPipeline.create(
                "debian",
                DistroMirrorProvisioner.MirrorSource.OFFICIAL,
                false
        );

        assertTrue(payload.hasMirrorConfig());
        assertFalse(payload.hasFirstBootScript());
        assertNull(payload.firstBootScript());
    }
}
