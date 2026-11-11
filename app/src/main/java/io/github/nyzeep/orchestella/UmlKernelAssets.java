package io.github.nyzeep.orchestella;

import java.io.ByteArrayInputStream;
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
import java.util.Set;
import java.util.TreeSet;

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

        /**
         * The rootfs tar's official byte count, locked first-hand on 2026-11-12
         * against the release CDN (dl-cdn.alpinelinux.org, v3.22 aarch64
         * directory): the HEAD {@code Content-Length} and the byte count of a
         * full download both read 3,981,043, and the downloaded bytes hash to
         * the {@link #PIN_MANIFEST} rootfs pin (the official {@code .sha256}
         * sidecar agrees). This closes the research manifest's open item — the
         * manifest pinned the digest but recorded no size — with the distro's
         * own number, never a guessed one. The x86_64 point release and the
         * other v3.22 point releases are different artifacts and stay unpinned.
         */
        public static final long ROOTFS_TAR_SIZE_BYTES = 3_981_043L;

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
         * The whole family in fixed registry order, with the byte counts. The
         * four build products carry the research manifest's sizes; the rootfs
         * tar carries the officially locked CDN size ({@link
         * #ROOTFS_TAR_SIZE_BYTES}) — the research manifest pinned only its
         * digest, and the missing number came from the release CDN itself
         * rather than a guess.
         */
        public static List<PinnedArtifact> artifacts() {
            return List.of(
                    new PinnedArtifact(KERNEL, PINS.get(KERNEL), 9_517_760L),
                    new PinnedArtifact(KERNEL_NOINITRD, PINS.get(KERNEL_NOINITRD), 8_862_400L),
                    new PinnedArtifact(STUB, PINS.get(STUB), 2_056L),
                    new PinnedArtifact(INITRAMFS, PINS.get(INITRAMFS), 661_899L),
                    new PinnedArtifact(ROOTFS_TAR, PINS.get(ROOTFS_TAR), ROOTFS_TAR_SIZE_BYTES));
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
     * The 方案 B (downloaded rootfs) verification chain in the distro-catalog
     * paradigm — the download transport itself stays flag-gated and unwired
     * until the ACR lands it.
     *
     * <p><b>The three layers, and where each lives.</b> L1 transport
     * integrity (HTTPS-only fixed source, byte-count parity, {@code .part}
     * atomic rename, If-Range resume) is the ResilientDownloadEngine's job —
     * this class owns the verification half that runs on the received bytes:
     * the declared size must match the pinned size when one is pinned, and
     * the streamed SHA-256 must match the pin. L2 identity is the pin compare
     * itself — constant-time, fail-closed, no mirror failover on a mismatch
     * (the catalog's "candidates must be byte-identical" rule); the pin and
     * size are the officially locked ones (2026-11-12 first-hand: the CDN's
     * own {@code .sha256} sidecar, a full download hashed, and a good ncopa
     * GPG signature all agree — see the download-pipeline research note).
     * L3 content self-check is the verify.sh-calibrated five-stage pipeline
     * ({@link #selfCheckPlan()}), implemented as pure-JVM static analysis over
     * the received tar stream: identity fields, artifact digests, package
     * database, top-level structure — with {@link SelfCheckStage#FIRST_BOOT_CLEANLINESS}
     * honestly {@link StageState#PLANNED} (it needs real-device first-boot
     * data; a stage that cannot run must not pretend it passed). {@link
     * #requireVerifiedAndSelfChecked} chains L1/L2/L3 fail-closed in one call.
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

            /**
             * The production entry: the pinned Alpine minirootfs tar, with the
             * officially locked CDN byte count ({@link FastProfile#ROOTFS_TAR_SIZE_BYTES})
             * — first-hand verified on 2026-11-12 (HEAD Content-Length and a
             * full download agree; the bytes also hash to the pin and carry a
             * good ncopa GPG signature, see the download-pipeline research
             * note). A pinned size makes the L1 byte gate exact instead of
             * digest-only.
             */
            public static Entry alpineMinirootfsAarch64() {
                return new Entry(
                        ENTRY_ID, ENTRY_VERSION, ENTRY_ARCHITECTURE,
                        URI.create(ROOTFS_TAR_URL), FastProfile.ROOTFS_TAR,
                        FastProfile.pins().get(FastProfile.ROOTFS_TAR),
                        FastProfile.ROOTFS_TAR_SIZE_BYTES);
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

            /** Same stable reason code, with non-secret detail riding the message. */
            RootfsRejectionException(String reason, String detail) {
                super(detail == null || detail.isEmpty() ? reason : reason + ": " + detail);
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
         * tree structure → first-boot cleanliness. {@link #selfCheck}
         * evaluates exactly this plan, in this order.
         */
        public static List<SelfCheckStage> selfCheckPlan() {
            return List.of(SelfCheckStage.values());
        }

        /** The L3 stages, in fail-closed evaluation order. */
        public enum SelfCheckStage {
            /**
             * The archive's identity fields parse and match the entry's
             * declaration ({@code etc/alpine-release} against the pinned
             * version, {@code etc/hostname} present), and the archive is
             * structurally sane (parses, no path-traversal entries). The
             * verify.sh stage-① analogue; the "manifest" it reads is the
             * download entry plus the tarball's own identity files.
             */
            MANIFEST_FIELDS,
            /**
             * The received artifact's SHA-256 and byte count match the entry's
             * pins — the verify.sh stage-② analogue, re-verifying over the
             * exact bytes this stage was handed (constant-time compare), so a
             * caller cannot pass L2 on one byte sequence and L3 on another.
             */
            ARTIFACT_DIGESTS,
            /**
             * {@code lib/apk/db/installed} parses statically and carries the
             * required packages — the verify.sh stage-⑤ analogue narrowed to
             * what a static read can prove. The apk database grammar only
             * ({@code P:}/{@code V:} stanzas on blank-line separators, the same
             * shape verify.sh's parser reads); apk itself is never executed.
             */
            PACKAGE_DATABASE,
            /**
             * The archive's top-level directory structure is complete (the FHS
             * directories the official tarball ships, each as an explicit
             * directory entry) — the download-side analogue of verify.sh's
             * whole-tree digest, honest about the narrowing: a byte-exact
             * whole-tree digest needs the expanded tree and stays with the
             * prebake route's verify.sh; a tar stream can prove structure, not
             * every file's content.
             */
            TREE_DIGEST,
            /**
             * First-boot cleanliness: no package-manager queue/cache/lock, no
             * baked host keys, empty machine-id/resolv.conf. <b>Declared, not
             * implemented</b> — these are properties of the expanded tree on a
             * real device's first boot, which no static read of the tarball
             * can prove (the official minirootfs ships none of that state to
             * begin with; it is produced — or avoided — at first boot). The
             * stage answers {@link StageState#PLANNED} until a device window
             * supplies the first-boot data the verify.sh port needs; it never
             * masquerades as a pass.
             */
            FIRST_BOOT_CLEANLINESS
        }

        /** Fixed per-stage answers: implemented stages answer PASS/FAIL, declared-only stages PLANNED. */
        public enum StageState {
            /** The stage's checks ran and all passed. */
            PASS,
            /** The stage's checks ran and found a defect (fail-closed). */
            FAIL,
            /**
             * The stage is declared by the plan but not yet implementable from
             * the inputs this pipeline receives — it never counts as a pass
             * and never fails the chain by itself.
             */
            PLANNED
        }

        /** One stage's L3 answer: the fixed state plus a non-secret detail. */
        public record StageResult(SelfCheckStage stage, StageState state, String detail) {
            public StageResult {
                Objects.requireNonNull(stage, "stage");
                Objects.requireNonNull(state, "state");
                Objects.requireNonNull(detail, "detail");
            }
        }

        /**
         * The whole L3 answer, stages in {@link #selfCheckPlan()} order.
         * {@link #failed()} is the fail-closed gate; {@link #plannedStages()}
         * keeps what is still declared-only honestly visible.
         */
        public record SelfCheckReport(List<StageResult> stages) {
            public SelfCheckReport {
                stages = List.copyOf(stages);
            }

            /** True when any implemented stage failed. */
            public boolean failed() {
                return stages.stream().anyMatch(stage -> stage.state() == StageState.FAIL);
            }

            /** The declared-only stages, in plan order (empty once L3 fully lands). */
            public List<SelfCheckStage> plannedStages() {
                return stages.stream().filter(stage -> stage.state() == StageState.PLANNED)
                        .map(StageResult::stage)
                        .collect(java.util.stream.Collectors.toList());
            }

            /** The failing stages' detail lines, in plan order; empty when nothing failed. */
            public List<String> describe() {
                List<String> lines = new ArrayList<>();
                for (StageResult stage : stages) {
                    if (stage.state() == StageState.FAIL) {
                        lines.add(stage.stage() + ": " + stage.detail());
                    }
                }
                return lines;
            }
        }

        /** Fail-closed rejection reason for a failing L3 stage. */
        public static final String REASON_SELF_CHECK_FAILED = "uml-rootfs-self-check-failed";

        /**
         * L3 over the received artifact bytes: one pure-JVM pass (gzip → tar
         * header walk, no disk expansion, no apk execution) records the
         * snapshot every content stage consumes, then {@link #selfCheckPlan()}
         * evaluates in order. Never throws for a content defect — defects are
         * {@link StageState#FAIL} stage states; {@link
         * SelfCheckStage#FIRST_BOOT_CLEANLINESS} answers
         * {@link StageState#PLANNED}.
         */
        public static SelfCheckReport selfCheck(Entry entry, byte[] artifact) throws IOException {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(artifact, "artifact");
            TarSnapshot snapshot;
            try (InputStream tar = new java.util.zip.GZIPInputStream(
                    new ByteArrayInputStream(artifact))) {
                snapshot = TarSnapshot.read(tar);
            } catch (IOException unreadable) {
                // A gzip/tar-level defect must surface as stage failures (with
                // the artifact digest verdict intact), not as a transport error.
                snapshot = TarSnapshot.unreadable(unreadable.getClass().getSimpleName());
            }
            List<StageResult> stages = new ArrayList<>(SelfCheckStage.values().length);
            for (SelfCheckStage stage : selfCheckPlan()) {
                stages.add(evaluateStage(entry, artifact, snapshot, stage));
            }
            return new SelfCheckReport(stages);
        }

        /**
         * The fail-closed form: like {@link #selfCheck}, but any failing stage
         * rejects the artifact with {@link #REASON_SELF_CHECK_FAILED}, the
         * failing stages' details riding the message.
         */
        public static SelfCheckReport requireSelfCheck(Entry entry, byte[] artifact)
                throws IOException {
            SelfCheckReport report = selfCheck(entry, artifact);
            if (report.failed()) {
                throw new RootfsRejectionException(REASON_SELF_CHECK_FAILED,
                        String.join("; ", report.describe()));
            }
            return report;
        }

        /**
         * The L2→L3 chain in one call: {@link #requireVerified} gates the
         * declared size and the pin (L1/L2) on the stream, then {@link
         * #requireSelfCheck} gates the content (L3) over the same bytes. Any
         * layer's rejection fails the whole call closed — a download proceeds
         * only with both the transport identity and the content self-check
         * behind it.
         */
        public static SelfCheckReport requireVerifiedAndSelfChecked(
                Entry entry, byte[] artifact, Long declaredSize) throws IOException {
            Objects.requireNonNull(artifact, "artifact");
            requireVerified(entry, new ByteArrayInputStream(artifact), declaredSize);
            return requireSelfCheck(entry, artifact);
        }

        private static StageResult evaluateStage(
                Entry entry, byte[] artifact, TarSnapshot snapshot, SelfCheckStage stage) {
            switch (stage) {
                case MANIFEST_FIELDS:
                    return manifestFieldsStage(entry, snapshot);
                case ARTIFACT_DIGESTS:
                    return artifactDigestsStage(entry, artifact);
                case PACKAGE_DATABASE:
                    return packageDatabaseStage(snapshot);
                case TREE_DIGEST:
                    return treeDigestStage(snapshot);
                case FIRST_BOOT_CLEANLINESS:
                    return new StageResult(stage, StageState.PLANNED,
                            "first-boot cleanliness needs real-device first-boot data;"
                                    + " declared by the plan, ported from verify.sh when the"
                                    + " device window supplies it — never faked as a pass");
                default:
                    throw new IllegalStateException("stage outside the declared plan: " + stage);
            }
        }

        private static StageResult manifestFieldsStage(Entry entry, TarSnapshot snapshot) {
            if (snapshot.parseError != null) {
                return new StageResult(SelfCheckStage.MANIFEST_FIELDS, StageState.FAIL,
                        "archive unreadable: " + snapshot.parseError);
            }
            if (!snapshot.unsafeEntryNames.isEmpty()) {
                return new StageResult(SelfCheckStage.MANIFEST_FIELDS, StageState.FAIL,
                        "unsafe archive entries (extraction would escape the rootfs root): "
                                + String.join(", ", snapshot.unsafeEntryNames));
            }
            byte[] release = snapshot.fileContents.get(ALPINE_RELEASE_PATH);
            if (release == null) {
                return new StageResult(SelfCheckStage.MANIFEST_FIELDS, StageState.FAIL,
                        "missing required identity file: " + ALPINE_RELEASE_PATH);
            }
            String releaseVersion = text(release);
            if (!entry.version().equals(releaseVersion)) {
                return new StageResult(SelfCheckStage.MANIFEST_FIELDS, StageState.FAIL,
                        ALPINE_RELEASE_PATH + " says \"" + releaseVersion
                                + "\" but the entry pins version " + entry.version());
            }
            byte[] hostname = snapshot.fileContents.get(HOSTNAME_PATH);
            if (hostname == null || text(hostname).isEmpty()) {
                return new StageResult(SelfCheckStage.MANIFEST_FIELDS, StageState.FAIL,
                        "missing or blank required identity file: " + HOSTNAME_PATH);
            }
            // .PKGINFO is apk's own marker, not part of the official minirootfs
            // tarball (first-hand: the v3.22.2 aarch64 archive carries none) —
            // parsed when present, never required: demanding it would fail the
            // official artifact exactly as shipped.
            byte[] pkgInfo = snapshot.fileContents.get(PKGINFO_PATH);
            if (pkgInfo != null && text(pkgInfo).isEmpty()) {
                return new StageResult(SelfCheckStage.MANIFEST_FIELDS, StageState.FAIL,
                        PKGINFO_PATH + " present but blank");
            }
            return new StageResult(SelfCheckStage.MANIFEST_FIELDS, StageState.PASS,
                    "version=" + releaseVersion + " hostname=" + text(hostname)
                            + (pkgInfo != null
                                    ? " .PKGINFO present"
                                    : " .PKGINFO absent (the official tarball's shape)"));
        }

        private static StageResult artifactDigestsStage(Entry entry, byte[] artifact) {
            String actual = sha256Hex(artifact);
            if (!MessageDigest.isEqual(
                    entry.sha256Pin().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
                return new StageResult(SelfCheckStage.ARTIFACT_DIGESTS, StageState.FAIL,
                        "sha256 mismatch: expected=" + entry.sha256Pin() + " actual=" + actual);
            }
            if (entry.sizeBytes() != null && artifact.length != entry.sizeBytes().longValue()) {
                return new StageResult(SelfCheckStage.ARTIFACT_DIGESTS, StageState.FAIL,
                        "size mismatch: pinned=" + entry.sizeBytes()
                                + " actual=" + artifact.length);
            }
            return new StageResult(SelfCheckStage.ARTIFACT_DIGESTS, StageState.PASS,
                    "sha256 ok, " + artifact.length + " bytes"
                            + (entry.sizeBytes() != null ? " (size pinned)" : " (size unpinned)"));
        }

        private static StageResult packageDatabaseStage(TarSnapshot snapshot) {
            if (snapshot.parseError != null) {
                return new StageResult(SelfCheckStage.PACKAGE_DATABASE, StageState.FAIL,
                        "archive unreadable: " + snapshot.parseError);
            }
            byte[] db = snapshot.fileContents.get(APK_INSTALLED_DB_PATH);
            if (db == null) {
                return new StageResult(SelfCheckStage.PACKAGE_DATABASE, StageState.FAIL,
                        "missing package database: " + APK_INSTALLED_DB_PATH);
            }
            // Static parse of the apk installed-db grammar (single-char key
            // lines, blank-line stanza separators — the shape verify.sh's
            // parse_alpine reads). apk itself is never executed.
            List<Map<String, String>> stanzas = new ArrayList<>();
            Map<String, String> stanza = new LinkedHashMap<>();
            for (String line : text(db).split("\n", -1)) {
                if (line.isEmpty()) {
                    if (!stanza.isEmpty()) {
                        stanzas.add(stanza);
                        stanza = new LinkedHashMap<>();
                    }
                } else if (line.length() > 2 && line.charAt(1) == ':') {
                    stanza.put(line.substring(0, 1), line.substring(2));
                }
            }
            if (!stanza.isEmpty()) {
                stanzas.add(stanza);
            }
            if (stanzas.isEmpty()) {
                return new StageResult(SelfCheckStage.PACKAGE_DATABASE, StageState.FAIL,
                        "package database parsed to zero packages");
            }
            Map<String, String> versions = new LinkedHashMap<>();
            for (Map<String, String> parsed : stanzas) {
                String name = parsed.get("P");
                String version = parsed.get("V");
                if (name == null || name.isEmpty() || version == null || version.isEmpty()) {
                    return new StageResult(SelfCheckStage.PACKAGE_DATABASE, StageState.FAIL,
                            "malformed stanza (missing or blank P/V)");
                }
                versions.put(name, version);
            }
            List<String> missing = new ArrayList<>();
            for (String required : REQUIRED_PACKAGES) {
                if (!versions.containsKey(required)) {
                    missing.add(required);
                }
            }
            if (!missing.isEmpty()) {
                return new StageResult(SelfCheckStage.PACKAGE_DATABASE, StageState.FAIL,
                        "required packages absent: " + String.join(", ", missing));
            }
            return new StageResult(SelfCheckStage.PACKAGE_DATABASE, StageState.PASS,
                    stanzas.size() + " packages parsed; all "
                            + REQUIRED_PACKAGES.size() + " required packages present");
        }

        private static StageResult treeDigestStage(TarSnapshot snapshot) {
            if (snapshot.parseError != null) {
                return new StageResult(SelfCheckStage.TREE_DIGEST, StageState.FAIL,
                        "archive unreadable: " + snapshot.parseError);
            }
            List<String> missing = new ArrayList<>();
            for (String dir : REQUIRED_TOP_LEVEL_DIRS) {
                if (!snapshot.topLevelDirs.contains(dir)) {
                    missing.add(dir);
                }
            }
            if (!missing.isEmpty()) {
                return new StageResult(SelfCheckStage.TREE_DIGEST, StageState.FAIL,
                        "required top-level directories absent: "
                                + String.join(", ", missing));
            }
            return new StageResult(SelfCheckStage.TREE_DIGEST, StageState.PASS,
                    REQUIRED_TOP_LEVEL_DIRS.size() + "/" + REQUIRED_TOP_LEVEL_DIRS.size()
                            + " required top-level directories present ("
                            + snapshot.entryCount + " entries walked)");
        }

        /**
         * The packages a rooted Alpine userland cannot be without — the
         * package manager itself, the base layout, the trust-anchor keyring,
         * the userland shell, the libc and the TLS certificate bundle. Static
         * name membership only; a missing required package fails
         * {@link SelfCheckStage#PACKAGE_DATABASE} closed.
         */
        static final List<String> REQUIRED_PACKAGES = List.of(
                "alpine-baselayout",
                "alpine-keys",
                "apk-tools",
                "busybox",
                "ca-certificates-bundle",
                "musl");

        /**
         * The FHS top-level directories the official minirootfs tarball ships,
         * each as an explicit directory entry (first-hand over the v3.22.2
         * aarch64 archive, 2026-11-12). Extra top-level names do not fail —
         * completeness, not equality; an absent one fails
         * {@link SelfCheckStage#TREE_DIGEST} closed.
         */
        static final List<String> REQUIRED_TOP_LEVEL_DIRS = List.of(
                "bin", "dev", "etc", "home", "lib", "media", "mnt", "opt", "proc",
                "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var");

        /** Key file paths inside the official tarball, spelled without the leading {@code ./}. */
        private static final String ALPINE_RELEASE_PATH = "etc/alpine-release";
        private static final String HOSTNAME_PATH = "etc/hostname";
        private static final String PKGINFO_PATH = ".PKGINFO";
        private static final String APK_INSTALLED_DB_PATH = "lib/apk/db/installed";
        /** Content retention cap: the stages need small text files only. */
        private static final long FILE_CONTENT_CAP = 8L * 1024 * 1024;

        /**
         * What one pass over the (already gunzipped) tar stream recorded — the
         * pure input every content stage consumes. Nothing is written to disk
         * and nothing is executed; file contents beyond {@link
         * #FILE_CONTENT_CAP} are walked but not retained.
         */
        private static final class TarSnapshot {
            final String parseError;
            final List<String> unsafeEntryNames;
            final Map<String, byte[]> fileContents;
            final Set<String> topLevelDirs;
            final int entryCount;

            TarSnapshot(String parseError, List<String> unsafeEntryNames,
                    Map<String, byte[]> fileContents, Set<String> topLevelDirs,
                    int entryCount) {
                this.parseError = parseError;
                this.unsafeEntryNames = List.copyOf(unsafeEntryNames);
                this.fileContents = Map.copyOf(fileContents);
                this.topLevelDirs = Set.copyOf(topLevelDirs);
                this.entryCount = entryCount;
            }

            static TarSnapshot unreadable(String reason) {
                return new TarSnapshot("tar stream unreadable (" + reason + ")",
                        List.of(), Map.of(), Set.of(), 0);
            }

            /**
             * One defensive ustar pass: header checksum verified, GNU long
             * names ({@code L}) and PAX {@code path=} overrides applied, data
             * blocks padded through, and — the non-negotiable part — entries
             * whose names escape the archive root recorded as unsafe instead
             * of silently trusted. A checksum mismatch, a truncation or an
             * absurd metadata block ends the walk with a {@link #parseError};
             * the stages then fail closed.
             */
            static TarSnapshot read(InputStream tar) throws IOException {
                List<String> unsafe = new ArrayList<>();
                Map<String, byte[]> contents = new LinkedHashMap<>();
                Set<String> topLevelDirs = new TreeSet<>();
                int entryCount = 0;
                String pendingLongName = null;
                String pendingPaxPath = null;
                byte[] header = new byte[512];
                while (true) {
                    if (!readFully(tar, header)) {
                        break; // clean EOF at a header boundary
                    }
                    if (isZeroBlock(header)) {
                        break; // the tar end marker (GNU tar writes two; one ends it)
                    }
                    long declaredChecksum = parseNumber(header, 148, 8);
                    if (declaredChecksum < 0 || headerChecksum(header) != declaredChecksum) {
                        return new TarSnapshot("tar header checksum mismatch", unsafe,
                                contents, topLevelDirs, entryCount);
                    }
                    long size = parseNumber(header, 124, 12);
                    if (size < 0) {
                        return new TarSnapshot("tar size field unreadable", unsafe,
                                contents, topLevelDirs, entryCount);
                    }
                    char type = (char) (header[156] & 0xFF);
                    String name = cString(header, 0, 100);
                    String prefix = cString(header, 345, 155);
                    if (isUstar(header) && !prefix.isEmpty()) {
                        name = prefix + "/" + name;
                    }
                    String effectiveName = name;
                    if (pendingLongName != null) {
                        effectiveName = pendingLongName;
                        pendingLongName = null;
                    }
                    if (pendingPaxPath != null) {
                        effectiveName = pendingPaxPath;
                        pendingPaxPath = null;
                    }
                    long padded = (size + 511L) / 512L * 512L;
                    switch (type) {
                        case 'L': // GNU long name: the data is the next entry's name
                            if (size > 1024 * 1024) {
                                return new TarSnapshot("tar long-name block absurd", unsafe,
                                        contents, topLevelDirs, entryCount);
                            }
                            pendingLongName = text(readExactly(tar, size));
                            skipFully(tar, padded - size);
                            break;
                        case 'x': // PAX extended header: only the path override matters here
                        case 'g':
                            if (size > 1024 * 1024) {
                                return new TarSnapshot("tar pax block absurd", unsafe,
                                        contents, topLevelDirs, entryCount);
                            }
                            if (type == 'x') {
                                pendingPaxPath = paxPathOverride(readExactly(tar, size));
                            }
                            skipFully(tar, padded - size);
                            break;
                        case '0':
                        case '\0':
                        case '5': // the entry types the stages reason about
                        case '1':
                        case '2':
                        default: // unknown-but-plausible types: walked, never executed
                            recordEntry(effectiveName, type, size, tar, padded,
                                    unsafe, contents, topLevelDirs);
                            entryCount++;
                            break;
                    }
                }
                return new TarSnapshot(null, unsafe, contents, topLevelDirs, entryCount);
            }

            private static void recordEntry(String effectiveName, char type, long size,
                    InputStream tar, long padded, List<String> unsafe,
                    Map<String, byte[]> contents, Set<String> topLevelDirs) throws IOException {
                String normalized = normalize(effectiveName);
                if (normalized == null) {
                    skipFully(tar, padded);
                    return; // the archive root itself ("./")
                }
                int firstSlash = normalized.indexOf('/');
                String top = firstSlash < 0 ? normalized : normalized.substring(0, firstSlash);
                if (top.isEmpty() || top.equals(".") || containsDotDotSegment(normalized)) {
                    unsafe.add(effectiveName);
                }
                if (type == '5') {
                    topLevelDirs.add(top);
                }
                if (type == '0' || type == '\0') {
                    if (size <= FILE_CONTENT_CAP) {
                        contents.put(normalized, readExactly(tar, size));
                        skipFully(tar, padded - size);
                    } else {
                        // Too big to retain: walked for structure, content skipped.
                        skipFully(tar, padded);
                    }
                } else {
                    skipFully(tar, padded);
                }
            }

            /** True when any path segment is {@code ..} — extraction would climb out of the root. */
            private static boolean containsDotDotSegment(String path) {
                for (String segment : path.split("/")) {
                    if (segment.equals("..")) {
                        return true;
                    }
                }
                return false;
            }

            /** Strips the {@code ./} prefix; {@code null} for the archive root. */
            private static String normalize(String name) {
                String normalized = name;
                while (normalized.startsWith("./")) {
                    normalized = normalized.substring(2);
                }
                if (normalized.isEmpty()) {
                    return null;
                }
                return normalized;
            }

            private static String paxPathOverride(byte[] records) {
                for (String record : text(records).split("\n")) {
                    int space = record.indexOf(' ');
                    if (space <= 0) {
                        continue;
                    }
                    String value = record.substring(space + 1);
                    if (value.startsWith("path=")) {
                        return value.substring("path=".length());
                    }
                }
                return null;
            }

            private static boolean isUstar(byte[] header) {
                return header[257] == 'u' && header[258] == 's' && header[259] == 't'
                        && header[260] == 'a' && header[261] == 'r';
            }

            private static boolean isZeroBlock(byte[] block) {
                for (byte b : block) {
                    if (b != 0) {
                        return false;
                    }
                }
                return true;
            }

            private static long headerChecksum(byte[] header) {
                long sum = 0;
                for (int i = 0; i < 512; i++) {
                    sum += (i >= 148 && i < 156) ? 0x20 : (header[i] & 0xFF);
                }
                return sum;
            }

            private static String cString(byte[] header, int offset, int length) {
                int end = offset;
                int limit = offset + length;
                while (end < limit && header[end] != 0) {
                    end++;
                }
                return new String(header, offset, end - offset,
                        java.nio.charset.StandardCharsets.UTF_8);
            }

            /** Tar's numeric fields: octal with space/NUL padding, or GNU base-256. */
            private static long parseNumber(byte[] header, int offset, int length) {
                if ((header[offset] & 0x80) != 0) {
                    long value = header[offset] & 0x7F;
                    for (int i = offset + 1; i < offset + length; i++) {
                        value = (value << 8) | (header[i] & 0xFF);
                    }
                    return value;
                }
                long value = 0;
                boolean sawDigit = false;
                for (int i = offset; i < offset + length; i++) {
                    byte b = header[i];
                    if (b == ' ' || b == 0) {
                        if (sawDigit) {
                            break;
                        }
                        continue;
                    }
                    if (b < '0' || b > '7') {
                        return -1;
                    }
                    sawDigit = true;
                    value = value * 8 + (b - '0');
                }
                return sawDigit ? value : -1;
            }

            private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
                int read = 0;
                while (read < buffer.length) {
                    int chunk = in.read(buffer, read, buffer.length - read);
                    if (chunk < 0) {
                        if (read == 0) {
                            return false; // clean EOF exactly at a header boundary
                        }
                        throw new java.io.EOFException("tar stream truncated mid-header");
                    }
                    read += chunk;
                }
                return true;
            }

            private static byte[] readExactly(InputStream in, long size) throws IOException {
                byte[] data = new byte[(int) size];
                int read = 0;
                while (read < data.length) {
                    int chunk = in.read(data, read, data.length - read);
                    if (chunk < 0) {
                        throw new java.io.EOFException("tar stream truncated mid-entry");
                    }
                    read += chunk;
                }
                return data;
            }

            private static void skipFully(InputStream in, long count) throws IOException {
                long skipped = 0;
                while (skipped < count) {
                    long chunk = in.skip(count - skipped);
                    if (chunk <= 0) {
                        if (in.read() < 0) {
                            throw new java.io.EOFException("tar stream truncated mid-padding");
                        }
                        skipped++;
                    } else {
                        skipped += chunk;
                    }
                }
            }
        }

        private static String text(byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        }

        private static String sha256Hex(byte[] bytes) {
            MessageDigest digest = sha256Digest();
            digest.update(bytes);
            return toLowerHex(digest.digest());
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
