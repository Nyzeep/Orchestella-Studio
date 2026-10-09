package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed, non-self-referencing runtime artifact manifest for QEMU APK assets.
 *
 * <p>The manifest is expected to be packaged with the signed application. This class never downloads,
 * copies, extracts, or executes a runtime artifact; it only verifies the installed native-library file
 * before a caller may construct a fixed QEMU launch request.
 */
public final class RuntimeArtifactManifest {
    public static final String SCHEMA_FILE_NAME = "runtime-artifacts-v1.sha256";
    public static final String QEMU_ARTIFACT_PATH = "lib/arm64-v8a/libdroidlayer_qemu.so";

    private static final Set<String> REQUIRED_ARTIFACT_PATHS =
            Set.of(
                    "Image",
                    "initramfs.cpio.gz",
                    "QEMU-BUILD-INPUTS",
                    "QEMU-GPL-SOURCES.md",
                    "LICENSE.GPL-2.0",
                    "NOTICE.QEMU",
                    QEMU_ARTIFACT_PATH);

    private static final Pattern ENTRY = Pattern.compile("^([0-9a-f]{64}) {2}([^\\r\\n]+)$");

    private final Map<String, String> hashesByPath;

    private RuntimeArtifactManifest(Map<String, String> hashesByPath) {
        this.hashesByPath = Collections.unmodifiableMap(new LinkedHashMap<>(hashesByPath));
    }

    /** Parses the signed-app manifest with strict, non-self-referencing path validation. */
    public static RuntimeArtifactManifest parse(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        Map<String, String> entries = new LinkedHashMap<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    throw rejected("runtime-artifact-manifest-invalid");
                }
                Matcher matcher = ENTRY.matcher(line);
                if (!matcher.matches()) {
                    throw rejected("runtime-artifact-manifest-invalid");
                }
                String path = matcher.group(2);
                if (!isSafeRelativeArtifactPath(path)
                        || SCHEMA_FILE_NAME.equals(path)
                        || entries.put(path, matcher.group(1)) != null) {
                    throw rejected("runtime-artifact-manifest-invalid");
                }
            }
        }
        if (entries.isEmpty()) {
            throw rejected("runtime-artifact-manifest-invalid");
        }
        if (!entries.keySet().containsAll(REQUIRED_ARTIFACT_PATHS)) {
            throw rejected("runtime-artifact-manifest-incomplete");
        }
        return new RuntimeArtifactManifest(entries);
    }

    /**
     * Verifies the QEMU ELF at the one installer-extracted native-library location allowed by policy.
     *
     * @return canonical path to the verified QEMU ELF; callers must still use a fixed argument array.
     */
    public Path requireVerifiedInstalledQemu(Path nativeLibraryDirectory) throws IOException {
        Objects.requireNonNull(nativeLibraryDirectory, "nativeLibraryDirectory");
        String expectedHash = hashesByPath.get(QEMU_ARTIFACT_PATH);
        if (expectedHash == null) {
            throw rejected("runtime-artifact-manifest-invalid");
        }

        Path canonicalDirectory;
        try {
            canonicalDirectory = nativeLibraryDirectory.toRealPath();
        } catch (IOException unavailable) {
            throw rejected("runtime-artifact-native-library-unavailable", unavailable);
        }
        if (!Files.isDirectory(canonicalDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw rejected("runtime-artifact-native-library-unavailable");
        }

        Path candidate = canonicalDirectory.resolve("libdroidlayer_qemu.so").normalize();
        if (!candidate.startsWith(canonicalDirectory)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)) {
            throw rejected("runtime-artifact-native-library-unavailable");
        }
        Path canonicalCandidate = candidate.toRealPath();
        if (!canonicalCandidate.getParent().equals(canonicalDirectory)) {
            throw rejected("runtime-artifact-native-library-unavailable");
        }

        String actualHash = sha256(canonicalCandidate);
        if (!MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII))) {
            throw rejected("runtime-artifact-checksum-mismatch");
        }
        return canonicalCandidate;
    }

    /**
     * Verifies one caller-provided packaged asset before it is copied, extracted, or otherwise used.
     *
     * <p>The caller owns the input stream and its lifecycle. This method never writes an asset and
     * never treats an absent manifest entry as an optional input.
     */
    public void requireVerifiedPackagedAsset(String artifactPath, InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        if (!isSafeRelativeArtifactPath(artifactPath)) {
            throw rejected("runtime-artifact-manifest-invalid");
        }
        String expectedHash = hashesByPath.get(artifactPath);
        if (expectedHash == null) {
            throw rejected("runtime-artifact-manifest-invalid");
        }
        String actualHash = sha256(input);
        if (!MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII))) {
            throw rejected("runtime-artifact-checksum-mismatch");
        }
    }

    private static boolean isSafeRelativeArtifactPath(String value) {
        if (value.isEmpty() || value.startsWith("/") || value.contains("\\")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > 0) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static IntegrityRejectedException rejected(String reason) {
        return new IntegrityRejectedException(reason, null);
    }

    private static IntegrityRejectedException rejected(String reason, Throwable cause) {
        return new IntegrityRejectedException(reason, cause);
    }

    /** Stable, non-secret failure reason for an integrity gate. */
    public static final class IntegrityRejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        private final String reason;

        private IntegrityRejectedException(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
