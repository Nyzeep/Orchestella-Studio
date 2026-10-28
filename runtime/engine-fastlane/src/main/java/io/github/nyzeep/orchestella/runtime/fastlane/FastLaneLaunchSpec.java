package io.github.nyzeep.orchestella.runtime.fastlane;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable description of one protected Microdroid app-payload launch.
 *
 * <p>This is deliberately narrower than the AVF custom-image spec: it names a
 * payload inside the owning APK, encrypted storage, and the four vsock
 * endpoints the guest runtime exposes. It never names a kernel, initrd, or a
 * raw root volume.
 */
public final class FastLaneLaunchSpec {
    private static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern PAYLOAD_NAME = Pattern.compile("[A-Za-z0-9._-]+\\.so");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public enum CpuTopology {
        ONE_CPU,
        MATCH_HOST
    }

    public enum DebugLevel {
        NONE,
        FULL
    }

    private final String instanceId;
    private final String payloadBinaryName;
    /** Nullable: absolute path of the APK carrying the payload. Null = the
     * platform default (the calling app's own APK). See {@link #withPayloadApkPath}. */
    private final String payloadApkPath;
    private final int memoryMb;
    private final CpuTopology cpuTopology;
    private final long encryptedStorageBytes;
    private final DebugLevel debugLevel;
    private final int controlPort;
    private final int terminalPort;
    private final int proxyPort;
    private final int distccPort;
    private final String toolboxManifestSha256;

    /**
     * Builds a launch spec.
     *
     * <p>{@code toolboxManifestSha256} describes the FLN1 toolbox manifest that
     * ships inside the payload: an empty string means this payload carries no
     * FLN1 toolbox at all (for example a compiler-only payload); a non-empty
     * value must be 64 lowercase hex characters. An empty value is never
     * replaced by a placeholder digest — the absence is stated, not faked.
     */
    public FastLaneLaunchSpec(
            String instanceId,
            String payloadBinaryName,
            int memoryMb,
            CpuTopology cpuTopology,
            long encryptedStorageBytes,
            DebugLevel debugLevel,
            int controlPort,
            int terminalPort,
            int proxyPort,
            int distccPort,
            String toolboxManifestSha256) {
        this(instanceId, payloadBinaryName, null, memoryMb, cpuTopology, encryptedStorageBytes,
                debugLevel, controlPort, terminalPort, proxyPort, distccPort,
                toolboxManifestSha256);
    }

    /**
     * Full constructor including the payload APK path (see
     * {@link #withPayloadApkPath} for the semantics).
     */
    public FastLaneLaunchSpec(
            String instanceId,
            String payloadBinaryName,
            String payloadApkPath,
            int memoryMb,
            CpuTopology cpuTopology,
            long encryptedStorageBytes,
            DebugLevel debugLevel,
            int controlPort,
            int terminalPort,
            int proxyPort,
            int distccPort,
            String toolboxManifestSha256) {
        this.instanceId = require(instanceId, "instanceId");
        if (!INSTANCE_ID.matcher(instanceId).matches()) {
            throw new IllegalArgumentException("instanceId must match [A-Za-z0-9._-]{1,64}");
        }
        this.payloadBinaryName = require(payloadBinaryName, "payloadBinaryName");
        if (!PAYLOAD_NAME.matcher(payloadBinaryName).matches()) {
            throw new IllegalArgumentException("payloadBinaryName must be a .so file name without a path");
        }
        this.payloadApkPath = requirePayloadApkPath(payloadApkPath);
        if (memoryMb <= 0 || memoryMb > 16384) {
            throw new IllegalArgumentException("memoryMb must be between 1 and 16384");
        }
        this.memoryMb = memoryMb;
        this.cpuTopology = Objects.requireNonNull(cpuTopology, "cpuTopology");
        if (encryptedStorageBytes <= 0) {
            throw new IllegalArgumentException("encryptedStorageBytes must be positive");
        }
        this.encryptedStorageBytes = encryptedStorageBytes;
        this.debugLevel = Objects.requireNonNull(debugLevel, "debugLevel");
        this.controlPort = requirePort(controlPort, "controlPort");
        this.terminalPort = requirePort(terminalPort, "terminalPort");
        this.proxyPort = requirePort(proxyPort, "proxyPort");
        this.distccPort = requirePort(distccPort, "distccPort");
        if (controlPort == terminalPort
                || controlPort == proxyPort
                || controlPort == distccPort
                || terminalPort == proxyPort
                || terminalPort == distccPort
                || proxyPort == distccPort) {
            throw new IllegalArgumentException("vsock ports must be distinct");
        }
        this.toolboxManifestSha256 =
                Objects.requireNonNull(toolboxManifestSha256, "toolboxManifestSha256");
        if (!toolboxManifestSha256.isEmpty() && !SHA256.matcher(toolboxManifestSha256).matches()) {
            throw new IllegalArgumentException(
                    "toolboxManifestSha256 must be empty or 64 lowercase hex characters");
        }
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Null means "no explicit payload APK": the platform loads the payload
     * from the calling app's own APK. Non-null must be an absolute path to an
     * installed APK file — the two-package accelerator extension case, where
     * the payload lives in a separate app's {@code sourceDir} and the host
     * app never needs to read the file itself (the virtualization service
     * does).
     */
    private static String requirePayloadApkPath(String payloadApkPath) {
        if (payloadApkPath == null) {
            return null;
        }
        if (payloadApkPath.isBlank() || !payloadApkPath.startsWith("/")) {
            throw new IllegalArgumentException(
                    "payloadApkPath must be null or an absolute path");
        }
        return payloadApkPath;
    }

    /**
     * Returns a copy of this spec whose payload is loaded from the given
     * absolute APK path (the platform's {@code setApkPath} semantics) instead
     * of the calling app's own APK. The path is carried verbatim; this class
     * never touches the file.
     */
    public FastLaneLaunchSpec withPayloadApkPath(String payloadApkPath) {
        return new FastLaneLaunchSpec(
                this.instanceId,
                this.payloadBinaryName,
                requirePayloadApkPath(payloadApkPath),
                this.memoryMb,
                this.cpuTopology,
                this.encryptedStorageBytes,
                this.debugLevel,
                this.controlPort,
                this.terminalPort,
                this.proxyPort,
                this.distccPort,
                this.toolboxManifestSha256);
    }

    private static int requirePort(int port, String name) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException(name + " must be in [1024, 65535]");
        }
        return port;
    }

    public String instanceId() {
        return instanceId;
    }

    public String payloadBinaryName() {
        return payloadBinaryName;
    }

    /** Null when the payload rides the calling app's own APK. */
    public String payloadApkPath() {
        return payloadApkPath;
    }

    public int memoryMb() {
        return memoryMb;
    }

    public CpuTopology cpuTopology() {
        return cpuTopology;
    }

    public long encryptedStorageBytes() {
        return encryptedStorageBytes;
    }

    public DebugLevel debugLevel() {
        return debugLevel;
    }

    public int controlPort() {
        return controlPort;
    }

    public int terminalPort() {
        return terminalPort;
    }

    public int proxyPort() {
        return proxyPort;
    }

    public int distccPort() {
        return distccPort;
    }

    /**
     * The FLN1 toolbox manifest digest, or an empty string when this payload
     * carries no FLN1 toolbox.
     */
    public String toolboxManifestSha256() {
        return toolboxManifestSha256;
    }
}
