package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GuestAssetsToolTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exposesConfigVerificationAsANonZeroCliResult() throws Exception {
        Path config = temporaryDirectory.resolve(".config");
        Files.writeString(config, "# CONFIG_VSOCKETS is not set\n");
        Path requirements = temporaryDirectory.resolve("required.config");
        Files.writeString(requirements, "CONFIG_VSOCKETS=y\n");
        StringWriter standardOutput = new StringWriter();
        StringWriter standardError = new StringWriter();

        int exitCode =
                GuestAssetsTool.run(
                        new String[] {
                            "verify-config", config.toString(), requirements.toString()
                        },
                        new PrintWriter(standardOutput, true),
                        new PrintWriter(standardError, true));

        assertEquals(1, exitCode);
        assertEquals("", standardOutput.toString());
        assertEquals(
                "CONFIG_VSOCKETS: expected y but was n\n",
                standardError.toString().replace("\r\n", "\n"));
    }

    @Test
    void exposesStableManifestGenerationThroughTheCli() throws Exception {
        Path baseDirectory = temporaryDirectory.resolve("artifacts");
        Files.createDirectories(baseDirectory);
        Path image = baseDirectory.resolve("Image");
        Files.writeString(image, "abc");
        Path manifest = temporaryDirectory.resolve("SHA256SUMS");
        StringWriter standardOutput = new StringWriter();
        StringWriter standardError = new StringWriter();

        int exitCode =
                GuestAssetsTool.run(
                        new String[] {
                            "generate-manifest",
                            baseDirectory.toString(),
                            manifest.toString(),
                            image.toString()
                        },
                        new PrintWriter(standardOutput, true),
                        new PrintWriter(standardError, true));

        assertEquals(0, exitCode);
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  Image\n",
                Files.readString(manifest).replace("\r\n", "\n"));
        assertEquals("", standardOutput.toString());
        assertEquals("", standardError.toString());
    }
}
