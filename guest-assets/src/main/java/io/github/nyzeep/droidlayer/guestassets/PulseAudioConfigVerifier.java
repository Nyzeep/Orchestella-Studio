package io.github.nyzeep.droidlayer.guestassets;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Verifier for headless PulseAudio configuration (default.pa).
 * Ensures null-sink and module-simple-protocol-tcp parameters conform to ADR-0018.
 */
public final class PulseAudioConfigVerifier {

    private PulseAudioConfigVerifier() {}

    public static int verify(Path defaultPaPath) {
        return verify(defaultPaPath, new PrintWriter(System.err, true));
    }

    public static int verify(Path defaultPaPath, PrintWriter errorOutput) {
        if (defaultPaPath == null || !Files.exists(defaultPaPath)) {
            errorOutput.println("PulseAudio configuration file does not exist: " + defaultPaPath);
            return 1;
        }

        try {
            byte[] bytes = Files.readAllBytes(defaultPaPath);
            String content = new String(bytes, StandardCharsets.UTF_8);

            if (content.contains("\r")) {
                errorOutput.println("default.pa must use LF line endings only");
                return 1;
            }

            // Required parameters
            if (!content.contains("load-module module-null-sink") || !content.contains("sink_name=droidlayer_sink")) {
                errorOutput.println("default.pa missing module-null-sink or sink_name=droidlayer_sink");
                return 1;
            }
            if (!content.contains("set-default-sink droidlayer_sink")) {
                errorOutput.println("default.pa missing set-default-sink droidlayer_sink");
                return 1;
            }
            if (!content.contains("load-module module-simple-protocol-tcp")) {
                errorOutput.println("default.pa missing module-simple-protocol-tcp");
                return 1;
            }
            if (!content.contains("rate=48000")) {
                errorOutput.println("default.pa missing rate=48000");
                return 1;
            }
            if (!content.contains("format=s16le")) {
                errorOutput.println("default.pa missing format=s16le");
                return 1;
            }
            if (!content.contains("channels=2")) {
                errorOutput.println("default.pa missing channels=2");
                return 1;
            }
            if (!content.contains("source=droidlayer_sink.monitor")) {
                errorOutput.println("default.pa missing source=droidlayer_sink.monitor");
                return 1;
            }
            if (!content.contains("port=9104")) {
                errorOutput.println("default.pa missing port=9104");
                return 1;
            }
            if (!content.contains("listen=127.0.0.1")) {
                errorOutput.println("default.pa missing listen=127.0.0.1");
                return 1;
            }

            // Heavyweight forbidden modules
            List<String> forbidden = List.of(
                    "module-udev-detect",
                    "module-dbus-protocol",
                    "module-native-protocol-tcp",
                    "module-bluetooth-discover"
            );
            for (String mod : forbidden) {
                if (content.contains("load-module " + mod)) {
                    errorOutput.println("default.pa contains forbidden heavyweight module: " + mod);
                    return 1;
                }
            }

            return 0;
        } catch (Exception ex) {
            errorOutput.println("Error verifying PulseAudio configuration: " + ex.getMessage());
            return 1;
        }
    }
}
