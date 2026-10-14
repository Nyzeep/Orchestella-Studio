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
        return create(distroId, "latest", mirrorSource, autoUpdate);
    }

    public static ProvisionPayload create(
            String distroId,
            String distroVersion,
            DistroMirrorProvisioner.MirrorSource mirrorSource,
            boolean autoUpdate) {
        Objects.requireNonNull(distroId, "distroId");
        Objects.requireNonNull(mirrorSource, "mirrorSource");
        String fileName = DistroMirrorProvisioner.getConfigFileName(distroId);
        String mirrorText = DistroMirrorProvisioner.generateMirrorConfig(distroId, distroVersion, mirrorSource);
        byte[] mirrorBytes = mirrorText.getBytes(StandardCharsets.UTF_8);
        return new ProvisionPayload(fileName, mirrorBytes,
                GuestRuntimeProvisioner.firstBootBytes(distroId, autoUpdate));
    }

    public static String generateScriptForDistro(String distroId) {
        return GuestRuntimeProvisioner.firstBootScript(distroId, false);
    }
}
