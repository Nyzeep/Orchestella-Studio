package io.github.nyzeep.orchestella.guestassets;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class GuestAssetsTool {
    private static final int USAGE_ERROR = 64;

    private GuestAssetsTool() {}

    public static void main(String[] arguments) {
        int exitCode =
                run(
                        arguments,
                        new PrintWriter(System.out, true),
                        new PrintWriter(System.err, true));
        System.exit(exitCode);
    }

    public static int run(
            String[] arguments, PrintWriter standardOutput, PrintWriter standardError) {
        try {
            if (arguments.length == 3 && arguments[0].equals("verify-config")) {
                return ConfigVerifier.run(
                        Path.of(arguments[1]), Path.of(arguments[2]), standardError);
            }
            if (arguments.length == 2 && arguments[0].equals("verify-desktop-configs")) {
                return LabwcConfigVerifier.verifyAll(Path.of(arguments[1]), standardError);
            }
            if (arguments.length >= 4 && arguments[0].equals("generate-manifest")) {
                List<Path> artifacts =
                        Arrays.stream(arguments, 3, arguments.length).map(Path::of).toList();
                Sha256Manifest.generate(Path.of(arguments[1]), artifacts, Path.of(arguments[2]));
                return 0;
            }
            standardError.println(
                    "usage: guest-assets-tools verify-config <.config> <requirements>");
            standardError.println(
                    "   or: guest-assets-tools verify-desktop-configs <desktop-dir>");
            standardError.println(
                    "   or: guest-assets-tools generate-manifest <base> <manifest> <artifact>...");
            return USAGE_ERROR;
        } catch (IOException | IllegalArgumentException failure) {
            standardError.println("error: " + failure.getMessage());
            return 2;
        }
    }
}
