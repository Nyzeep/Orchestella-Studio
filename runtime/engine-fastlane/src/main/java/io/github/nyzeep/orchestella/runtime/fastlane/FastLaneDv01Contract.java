package io.github.nyzeep.orchestella.runtime.fastlane;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure parameter and report-shape contract for the DV-01 protected-payload
 * device probe.
 *
 * <p>This class deliberately has no Android dependency: the same validation
 * runs in JVM tests and in the on-device instrumentation, so a rejection is
 * decided by one implementation rather than two drifting copies.
 */
public final class FastLaneDv01Contract {
    public static final String SCHEMA = "orchestella.fastlane.dv01.v1";
    public static final String PAYLOAD_NAME = "libfastlane_payload.so";
    // Must match PayloadConfig in fastlane-payload/include/fastlane/runtime.h.
    public static final int DEFAULT_CONTROL_PORT = 5100;
    public static final int DEFAULT_TERMINAL_PORT = 5101;
    public static final int DEFAULT_PROXY_PORT = 5102;
    public static final int DEFAULT_DISTCC_PORT = 5103;
    public static final int MEMORY_MB = 1024;
    public static final long ENCRYPTED_STORAGE_BYTES = 256L * 1024L * 1024L;
    public static final int DEFAULT_CYCLE_COUNT = 20;
    public static final int MAX_CYCLE_COUNT = 20;
    public static final String AUTH_TOKEN_EMPTY = "empty";

    public static final List<String> REQUIRED_REPORT_KEYS = Arrays.asList(
            "schema",
            "device_alias",
            "sdk_int",
            "build_id",
            "apk_sha256",
            "payload_sha256",
            "toolbox_manifest_sha256",
            "auth_token",
            "stage_results",
            "cycles",
            "stop_timings_ms",
            "failure_codes",
            "negative_cases",
            "not_tested",
            "outcome",
            "started_at_utc",
            "finished_at_utc");

    private static final Pattern RUN_TOKEN = Pattern.compile("[0-9a-f]{16,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern INSTANCE_SUFFIX = Pattern.compile("[A-Za-z0-9._-]{1,16}");
    private static final Pattern DEVICE_ALIAS = Pattern.compile("[A-Za-z0-9._-]{1,32}");

    private FastLaneDv01Contract() {}

    /** Rejects everything except the 16-64 lowercase hex QA run token. */
    public static void requireRunToken(String token) {
        if (token == null || !RUN_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("run token must be 16-64 lowercase hex");
        }
    }

    /** Requires a 64-character lowercase SHA-256, as produced by sha256sum. */
    public static void requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lowercase hex");
        }
    }

    /** DV-01 only launches the shipped payload name; a path is never accepted. */
    public static void requirePayloadName(String payloadName) {
        if (!PAYLOAD_NAME.equals(payloadName)) {
            throw new IllegalArgumentException("payload must be " + PAYLOAD_NAME);
        }
    }

    /** Requires four distinct vsock ports in the legal range. */
    public static void requirePorts(int control, int terminal, int proxy, int distcc) {
        int[] ports = {control, terminal, proxy, distcc};
        for (int port : ports) {
            if (port < 1024 || port > 65535) {
                throw new IllegalArgumentException("vsock port must be in [1024, 65535]");
            }
        }
        if (control == terminal || control == proxy || control == distcc
                || terminal == proxy || terminal == distcc || proxy == distcc) {
            throw new IllegalArgumentException("vsock ports must be distinct");
        }
    }

    /** DV-01 runs at most 20 cycles; zero is not a cycle test. */
    public static void requireCycleCount(int cycleCount) {
        if (cycleCount < 1 || cycleCount > MAX_CYCLE_COUNT) {
            throw new IllegalArgumentException("cycle count must be in [1, 20]");
        }
    }

    /** A short, non-secret device alias supplied by the operator. */
    public static void requireDeviceAlias(String alias) {
        if (alias == null || !DEVICE_ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("device alias must match [A-Za-z0-9._-]{1,32}");
        }
    }

    /** Builds a VM name that fits the platform's 64-character instance limit. */
    public static String instanceId(String token, String suffix) {
        requireRunToken(token);
        if (suffix == null || !INSTANCE_SUFFIX.matcher(suffix).matches()) {
            throw new IllegalArgumentException("instance suffix must match [A-Za-z0-9._-]{1,16}");
        }
        String prefix = token.substring(0, Math.min(12, token.length()));
        String value = "dv01-" + prefix + "-" + suffix;
        if (value.length() > 64) {
            throw new IllegalArgumentException("instance id exceeds 64 characters");
        }
        return value;
    }

    /**
     * Fails unless every key the report schema promises is present. Extra
     * keys are allowed so the probe can add diagnostics without a schema bump.
     */
    public static void requireReportKeys(Set<String> keys) {
        Objects.requireNonNull(keys, "keys");
        for (String required : REQUIRED_REPORT_KEYS) {
            if (!keys.contains(required)) {
                throw new IllegalArgumentException("report is missing key: " + required);
            }
        }
    }

    /** Maps a typed host reason to the stable DV-01 failure taxonomy. */
    public static String classifyFailure(String reason) {
        if (reason == null) {
            return "FASTLANE_UNKNOWN";
        }
        return switch (reason) {
            case FastLaneException.HIDDEN_API_BLOCKED -> "HIDDEN_API_BLOCKED";
            case FastLaneException.PERMISSION_DENIED -> "PERMISSION_DENIED";
            case FastLaneException.PROTECTED_UNAVAILABLE -> "PROTECTED_UNAVAILABLE";
            case FastLaneException.PAYLOAD_VERIFY_FAILED -> "PAYLOAD_VERIFY_FAILED";
            case FastLaneException.VSOCK_FAILED -> "VSOCK_FAILED";
            case FastLaneException.STOP_UNCONFIRMED -> "STOP_UNCONFIRMED";
            case FastLaneException.CONFIG_INVALID -> "CONFIG_INVALID";
            case FastLaneException.VM_CREATE_FAILED -> "VM_CREATE_FAILED";
            default -> "FASTLANE_UNKNOWN";
        };
    }

    /**
     * Reduces the payload's fixed exec diagnostics to a stable class and a
     * numeric errno. The raw message and any path never reach the report.
     */
    public static String terminalDetail(String message) {
        if (message == null) {
            return "unclassified";
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        String stage;
        if (lower.contains("realpath")) {
            stage = "realpath";
        } else if (lower.contains("execve")) {
            stage = "execve";
        } else if (lower.contains("access")) {
            stage = "access";
        } else if (lower.contains("stat")) {
            stage = "stat";
        } else if (lower.contains("outside")) {
            stage = "outside";
        } else if (lower.contains("regular file")) {
            stage = "not_regular";
        } else {
            return "unclassified";
        }
        java.util.regex.Matcher errno =
                java.util.regex.Pattern.compile("errno=(\\d+)").matcher(lower);
        return errno.find() ? stage + "_errno_" + errno.group(1) : stage;
    }
}
