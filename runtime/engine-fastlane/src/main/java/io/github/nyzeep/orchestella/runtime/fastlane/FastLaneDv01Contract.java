package io.github.nyzeep.orchestella.runtime.fastlane;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
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

    // DV-01B bounded preflight. The mode is a separate QA run, not a variant
    // of the full report: it has its own schema, outcome values and stages.
    public static final String PREFLIGHT_MODE = "fastlane-dv01-preflight";
    public static final String PREFLIGHT_SCHEMA = "orchestella.fastlane.dv01.preflight.v1";
    public static final String PREFLIGHT_REPORT_PREFIX = "fastlane-dv01-preflight-";
    public static final String PREFLIGHT_DEFAULT_ALIAS = "qa-device";
    public static final String PREFLIGHT_TERMINAL_MARKER = "DV01_TERMINAL_OK";
    public static final String PREFLIGHT_TERMINAL_SCRIPT = "printf DV01_TERMINAL_OK";
    public static final String PREFLIGHT_OUTCOME_OK = "PREFLIGHT_TERMINAL_OK";
    public static final String PREFLIGHT_OUTCOME_TERMINAL_BLOCKED = "PREFLIGHT_TERMINAL_BLOCKED";
    public static final String PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL = "PREFLIGHT_BLOCKED_EXTERNAL";
    public static final String TOOLBOX_EXEC_BLOCKED_BY_PLATFORM =
            "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM";
    public static final String PREFLIGHT_EXEC_STAGE_NOT_TESTED = "not-tested";
    public static final int PREFLIGHT_CYCLE_COUNT = 1;
    public static final List<String> PREFLIGHT_STAGES = List.of(
            "parameter_validation",
            "platform_facts",
            "create",
            "run",
            "payload_ready",
            "control_hello",
            "terminal_pty",
            "stop",
            "delete");
    public static final List<String> PREFLIGHT_STAGE_RESULT_KEYS =
            List.of("status", "reason_code");
    public static final List<String> PREFLIGHT_NOT_TESTED = List.of(
            "encrypted_storage_persistence",
            "20_cycles",
            "negative_cases");
    public static final List<String> PREFLIGHT_REQUIRED_REPORT_KEYS = List.of(
            "schema",
            "mode",
            "device_alias",
            "sdk_int",
            "abi",
            "virtualization_framework",
            "protected_bit",
            "permission",
            "apk_sha256",
            "payload_sha256",
            "toolbox_manifest_sha256",
            "stage_results",
            "terminal",
            "stop",
            "outcome",
            "started_at_utc",
            "finished_at_utc",
            "not_tested");
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

    /** Applies the preflight alias default, then the same safe-alias rules. */
    public static String preflightDeviceAlias(String alias) {
        if (alias == null || alias.isEmpty()) {
            return PREFLIGHT_DEFAULT_ALIAS;
        }
        requireDeviceAlias(alias);
        return alias;
    }

    /** Preflight creates exactly one VM; a cycle count is never a run option. */
    public static void requirePreflightCycleCount(int cycleCount) {
        if (cycleCount != PREFLIGHT_CYCLE_COUNT) {
            throw new IllegalArgumentException("preflight cycle count must be 1");
        }
    }

    /** The single protected VM instance owned by one preflight run. */
    public static String preflightInstanceId(String token) {
        return instanceId(token, "preflight");
    }

    /** Device-side report path from the preflight contract, never absolute. */
    public static String preflightReportPath(String token) {
        requireRunToken(token);
        return "files/avf-qa/" + PREFLIGHT_REPORT_PREFIX + token + ".json";
    }

    /**
     * The preflight report has exactly nine stages; an extra stage (for
     * example a full-mode cycle table) is a contract violation.
     */
    public static void requirePreflightStages(Set<String> stages) {
        Objects.requireNonNull(stages, "stages");
        if (!stages.equals(new HashSet<>(PREFLIGHT_STAGES))) {
            throw new IllegalArgumentException(
                    "preflight must report exactly: " + PREFLIGHT_STAGES);
        }
    }

    /**
     * Every stage entry carries status and reason_code; detail is the only
     * optional key, and no raw message or path may be added.
     */
    public static void requirePreflightStageResultKeys(Set<String> keys) {
        Objects.requireNonNull(keys, "keys");
        for (String required : PREFLIGHT_STAGE_RESULT_KEYS) {
            if (!keys.contains(required)) {
                throw new IllegalArgumentException("stage is missing key: " + required);
            }
        }
        for (String key : keys) {
            if (!PREFLIGHT_STAGE_RESULT_KEYS.contains(key) && !"detail".equals(key)) {
                throw new IllegalArgumentException("stage has unsupported key: " + key);
            }
        }
    }

    /**
     * The three out-of-scope areas are mandatory and may not be replaced by a
     * generic "not run" marker.
     */
    public static void requirePreflightNotTested(Collection<String> values) {
        Objects.requireNonNull(values, "values");
        if (!values.containsAll(PREFLIGHT_NOT_TESTED)) {
            throw new IllegalArgumentException(
                    "preflight not_tested is missing one of: " + PREFLIGHT_NOT_TESTED);
        }
    }

    /** The preflight report's required top-level keys, mirroring the schema. */
    public static void requirePreflightReportKeys(Set<String> keys) {
        Objects.requireNonNull(keys, "keys");
        for (String required : PREFLIGHT_REQUIRED_REPORT_KEYS) {
            if (!keys.contains(required)) {
                throw new IllegalArgumentException("preflight report is missing key: " + required);
            }
        }
    }

    /**
     * Classifies one terminal attempt. FLN1 7 with an execve EACCES is the
     * Android 16 microdroid_app platform block and keeps only the fixed
     * reason code plus fln1/exec stage/errno; its raw message and path never
     * enter the classification.
     */
    public static TerminalClassification classifyPreflightTerminal(
            boolean ok, int fln1Code, String detail, String failureReason) {
        if (ok) {
            return new TerminalClassification("pass", PREFLIGHT_OUTCOME_OK, -1,
                    PREFLIGHT_EXEC_STAGE_NOT_TESTED, -1);
        }
        String normalizedDetail = detail == null ? "unclassified" : detail;
        if (fln1Code == 7 && "execve_errno_13".equals(normalizedDetail)) {
            return new TerminalClassification("blocked", TOOLBOX_EXEC_BLOCKED_BY_PLATFORM,
                    7, "execve", 13);
        }
        if (fln1Code >= 0) {
            return new TerminalClassification("fail", "TERMINAL_ERROR_FLN1_" + fln1Code,
                    fln1Code, execStage(normalizedDetail), parseErrno(normalizedDetail));
        }
        String reason = failureReason == null || failureReason.isEmpty()
                ? "TERMINAL_FAILED" : failureReason;
        // The schema pins VSOCK_FAILED to a blocked terminal; every other
        // non-FLN failure keeps the fail status of the failed attempt.
        String status = "VSOCK_FAILED".equals(reason) ? "blocked" : "fail";
        return new TerminalClassification(status, reason, -1, "unknown", -1);
    }

    /** Normalizes implementation-side values to the three contract outcomes. */
    public static String normalizePreflightOutcome(String implementationValue,
            boolean lifecycleStarted) {
        if (implementationValue == null) {
            return lifecycleStarted
                    ? PREFLIGHT_OUTCOME_TERMINAL_BLOCKED : PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
        }
        return switch (implementationValue) {
            case PREFLIGHT_OUTCOME_OK, "PREFLIGHT_PASS" -> PREFLIGHT_OUTCOME_OK;
            case PREFLIGHT_OUTCOME_TERMINAL_BLOCKED,
                    TOOLBOX_EXEC_BLOCKED_BY_PLATFORM,
                    "VSOCK_FAILED" -> PREFLIGHT_OUTCOME_TERMINAL_BLOCKED;
            case PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL,
                    "BLOCKED_EXTERNAL" -> PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
            case "PREFLIGHT_FAILED", "FAILED" -> lifecycleStarted
                    ? PREFLIGHT_OUTCOME_TERMINAL_BLOCKED : PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
            default -> lifecycleStarted
                    ? PREFLIGHT_OUTCOME_TERMINAL_BLOCKED : PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
        };
    }

    /**
     * Final outcome gate. A terminal pass is not enough: stop must be
     * CONFIRMED and delete must have passed; otherwise the run is blocked.
     */
    public static String preflightOutcome(boolean lifecycleStarted, String terminalStatus,
            String terminalReason, String stop, String deleteStatus) {
        if (!lifecycleStarted) {
            return PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
        }
        if ("pass".equals(terminalStatus)
                && PREFLIGHT_OUTCOME_OK.equals(terminalReason)
                && "CONFIRMED".equals(stop)
                && "pass".equals(deleteStatus)) {
            return PREFLIGHT_OUTCOME_OK;
        }
        return PREFLIGHT_OUTCOME_TERMINAL_BLOCKED;
    }

    private static String execStage(String detail) {
        for (String stage : new String[] {
                "open_pty", "execve", "fork", "connect", "hello", "read", "write", "stop"}) {
            if (detail.startsWith(stage)) {
                return stage;
            }
        }
        return "unknown";
    }

    private static int parseErrno(String detail) {
        Matcher matcher = Pattern.compile("errno[=_-](\\d+)").matcher(detail);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    /**
     * Sanitized terminal classification. Only the five fields the preflight
     * schema allows are exposed; there is deliberately no message or path.
     */
    public static final class TerminalClassification {
        public final String status;
        public final String reasonCode;
        public final int fln1Code;
        public final String execStage;
        public final int errno;

        private TerminalClassification(String status, String reasonCode, int fln1Code,
                String execStage, int errno) {
            this.status = status;
            this.reasonCode = reasonCode;
            this.fln1Code = fln1Code;
            this.execStage = execStage;
            this.errno = errno;
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
