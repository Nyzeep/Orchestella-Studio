package io.github.nyzeep.orchestella.runtime.fastlane;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One canonical protected-lane failure: fixed code, fixed stage, optional errno.
 *
 * <p>The type deliberately has no free-form message. Raw AVC text, logcat
 * lines, paths and tokens cannot enter a report through this class.
 *
 * <p>Rendering is stable and machine-parseable:
 *
 * <pre>
 * CODE
 * CODE stage=execve
 * CODE stage=execve errno=13
 * </pre>
 */
public final class ProtectedFailure {
    public static final String STAGE_PREFLIGHT = "preflight";
    public static final String STAGE_CREATE = "create";
    public static final String STAGE_RUN = "run";
    public static final String STAGE_PAYLOAD_READY = "payload_ready";
    public static final String STAGE_CONTROL_HELLO = "control_hello";
    public static final String STAGE_TERMINAL_PTY = "terminal_pty";
    public static final String STAGE_EXECVE = "execve";
    public static final String STAGE_OPEN_PTY = "open_pty";
    public static final String STAGE_FORK = "fork";
    public static final String STAGE_CONNECT = "connect";
    public static final String STAGE_HELLO = "hello";
    public static final String STAGE_READ = "read";
    public static final String STAGE_WRITE = "write";
    public static final String STAGE_STOP = "stop";
    public static final String STAGE_DELETE = "delete";
    public static final String STAGE_INSTALL = "install";

    private static final Set<String> STAGES = Set.of(
            STAGE_PREFLIGHT, STAGE_CREATE, STAGE_RUN, STAGE_PAYLOAD_READY,
            STAGE_CONTROL_HELLO, STAGE_TERMINAL_PTY, STAGE_EXECVE, STAGE_OPEN_PTY,
            STAGE_FORK, STAGE_CONNECT, STAGE_HELLO, STAGE_READ, STAGE_WRITE,
            STAGE_STOP, STAGE_DELETE, STAGE_INSTALL);

    private static final String[] DETAIL_STAGES = {
        STAGE_OPEN_PTY, STAGE_EXECVE, STAGE_FORK, STAGE_CONNECT,
        STAGE_HELLO, STAGE_READ, STAGE_WRITE, STAGE_STOP
    };

    private static final Pattern ERRNO = Pattern.compile("errno[=_-](\\d+)");
    private static final Pattern MATRIX_ERRNO = Pattern.compile("errno=(\\d+)");

    private final ProtectedFailureCode code;
    private final String stage;
    private final Integer errno;

    private ProtectedFailure(ProtectedFailureCode code, String stage, Integer errno) {
        this.code = Objects.requireNonNull(code, "code");
        if (stage != null && !STAGES.contains(stage)) {
            throw new IllegalArgumentException("unrecognized stage");
        }
        if (errno != null && errno < 0) {
            throw new IllegalArgumentException("errno must be non-negative");
        }
        this.stage = stage;
        this.errno = errno;
    }

    public static ProtectedFailure of(ProtectedFailureCode code, String stage, Integer errno) {
        return new ProtectedFailure(code, stage, errno);
    }

    public static ProtectedFailure unknown() {
        return new ProtectedFailure(ProtectedFailureCode.FASTLANE_UNKNOWN, null, null);
    }

    /**
     * Classifies one failed terminal attempt. FLN1 7 with an execve EACCES is
     * the Android 16 platform block; every other FLN1 error is a generic
     * terminal error and keeps only the fixed stage plus errno.
     */
    public static ProtectedFailure fromTerminal(int fln1Code, String detail) {
        String normalized = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        String stage = detailStage(normalized);
        Integer errno = errno(normalized);
        if (fln1Code == 7 && STAGE_EXECVE.equals(stage) && Integer.valueOf(13).equals(errno)) {
            return new ProtectedFailure(
                    ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, STAGE_EXECVE, 13);
        }
        if (fln1Code >= 0) {
            return new ProtectedFailure(ProtectedFailureCode.TERMINAL_ERROR, stage, errno);
        }
        return unknown();
    }

    /** Maps a typed host runner reason to the same vocabulary. */
    public static ProtectedFailure fromRunnerReason(String reason) {
        if (reason == null) {
            return unknown();
        }
        return switch (reason) {
            case "TOOLBOX_EXEC_BLOCKED_BY_PLATFORM" ->
                    new ProtectedFailure(ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM,
                            STAGE_EXECVE, 13);
            case "PERMISSION_DENIED" -> of(ProtectedFailureCode.PERMISSION_DENIED, null, null);
            case "HIDDEN_API_BLOCKED" -> of(ProtectedFailureCode.HIDDEN_API_BLOCKED, null, null);
            case "PAYLOAD_VERIFY_FAILED" ->
                    of(ProtectedFailureCode.PAYLOAD_VERIFY_FAILED, STAGE_INSTALL, null);
            case "PROTECTED_UNAVAILABLE" ->
                    of(ProtectedFailureCode.PROTECTED_UNAVAILABLE, STAGE_PREFLIGHT, null);
            case "VSOCK_FAILED" -> of(ProtectedFailureCode.VSOCK_FAILED, STAGE_TERMINAL_PTY, null);
            case "STOP_UNCONFIRMED" -> of(ProtectedFailureCode.STOP_UNCONFIRMED, STAGE_STOP, null);
            case "CONFIG_INVALID", "SCHEMA_INVALID" ->
                    of(ProtectedFailureCode.SCHEMA_INVALID, STAGE_PREFLIGHT, null);
            case "VM_CREATE_FAILED" -> of(ProtectedFailureCode.VM_CREATE_FAILED, STAGE_CREATE, null);
            case "READY_TIMEOUT" -> of(ProtectedFailureCode.READY_TIMEOUT, STAGE_PAYLOAD_READY, null);
            case "HELLO_FAILED" -> of(ProtectedFailureCode.HELLO_FAILED, STAGE_CONTROL_HELLO, null);
            case "PROCESS_DIED" -> of(ProtectedFailureCode.PROCESS_DIED, STAGE_RUN, null);
            case "DRAIN_TIMEOUT" -> of(ProtectedFailureCode.DRAIN_TIMEOUT, STAGE_STOP, null);
            default -> unknown();
        };
    }

    /**
     * Normalizes a historical report or matrix spelling. This is the only
     * place legacy aliases are recognized; emitted values always come from
     * {@link #render()}.
     */
    public static ProtectedFailure fromLegacy(String value) {
        return fromLegacy(value, null, null);
    }

    public static ProtectedFailure fromLegacy(String value, Integer fln1Code, Integer errnoHint) {
        if (value == null) {
            return unknown();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return unknown();
        }
        if (trimmed.startsWith("avc:") || trimmed.contains("/")) {
            return unknown();
        }
        if (trimmed.startsWith("TOOLBOX_EXEC_BLOCKED_BY_PLATFORM")) {
            String stage = trimmed.toLowerCase(Locale.ROOT).contains("execve")
                    ? STAGE_EXECVE : null;
            Integer parsed = errnoHint != null ? errnoHint : parseMatrixErrno(trimmed);
            if (parsed == null && stage.equals(STAGE_EXECVE)) {
                parsed = 13;
            }
            return new ProtectedFailure(
                    ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, stage, parsed);
        }
        if ("execve_errno_13".equalsIgnoreCase(trimmed)) {
            return new ProtectedFailure(
                    ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, STAGE_EXECVE, 13);
        }
        if (trimmed.startsWith("TERMINAL_ERROR_FLN1_")) {
            String suffix = trimmed.substring("TERMINAL_ERROR_FLN1_".length());
            if ("7".equals(suffix)) {
                return new ProtectedFailure(
                        ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, STAGE_EXECVE, 13);
            }
            return of(ProtectedFailureCode.TERMINAL_ERROR, null, null);
        }
        if ("PREFLIGHT_TERMINAL_BLOCKED".equals(trimmed)) {
            if (Integer.valueOf(7).equals(fln1Code) && Integer.valueOf(13).equals(errnoHint)) {
                return new ProtectedFailure(
                        ProtectedFailureCode.TOOLBOX_EXEC_BLOCKED_BY_PLATFORM, STAGE_EXECVE, 13);
            }
            return of(ProtectedFailureCode.TERMINAL_ERROR, STAGE_TERMINAL_PTY, null);
        }
        return fromRunnerReason(trimmed);
    }

    public ProtectedFailureCode code() {
        return code;
    }

    public ProtectedFailureClass failureClass() {
        return code.failureClass();
    }

    /** Fixed stage name, or {@code null} when the failure has no stage. */
    public String stage() {
        return stage;
    }

    /** Linux errno, or {@code null} when unavailable. */
    public Integer errno() {
        return errno;
    }

    /** Canonical, secret-free rendering used by reports and the matrix. */
    public String render() {
        StringBuilder text = new StringBuilder(code.name());
        if (stage != null) {
            text.append(" stage=").append(stage);
        }
        if (errno != null) {
            text.append(" errno=").append(errno);
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return render();
    }

    private static String detailStage(String detail) {
        for (String candidate : DETAIL_STAGES) {
            if (detail.startsWith(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Integer errno(String detail) {
        Matcher matcher = ERRNO.matcher(detail);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static Integer parseMatrixErrno(String value) {
        Matcher matcher = MATRIX_ERRNO.matcher(value.toLowerCase(Locale.ROOT));
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
