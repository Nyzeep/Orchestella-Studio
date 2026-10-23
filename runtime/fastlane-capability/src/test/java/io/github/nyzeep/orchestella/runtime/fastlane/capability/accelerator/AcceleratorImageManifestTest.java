package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Optional;
import org.junit.Test;

/** AX-08: only a complete, well-formed manifest proves the image is installed. */
public final class AcceleratorImageManifestTest {
    private static final String VALID = String.join("\n",
            "# accelerator image record",
            "schema=accelerator-image-1",
            "compiler_triple=aarch64-linux-gnu",
            "");

    @Test
    public void parsesSchemaAndCompilerTriple() {
        Optional<AcceleratorImageManifest> parsed = AcceleratorImageManifest.parse(VALID);

        assertTrue(parsed.isPresent());
        assertEquals("aarch64-linux-gnu", parsed.get().compilerTriple());
    }

    @Test
    public void ignoresUnknownKeysForForwardCompatibility() {
        Optional<AcceleratorImageManifest> parsed = AcceleratorImageManifest.parse(
                "schema=accelerator-image-1\ncompiler_triple=aarch64-linux-gnu\nfuture_key=value\n");

        assertTrue(parsed.isPresent());
        assertEquals("aarch64-linux-gnu", parsed.get().compilerTriple());
    }

    @Test
    public void windowsLineEndingsAndSurroundingSpacesAreAccepted() {
        Optional<AcceleratorImageManifest> parsed = AcceleratorImageManifest.parse(
                "  schema = accelerator-image-1  \r\n compiler_triple = aarch64-linux-gnu \r\n");

        assertTrue(parsed.isPresent());
        assertEquals("aarch64-linux-gnu", parsed.get().compilerTriple());
    }

    @Test
    public void missingManifestIsNotPresent() {
        assertFalse(AcceleratorImageManifest.parse(null).isPresent());
        assertFalse(AcceleratorImageManifest.parse("").isPresent());
        assertFalse(AcceleratorImageManifest.parse("# only a comment\n").isPresent());
    }

    @Test
    public void wrongOrMissingSchemaIsRejected() {
        assertFalse(AcceleratorImageManifest.parse(
                "schema=accelerator-image-2\ncompiler_triple=aarch64-linux-gnu\n").isPresent());
        assertFalse(AcceleratorImageManifest.parse(
                "compiler_triple=aarch64-linux-gnu\n").isPresent());
    }

    @Test
    public void missingOrBlankCompilerTripleIsRejected() {
        assertFalse(AcceleratorImageManifest.parse(
                "schema=accelerator-image-1\n").isPresent());
        assertFalse(AcceleratorImageManifest.parse(
                "schema=accelerator-image-1\ncompiler_triple=   \n").isPresent());
    }

    @Test
    public void malformedOrDuplicatedLinesAreRejected() {
        assertFalse(AcceleratorImageManifest.parse(
                "schema=accelerator-image-1\ncompiler_triple\n").isPresent());
        assertFalse(AcceleratorImageManifest.parse(
                "schema=accelerator-image-1\ncompiler_triple=aarch64-linux-gnu\n"
                        + "compiler_triple=arm-linux-gnueabihf\n").isPresent());
        assertFalse(AcceleratorImageManifest.parse(
                "schema=accelerator-image-1\n=value\ncompiler_triple=aarch64-linux-gnu\n").isPresent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void ofRejectsBlankTriple() {
        AcceleratorImageManifest.of("  ");
    }
}
