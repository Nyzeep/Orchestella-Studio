package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

/**
 * Ticket 06: one neutral RFB display adapter serves QEMU and AVF. The AVF
 * launcher must not depend on QEMU engine types beyond the shared asset
 * manifest, and the never-wired Wayland/audio channels must not exist.
 */
public final class AvfDisplayWiringTest {

    @Test
    public void bothLaunchersUseTheNeutralRfbAdapter() throws IOException {
        String avf = read("AvfInstanceLauncher.java");
        String qemu = read("QemuInstanceLauncher.java");
        assertTrue("AVF must construct the neutral RFB adapter", avf.contains("RfbDisplaySession"));
        assertTrue("QEMU must construct the neutral RFB adapter", qemu.contains("RfbDisplaySession"));
        assertFalse("the QEMU-named channel must be gone", avf.contains("QemuWaylandChannel"));
        assertFalse(qemu.contains("QemuWaylandChannel"));
    }

    @Test
    public void avfLauncherDoesNotImportQemuEngineTypesBesidesTheAssetManifest() throws IOException {
        List<String> lines = List.of(read("AvfInstanceLauncher.java").split("\n"));
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ") || !trimmed.contains("runtime.engineqemu.")) {
                continue;
            }
            assertTrue("only RuntimeArtifactManifest is a permitted engine-qemu import: " + trimmed,
                    trimmed.equals("import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;"));
        }
    }

    @Test
    public void deadWaylandAndAudioChannelsDoNotExist() {
        assertFalse("AvfWaylandChannel is a dead path and must be removed",
                Files.exists(mainSource("AvfWaylandChannel.java")));
        assertFalse("AvfGraphicsVsockProtocol is a dead path and must be removed",
                Files.exists(mainSource("AvfGraphicsVsockProtocol.java")));
        assertFalse("AvfAudioChannel is a dead path and must be removed",
                Files.exists(mainSource("AvfAudioChannel.java")));
        assertFalse("AvfAudioVsockProtocol is a dead path and must be removed",
                Files.exists(mainSource("AvfAudioVsockProtocol.java")));
    }

    @Test
    public void engineNoLongerExposesUnwiredWaylandAndAudioFactories() throws IOException {
        String engine = read("AvfVirtualMachineEngine.java");
        assertFalse("no Wayland factory may survive", engine.contains("AvfWaylandChannel"));
        assertFalse("no audio factory may survive", engine.contains("AvfAudioChannel"));
        assertFalse("no audio port accessor may survive", engine.contains("audioVsockPort"));
    }

    @Test
    public void avfLauncherTakesTheGraphicsPortFromTheSpec() throws IOException {
        String avf = read("AvfInstanceLauncher.java");
        assertFalse("the display port must not be hardcoded", avf.contains("openChannel(9103)"));
        assertTrue("the display uses the spec-bound graphics channel",
                avf.contains("graphicsChannel()"));
    }

    private static String read(String fileName) throws IOException {
        return new String(Files.readAllBytes(mainSource(fileName)), StandardCharsets.UTF_8);
    }

    private static Path mainSource(String fileName) {
        Path appRelative = Paths.get("src", "main", "java", "io", "github", "nyzeep",
                "orchestella", fileName);
        if (Files.exists(appRelative)) {
            return appRelative;
        }
        Path engineRelative = Paths.get("..", "runtime", "engine-avf", "src", "main", "java",
                "io", "github", "nyzeep", "orchestella", "runtime", "engineavf", fileName);
        if (Files.exists(engineRelative)) {
            return engineRelative;
        }
        return Paths.get("runtime", "engine-avf", "src", "main", "java", "io", "github",
                "nyzeep", "orchestella", "runtime", "engineavf", fileName);
    }
}
