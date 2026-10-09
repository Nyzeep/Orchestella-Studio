package io.github.nyzeep.orchestella.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigVerifierTest {
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsAConfigThatSatisfiesEveryRequirement() throws Exception {
        Path config = temporaryDirectory.resolve(".config");
        Files.writeString(config, "CONFIG_VIRTIO=y\nCONFIG_EXT4_FS=y\n");
        Path requirements = temporaryDirectory.resolve("required.config");
        Files.writeString(requirements, "CONFIG_EXT4_FS=y\nCONFIG_VIRTIO=y\n");
        StringWriter diagnostics = new StringWriter();

        int exitCode = ConfigVerifier.run(config, requirements, new PrintWriter(diagnostics, true));

        assertEquals(0, exitCode);
        assertTrue(diagnostics.toString().isEmpty());
    }

    @Test
    void rejectsAConfigWithEveryDisabledOrMissingRequirementListed() throws Exception {
        Path config = temporaryDirectory.resolve(".config");
        Files.writeString(config, "# CONFIG_VSOCKETS is not set\nCONFIG_VIRTIO=y\n");
        Path requirements = temporaryDirectory.resolve("required.config");
        Files.writeString(requirements, "CONFIG_VSOCKETS=y\nCONFIG_EXT4_FS=y\n");
        StringWriter diagnostics = new StringWriter();

        int exitCode = ConfigVerifier.run(config, requirements, new PrintWriter(diagnostics, true));

        assertEquals(1, exitCode);
        assertEquals(
                "CONFIG_VSOCKETS: expected y but was n\n"
                        + "CONFIG_EXT4_FS: expected y but was missing\n",
                diagnostics.toString().replace("\r\n", "\n"));
    }

    @Test
    void rejectsARequirementFileWithNoEffectiveEntries() throws Exception {
        Path config = temporaryDirectory.resolve(".config");
        Files.writeString(config, "CONFIG_VIRTIO=y\n");
        Path requirements = temporaryDirectory.resolve("required.config");
        Files.writeString(requirements, "# no requirements\n\n");

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ConfigVerifier.run(
                                config, requirements, new PrintWriter(new StringWriter(), true)));
    }

    @Test
    void rejectsDuplicateRequirementSymbols() throws Exception {
        Path config = temporaryDirectory.resolve(".config");
        Files.writeString(config, "CONFIG_VIRTIO=y\n");
        Path requirements = temporaryDirectory.resolve("required.config");
        Files.writeString(requirements, "CONFIG_VIRTIO=y\nCONFIG_VIRTIO=y\n");

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ConfigVerifier.run(
                                        config,
                                        requirements,
                                        new PrintWriter(new StringWriter(), true)));

        assertTrue(failure.getMessage().contains("CONFIG_VIRTIO"));
    }

    @Test
    void rejectsMalformedRequirementLinesInsteadOfIgnoringThem() throws Exception {
        Path config = temporaryDirectory.resolve(".config");
        Files.writeString(config, "CONFIG_VIRTIO=y\n");
        Path requirements = temporaryDirectory.resolve("required.config");
        Files.writeString(requirements, "CONFIG_VIRTIO=y\nCONFIG_EXT4_FS=\n");

        IllegalArgumentException failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ConfigVerifier.run(
                                        config,
                                        requirements,
                                        new PrintWriter(new StringWriter(), true)));

        assertTrue(failure.getMessage().contains("CONFIG_EXT4_FS="));
    }
}
