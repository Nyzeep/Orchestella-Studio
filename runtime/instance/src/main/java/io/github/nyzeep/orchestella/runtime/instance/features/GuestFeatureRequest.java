package io.github.nyzeep.orchestella.runtime.instance.features;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds the whitelist extension only; authentication/transport belong to BridgeClient. */
public record GuestFeatureRequest(String action, String profile, List<String> arguments) {
    private static final Set<String> PROFILES = Set.of("desktop-labwc", "podman", "code-server", "docker-cli");
    private static final Set<String> COMMANDS = Set.of("PROBE", "INSTALL", "STATUS", "START", "STOP", "CANCEL", "EXPOSE", "UNEXPOSE");

    public GuestFeatureRequest {
        if (action == null || profile == null || arguments == null || !PROFILES.contains(profile)) {
            throw new IllegalArgumentException("unknown-profile");
        }
        arguments = List.copyOf(arguments);
        if (COMMANDS.contains(action)) {
            if (!arguments.isEmpty() || ((action.equals("EXPOSE") || action.equals("UNEXPOSE")) && !profile.equals("code-server"))) {
                throw new IllegalArgumentException("unsupported-action");
            }
        } else if (action.equals("CONFIRM-FRAME")) {
            if (!profile.equals("desktop-labwc") || arguments.size() != 1 || !arguments.get(0).matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("stale-frame");
            }
        } else if (action.equals("RESIZE")) {
            if (!profile.equals("desktop-labwc") || arguments.size() != 3) {
                throw new IllegalArgumentException("invalid-display-size");
            }
            try {
                validateSize(Integer.parseInt(arguments.get(0)), Integer.parseInt(arguments.get(1)), Double.parseDouble(arguments.get(2)));
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("invalid-display-size", failure);
            }
        } else {
            throw new IllegalArgumentException("unknown-action");
        }
    }

    public static GuestFeatureRequest command(String action, String profile) {
        return new GuestFeatureRequest(action, profile, List.of());
    }

    public static GuestFeatureRequest confirmFrame(GuestFeatureStatus status) {
        if (!status.profile().equals("desktop-labwc") || !status.state().equals("awaiting-frame") || !status.captureVerified()) {
            throw new IllegalArgumentException("capture-unverified");
        }
        return new GuestFeatureRequest("CONFIRM-FRAME", status.profile(), List.of(status.generation()));
    }

    public static GuestFeatureRequest resize(int width, int height, double scale) {
        validateSize(width, height, scale);
        return new GuestFeatureRequest("RESIZE", "desktop-labwc", List.of(Integer.toString(width), Integer.toString(height), Double.toString(scale)));
    }

    private static void validateSize(int width, int height, double scale) {
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 4_194_304 || !Double.isFinite(scale) || scale < 0.1 || scale > 5) {
            throw new IllegalArgumentException("invalid-display-size");
        }
    }

    public String wireLine() {
        return "ORCHESTELLA/FEATURES 1 " + action + " " + profile + (arguments.isEmpty() ? "" : " " + String.join(" ", arguments));
    }

    public List<String> cliArguments() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        result.add(action.toLowerCase(Locale.ROOT));
        result.add(profile);
        result.addAll(arguments);
        return List.copyOf(result);
    }
}
