package io.github.nyzeep.orchestella;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * C lane (UML acceleration round): the UML kernel assets' placement and
 * reconciliation layer — the runtime counterpart of the QEMU payload
 * reconciliation ({@code verifyRuntimeAssets} in {@code app/build.gradle.kts}:
 * manifest + per-entry SHA-256 + honest missing/empty/mismatch categories),
 * for the assets the build cannot check. The UML kernel binaries are
 * git-ignored build products placed on the device by the device window's
 * prepare flow (the prototype's flow, {@code research/uml-runner}); a build
 * machine sees them only through {@code -Porchestella.assets.allowMissingPayload=true},
 * so the honest verification has to happen at runtime, where the face is
 * about to start. This class only judges existence and checksums — it never
 * fabricates, downloads or repairs an asset.
 *
 * <p><b>Placement (the one place that names the paths).</b> The two binary
 * assets unpack from {@code jniLibs} into the app's {@code nativeLibraryDir}
 * ({@link UmlFaceLauncher#KERNEL_LIB_NAME}, {@link UmlFaceLauncher#STUB_LIB_NAME});
 * the guest rootfs is the hostfs channel directory under {@code filesDir}
 * ({@link UmlFaceLauncher#ROOTFS_DIR_NAME}); the checksum manifest is
 * {@link #MANIFEST_FILE_NAME} in {@code filesDir}, written by the same prepare
 * flow that places the assets, in the build-side payload manifest's line
 * format ({@code <64 lowercase hex>  <name>} per line; blank and
 * non-conforming lines are ignored — the same parser behaviour as the
 * build-side check).
 *
 * <p><b>Honest statuses, not guesses.</b> {@link #verify} answers one entry
 * per asset with a fixed {@link Status}: {@code MISSING} / {@code EMPTY} /
 * {@code NOT_EXECUTABLE} / {@code DIGEST_MISMATCH} / {@code OK} (a pinned
 * digest matched) / {@code OK_EXISTENCE_ONLY} (no manifest pin — exactly the
 * launcher's historical precheck) / {@code OK_DIR} (the rootfs is a directory;
 * directory contents are not hashed — the report says so instead of implying
 * a verification that did not happen). An absent manifest is a status, not an
 * error: without one the assets verify existence-only, which is today's
 * shipped behaviour unchanged.
 *
 * <p><b>Two layers, both honest.</b> The launcher's start gate keeps its
 * historical precheck ({@link #firstMissingName}: binaries must be an
 * executable file, the rootfs a directory) — its {@code MISSING_ASSET} fixed
 * code and detail contract is locked by tests and is the degradation the face
 * answers with. This class adds the richer reconciliation report on top: the
 * wiring owner surfaces {@link Inventory#describe()} (per-asset statuses plus
 * the obtain-the-assets remediation, in the build-side check's message style)
 * wherever it needs the honest why, not just the which.
 */
public final class UmlKernelAssets {

    /**
     * The checksum manifest the prepare flow writes into {@code filesDir} —
     * the same {@code <64 lowercase hex>  <name>} line format as the build
     * side's {@code runtime-artifacts-v1.sha256}.
     */
    public static final String MANIFEST_FILE_NAME = "uml-kernel-assets-v1.sha256";

    /** Fixed per-asset reconciliation statuses. */
    public enum Status {
        /** Exists, non-empty, executable, and a pinned digest matched. */
        OK,
        /**
         * Exists and executable but not described by a manifest pin — the
         * launcher's historical precheck level, reported honestly as
         * "existence verified only", never as a checksum verification.
         */
        OK_EXISTENCE_ONLY,
        /** The rootfs directory exists; directories are not hashed. */
        OK_DIR,
        /** Not there at all. */
        MISSING,
        /** A zero-length file where a kernel image belongs. */
        EMPTY,
        /** A regular non-empty file without the executable bit. */
        NOT_EXECUTABLE,
        /** The file's SHA-256 does not match the manifest pin. */
        DIGEST_MISMATCH
    }

    /** One asset's reconciliation answer: fixed status plus the digests when computed. */
    public record Entry(String name, File path, Status status, String expectedSha256,
            String actualSha256) {

        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(status, "status");
        }

        public boolean problem() {
            return status != Status.OK && status != Status.OK_EXISTENCE_ONLY
                    && status != Status.OK_DIR;
        }
    }

    /** What the manifest read saw: present or not, and how many pins it carries. */
    public record ManifestReport(File file, boolean present, int pinnedEntries) {
        public ManifestReport {
            Objects.requireNonNull(file, "file");
        }
    }

    /** One {@link #verify} answer: the manifest report plus one entry per asset. */
    public record Inventory(ManifestReport manifest, List<Entry> entries) {
        public Inventory {
            Objects.requireNonNull(manifest, "manifest");
            entries = List.copyOf(entries);
        }

        /** True when every entry verified at the launcher-gate level or better. */
        public boolean usable() {
            return entries.stream().noneMatch(Entry::problem);
        }

        /** The first problem entry, or null when every asset verified. */
        public Entry firstProblem() {
            return entries.stream().filter(Entry::problem).findFirst().orElse(null);
        }

        /**
         * The honest multi-line report, in the build-side payload check's
         * message style: what did not reconcile, then how the assets are
         * obtained. Empty when {@link #usable()}.
         */
        public List<String> describe() {
            List<String> lines = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            List<String> empty = new ArrayList<>();
            List<String> notExecutable = new ArrayList<>();
            List<String> mismatched = new ArrayList<>();
            for (Entry entry : entries) {
                switch (entry.status()) {
                    case MISSING -> missing.add(entry.name());
                    case EMPTY -> empty.add(entry.name());
                    case NOT_EXECUTABLE -> notExecutable.add(entry.name());
                    case DIGEST_MISMATCH -> mismatched.add(entry.name() + " expected="
                            + entry.expectedSha256() + " actual=" + entry.actualSha256());
                    default -> {
                        // The OK tiers and the report's problem sections do not mix.
                    }
                }
            }
            if (missing.isEmpty() && empty.isEmpty() && notExecutable.isEmpty()
                    && mismatched.isEmpty()) {
                return lines;
            }
            lines.add("The UML kernel assets do not reconcile with " + MANIFEST_FILE_NAME
                    + " (the device window's prepare-flow manifest).");
            if (!manifest.present()) {
                lines.add("  - manifest absent: " + manifest.file()
                        + " (verification was existence-only)");
            }
            if (!missing.isEmpty()) {
                lines.add("  - missing files: " + String.join(", ", missing));
            }
            if (!empty.isEmpty()) {
                lines.add("  - empty files: " + String.join(", ", empty));
            }
            if (!notExecutable.isEmpty()) {
                lines.add("  - not executable: " + String.join(", ", notExecutable));
            }
            mismatched.forEach(line -> lines.add("  - sha256 mismatch: " + line));
            lines.add("How to obtain the assets: they are git-ignored build products; place them"
                    + " with the device window's prepare flow (research/uml-runner), then write "
                    + MANIFEST_FILE_NAME + " (sha256sum) next to the rootfs and retry.");
            return lines;
        }
    }

    private UmlKernelAssets() {
    }

    /**
     * The launcher-gate parity check: the first asset that fails the
     * historical precheck, in the fixed priority kernel → stub → rootfs,
     * named exactly as the launcher's {@code MISSING_ASSET} detail names it.
     * Binaries must be an executable regular file; the rootfs must be a
     * directory. Null when everything the gate needs is in place.
     */
    public static String firstMissingName(UmlFaceLauncher.Environment environment) {
        Objects.requireNonNull(environment, "environment");
        File libDir = environment.nativeLibraryDir();
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        if (!kernel.isFile() || !kernel.canExecute()) {
            return UmlFaceLauncher.KERNEL_LIB_NAME;
        }
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        if (!stub.isFile() || !stub.canExecute()) {
            return UmlFaceLauncher.STUB_LIB_NAME;
        }
        File rootfs = new File(environment.filesDir(), UmlFaceLauncher.ROOTFS_DIR_NAME);
        if (!rootfs.isDirectory()) {
            return UmlFaceLauncher.ROOTFS_DIR_NAME;
        }
        return null;
    }

    /**
     * The production reconciliation: reads the manifest from
     * {@code filesDir/}{@link #MANIFEST_FILE_NAME} and verifies the placed
     * assets against it.
     */
    public static Inventory verify(UmlFaceLauncher.Environment environment) throws IOException {
        return verify(environment, readManifest(
                new File(environment.filesDir(), MANIFEST_FILE_NAME)));
    }

    /**
     * Verifies the placed assets against the given digest pins (asset name →
     * expected SHA-256 hex). Every asset is reported even when it has no pin —
     * an unpinned asset answers {@link Status#OK_EXISTENCE_ONLY}, honestly
     * below {@link Status#OK}. This never throws for a bad asset: problems are
     * statuses. Only an unreadable file (a permission error mid-hash) throws.
     */
    public static Inventory verify(
            UmlFaceLauncher.Environment environment, Map<String, String> pinnedDigests)
            throws IOException {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(pinnedDigests, "pinnedDigests");
        List<Entry> entries = new ArrayList<>(3);
        File libDir = environment.nativeLibraryDir();
        entries.add(binaryEntry(UmlFaceLauncher.KERNEL_LIB_NAME,
                new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME), pinnedDigests));
        entries.add(binaryEntry(UmlFaceLauncher.STUB_LIB_NAME,
                new File(libDir, UmlFaceLauncher.STUB_LIB_NAME), pinnedDigests));
        File rootfs = new File(environment.filesDir(), UmlFaceLauncher.ROOTFS_DIR_NAME);
        entries.add(new Entry(UmlFaceLauncher.ROOTFS_DIR_NAME, rootfs,
                rootfs.isDirectory() ? Status.OK_DIR : Status.MISSING, null, null));
        return new Inventory(manifestReport(environment, pinnedDigests), entries);
    }

    /**
     * Parses the manifest file: {@code <64 lowercase hex>  <name>} per line,
     * blank and non-conforming lines ignored — the same parser behaviour as
     * the build-side payload check. A missing manifest is an empty pin set,
     * not an error.
     */
    public static Map<String, String> readManifest(File manifestFile) throws IOException {
        Objects.requireNonNull(manifestFile, "manifestFile");
        Map<String, String> digests = new LinkedHashMap<>();
        if (!manifestFile.isFile()) {
            return digests;
        }
        java.util.regex.Pattern entry = java.util.regex.Pattern.compile("^([0-9a-f]{64})  (.+)$");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new FileInputStream(manifestFile), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher match = entry.matcher(line.trim());
                if (match.matches()) {
                    digests.put(match.group(2), match.group(1));
                }
            }
        }
        return digests;
    }

    private static Entry binaryEntry(String name, File file, Map<String, String> pinnedDigests)
            throws IOException {
        if (!file.isFile()) {
            return new Entry(name, file, Status.MISSING, pinnedDigests.get(name), null);
        }
        if (file.length() == 0L) {
            return new Entry(name, file, Status.EMPTY, pinnedDigests.get(name), null);
        }
        if (!file.canExecute()) {
            return new Entry(name, file, Status.NOT_EXECUTABLE, pinnedDigests.get(name), null);
        }
        String pin = pinnedDigests.get(name);
        if (pin == null) {
            return new Entry(name, file, Status.OK_EXISTENCE_ONLY, null, null);
        }
        String actual = sha256Hex(file);
        boolean matches = actual.equalsIgnoreCase(pin);
        return new Entry(name, file, matches ? Status.OK : Status.DIGEST_MISMATCH, pin, actual);
    }

    private static ManifestReport manifestReport(
            UmlFaceLauncher.Environment environment, Map<String, String> pinnedDigests) {
        File file = new File(environment.filesDir(), MANIFEST_FILE_NAME);
        return new ManifestReport(file, file.isFile(), pinnedDigests.size());
    }

    /** The SHA-256 of a file as lowercase hex, streamed (kernel images are large). */
    public static String sha256Hex(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", unavailable);
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder(digest.getDigestLength() * 2);
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** The three required asset names, in the gate's fixed priority order. */
    public static List<String> requiredNames() {
        return Collections.unmodifiableList(List.of(
                UmlFaceLauncher.KERNEL_LIB_NAME,
                UmlFaceLauncher.STUB_LIB_NAME,
                UmlFaceLauncher.ROOTFS_DIR_NAME));
    }
}
