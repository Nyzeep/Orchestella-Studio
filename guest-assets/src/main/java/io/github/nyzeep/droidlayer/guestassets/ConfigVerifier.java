package io.github.nyzeep.droidlayer.guestassets;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigVerifier {
    private static final Pattern REQUIREMENT =
            Pattern.compile(
                    "^(CONFIG_[A-Z0-9_]+)=(y|m|n|\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|-?[0-9]+|0x[0-9A-Fa-f]+)$");

    private ConfigVerifier() {}

    public static int run(Path config, Path requirements, PrintWriter diagnostics) throws IOException {
        Map<String, String> configuredValues = readConfig(config);
        boolean matches = true;
        for (Map.Entry<String, String> requirement : readRequirements(requirements).entrySet()) {
            if (!requirement.getValue().equals(configuredValues.get(requirement.getKey()))) {
                diagnostics.printf(
                        "%s: expected %s but was %s%n",
                        requirement.getKey(),
                        requirement.getValue(),
                        configuredValues.getOrDefault(requirement.getKey(), "missing"));
                matches = false;
            }
        }
        return matches ? 0 : 1;
    }

    private static Map<String, String> readConfig(Path path) throws IOException {
        Map<String, String> assignments = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith("# CONFIG_") && line.endsWith(" is not set")) {
                assignments.put(line.substring(2, line.length() - " is not set".length()), "n");
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals > 0) {
                assignments.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return assignments;
    }

    private static Map<String, String> readRequirements(Path path) throws IOException {
        Map<String, String> requirements = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = REQUIREMENT.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Malformed requirement line in " + path + ": " + line);
            }
            String symbol = matcher.group(1);
            String previous = requirements.putIfAbsent(symbol, matcher.group(2));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate requirement symbol " + symbol + " in " + path);
            }
        }
        if (requirements.isEmpty()) {
            throw new IllegalArgumentException("Requirement file has no effective entries: " + path);
        }
        return requirements;
    }
}
