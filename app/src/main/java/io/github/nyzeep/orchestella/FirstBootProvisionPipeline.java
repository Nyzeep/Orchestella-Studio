package io.github.nyzeep.orchestella;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encapsulates the provisioning artifact generation pipeline (Ticket 01).
 * Binds package manager mirror configuration files and firstboot automation scripts
 * into a single unified payload for injection into Guest rootfs.
 */
public final class FirstBootProvisionPipeline {

    public static final class ProvisionPayload {
        private final String mirrorConfigFileName;
        private final byte[] mirrorConfigFileContent;
        private final byte[] firstBootScript;

        public ProvisionPayload(
                String mirrorConfigFileName,
                byte[] mirrorConfigFileContent,
                byte[] firstBootScript) {
            this.mirrorConfigFileName = mirrorConfigFileName;
            this.mirrorConfigFileContent = mirrorConfigFileContent;
            this.firstBootScript = firstBootScript;
        }

        public String mirrorConfigFileName() {
            return mirrorConfigFileName;
        }

        public byte[] mirrorConfigFileContent() {
            return mirrorConfigFileContent;
        }

        public byte[] firstBootScript() {
            return firstBootScript;
        }

        public boolean hasMirrorConfig() {
            return mirrorConfigFileName != null && mirrorConfigFileContent != null;
        }

        public boolean hasFirstBootScript() {
            return firstBootScript != null && firstBootScript.length > 0;
        }
    }

    public static ProvisionPayload create(
            String distroId,
            DistroMirrorProvisioner.MirrorSource mirrorSource,
            boolean autoUpdate) {
        String fileName = DistroMirrorProvisioner.getConfigFileName(distroId);
        String mirrorText = DistroMirrorProvisioner.generateConfig(distroId, mirrorSource);
        byte[] mirrorBytes = mirrorText != null ? mirrorText.getBytes(StandardCharsets.UTF_8) : null;

        byte[] scriptBytes = null;
        if (autoUpdate) {
            String script = generateScriptForDistro(distroId);
            if (script != null) {
                scriptBytes = script.getBytes(StandardCharsets.UTF_8);
            }
        }

        return new ProvisionPayload(fileName, mirrorBytes, scriptBytes);
    }

    public static String generateScriptForDistro(String distroId) {
        HostHardwareProfile dummyProfile = new HostHardwareProfile(
                2, 2L * 1024L * 1024L * 1024L, 1024L * 1024L * 1024L, 512L * 1024L * 1024L, false);
        InstanceCreationWizardModel model = new InstanceCreationWizardModel(
                distroId, "latest", dummyProfile, InstanceCreationWizardModel.CreationStep.STEP_STORAGE);
        return model.generateFirstBootScript(distroId);
    }
}
