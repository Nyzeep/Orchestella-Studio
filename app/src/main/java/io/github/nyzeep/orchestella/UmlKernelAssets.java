package io.github.nyzeep.orchestella;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
 *
 * <p><b>UML payload productization round (fast-profile asset family).</b>
 * {@link FastProfile} registers the U1 fast-boot profile assets
 * ({@code linux-fast} / {@code linux-fast-noinitrd} / {@code stub_exe} /
 * {@code initramfs.cpio.gz} / the Alpine rootfs tar) into this same
 * reconciliation layer, with their research-pinned SHA-256 digests as the
 * single pin source. The two delivery routes under ACR vote both hang off this
 * registry and are both <b>default-off</b>: 方案 A (bundle the artifacts at
 * build time, {@link #verifyBundledFastProfile}) and 方案 B (download the
 * rootfs through the distro-catalog verification paradigm,
 * {@link RootfsDownload}) consume a build flag the wiring owner passes in —
 * off means zero file reads and zero behavior change, so the UML face keeps
 * its width-0 shape until the flag lands. The seven statuses above are the
 * whole problem vocabulary: a corrupt asset reads {@code EMPTY} /
 * {@code NOT_EXECUTABLE} / {@code DIGEST_MISMATCH}, a sha mismatch never
 * silently degrades to an existence-only pass.
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
     * The one manifest line shape — {@code <64 lowercase hex><two spaces><name>}
     * — shared by the runtime file parser and the {@link FastProfile#PIN_MANIFEST}
     * constant, so the research pins and a placed manifest file parse through
     * the exact same grammar.
     */
    private static final java.util.regex.Pattern MANIFEST_LINE =
            java.util.regex.Pattern.compile("^([0-9a-f]{64})  (.+)$");

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
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        new FileInputStream(manifestFile), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                java.util.regex.Matcher match = MANIFEST_LINE.matcher(line.trim());
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
        MessageDigest digest = sha256Digest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toLowerHex(digest.digest());
    }

    /** The three required asset names, in the gate's fixed priority order. */
    public static List<String> requiredNames() {
        return Collections.unmodifiableList(List.of(
                UmlFaceLauncher.KERNEL_LIB_NAME,
                UmlFaceLauncher.STUB_LIB_NAME,
                UmlFaceLauncher.ROOTFS_DIR_NAME));
    }

    /**
     * The U1 fast-profile asset family, registered into the same
     * reconciliation layer as the launcher's three runtime assets — the
     * product-asset single source of the payload productization round. The
     * artifact names and digests come verbatim from the research manifest
     * ({@code research/uml-fastboot/manifest.md} §3, ARM64 UP first build,
     * 2026-11-10); {@link #PIN_MANIFEST} spells them in the repo's
     * {@code <64-hex>␠␠<name>} manifest grammar so the build-side accounting
     * task parses the same constant this class serves at runtime.
     *
     * <p><b>Honest pin provenance.</b> The {@code linux-fast} digest is the
     * pinned-container build's pin. The wave2 device window proved the
     * built-in-initramfs kernel does <i>not</i> reproduce that digest across
     * build paths (difference isolated in the embedded initramfs segment; the
     * no-initrd kernel, stub and initramfs do reproduce byte-for-byte) — that
     * open item rides with the pin on purpose: an artifact that does not match
     * the pin fails closed here, and the ACR must either bless one build path
     * or publish the device-proven digest before a route switches on.
     *
     * <p>This class only registers and judges — it never fabricates, downloads
     * or repairs an asset, and the binaries themselves stay git-ignored build
     * products.
     */
    public static final class FastProfile {

        /** The build-side accounting manifest the bundled route writes (git-ignored, like the payloads). */
        public static final String BUNDLED_MANIFEST_FILE_NAME = "uml-fast-profile-v1.sha256";

        /** UP kernel with the initramfs built in — the fast-profile default kernel artifact. */
        public static final String KERNEL = "linux-fast";
        /** UP kernel without the embedded initramfs (external rootfs control variant). */
        public static final String KERNEL_NOINITRD = "linux-fast-noinitrd";
        /** The 16 KB-relinked {@code stub_exe} helper (byte-identical to the release stub recipe). */
        public static final String STUB = "stub_exe";
        /** The embedded initramfs copy (built into {@link #KERNEL}, shipped for accounting). */
        public static final String INITRAMFS = "initramfs.cpio.gz";
        /** The Alpine minirootfs tar — the rootfs entry both delivery routes consume. */
        public static final String ROOTFS_TAR = "alpine-minirootfs-3.22.2-aarch64.tar.gz";

        /** The kernel variant the bundled route stages when the wiring owner does not choose one. */
        public static final String DEFAULT_KERNEL = KERNEL;

        /**
         * The pinned digests, in the manifest grammar, one line per artifact —
         * the single pin source for the runtime verification, the gradle
         * build-time accounting and the contract tests. Values are the U1
         * 2026-11-10 research manifest's ARM64 UP column. The artifact names
         * are spelled as plain literals on purpose: the gradle accounting task
         * parses these same lines out of this file, and
         * {@code UmlKernelAssetsFastProfileTest} locks every literal against
         * the named constants so the two spellings cannot drift.
         */
        public static final String PIN_MANIFEST = String.join("\n",
                "21e1c3f97269a831fa8bab5fd3ff0e8a0e9edc59554d17cce34913924f584389  linux-fast",
                "a5efce2b9197071a2db89735e247fa3cf548644b7d036ea99bf688b14eff17fb  linux-fast-noinitrd",
                "87666f9167de09d0bf84b414618c39d9f460417a641f212f5f0f28c498a09a12  stub_exe",
                "7523c9a20b956a3104d04540559422a7b566ef630a592c9d4ca8fdb2e5bd8381  initramfs.cpio.gz",
                "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489  alpine-minirootfs-3.22.2-aarch64.tar.gz");

        private static final Map<String, String> PINS = parsePins(PIN_MANIFEST);

        private FastProfile() {
        }

        /** One registered artifact: name, pinned digest, and the size when the research manifest recorded one. */
        public record PinnedArtifact(String name, String sha256, Long sizeBytes) {
            public PinnedArtifact {
                Objects.requireNonNull(name, "name");
                Objects.requireNonNull(sha256, "sha256");
            }
        }

        /**
         * The pinned digests (artifact name → SHA-256 hex), parsed once through
         * the same line grammar as {@link #readManifest}. Malformed constants
         * fail at class initialization — a pin registry that cannot parse
         * itself must not ship.
         */
        public static Map<String, String> pins() {
            return PINS;
        }

        /**
         * The whole family in fixed registry order, with the research sizes
         * (bytes). The rootfs tar's size is {@code null} on purpose: the
         * research manifest pinned its digest but no byte count, and an
         * unpinned size must read as unpinned — never as a guessed number.
         */
        public static List<PinnedArtifact> artifacts() {
            return List.of(
                    new PinnedArtifact(KERNEL, PINS.get(KERNEL), 9_517_760L),
                    new PinnedArtifact(KERNEL_NOINITRD, PINS.get(KERNEL_NOINITRD), 8_862_400L),
                    new PinnedArtifact(STUB, PINS.get(STUB), 2_056L),
                    new PinnedArtifact(INITRAMFS, PINS.get(INITRAMFS), 661_899L),
                    new PinnedArtifact(ROOTFS_TAR, PINS.get(ROOTFS_TAR), null));
        }

        /**
         * The packaged placement of a fast-profile artifact: the kernel images
         * unpack from {@code jniLibs} as {@link UmlFaceLauncher#KERNEL_LIB_NAME},
         * the stub as {@link UmlFaceLauncher#STUB_LIB_NAME}. Any other artifact
         * has no packaged placement — the rootfs tar travels through the
         * resources/accounting path instead, and pretending otherwise would
         * corrupt the placement contract.
         */
        public static String packagedLibName(String artifact) {
            if (KERNEL.equals(artifact) || KERNEL_NOINITRD.equals(artifact)) {
                return UmlFaceLauncher.KERNEL_LIB_NAME;
            }
            if (STUB.equals(artifact)) {
                return UmlFaceLauncher.STUB_LIB_NAME;
            }
            throw new IllegalArgumentException(
                    "fast-profile artifact has no packaged lib placement: " + artifact);
        }

        /**
         * The bundle's pin set keyed by packaged placement name, for the
         * chosen kernel variant plus the fixed stub — the map the existing
         * {@link #verify} consumes. Refusing non-kernel variants keeps the
         * variant choice explicit at the call site.
         */
        public static Map<String, String> packagedPinsFor(String kernelArtifact) {
            return Map.of(
                    packagedLibName(kernelArtifact), requirePin(kernelArtifact),
                    packagedLibName(STUB), requirePin(STUB));
        }

        /** {@link #packagedPinsFor} with the {@link #DEFAULT_KERNEL} variant. */
        public static Map<String, String> packagedPins() {
            return packagedPinsFor(DEFAULT_KERNEL);
        }

        private static String requirePin(String artifact) {
            String pin = PINS.get(artifact);
            if (pin == null) {
                throw new IllegalArgumentException("artifact is not pinned: " + artifact);
            }
            return pin;
        }
    }

    /** The strict parser for the {@link FastProfile#PIN_MANIFEST} constant: every non-blank line must conform. */
    private static Map<String, String> parsePins(String pinManifest) {
        Map<String, String> digests = new LinkedHashMap<>();
        for (String line : pinManifest.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            java.util.regex.Matcher match = MANIFEST_LINE.matcher(line.trim());
            if (!match.matches()) {
                throw new IllegalStateException("malformed pin manifest line: " + line);
            }
            digests.put(match.group(2), match.group(1));
        }
        return Collections.unmodifiableMap(digests);
    }

    /**
     * The 方案 A (bundled fast profile) answer: whether the build flag promised
     * the fast-profile assets, and — only when it did — the reconciliation of
     * the placed assets against the fast-profile pins. A disabled report
     * carries no verification at all (zero entries, zero file reads): the
     * historical three-asset shape and its width-0 behavior are the whole
     * story, unchanged.
     */
    public record FastProfileBundleReport(boolean enabled, Inventory inventory) {
        public FastProfileBundleReport {
            Objects.requireNonNull(inventory, "inventory");
            if (!enabled && !inventory.entries().isEmpty()) {
                throw new IllegalArgumentException(
                        "a disabled bundled-fast-profile report must not carry verification entries");
            }
        }
    }

    /**
     * The bundled fast-profile reconciliation. The production wiring passes
     * {@code BuildConfig.UML_BUNDLED_FAST_PROFILE} (gradle property
     * {@code orchestella.uml.bundledFastProfile}, default {@code false}); off
     * means a {@linkplain FastProfileBundleReport disabled report} without a
     * single filesystem touch, so the default build is today's build.
     */
    public static FastProfileBundleReport verifyBundledFastProfile(
            UmlFaceLauncher.Environment environment, boolean bundledFastProfileBuildFlag)
            throws IOException {
        return verifyBundledFastProfile(
                environment, bundledFastProfileBuildFlag, FastProfile.packagedPins());
    }

    /**
     * The pin-injectable form (tests own the pins; production always goes
     * through the single-source overload). Same disabled contract: the flag
     * off answers before any pin or file is consulted.
     */
    static FastProfileBundleReport verifyBundledFastProfile(
            UmlFaceLauncher.Environment environment, boolean bundledFastProfileBuildFlag,
            Map<String, String> pinnedDigests) throws IOException {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(pinnedDigests, "pinnedDigests");
        if (!bundledFastProfileBuildFlag) {
            return new FastProfileBundleReport(false, new Inventory(
                    new ManifestReport(
                            new File(environment.filesDir(), MANIFEST_FILE_NAME), false, 0),
                    List.of()));
        }
        return new FastProfileBundleReport(true, verify(environment, pinnedDigests));
    }

    /**
     * The 方案 B (downloaded rootfs) skeleton: the pinned entry plus the
     * verification chain in the distro-catalog paradigm — the download
     * transport itself stays flag-gated and unwired until the ACR lands it.
     *
     * <p><b>The three layers, and where each lives.</b> L1 transport
     * integrity (HTTPS-only fixed source, byte-count parity, {@code .part}
     * atomic rename, If-Range resume) is the ResilientDownloadEngine's job —
     * this class owns the verification half that runs on the received bytes:
     * the declared size must match the pinned size when one is pinned, and
     * the streamed SHA-256 must match the pin. L2 identity is the pin compare
     * itself — constant-time, fail-closed, no mirror failover on a mismatch
     * (the catalog's "candidates must be byte-identical" rule). L3 content
     * self-check is the verify.sh-calibrated stage plan ({@link #selfCheckPlan()}):
     * declared and ordered here, implemented on the expanded tree by the
     * follow-up round — a skeleton that pretended to pass L3 would be exactly
     * the dishonesty this class exists to prevent.
     */
    public static final class RootfsDownload {

        /** The pinned entry id (the "UML rootfs" entry type in the distro-catalog sense). */
        public static final String ENTRY_ID = "uml-alpine-minirootfs";
        /** The pinned upstream version. */
        public static final String ENTRY_VERSION = "3.22.2";
        /** The pinned architecture (the app's only ABI). */
        public static final String ENTRY_ARCHITECTURE = "aarch64";
        /** The official fixed HTTPS source of the pinned tarball (Alpine release CDN). */
        public static final String ROOTFS_TAR_URL =
                "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/"
                        + FastProfile.ROOTFS_TAR;

        /** Fixed rejection reasons — stable, non-secret, mirroring the distro manifest's naming. */
        public static final String REASON_SIZE_MISMATCH = "uml-rootfs-size-mismatch";
        public static final String REASON_CHECKSUM_MISMATCH = "uml-rootfs-checksum-mismatch";

        private RootfsDownload() {
        }

        /** The build flag's gate: the download entry point stays closed unless the build opened it. */
        public enum DownloadGate {
            /** Default: no download may start (the ACR-pending default build). */
            DISABLED,
            /** The build flag opened the gate; the transport still has to be wired by the follow-up round. */
            ENABLED;

            /** The gate the production wiring builds from {@code BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED}. */
            public static DownloadGate of(boolean buildFlag) {
                return buildFlag ? ENABLED : DISABLED;
            }

            public boolean allowsDownload() {
                return this == ENABLED;
            }
        }

        /**
         * One pinned downloadable rootfs. The validation mirrors the distro
         * manifest's: a fixed HTTPS source with no userinfo/query/fragment
         * whose path ends in the declared file name, a lowercase 64-hex pin,
         * and a positive size whenever a size is pinned at all.
         */
        public record Entry(
                String id, String version, String architecture, URI source, String fileName,
                String sha256Pin, Long sizeBytes) {

            public Entry {
                requireNonBlank("id", id);
                requireNonBlank("version", version);
                requireNonBlank("architecture", architecture);
                requireNonBlank("fileName", fileName);
                Objects.requireNonNull(source, "source");
                Objects.requireNonNull(sha256Pin, "sha256Pin");
                if (!"https".equalsIgnoreCase(source.getScheme())
                        || source.getHost() == null
                        || source.getUserInfo() != null
                        || source.getQuery() != null
                        || source.getFragment() != null
                        || source.getPath() == null
                        || !source.getPath().endsWith("/" + fileName)) {
                    throw new IllegalArgumentException(
                            "source must be a fixed HTTPS URL ending in the declared file name");
                }
                if (!sha256Pin.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("sha256Pin must be 64 lowercase hex digits");
                }
                if (sizeBytes != null && sizeBytes <= 0L) {
                    throw new IllegalArgumentException("sizeBytes must be positive when pinned");
                }
            }

            /** The production entry: the pinned Alpine minirootfs tar (size deliberately unpinned). */
            public static Entry alpineMinirootfsAarch64() {
                return new Entry(
                        ENTRY_ID, ENTRY_VERSION, ENTRY_ARCHITECTURE,
                        URI.create(ROOTFS_TAR_URL), FastProfile.ROOTFS_TAR,
                        FastProfile.pins().get(FastProfile.ROOTFS_TAR), null);
            }
        }

        /** Fail-closed rejection with a stable reason code (never a raw transport message). */
        public static final class RootfsRejectionException extends IOException {
            private static final long serialVersionUID = 1L;

            private final String reason;

            RootfsRejectionException(String reason) {
                super(reason);
                this.reason = reason;
            }

            public String reason() {
                return reason;
            }
        }

        /** Proof returned only after L1 (size) and L2 (pin) both passed on the received bytes. */
        public record VerifiedRootfs(String entryId, String sha256, long observedBytes,
                boolean sizeVerified) {
            public VerifiedRootfs {
                Objects.requireNonNull(entryId, "entryId");
                Objects.requireNonNull(sha256, "sha256");
            }
        }

        /**
         * Verifies the received rootfs bytes: the declared size against the
         * pinned size when one is pinned (L1's verification half), the
         * streamed SHA-256 against the entry's pin with a constant-time
         * compare (L2), fail-closed on every mismatch. No download happens
         * here — the caller owns the transport; this judges its bytes.
         *
         * @param declaredSize the transport's reported byte count, or
         *        {@code null} when the transport could not report one (an
         *        unpinned entry size skips the declared check honestly; a
         *        pinned size rejects a missing declaration instead of hoping)
         */
        public static VerifiedRootfs requireVerified(Entry entry, InputStream input, Long declaredSize)
                throws IOException {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(input, "input");
            if (entry.sizeBytes() != null && declaredSize == null) {
                throw new RootfsRejectionException(REASON_SIZE_MISMATCH);
            }
            if (declaredSize != null && entry.sizeBytes() != null
                    && declaredSize.longValue() != entry.sizeBytes().longValue()) {
                throw new RootfsRejectionException(REASON_SIZE_MISMATCH);
            }
            MessageDigest digest = sha256Digest();
            long observed = 0L;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (entry.sizeBytes() != null && read > entry.sizeBytes() - observed) {
                    throw new RootfsRejectionException(REASON_SIZE_MISMATCH);
                }
                observed += read;
                digest.update(buffer, 0, read);
            }
            boolean sizeVerified = entry.sizeBytes() != null;
            if (sizeVerified && observed != entry.sizeBytes()) {
                throw new RootfsRejectionException(REASON_SIZE_MISMATCH);
            }
            String actual = toLowerHex(digest.digest());
            if (!MessageDigest.isEqual(
                    entry.sha256Pin().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                throw new RootfsRejectionException(REASON_CHECKSUM_MISMATCH);
            }
            return new VerifiedRootfs(entry.id(), actual, observed, sizeVerified);
        }

        /**
         * The L3 content self-check plan, in verify.sh's calibration order:
         * manifest structure → artifact digests → package database parity →
         * whole-tree digest → first-boot cleanliness. Declared here so the
         * chain's shape is locked before any stage is implemented; the
         * follow-up round implements the stages on the expanded tree.
         */
        public static List<SelfCheckStage> selfCheckPlan() {
            return List.of(SelfCheckStage.values());
        }

        /** The L3 stages, in fail-closed evaluation order. */
        public enum SelfCheckStage {
            /** The rootfs manifest's structure and fields are legal. */
            MANIFEST_FIELDS,
            /** Every artifact's sha256/size matches the manifest. */
            ARTIFACT_DIGESTS,
            /** The package database matches the manifest's package set. */
            PACKAGE_DATABASE,
            /** The normalized whole-tree digest matches the manifest. */
            TREE_DIGEST,
            /** First-boot cleanliness: no package-manager state, no host keys, empty machine-id. */
            FIRST_BOOT_CLEANLINESS
        }

        private static void requireNonBlank(String field, String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", unavailable);
        }
    }

    private static String toLowerHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
