package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Ticket 04: the activity must not keep a private capability matrix that
 * nothing calls. The only source is the live session (or the launch result).
 */
public final class CapabilitySourceOfTruthTest {

    @Test
    public void mainActivityHasNoSecondCapabilityMatrix() throws IOException {
        Path source = mainSource("MainActivity.kt");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse("MainActivity must not define an unused currentCapabilityMatrix()",
                text.contains("currentCapabilityMatrix"));
        assertFalse("MainActivity must not fabricate a graphics matrix of its own",
                text.contains("softwareVirtualizationWithGraphics")
                        || text.contains("hardwareVirtualizationWithGraphics"));
    }

    @Test
    public void desktopHudReadsTheSessionMatrix() throws IOException {
        Path source = mainSource("DesktopActivity.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertTrue("the HUD must read the live session matrix",
                text.contains("session.capabilityMatrix()"));
    }

    private static Path mainSource(String fileName) {
        Path moduleRelative = Paths.get("src", "main", "java", "io", "github", "nyzeep",
                "orchestella", fileName);
        if (Files.exists(moduleRelative)) {
            return moduleRelative;
        }
        return Paths.get("app", "src", "main", "java", "io", "github", "nyzeep",
                "orchestella", fileName);
    }
}
