package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PulseAudioConfigVerifierTest {

    private static Path getDesktopDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("scripts").resolve("desktop");
        }
        return Path.of("scripts").resolve("desktop");
    }

    private static Path getDefaultPaPath() {
        return getDesktopDir().resolve("pulseaudio").resolve("default.pa");
    }

    @Test
    void defaultPaConfigFileExistsAndHasLfOnly() throws IOException {
        Path defaultPa = getDefaultPaPath();
        assertTrue(Files.exists(defaultPa), "default.pa must exist at scripts/desktop/pulseaudio/default.pa");
        assertTrue(Files.size(defaultPa) > 0, "default.pa must not be empty");

        byte[] bytes = Files.readAllBytes(defaultPa);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "default.pa must use LF line endings only");
    }

    @Test
    void defaultPaConfiguresHeadlessNullSinkAndMonitor() throws IOException {
        Path defaultPa = getDefaultPaPath();
        String content = Files.readString(defaultPa, StandardCharsets.UTF_8);

        assertTrue(content.contains("load-module module-null-sink"),
                "default.pa must load module-null-sink");
        assertTrue(content.contains("sink_name=droidlayer_sink"),
                "default.pa must define sink_name=droidlayer_sink");
        assertTrue(content.contains("set-default-sink droidlayer_sink"),
                "default.pa must set droidlayer_sink as default sink");
    }

    @Test
    void defaultPaConfiguresSimpleProtocolTcpWithExactAudioParameters() throws IOException {
        Path defaultPa = getDefaultPaPath();
        String content = Files.readString(defaultPa, StandardCharsets.UTF_8);

        assertTrue(content.contains("load-module module-simple-protocol-tcp"),
                "default.pa must load module-simple-protocol-tcp");
        assertTrue(content.contains("rate=48000"),
                "default.pa must lock sample rate to 48000Hz");
        assertTrue(content.contains("format=s16le"),
                "default.pa must lock format to s16le (16-bit signed little-endian)");
        assertTrue(content.contains("channels=2"),
                "default.pa must lock channels to 2 (stereo)");
        assertTrue(content.contains("source=droidlayer_sink.monitor"),
                "default.pa must record from droidlayer_sink.monitor");
        assertTrue(content.contains("port=9104"),
                "default.pa must listen on port 9104");
        assertTrue(content.contains("listen=127.0.0.1"),
                "default.pa must bind listen to 127.0.0.1");
    }

    @Test
    void defaultPaDisablesHeavyweightModulesForMinimalFootprint() throws IOException {
        Path defaultPa = getDefaultPaPath();
        String content = Files.readString(defaultPa, StandardCharsets.UTF_8);

        // Heavy modules must not be loaded
        List<String> forbiddenModules = List.of(
                "module-udev-detect",
                "module-dbus-protocol",
                "module-native-protocol-tcp",
                "module-bluetooth-discover",
                "module-alsa-sink",
                "module-alsa-source",
                "module-oss",
                "module-zeroconf-discover"
        );

        for (String forbidden : forbiddenModules) {
            assertFalse(content.contains("load-module " + forbidden),
                    "default.pa must not load heavy module: " + forbidden);
        }
    }

    @Test
    void verifierClassValidatesConfigurationSuccessfully() {
        int exitCode = PulseAudioConfigVerifier.verify(getDefaultPaPath());
        assertEquals(0, exitCode, "PulseAudioConfigVerifier must validate default.pa successfully");
    }
}
