package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AX-08: the accelerator image's own manifest.
 *
 * <p>A parseable manifest is the only evidence that the accelerator image is
 * installed, and it is the only source of the image's compiler identity. A
 * missing, unreadable or malformed manifest never counts as "present": the
 * caller gets {@link Optional#empty()} and the policy refuses the lane.
 *
 * <p>The on-disk format follows the shipped {@code QEMU-BUILD-INPUTS} record:
 * {@code key=value} lines, {@code #} comments, blank lines ignored. Unknown
 * keys are ignored so a newer image can add fields, but a duplicate key or a
 * missing required field is a manifest failure, not a warning.
 *
 * <p>The manifest carries no path, token or device identifier; reading the
 * file stays in the platform adapter and never enters these facts.
 */
public final class AcceleratorImageManifest {
    /** The only accepted schema tag. */
    public static final String SCHEMA = "accelerator-image-1";

    /** Manifest key names; they are part of the on-disk contract. */
    public static final String KEY_SCHEMA = "schema";
    public static final String KEY_COMPILER_TRIPLE = "compiler_triple";

    private final String compilerTriple;

    private AcceleratorImageManifest(String compilerTriple) {
        this.compilerTriple = compilerTriple;
    }

    /** The accelerator image's compiler identity, never the host compiler. */
    public String compilerTriple() {
        return compilerTriple;
    }

    public static AcceleratorImageManifest of(String compilerTriple) {
        Objects.requireNonNull(compilerTriple, "compilerTriple");
        if (compilerTriple.isBlank()) {
            throw new IllegalArgumentException("compilerTriple must not be blank");
        }
        return new AcceleratorImageManifest(compilerTriple.trim());
    }

    /**
     * Parses a manifest body. Any defect — null, empty, wrong schema, missing
     * or blank compiler triple, duplicated key — yields {@link Optional#empty()}.
     */
    public static Optional<AcceleratorImageManifest> parse(String text) {
        Map<String, String> fields = readFields(text);
        if (fields == null) {
            return Optional.empty();
        }
        if (!SCHEMA.equals(fields.get(KEY_SCHEMA))) {
            return Optional.empty();
        }
        String triple = fields.get(KEY_COMPILER_TRIPLE);
        if (triple == null || triple.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AcceleratorImageManifest(triple.trim()));
    }

    /** Returns null when a line is malformed or a key repeats. */
    private static Map<String, String> readFields(String text) {
        if (text == null) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                return null;
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (key.isEmpty() || fields.putIfAbsent(key, value) != null) {
                return null;
            }
        }
        return fields;
    }
}
