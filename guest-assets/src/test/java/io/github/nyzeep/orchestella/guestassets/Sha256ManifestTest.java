package io.github.nyzeep.orchestella.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Sha256ManifestTest {
    @TempDir Path temporaryDirectory;

    @Test
    void generatesAStableManifestWithKnownDigestsAndSortedPaths() throws Exception {
        Path baseDirectory = temporaryDirectory.resolve("artifacts");
        Files.createDirectories(baseDirectory);
        Path initramfs = baseDirectory.resolve("initramfs.cpio.gz");
        Files.write(initramfs, new byte[0]);
        Path image = baseDirectory.resolve("Image");
        Files.writeString(image, "abc");
        Path manifest = temporaryDirectory.resolve("SHA256SUMS");

        Sha256Manifest.generate(baseDirectory, List.of(initramfs, image), manifest);

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  Image\n"
                        + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  initramfs.cpio.gz\n",
                Files.readString(manifest).replace("\r\n", "\n"));
    }

    @Test
    void rejectsAnArtifactChangedAfterManifestGeneration() throws Exception {
        Path baseDirectory = temporaryDirectory.resolve("artifacts");
        Files.createDirectories(baseDirectory);
        Path image = baseDirectory.resolve("Image");
        Files.writeString(image, "trusted");
        Path manifest = temporaryDirectory.resolve("SHA256SUMS");
        Sha256Manifest.generate(baseDirectory, List.of(image), manifest);
        Files.writeString(image, "tampered");
        StringWriter diagnostics = new StringWriter();

        int exitCode =
                Sha256Manifest.verify(
                        baseDirectory, manifest, new PrintWriter(diagnostics, true));

        assertEquals(1, exitCode);
        assertEquals("Image: checksum mismatch\n", diagnostics.toString().replace("\r\n", "\n"));
    }

    @Test
    void rejectsAnArtifactMissingAfterManifestGeneration() throws Exception {
        Path baseDirectory = temporaryDirectory.resolve("artifacts");
        Files.createDirectories(baseDirectory);
        Path image = baseDirectory.resolve("Image");
        Files.writeString(image, "trusted");
        Path manifest = temporaryDirectory.resolve("SHA256SUMS");
        Sha256Manifest.generate(baseDirectory, List.of(image), manifest);
        Files.delete(image);
        StringWriter diagnostics = new StringWriter();

        int exitCode =
                Sha256Manifest.verify(
                        baseDirectory, manifest, new PrintWriter(diagnostics, true));

        assertEquals(1, exitCode);
        assertEquals("Image: missing\n", diagnostics.toString().replace("\r\n", "\n"));
    }
}
