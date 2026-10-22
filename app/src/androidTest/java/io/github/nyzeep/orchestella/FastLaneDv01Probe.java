package io.github.nyzeep.orchestella;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformFacts;
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfPlatformAccess;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneDv01Contract;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.ReflectiveFastLaneApi;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1Payloads;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1TerminalCodec;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalCloseReason;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalErrorCode;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrame;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrameType;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMux;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMuxChannel;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * DV-01 protected Fast Lane device probe.
 *
 * <p>The probe is pure QA instrumentation: it is not referenced by the product
 * UI and never runs in a release package. It records only stable aliases,
 * counters and fixed reason codes; it never writes a serial, IMEI, Android ID,
 * account, token, local path, key content, screenshot or logcat.
 */
final class FastLaneDv01Probe {
    private static final String PAYLOAD_ENTRY = "lib/arm64-v8a/libfastlane_payload.so";
    private static final String TOOLBOX_ENTRY = "bin/manifest.json";
    private static final String SHELL_ENTRY = "bin/sh";
    private static final String SHELL_PATH = "/mnt/apk/bin/sh";
    private static final String MARKER_PATH = "/mnt/encryptedstore/dv01-marker";
    private static final String WRITE_SCRIPT =
            "printf DV01_TERMINAL_OK; echo dv01 > " + MARKER_PATH + "; cat " + MARKER_PATH;
    private static final String READ_SCRIPT =
            "printf DV01_PERSIST_OK; cat " + MARKER_PATH;
    private static final String TERMINAL_OK = "DV01_TERMINAL_OK";
    private static final String PERSIST_OK = "DV01_PERSIST_OK";
    private static final String MARKER_VALUE = "dv01";
    private static final int CAPABILITY_PROTECTED_VM = 1;
    private static final int MAX_TERMINAL_OUTPUT = 8192;
    private static final int DEFAULT_PAYLOAD_READY_MS = 60000;
    private static final int MAX_PAYLOAD_READY_MS = 180000;
    private static final int DEFAULT_VSOCK_TIMEOUT_MS = 120000;
    private static final int MAX_VSOCK_TIMEOUT_MS = 300000;
    private static final int CYCLE_VSOCK_TIMEOUT_MS = 30000;
    private static final int NEGATIVE_VSOCK_TIMEOUT_MS = 10000;
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,96}");
    private static final String[] REQUIRED_FAILURE_CODES = {
            "HIDDEN_API_BLOCKED",
            "PERMISSION_DENIED",
            "PROTECTED_UNAVAILABLE",
            "PAYLOAD_VERIFY_FAILED",
            "VSOCK_FAILED",
            "STOP_UNCONFIRMED"
    };

    private final Context target;
    private final Bundle arguments;
    private final int stopDeadlineMs;
    private final JSONObject stages = new JSONObject();
    private final JSONArray cycles = new JSONArray();
    private final JSONArray stopTimings = new JSONArray();
    private final JSONArray negativeCases = new JSONArray();
    private final JSONArray notTested = new JSONArray();
    private final JSONObject failureCodes = new JSONObject();
    private final long startedAtMs = System.currentTimeMillis();

    private String outcome = "FAILED";
    private String token;
    private String deviceAlias = "qa-device";
    private String toolboxSha;
    private String expectedPayloadSha;
    private String expectedApkSha;
    private String apkSha = "not-tested";
    private String payloadSha = "not-tested";
    private String payloadReadyStatus = "not-tested";
    private String terminalStatus = "not-tested";
    private String persistenceStatus = "not-tested";
    private boolean corePass;
    private boolean cyclesPass;
    private boolean payloadReadyObserved = true;
    private String controlDiagnostic = "none";
    private String terminalDiagnostic = "none";
    private int controlPort;
    private int terminalPort;
    private int proxyPort;
    private int distccPort;
    private int payloadReadyDeadlineMs;
    private int vsockTimeoutMs;
    private int cycleCount;
    private String instanceMain;
    private Object manager;
    private Class<?> managerClass;
    private FastLaneVirtualMachineApi api;
    private JSONObject qualification;

    // Preflight-only state. A preflight probe instance never runs the full
    // lifecycle, so these fields cannot leak into a full-mode report.
    private String preflightStop = "NOT_TESTED";
    private String preflightPermission = "UNKNOWN";
    private String preflightToolboxSha = "not-tested";
    private boolean preflightLifecycleStarted;
    private FastLaneDv01Contract.TerminalClassification preflightTerminal;

    FastLaneDv01Probe(Context target, Bundle arguments, int stopDeadlineMs) {
        this.target = target;
        this.arguments = arguments == null ? Bundle.EMPTY : arguments;
        this.stopDeadlineMs = stopDeadlineMs;
    }

    JSONObject run() {
        try {
            prepareFailureCodes();
            if (!parseArguments()) {
                return finishReport();
            }
            stages.put("parameter_validation", passed("QA_ARGUMENTS_VALID"));
            if (!preflight()) {
                return finishReport();
            }
            negativePreflightCases();
            positiveLifecycle();
            if (!"FAILED".equals(outcome)) {
                wrongPortCase();
                wrongPayloadCase();
                runCycles();
            }
        } catch (Throwable failure) {
            try {
                stages.put("probe", failed("QA_PROBE_EXCEPTION", exceptionName(failure)));
                observeFailure("FASTLANE_UNKNOWN", "probe", "instrumentation");
                outcome = "FAILED";
            } catch (JSONException ignored) {
                outcome = "FAILED";
            }
        }
        return finishReport();
    }

    /**
     * Bounded DV-01B preflight: one VM, one run, one fixed terminal command,
     * one stop and one delete. It never starts a second VM, never writes a
     * storage marker, never runs cycles or the negative-case matrix.
     */
    JSONObject runPreflight() {
        preparePreflightNotTested();
        try {
            if (!parsePreflightArguments()) {
                return finishPreflightReport();
            }
            if (!preflightIntegrityCheck()) {
                return finishPreflightReport();
            }
            stages.put("parameter_validation", passed("QA_ARGUMENTS_VALID"));
            if (!preflightPlatformFacts()) {
                return finishPreflightReport();
            }
            preflightLifecycle();
        } catch (Throwable failure) {
            // No exception text: it can contain a path or a platform message.
            try {
                if (!stages.has("parameter_validation")) {
                    stages.put("parameter_validation", blocked("PREFLIGHT_FAILED"));
                }
                preflightFillBlocked("PREFLIGHT_BLOCKED");
                if (preflightTerminal == null) {
                    preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                            false, -1, null, "PREFLIGHT_FAILED");
                }
                outcome = FastLaneDv01Contract.preflightOutcome(
                        preflightLifecycleStarted, preflightTerminal.status,
                        preflightTerminal.reasonCode, preflightStop,
                        stages.has("delete")
                                ? stages.getJSONObject("delete").optString("status", "fail")
                                : "fail");
            } catch (JSONException ignored) {
                outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
            }
        }
        return finishPreflightReport();
    }

    private void preparePreflightNotTested() {
        if (notTested.length() > 0) {
            return;
        }
        for (String value : FastLaneDv01Contract.PREFLIGHT_NOT_TESTED) {
            notTested.put(value);
        }
    }

    private boolean parsePreflightArguments() {
        try {
            token = arguments.getString("qaRunToken");
            FastLaneDv01Contract.requireRunToken(token);
            String payload = arguments.getString("qaPayload", FastLaneDv01Contract.PAYLOAD_NAME);
            FastLaneDv01Contract.requirePayloadName(payload);
            toolboxSha = arguments.getString("qaToolboxSha");
            FastLaneDv01Contract.requireSha256(toolboxSha, "qaToolboxSha");
            deviceAlias = FastLaneDv01Contract.preflightDeviceAlias(
                    arguments.getString("qaDeviceAlias"));
            controlPort = boundedInt(arguments.getString("qaControlPort"),
                    FastLaneDv01Contract.DEFAULT_CONTROL_PORT, 1024, 65535);
            terminalPort = boundedInt(arguments.getString("qaTerminalPort"),
                    FastLaneDv01Contract.DEFAULT_TERMINAL_PORT, 1024, 65535);
            proxyPort = boundedInt(arguments.getString("qaProxyPort"),
                    FastLaneDv01Contract.DEFAULT_PROXY_PORT, 1024, 65535);
            distccPort = boundedInt(arguments.getString("qaDistccPort"),
                    FastLaneDv01Contract.DEFAULT_DISTCC_PORT, 1024, 65535);
            FastLaneDv01Contract.requirePorts(controlPort, terminalPort, proxyPort, distccPort);
            payloadReadyDeadlineMs = boundedInt(arguments.getString("qaPayloadReadyDeadlineMs"),
                    DEFAULT_PAYLOAD_READY_MS, 1000, MAX_PAYLOAD_READY_MS);
            vsockTimeoutMs = boundedInt(arguments.getString("qaVsockTimeoutMs"),
                    DEFAULT_VSOCK_TIMEOUT_MS, 1000, MAX_VSOCK_TIMEOUT_MS);
            cycleCount = boundedInt(arguments.getString("qaCycleCount"),
                    FastLaneDv01Contract.PREFLIGHT_CYCLE_COUNT, 1,
                    FastLaneDv01Contract.MAX_CYCLE_COUNT);
            FastLaneDv01Contract.requirePreflightCycleCount(cycleCount);
            expectedPayloadSha = optionalSha("qaExpectedPayloadSha");
            expectedApkSha = optionalSha("qaExpectedApkSha");
            instanceMain = FastLaneDv01Contract.preflightInstanceId(token);
            return true;
        } catch (IllegalArgumentException | JSONException rejected) {
            try {
                // Never echo the rejected value; it may be a token or a path.
                stages.put("parameter_validation", blocked("QA_ARGUMENTS_REJECTED"));
                preflightFillBlocked("PREFLIGHT_BLOCKED");
                preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                        false, -1, null, "QA_ARGUMENTS_REJECTED");
                preflightStop = "NOT_TESTED";
                outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
            } catch (JSONException ignored) {
                outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
            }
            return false;
        }
    }

    private boolean preflightIntegrityCheck() throws JSONException {
        File apk = apkFile();
        if (apk == null || !apk.isFile()) {
            return failPreflightIntegrity("APK_NOT_READABLE");
        }
        apkSha = sha256File(apk);
        if (!apkSha.matches("[0-9a-f]{64}")) {
            return failPreflightIntegrity("APK_NOT_READABLE");
        }
        payloadSha = sha256ZipEntry(apk, PAYLOAD_ENTRY);
        String actualToolboxSha = sha256ZipEntry(apk, TOOLBOX_ENTRY);
        if (payloadSha == null) {
            return failPreflightIntegrity("PAYLOAD_ENTRY_MISSING");
        }
        if (actualToolboxSha == null || !actualToolboxSha.equals(toolboxSha)) {
            return failPreflightIntegrity("TOOLBOX_SHA_MISMATCH");
        }
        preflightToolboxSha = actualToolboxSha;
        if (!zipEntryExists(apk, SHELL_ENTRY)) {
            return failPreflightIntegrity("TOOLBOX_SHELL_MISSING");
        }
        if (expectedPayloadSha != null && !expectedPayloadSha.equals(payloadSha)) {
            return failPreflightIntegrity("PAYLOAD_SHA_MISMATCH");
        }
        if (expectedApkSha != null && !expectedApkSha.equals(apkSha)) {
            return failPreflightIntegrity("APK_SHA_MISMATCH");
        }
        return true;
    }

    private boolean failPreflightIntegrity(String reason) throws JSONException {
        stages.put("parameter_validation", failed(reason));
        preflightFillBlocked("PREFLIGHT_BLOCKED");
        // The preflight schema allows PAYLOAD_VERIFY_FAILED as the sanitized
        // terminal reason; the stage keeps the finer fixed reason code.
        preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                false, -1, null, "PAYLOAD_VERIFY_FAILED");
        preflightStop = "NOT_TESTED";
        outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
        return false;
    }

    private boolean preflightPlatformFacts() throws JSONException {
        AvfHiddenApiExemption.State exemption = AvfHiddenApiExemption.installOnce();
        openManager();
        ReflectiveAvfPlatformAccess access = new ReflectiveAvfPlatformAccess(
                target, target.getClassLoader(), AvfHiddenApiExemption::installOnce,
                romBinding());
        AvfPlatformFacts facts = access.observe();
        preflightPermission = facts.managePermission().name();
        qualification = new JSONObject();
        qualification.put("access_state", facts.accessState().name());
        qualification.put("exemption_state", facts.exemptionState().name());
        qualification.put("manager_present", facts.managerPresent());
        qualification.put("manage_permission", facts.managePermission().name());
        qualification.put("custom_permission", facts.customPermission().name());
        qualification.put("protected_support", facts.modeFacts().protectedVm().name());
        qualification.put("non_protected_support", facts.modeFacts().nonProtectedVm().name());
        qualification.put("capabilities_raw", readCapabilities());
        qualification.put("protected_bit", hasProtectedBit());
        stages.put("platform_facts", passed("PLATFORM_OBSERVED"));

        String blockCode = null;
        if (facts.accessState() == AvfPlatformFacts.AccessState.HIDDEN_API_BLOCKED) {
            blockCode = "HIDDEN_API_BLOCKED";
        } else if (facts.accessState() == AvfPlatformFacts.AccessState.PERMISSION_DENIED
                || facts.managePermission() != AvfCapabilityEvaluator.PermissionState.GRANTED) {
            blockCode = "PERMISSION_DENIED";
        } else if (exemption != AvfHiddenApiExemption.State.INSTALLED) {
            blockCode = "HIDDEN_API_BLOCKED";
        } else if (manager == null || readCapabilities() < 0
                || facts.modeFacts().protectedVm()
                        != AvfCapabilityEvaluator.VmSupport.SUPPORTED
                || !hasProtectedBit()) {
            blockCode = "PROTECTED_UNAVAILABLE";
        }
        if (blockCode == null) {
            return true;
        }
        stages.put("platform_facts", blocked(blockCode));
        preflightFillBlocked("PREFLIGHT_BLOCKED");
        preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                false, -1, null, blockCode);
        preflightStop = "NOT_TESTED";
        outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
        return false;
    }

    private void preflightLifecycle() throws JSONException {
        api = new ReflectiveFastLaneApi(target, target.getClassLoader());
        openManager();
        FastLaneLaunchSpec mainSpec = spec(instanceMain);
        FastLaneSession session = null;
        try {
            FastLaneHandle handle = api.start(mainSpec);
            session = new FastLaneSession(api, handle, mainSpec);
            preflightLifecycleStarted = true;
            stages.put("create", passed("VM_CREATED"));
            stages.put("run", passed("RUN_RETURNED"));
        } catch (FastLaneException failure) {
            String reason = FastLaneDv01Contract.classifyFailure(failure.reason());
            stages.put("create", failed("CREATE_FAILED", diagnostic(failure)));
            stages.put("run", blocked("CREATE_BLOCKED"));
            preflightFillBlocked("CREATE_BLOCKED");
            preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                    false, -1, null, preflightStartReason(reason));
            preflightStop = "NOT_TESTED";
            outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
            return;
        } catch (Throwable failure) {
            stages.put("create", failed("CREATE_FAILED", exceptionName(failure)));
            stages.put("run", blocked("CREATE_BLOCKED"));
            preflightFillBlocked("CREATE_BLOCKED");
            preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                    false, -1, null, "PREFLIGHT_FAILED");
            preflightStop = "NOT_TESTED";
            outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
            return;
        }

        try {
            Wait ready = waitForPayloadReady(session, payloadReadyDeadlineMs);
            stages.put("payload_ready", ready.observed
                    ? passed("PAYLOAD_READY_OBSERVED", "elapsed_ms=" + ready.elapsedMs)
                    : blocked("PAYLOAD_READY_NOT_OBSERVED", "elapsed_ms=" + ready.elapsedMs));
            payloadReadyStatus = ready.observed ? "pass" : "not-observed";

            ControlResult control = controlHandshake(session, mainSpec, vsockTimeoutMs);
            stages.put("control_hello", control.ok
                    ? passed("HELLO_HELLO_OK", "elapsed_ms=" + control.elapsedMs)
                    : failed("VSOCK_FAILED", control.layer));

            if (control.ok) {
                TerminalResult terminal = runTerminal(
                        session, mainSpec, FastLaneDv01Contract.PREFLIGHT_TERMINAL_SCRIPT,
                        vsockTimeoutMs, true);
                preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                        terminal.ok, terminal.fln1Code, terminal.detail, terminal.failureCode);
                stages.put("terminal_pty", terminal.ok
                        ? passed("TERMINAL_EXIT_0",
                                "output_bytes=" + terminal.outputBytes)
                        : stageFor(preflightTerminal,
                                "output_bytes=" + terminal.outputBytes));
            } else {
                preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                        false, -1, null, "VSOCK_FAILED");
                stages.put("terminal_pty", blocked("VSOCK_FAILED"));
            }
        } finally {
            // Stop once, bounded. Only a CONFIRMED stop permits delete.
            FastLaneStopResult stopResult;
            try {
                stopResult = session.stopRuntime(stopDeadlineMs);
            } catch (Throwable failure) {
                stopResult = FastLaneStopResult.UNCONFIRMED;
            }
            // stopRuntime already closes the channels. Re-entering close()
            // after UNCONFIRMED would issue a second platform stop, so only
            // close a settled session (where close() is a no-op stop).
            if (stopResult == FastLaneStopResult.CONFIRMED
                    || stopResult == FastLaneStopResult.ALREADY_STOPPED) {
                session.close();
            }
            switch (stopResult) {
                case CONFIRMED -> {
                    preflightStop = "CONFIRMED";
                    stages.put("stop", passed("STOP_CONFIRMED"));
                }
                case ALREADY_STOPPED -> {
                    preflightStop = "ALREADY_STOPPED";
                    stages.put("stop", blocked("STOP_ALREADY_STOPPED"));
                }
                default -> {
                    preflightStop = "UNCONFIRMED";
                    stages.put("stop", failed("STOP_UNCONFIRMED"));
                }
            }
        }

        if ("CONFIRMED".equals(preflightStop)) {
            if (preflightDelete()) {
                stages.put("delete", passed("VM_DELETED"));
            } else {
                stages.put("delete", failed("VM_DELETE_UNCONFIRMED"));
            }
        } else {
            stages.put("delete", blocked("DELETE_SKIPPED_STOP_UNCONFIRMED"));
            notTested.put("vm_delete_skipped_after_unconfirmed_stop");
        }

        outcome = FastLaneDv01Contract.preflightOutcome(
                preflightLifecycleStarted,
                preflightTerminal == null ? "fail" : preflightTerminal.status,
                preflightTerminal == null ? "PREFLIGHT_FAILED" : preflightTerminal.reasonCode,
                preflightStop,
                stages.getJSONObject("delete").optString("status", "fail"));
    }

    private String preflightStartReason(String reason) {
        return switch (reason) {
            case FastLaneException.HIDDEN_API_BLOCKED,
                    FastLaneException.PERMISSION_DENIED,
                    FastLaneException.PROTECTED_UNAVAILABLE,
                    FastLaneException.PAYLOAD_VERIFY_FAILED -> reason;
            default -> "PREFLIGHT_FAILED";
        };
    }

    private boolean preflightDelete() {
        if (manager == null || managerClass == null) {
            return false;
        }
        try {
            managerClass.getMethod("delete", String.class).invoke(manager, instanceMain);
        } catch (Throwable failure) {
            return false;
        }
        try {
            Object residual = managerClass.getMethod("get", String.class)
                    .invoke(manager, instanceMain);
            return residual == null;
        } catch (InvocationTargetException wrapped) {
            // A not-found VirtualMachineException is the expected absence.
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private void preflightFillBlocked(String reason) throws JSONException {
        for (String stage : FastLaneDv01Contract.PREFLIGHT_STAGES) {
            if (!stages.has(stage)) {
                stages.put(stage, blocked(reason));
            }
        }
    }

    private JSONObject stageFor(
            FastLaneDv01Contract.TerminalClassification classification, String detail)
            throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("status", classification.status);
        entry.put("reason_code", classification.reasonCode);
        if (detail != null) {
            entry.put("detail", detail);
        }
        return entry;
    }

    private JSONObject finishPreflightReport() {
        try {
            preflightFillBlocked("PREFLIGHT_BLOCKED");
            if (preflightTerminal == null) {
                preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                        false, -1, null, "PREFLIGHT_FAILED");
            }
            if (outcome == null || !isPreflightOutcome(outcome)) {
                outcome = FastLaneDv01Contract.preflightOutcome(
                        preflightLifecycleStarted, preflightTerminal.status,
                        preflightTerminal.reasonCode, preflightStop,
                        stages.has("delete")
                                ? stages.getJSONObject("delete").optString("status", "fail")
                                : "fail");
            }
            JSONObject report = new JSONObject();
            report.put("schema", FastLaneDv01Contract.PREFLIGHT_SCHEMA);
            report.put("mode", FastLaneDv01Contract.PREFLIGHT_MODE);
            report.put("device_alias", deviceAlias);
            report.put("sdk_int", Build.VERSION.SDK_INT);
            report.put("abi", primaryAbi());
            report.put("virtualization_framework", hasVirtualizationFramework());
            report.put("protected_bit", hasProtectedBit());
            report.put("permission", preflightPermission);
            report.put("apk_sha256", apkSha);
            report.put("payload_sha256", payloadSha);
            report.put("toolbox_manifest_sha256", preflightToolboxSha);
            report.put("stage_results", stages);
            report.put("terminal", preflightTerminalJson());
            report.put("stop", preflightStop);
            report.put("outcome", outcome);
            report.put("started_at_utc", instant(startedAtMs));
            report.put("finished_at_utc", instant(System.currentTimeMillis()));
            report.put("not_tested", notTested);
            report.put("qualification", qualification == null ? new JSONObject() : qualification);
            report.put("payload_ready", payloadReadyStatus);
            report.put("vsock_control", controlDiagnostic);
            report.put("vsock_terminal", terminalDiagnostic);

            if (!preflightContractSatisfied(report)) {
                outcome = FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL;
                preflightTerminal = FastLaneDv01Contract.classifyPreflightTerminal(
                        false, -1, null, "PREFLIGHT_FAILED");
                report.put("terminal", preflightTerminalJson());
                report.put("outcome", outcome);
            }
            return report;
        } catch (JSONException failure) {
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("schema", FastLaneDv01Contract.PREFLIGHT_SCHEMA);
                fallback.put("mode", FastLaneDv01Contract.PREFLIGHT_MODE);
                fallback.put("device_alias", FastLaneDv01Contract.PREFLIGHT_DEFAULT_ALIAS);
                fallback.put("outcome", FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL);
                fallback.put("not_tested", notTested);
            } catch (JSONException ignored) {
                // The report cannot be constructed at all.
            }
            return fallback;
        }
    }

    private JSONObject preflightTerminalJson() throws JSONException {
        JSONObject terminal = new JSONObject();
        terminal.put("status", preflightTerminal.status);
        terminal.put("reason_code", preflightTerminal.reasonCode);
        terminal.put("fln1_code", preflightTerminal.fln1Code);
        terminal.put("exec_stage", preflightTerminal.execStage);
        terminal.put("errno", preflightTerminal.errno);
        return terminal;
    }

    private boolean preflightContractSatisfied(JSONObject report) throws JSONException {
        Set<String> reportKeys = new HashSet<>();
        java.util.Iterator<String> reportIterator = report.keys();
        while (reportIterator.hasNext()) {
            reportKeys.add(reportIterator.next());
        }
        Set<String> stageKeys = new HashSet<>();
        java.util.Iterator<String> stageIterator = stages.keys();
        while (stageIterator.hasNext()) {
            stageKeys.add(stageIterator.next());
        }
        java.util.List<String> notTestedValues = new java.util.ArrayList<>();
        for (int index = 0; index < notTested.length(); index++) {
            notTestedValues.add(notTested.getString(index));
        }
        try {
            FastLaneDv01Contract.requirePreflightReportKeys(reportKeys);
            FastLaneDv01Contract.requirePreflightStages(stageKeys);
            FastLaneDv01Contract.requirePreflightNotTested(notTestedValues);
            for (String stage : FastLaneDv01Contract.PREFLIGHT_STAGES) {
                Set<String> keys = new HashSet<>();
                java.util.Iterator<String> iterator = stages.getJSONObject(stage).keys();
                while (iterator.hasNext()) {
                    keys.add(iterator.next());
                }
                FastLaneDv01Contract.requirePreflightStageResultKeys(keys);
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private boolean isPreflightOutcome(String value) {
        return FastLaneDv01Contract.PREFLIGHT_OUTCOME_OK.equals(value)
                || FastLaneDv01Contract.PREFLIGHT_OUTCOME_TERMINAL_BLOCKED.equals(value)
                || FastLaneDv01Contract.PREFLIGHT_OUTCOME_BLOCKED_EXTERNAL.equals(value);
    }

    private String primaryAbi() {
        return Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
    }

    private boolean hasVirtualizationFramework() {
        return target.getPackageManager().hasSystemFeature(
                "android.software.virtualization.framework");
    }

    private boolean parseArguments() {
        try {
            token = arguments.getString("qaRunToken");
            FastLaneDv01Contract.requireRunToken(token);
            String payload = arguments.getString("qaPayload", FastLaneDv01Contract.PAYLOAD_NAME);
            FastLaneDv01Contract.requirePayloadName(payload);
            toolboxSha = arguments.getString("qaToolboxSha");
            FastLaneDv01Contract.requireSha256(toolboxSha, "qaToolboxSha");
            deviceAlias = arguments.getString("qaDeviceAlias", "qa-device");
            FastLaneDv01Contract.requireDeviceAlias(deviceAlias);
            controlPort = boundedInt(arguments.getString("qaControlPort"),
                    FastLaneDv01Contract.DEFAULT_CONTROL_PORT, 1024, 65535);
            terminalPort = boundedInt(arguments.getString("qaTerminalPort"),
                    FastLaneDv01Contract.DEFAULT_TERMINAL_PORT, 1024, 65535);
            proxyPort = boundedInt(arguments.getString("qaProxyPort"),
                    FastLaneDv01Contract.DEFAULT_PROXY_PORT, 1024, 65535);
            distccPort = boundedInt(arguments.getString("qaDistccPort"),
                    FastLaneDv01Contract.DEFAULT_DISTCC_PORT, 1024, 65535);
            FastLaneDv01Contract.requirePorts(controlPort, terminalPort, proxyPort, distccPort);
            payloadReadyDeadlineMs = boundedInt(arguments.getString("qaPayloadReadyDeadlineMs"),
                    DEFAULT_PAYLOAD_READY_MS, 1000, MAX_PAYLOAD_READY_MS);
            vsockTimeoutMs = boundedInt(arguments.getString("qaVsockTimeoutMs"),
                    DEFAULT_VSOCK_TIMEOUT_MS, 1000, MAX_VSOCK_TIMEOUT_MS);
            cycleCount = boundedInt(arguments.getString("qaCycleCount"),
                    FastLaneDv01Contract.DEFAULT_CYCLE_COUNT, 1,
                    FastLaneDv01Contract.MAX_CYCLE_COUNT);
            FastLaneDv01Contract.requireCycleCount(cycleCount);
            expectedPayloadSha = optionalSha("qaExpectedPayloadSha");
            expectedApkSha = optionalSha("qaExpectedApkSha");
            instanceMain = FastLaneDv01Contract.instanceId(token, "main");
            return true;
        } catch (IllegalArgumentException | JSONException rejected) {
            try {
                // Never echo the rejected value; it may be a token or a path.
                stages.put("parameter_validation", blocked("QA_ARGUMENTS_REJECTED"));
                notTested.put("device_execution_parameter_rejected");
                outcome = "REJECTED";
            } catch (JSONException ignored) {
                outcome = "REJECTED";
            }
            return false;
        }
    }

    private boolean preflight() {
        try {
            AvfHiddenApiExemption.State exemption = AvfHiddenApiExemption.installOnce();
            stages.put("hidden_api_exemption",
                    exemption == AvfHiddenApiExemption.State.INSTALLED
                            ? passed("EXEMPTION_INSTALLED")
                            : failed("EXEMPTION_FAILED", exemption.name()));

            ReflectiveAvfPlatformAccess access = new ReflectiveAvfPlatformAccess(
                    target, target.getClassLoader(), AvfHiddenApiExemption::installOnce,
                    romBinding());
            AvfPlatformFacts facts = access.observe();
            qualification = new JSONObject();
            qualification.put("access_state", facts.accessState().name());
            qualification.put("exemption_state", facts.exemptionState().name());
            qualification.put("manager_present", facts.managerPresent());
            qualification.put("manage_permission", facts.managePermission().name());
            qualification.put("custom_permission", facts.customPermission().name());
            qualification.put("protected_support",
                    facts.modeFacts().protectedVm().name());
            qualification.put("non_protected_support",
                    facts.modeFacts().nonProtectedVm().name());
            qualification.put("capabilities_raw", readCapabilities());
            qualification.put("protected_bit", hasProtectedBit());
            stages.put("platform_facts", passed("PLATFORM_OBSERVED"));
            stages.put("protected_bit",
                    hasProtectedBit() ? passed("PROTECTED_BIT_PRESENT")
                            : failed("PROTECTED_BIT_MISSING"));
            stages.put("set_protected_vm_true", protectedModeProbe(true));
            JSONObject falseProbe = protectedModeProbe(false);
            stages.put("set_protected_vm_false", falseProbe);

            if (facts.accessState() == AvfPlatformFacts.AccessState.HIDDEN_API_BLOCKED) {
                return failPreflight("HIDDEN_API_BLOCKED", "platform_facts");
            }
            if (facts.accessState() == AvfPlatformFacts.AccessState.PERMISSION_DENIED) {
                return failPreflight("PERMISSION_DENIED", "platform_facts");
            }
            if (exemption != AvfHiddenApiExemption.State.INSTALLED) {
                return failPreflight("HIDDEN_API_BLOCKED", "hidden_api_exemption");
            }
            if (facts.managePermission() != AvfCapabilityEvaluator.PermissionState.GRANTED) {
                return failPreflight("PERMISSION_DENIED", "platform_facts");
            }
            if (!"accepted".equals(((JSONObject) stages.get("set_protected_vm_true"))
                    .optString("result"))) {
                return failPreflight("PROTECTED_UNAVAILABLE", "set_protected_vm_true");
            }
            if (manager == null || readCapabilities() < 0
                    || facts.modeFacts().protectedVm()
                            != AvfCapabilityEvaluator.VmSupport.SUPPORTED) {
                return failPreflight("PROTECTED_UNAVAILABLE", "platform_facts");
            }
            if (!hasProtectedBit()) {
                return failPreflight("PROTECTED_UNAVAILABLE", "protected_bit");
            }
            if (!integrityCheck()) {
                return false;
            }
            return true;
        } catch (JSONException failure) {
            try {
                stages.put("platform_facts", failed("PLATFORM_FACTS_FAILED",
                        exceptionName(failure)));
                observeFailure("HIDDEN_API_BLOCKED", "platform_facts", "reflection");
            } catch (JSONException ignored) {
                // The report builder is best effort from here.
            }
            outcome = "FAILED";
            return false;
        }
    }

    private boolean failPreflight(String code, String stage) throws JSONException {
        observeFailure(code, stage, "preflight");
        outcome = "PERMISSION_DENIED".equals(code) || "PROTECTED_UNAVAILABLE".equals(code)
                ? "BLOCKED_EXTERNAL" : "FAILED";
        notTested.put("device_lifecycle");
        notTested.put("dv02_terminal_ui");
        notTested.put("proxy_distcc_export_import");
        stages.put("create", blocked("PREFLIGHT_BLOCKED"));
        stages.put("run", blocked("PREFLIGHT_BLOCKED"));
        stages.put("payload_ready", blocked("PREFLIGHT_BLOCKED"));
        stages.put("control_hello", blocked("PREFLIGHT_BLOCKED"));
        stages.put("terminal_pty", blocked("PREFLIGHT_BLOCKED"));
        stages.put("storage_marker", blocked("PREFLIGHT_BLOCKED"));
        stages.put("storage_persistence", blocked("PREFLIGHT_BLOCKED"));
        stages.put("stop_first", blocked("PREFLIGHT_BLOCKED"));
        stages.put("cycles", blocked("PREFLIGHT_BLOCKED"));
        return false;
    }

    private boolean integrityCheck() throws JSONException {
        File apk = apkFile();
        if (apk == null || !apk.isFile()) {
            stages.put("apk_payload_integrity", failed("APK_NOT_READABLE"));
            observeFailure("PAYLOAD_VERIFY_FAILED", "apk_payload_integrity", "apk_read");
            outcome = "FAILED";
            return false;
        }
        apkSha = sha256File(apk);
        if (!apkSha.matches("[0-9a-f]{64}")) {
            stages.put("apk_payload_integrity", failed("APK_NOT_READABLE"));
            observeFailure("PAYLOAD_VERIFY_FAILED", "apk_payload_integrity", "apk_read");
            outcome = "FAILED";
            return false;
        }
        payloadSha = sha256ZipEntry(apk, PAYLOAD_ENTRY);
        String actualToolboxSha = sha256ZipEntry(apk, TOOLBOX_ENTRY);
        String shellMode = zipEntryExists(apk, SHELL_ENTRY) ? "present" : "missing";
        if (payloadSha == null) {
            stages.put("apk_payload_integrity", failed("PAYLOAD_ENTRY_MISSING"));
            observeFailure("PAYLOAD_VERIFY_FAILED", "apk_payload_integrity", "apk_entry");
            outcome = "FAILED";
            return false;
        }
        if (actualToolboxSha == null || !actualToolboxSha.equals(toolboxSha)) {
            stages.put("apk_payload_integrity", failed("TOOLBOX_SHA_MISMATCH"));
            observeFailure("TOOLBOX_VERIFY_FAILED", "apk_payload_integrity", "toolbox_manifest");
            outcome = "FAILED";
            return false;
        }
        if (!"present".equals(shellMode)) {
            stages.put("apk_payload_integrity", failed("TOOLBOX_SHELL_MISSING"));
            observeFailure("TOOLBOX_VERIFY_FAILED", "apk_payload_integrity", "bin_sh");
            outcome = "FAILED";
            return false;
        }
        if (expectedPayloadSha != null && !expectedPayloadSha.equals(payloadSha)) {
            stages.put("apk_payload_integrity", failed("PAYLOAD_SHA_MISMATCH"));
            observeFailure("PAYLOAD_VERIFY_FAILED", "apk_payload_integrity", "expected_payload_sha");
            outcome = "FAILED";
            return false;
        }
        if (expectedApkSha != null && !expectedApkSha.equals(apkSha)) {
            stages.put("apk_payload_integrity", failed("APK_SHA_MISMATCH"));
            observeFailure("PAYLOAD_VERIFY_FAILED", "apk_payload_integrity", "expected_apk_sha");
            outcome = "FAILED";
            return false;
        }
        stages.put("apk_payload_integrity", passed("APK_PAYLOAD_SHA_MATCH"));
        return true;
    }

    private void negativePreflightCases() {
        try {
            JSONObject custom = new JSONObject();
            custom.put("case", "custom_image_config");
            custom.put("layer", "config_builder");
            try {
                Class<?> imageBuilderClass = Class.forName(
                        "android.system.virtualmachine.VirtualMachineCustomImageConfig$Builder");
                Object imageBuilder = imageBuilderClass.getConstructor().newInstance();
                File placeholder = new File(target.getFilesDir(),
                        "avf-qa/dv01/not-present-custom-artifact");
                imageBuilderClass.getMethod("setKernelPath", String.class)
                        .invoke(imageBuilder, placeholder.getAbsolutePath());
                imageBuilderClass.getMethod("setInitrdPath", String.class)
                        .invoke(imageBuilder, placeholder.getAbsolutePath());
                Object image = imageBuilderClass.getMethod("build").invoke(imageBuilder);
                Class<?> configBuilderClass = Class.forName(
                        "android.system.virtualmachine.VirtualMachineConfig$Builder");
                Object builder = configBuilderClass.getConstructor(Context.class)
                        .newInstance(target);
                configBuilderClass.getMethod("setCustomImageConfig",
                                Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig"))
                        .invoke(builder, image);
                configBuilderClass.getMethod("setProtectedVm", boolean.class)
                        .invoke(builder, true);
                configBuilderClass.getMethod("build").invoke(builder);
                custom.put("result", "accepted");
                notTested.put("custom_image_create_layer");
            } catch (InvocationTargetException wrapped) {
                custom.put("result", "rejected");
                custom.put("exception", exceptionName(wrapped.getCause()));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                custom.put("result", "rejected");
                custom.put("exception", exceptionName(failure));
            }
            negativeCases.put(custom);
        } catch (JSONException ignored) {
            // Negative probes never block the positive lifecycle.
        }
    }

    private void positiveLifecycle() throws JSONException {
        api = new ReflectiveFastLaneApi(target, target.getClassLoader());
        openManager();
        FastLaneLaunchSpec mainSpec = spec(instanceMain);
        SessionRun first = launch(mainSpec, "first");
        if (first == null) {
            outcome = "FAILED";
            return;
        }
        ControlResult control = controlHandshake(first.session, mainSpec, vsockTimeoutMs);
        stages.put("control_hello", control.ok
                ? passed("HELLO_HELLO_OK", "caps="
                        + (control.hello == null ? "unknown" : control.hello.caps())
                        + " elapsed_ms=" + control.elapsedMs)
                : failed(control.failureCode, control.errorCode >= 0
                        ? "fln1_error" : control.layer + " " + controlDiagnostic));
        if (!control.ok) {
            observeFailure("VSOCK_FAILED", "control_hello", control.layer);
        }
        TerminalResult terminal = runTerminal(first.session, mainSpec, WRITE_SCRIPT,
                vsockTimeoutMs);
        stages.put("terminal_pty", terminal.ok
                ? passed("TERMINAL_EXIT_0", "output_bytes=" + terminal.outputBytes)
                : failed(terminal.failureCode, terminalDiagnostic));
        terminalStatus = terminal.ok ? "pass" : "fail";
        if (terminal.ok) {
            stages.put("storage_marker", passed("MARKER_WRITTEN_AND_READ"));
        } else {
            stages.put("storage_marker", blocked("TERMINAL_NOT_CONFIRMED"));
        }

        StopOutcome firstStop = stop(first.session, "stop_first", 0);
        boolean firstConfirmed = firstStop.result == FastLaneStopResult.CONFIRMED;
        boolean firstSettled = firstStop.result != FastLaneStopResult.UNCONFIRMED;
        if (!firstConfirmed) {
            observeFailure(firstStop.result == FastLaneStopResult.UNCONFIRMED
                    ? "STOP_UNCONFIRMED" : "STOP_ALREADY_STOPPED", "stop_first", "stop");
        }

        SessionRun second = null;
        boolean persistence = false;
        boolean secondConfirmed = false;
        if (firstConfirmed) {
            second = launch(mainSpec, "second");
            if (second != null) {
                ControlResult secondControl =
                        controlHandshake(second.session, mainSpec, vsockTimeoutMs);
                stages.put("second_control_hello", secondControl.ok
                        ? passed("HELLO_HELLO_OK") : failed(secondControl.failureCode));                TerminalResult persisted = runTerminal(second.session, mainSpec, READ_SCRIPT,
                        vsockTimeoutMs);
                persistence = persisted.ok;
                stages.put("storage_persistence", persistence
                        ? passed("MARKER_SURVIVED_STOP_START")
                        : failed(persisted.failureCode));
                persistenceStatus = persistence ? "pass" : "fail";
                StopOutcome secondStop = stop(second.session, "stop_second", 0);
                secondConfirmed = secondStop.result == FastLaneStopResult.CONFIRMED;
                if (!secondConfirmed) {
                    observeFailure(secondStop.result == FastLaneStopResult.UNCONFIRMED
                            ? "STOP_UNCONFIRMED" : "STOP_ALREADY_STOPPED",
                            "stop_second", "stop");
                }
            }
        } else {
            stages.put("storage_persistence", blocked("STOP_NOT_CONFIRMED"));
            persistenceStatus = "not-tested";
        }

        if (second != null && secondConfirmed) {
            deleteAndResidual(instanceMain, null);
        } else if (second == null && firstSettled) {
            deleteAndResidual(instanceMain, null);
        } else if (second != null && !secondConfirmed) {
            notTested.put("vm_delete_skipped_after_unconfirmed_stop");
        }

        corePass = firstConfirmed && control.ok && secondConfirmed && payloadReadyObserved;
        if (!firstConfirmed) {
            outcome = "FAILED";
        } else if (!control.ok) {
            outcome = "FAILED";
        } else if (!terminal.ok) {
            outcome = "DV01_CORE_PASS_TERMINAL_BLOCKED";
        } else if (!persistence) {
            outcome = "FAILED";
        } else if (!secondConfirmed) {
            outcome = "FAILED";
        } else {
            outcome = "PASS";
        }
        if (!persistence && firstConfirmed && terminal.ok) {
            notTested.put("storage_persistence_confirmation");
        }
    }

    private void runCycles() throws JSONException {
        if (api == null) {
            stages.put("cycles", blocked("API_NOT_READY"));
            return;
        }
        int completed = 0;
        boolean abort = false;
        for (int cycle = 1; cycle <= cycleCount; cycle++) {
            JSONObject entry = new JSONObject();
            entry.put("cycle", cycle);
            String name = FastLaneDv01Contract.instanceId(token,
                    cycle < 10 ? "c0" + cycle : "c" + cycle);
            FastLaneLaunchSpec cycleSpec = spec(name);
            long started = SystemClock.uptimeMillis();
            FastLaneSession session = null;
            try {
                session = new FastLaneSession(api, api.start(cycleSpec), cycleSpec);
                Wait ready = waitForPayloadReady(session,
                        Math.min(payloadReadyDeadlineMs, 30000));
                entry.put("payload_ready", ready.observed);
                ControlResult control = controlHandshake(session, cycleSpec,
                        Math.min(vsockTimeoutMs, CYCLE_VSOCK_TIMEOUT_MS));
                entry.put("control", control.ok ? "pass" : "fail");
                if (!control.ok) {
                    observeFailure("VSOCK_FAILED", "cycle_" + cycle, control.layer);
                }
                long stopStarted = SystemClock.uptimeMillis();
                FastLaneStopResult stopResult = session.stopRuntime(stopDeadlineMs);
                session.close();
                long stopMs = SystemClock.uptimeMillis() - stopStarted;
                entry.put("stop", stopResult.name());
                entry.put("stop_ms", stopMs);
                stopTimings.put(new JSONObject()
                        .put("cycle", cycle)
                        .put("stop_ms", stopMs)
                        .put("result", stopResult.name()));
                if (stopResult == FastLaneStopResult.UNCONFIRMED) {
                    entry.put("failure_code", "STOP_UNCONFIRMED");
                    observeFailure("STOP_UNCONFIRMED", "cycle_" + cycle, "stop");
                    abort = true;
                } else {
                    deleteAndResidual(name, entry);
                }
                entry.put("cycle_ms", SystemClock.uptimeMillis() - started);
                boolean noResidual = Boolean.FALSE.equals(entry.opt("residual"));
                boolean cycleOk = control.ok
                        && stopResult == FastLaneStopResult.CONFIRMED
                        && noResidual;
                entry.put("status", cycleOk ? "pass" : "fail");
                if (cycleOk) {
                    completed++;
                }
            } catch (FastLaneException failure) {
                entry.put("status", "fail");
                entry.put("failure_code", FastLaneDv01Contract.classifyFailure(failure.reason()));
                entry.put("layer", "start");
                observeFailure(entry.optString("failure_code"), "cycle_" + cycle, "start");
                abort = true;
            } catch (Throwable failure) {
                entry.put("status", "fail");
                entry.put("failure_code", "CYCLE_EXCEPTION");
                entry.put("exception", exceptionName(failure));
                abort = true;
            } finally {
                if (session != null) {
                    session.close();
                }
            }
            cycles.put(entry);
            if (abort) {
                break;
            }
        }
        cyclesPass = completed == cycleCount
                && cycleCount == FastLaneDv01Contract.DEFAULT_CYCLE_COUNT;
        stages.put("cycles", cyclesPass
                ? passed("CYCLE_TABLE_COMPLETE")
                : failed("CYCLE_TABLE_INCOMPLETE", "completed=" + completed));
        if (!cyclesPass) {
            outcome = "FAILED";
            notTested.put("remaining_create_run_stop_cycles");
        } else if (!payloadReadyObserved && "PASS".equals(outcome)) {
            // Control HELLO_OK is strong readiness evidence, but DV-01's
            // acceptance also requires the typed payloadReady() observation.
            observeFailure("PAYLOAD_READY_NOT_OBSERVED", "payload_ready", "callback");
            outcome = "FAILED";
        } else if ("PASS".equals(outcome)) {
            outcome = "PASS";
        }
    }

    private SessionRun launch(FastLaneLaunchSpec spec, String prefix) throws JSONException {
        try {
            FastLaneHandle handle = api.start(spec);
            FastLaneSession session = new FastLaneSession(api, handle, spec);
            stages.put(prefix + "_create", passed("VM_CREATED"));
            stages.put(prefix + "_run", passed("RUN_RETURNED"));
            if ("first".equals(prefix)) {
                stages.put("create", stages.get(prefix + "_create"));
                stages.put("run", stages.get(prefix + "_run"));
            }
            Wait ready = waitForPayloadReady(session, payloadReadyDeadlineMs);
            stages.put("payload_ready", ready.observed
                    ? passed("PAYLOAD_READY_OBSERVED", "elapsed_ms=" + ready.elapsedMs)
                    : blocked("PAYLOAD_READY_NOT_OBSERVED", "elapsed_ms=" + ready.elapsedMs));
            if (!ready.observed) {
                payloadReadyObserved = false;
                payloadReadyStatus = "not-observed";
                notTested.put("payload_ready_callback");
            } else if ("not-tested".equals(payloadReadyStatus)) {
                payloadReadyStatus = "pass";
            }
            return new SessionRun(session, handle);
        } catch (FastLaneException failure) {
            String diagnostic = diagnostic(failure);
            stages.put(prefix + "_create", failed("CREATE_FAILED", diagnostic));
            stages.put(prefix + "_run", blocked("CREATE_BLOCKED"));
            stages.put("payload_ready", blocked("CREATE_BLOCKED"));
            if ("first".equals(prefix)) {
                stages.put("create", stages.get(prefix + "_create"));
                stages.put("run", stages.get(prefix + "_run"));
            }
            observeFailure(FastLaneDv01Contract.classifyFailure(failure.reason()),
                    prefix + "_create", "start");
            if (FastLaneException.PERMISSION_DENIED.equals(failure.reason())
                    || FastLaneException.PROTECTED_UNAVAILABLE.equals(failure.reason())) {
                outcome = "BLOCKED_EXTERNAL";
            } else {
                outcome = "FAILED";
            }
            return null;
        } catch (Throwable failure) {
            stages.put(prefix + "_create", failed("CREATE_FAILED", exceptionName(failure)));
            stages.put(prefix + "_run", blocked("CREATE_BLOCKED"));
            stages.put("payload_ready", blocked("CREATE_BLOCKED"));
            if ("first".equals(prefix)) {
                stages.put("create", stages.get(prefix + "_create"));
                stages.put("run", stages.get(prefix + "_run"));
            }
            outcome = "FAILED";
            return null;
        }
    }

    private Wait waitForPayloadReady(FastLaneSession session, int timeoutMs) {
        long started = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - started < timeoutMs) {
            if (session.payloadReady()) {
                return new Wait(true, SystemClock.uptimeMillis() - started);
            }
            SystemClock.sleep(200);
        }
        return new Wait(false, SystemClock.uptimeMillis() - started);
    }

    private ControlResult controlHandshake(FastLaneSession session, FastLaneLaunchSpec spec,
            int timeoutMs) {
        long started = SystemClock.uptimeMillis();
        long deadline = started + timeoutMs;
        String lastFailure = "VSOCK_FAILED";
        String lastLayer = "connect";
        while (SystemClock.uptimeMillis() < deadline) {
            FastLaneChannel channel = null;
            ExecutorService executor = newDaemonExecutor();
            Future<ControlRead> future = null;
            try {
                channel = openChannelBounded(session, spec.controlPort(), 5000);
                final InputStream input = channel.inputStream();
                OutputStream output = channel.outputStream();
                Fln1TerminalCodec codec = new Fln1TerminalCodec(TerminalMux.Role.HOST);
                codec.write(output, TerminalFrame.of(TerminalFrameType.HELLO, 0,
                        Fln1Payloads.encodeHello(new Fln1Payloads.Hello(1, 1, ""))));
                output.flush();
                final Fln1TerminalCodec reader = codec;
                future = executor.submit((Callable<ControlRead>) () -> {
                    TerminalFrame frame = reader.read(input);
                    if (frame == null) {
                        return ControlRead.failure("VSOCK_EOF", -1);
                    }
                    if (frame.type() == TerminalFrameType.ERROR) {
                        Fln1Payloads.Error error = Fln1Payloads.parseError(frame.payload());
                        return ControlRead.failure("VSOCK_PROTOCOL_ERROR", error.code());
                    }
                    Fln1Payloads.HelloOk hello = Fln1Payloads.parseHelloOk(frame.payload());
                    return ControlRead.success(hello);
                });
                long remaining = Math.max(1000L, deadline - SystemClock.uptimeMillis());
                ControlRead read = future.get(Math.min(remaining, 5000L), TimeUnit.MILLISECONDS);
                if (read.ok) {
                    return new ControlResult(true, null, -1, read.hello,
                            SystemClock.uptimeMillis() - started, "control");
                }
                lastFailure = read.failureCode;
                lastLayer = "hello";
                if ("VSOCK_PROTOCOL_ERROR".equals(read.failureCode)) {
                    return new ControlResult(false, read.failureCode, read.errorCode, null,
                            SystemClock.uptimeMillis() - started, "hello");
                }
            } catch (TimeoutException timeout) {
                lastFailure = "VSOCK_TIMEOUT";
                lastLayer = "hello_timeout";
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new ControlResult(false, "VSOCK_INTERRUPTED", -1, null,
                        SystemClock.uptimeMillis() - started, "interrupted");
            } catch (ExecutionException failure) {
                lastFailure = "VSOCK_FAILED";
                lastLayer = "hello_read";
            } catch (FastLaneException failure) {
                lastFailure = failure.reason();
                lastLayer = "connect";
                controlDiagnostic = diagnostic(failure);
                if (FastLaneException.PERMISSION_DENIED.equals(failure.reason())
                        || FastLaneException.PROTECTED_UNAVAILABLE.equals(failure.reason())) {
                    return new ControlResult(false, lastFailure, -1, null,
                            SystemClock.uptimeMillis() - started, lastLayer);
                }
            } catch (IOException failure) {
                lastFailure = "VSOCK_FAILED";
                lastLayer = "io";
            } finally {
                if (future != null) {
                    future.cancel(true);
                }
                closeQuietly(channel);
                executor.shutdownNow();
            }
            SystemClock.sleep(500);
        }
        return new ControlResult(false, lastFailure, -1, null,
                SystemClock.uptimeMillis() - started, lastLayer);
    }

    /**
     * Bounds the platform connect call. The binder call has no timeout of its
     * own; a stale VM can otherwise block the whole probe indefinitely.
     */
    private FastLaneChannel openChannelBounded(FastLaneSession session, int port, int timeoutMs)
            throws FastLaneException {
        ExecutorService executor = newDaemonExecutor();
        Future<FastLaneChannel> future = executor.submit(() -> session.openChannel(port));
        try {
            return future.get(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new FastLaneException(FastLaneException.VSOCK_FAILED,
                    "vsock connect timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FastLaneException(FastLaneException.VSOCK_FAILED,
                    "vsock connect interrupted");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof FastLaneException) {
                throw (FastLaneException) cause;
            }
            throw new FastLaneException(FastLaneException.VSOCK_FAILED,
                    "vsock connect failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private TerminalResult runTerminal(FastLaneSession session, FastLaneLaunchSpec spec,
            String script, int timeoutMs) {
        return runTerminal(session, spec, script, timeoutMs, false);
    }

    private TerminalResult runTerminal(FastLaneSession session, FastLaneLaunchSpec spec,
            String script, int timeoutMs, boolean markerOnly) {
        long started = SystemClock.uptimeMillis();
        FastLaneChannel channel = null;
        TerminalMuxChannel muxChannel = null;
        TerminalCollector collector = new TerminalCollector();
        Thread pump = null;
        try {
            channel = openChannelBounded(session, spec.terminalPort(),
                    Math.min(timeoutMs, 10000));
            muxChannel = TerminalMuxChannel.open(TerminalMux.Role.HOST, 1, collector,
                    channel.inputStream(), channel.outputStream());
            final TerminalMuxChannel pumping = muxChannel;
            pump = new Thread(() -> {
                try {
                    pumping.pumpAll();
                } catch (Throwable failure) {
                    collector.pumpFailure = exceptionName(failure);
                }
            }, "dv01-terminal");
            pump.setDaemon(true);
            pump.start();
            muxChannel.mux().sendHello(new Fln1Payloads.Hello(1, 1, ""));
            muxChannel.mux().openSession(80, 24, SHELL_PATH, "-c", script);
            boolean opened = collector.opened.await(timeoutMs, TimeUnit.MILLISECONDS);
            boolean closed = opened
                    && collector.closed.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!opened) {
                if (collector.fln1Code != null) {
                    int fln1 = collector.fln1Code;
                    terminalDiagnostic = "fln1=" + fln1 + " " + collector.fln1Detail;
                    observeTerminalFailure(fln1);
                    return new TerminalResult(false, "TERMINAL_ERROR_FLN1_" + fln1, -1,
                            collector.outputSize(), false, false, fln1, collector.fln1Detail);
                }
                if (collector.errorCode != null) {
                    terminalDiagnostic = "host_code=" + collector.errorCode.name();
                    return new TerminalResult(false,
                            "TERMINAL_ERROR_" + collector.errorCode.name(), -1,
                            collector.outputSize(), false, false);
                }
                return new TerminalResult(false, "TERMINAL_OPEN_TIMEOUT", -1,
                        collector.outputSize(), false, false);
            }
            if (!closed) {
                return new TerminalResult(false, "TERMINAL_EXIT_TIMEOUT", -1,
                        collector.outputSize(), false, false);
            }
            if (collector.errorCode != null) {
                return new TerminalResult(false, "TERMINAL_ERROR_" + collector.errorCode.name(),
                        -1, collector.outputSize(), false, false);
            }
            if (collector.pumpFailure != null) {
                return new TerminalResult(false, "TERMINAL_TRANSPORT", -1,
                        collector.outputSize(), false, false);
            }
            if (collector.exitCode == null || collector.exitCode != 0) {
                return new TerminalResult(false, "TERMINAL_EXIT_NONZERO",
                        collector.exitCode == null ? -1 : collector.exitCode,
                        collector.outputSize(), false, false);
            }
            String output = collector.output();
            boolean expected = markerOnly
                    ? output.contains(FastLaneDv01Contract.PREFLIGHT_TERMINAL_MARKER)
                    : script.startsWith("printf DV01_TERMINAL_OK")
                            ? output.contains(TERMINAL_OK) && output.contains(MARKER_VALUE)
                            : output.contains(PERSIST_OK) && output.contains(MARKER_VALUE);
            if (!expected) {
                return new TerminalResult(false, "TERMINAL_OUTPUT_MISMATCH", 0,
                        collector.outputSize(), false, false);
            }
            return new TerminalResult(true, "TERMINAL_EXIT_0", 0,
                    collector.outputSize(), true, !markerOnly);
        } catch (FastLaneException failure) {
            terminalDiagnostic = diagnostic(failure);
            observeFailure(FastLaneDv01Contract.classifyFailure(failure.reason()),
                    "terminal_pty", "connect");
            return new TerminalResult(false, "TERMINAL_CONNECT_FAILED", -1, 0, false, false);
        } catch (IOException failure) {
            return new TerminalResult(false, "TERMINAL_IO", -1, 0, false, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new TerminalResult(false, "TERMINAL_INTERRUPTED", -1, 0, false, false);
        } finally {
            if (muxChannel != null) {
                final TerminalMuxChannel closing = muxChannel;
                Thread closer = new Thread(() -> {
                    try {
                        closing.close();
                    } catch (IOException ignored) {
                        // The channel is already gone.
                    }
                }, "dv01-terminal-close");
                closer.setDaemon(true);
                closer.start();
                try {
                    closer.join(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            closeQuietly(channel);
            if (pump != null) {
                try {
                    pump.join(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private StopOutcome stop(FastLaneSession session, String stage, int cycle)
            throws JSONException {        long started = SystemClock.uptimeMillis();
        FastLaneStopResult result;
        try {
            result = session.stopRuntime(stopDeadlineMs);
        } catch (Throwable failure) {
            result = FastLaneStopResult.UNCONFIRMED;
        }
        long elapsed = SystemClock.uptimeMillis() - started;
        session.close();
        stages.put(stage, result == FastLaneStopResult.CONFIRMED
                ? passed("STOP_CONFIRMED")
                : result == FastLaneStopResult.ALREADY_STOPPED
                        ? blocked("STOP_ALREADY_STOPPED")
                        : failed("STOP_UNCONFIRMED"));
        JSONObject timing = new JSONObject();
        timing.put("cycle", cycle);
        timing.put("stop_ms", elapsed);
        timing.put("result", result.name());
        stopTimings.put(timing);
        return new StopOutcome(result, elapsed);
    }

    private void deleteAndResidual(String instanceId, JSONObject entry) throws JSONException {
        if (manager == null || managerClass == null) {
            if (entry != null) {
                entry.put("delete", "not-tested");
                entry.put("residual", "unreadable");
            }
            notTested.put("vm_residual_listing");
            return;
        }
        try {
            managerClass.getMethod("delete", String.class).invoke(manager, instanceId);
            if (entry != null) {
                entry.put("delete", "pass");
            }
        } catch (Throwable failure) {
            if (entry != null) {
                entry.put("delete", "fail");
                entry.put("delete_exception", exceptionName(unwrap(failure)));
            }
        }
        try {
            Object residual = managerClass.getMethod("get", String.class)
                    .invoke(manager, instanceId);
            if (entry != null) {
                entry.put("residual", residual != null);
            }
        } catch (InvocationTargetException wrapped) {
            // A not-found VirtualMachineException is the expected absence.
            if (entry != null) {
                entry.put("residual", false);
                entry.put("residual_check", exceptionName(wrapped.getCause()));
            }
        } catch (Throwable failure) {
            if (entry != null) {
                entry.put("residual", "unreadable");
                entry.put("residual_check", exceptionName(unwrap(failure)));
            }
            notTested.put("vm_residual_listing");
        }
    }

    private void wrongPortCase() throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("case", "wrong_vsock_port");
        entry.put("layer", "FastLaneLaunchSpec");
        try {
            FastLaneLaunchSpec badSpec = spec(FastLaneDv01Contract.instanceId(token, "port"));
            try {
                new FastLaneLaunchSpec(
                        badSpec.instanceId(), badSpec.payloadBinaryName(),
                        badSpec.memoryMb(), badSpec.cpuTopology(),
                        badSpec.encryptedStorageBytes(), badSpec.debugLevel(),
                        badSpec.controlPort(), badSpec.controlPort(),
                        badSpec.proxyPort(), badSpec.distccPort(),
                        badSpec.toolboxManifestSha256());
                entry.put("result", "unexpected_accept");
                entry.put("failure_code", "CONFIG_INVALID");
                observeFailure("PORT_GUARD_BYPASSED", "wrong_vsock_port", "host_validation");
                outcome = "FAILED";
            } catch (IllegalArgumentException expected) {
                entry.put("result", "rejected");
                entry.put("failure_code", "CONFIG_INVALID");
            }
        } catch (IllegalArgumentException rejected) {
            entry.put("result", "rejected");
            entry.put("failure_code", "CONFIG_INVALID");
        }
        negativeCases.put(entry);
    }

    private void wrongPayloadCase() throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("case", "wrong_payload_name");
        entry.put("layer", "platform_payload_verify");
        String name = FastLaneDv01Contract.instanceId(token, "wrong");
        FastLaneLaunchSpec wrong = new FastLaneLaunchSpec(
                name, "libmissing_fastlane_payload.so",
                FastLaneDv01Contract.MEMORY_MB, FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                FastLaneDv01Contract.ENCRYPTED_STORAGE_BYTES,
                FastLaneLaunchSpec.DebugLevel.NONE,
                controlPort, terminalPort, proxyPort, distccPort, toolboxSha);
        FastLaneSession session = null;
        try {
            FastLaneHandle handle = api.start(wrong);
            session = new FastLaneSession(api, handle, wrong);
            Wait ready = waitForPayloadReady(session, NEGATIVE_VSOCK_TIMEOUT_MS);
            if (ready.observed) {
                entry.put("result", "unexpected_ready");
                entry.put("failure_code", "PAYLOAD_VERIFY_FAILED");
                observeFailure("PAYLOAD_VERIFY_FAILED", "wrong_payload", "unexpected_ready");
                outcome = "FAILED";
            } else {
                String reason = waitForExitReason(handle, NEGATIVE_VSOCK_TIMEOUT_MS);
                entry.put("result", "unconfirmed");
                entry.put("failure_code", reason);
                if ("PAYLOAD_VERIFY_FAILED".equals(reason)
                        || "VM_CREATE_FAILED".equals(reason)) {
                    entry.put("result", "rejected");
                    entry.put("platform_reason", reason);
                    observeFailure("PAYLOAD_VERIFY_FAILED", "wrong_payload", "platform_start");
                } else {
                    // A missing payload that neither becomes ready nor yields a
                    // typed rejection is an unconfirmed negative, not proof of
                    // a pass. Keep the lifecycle outcome visible.
                    observeFailure("NEGATIVE_CASE_UNCONFIRMED", "wrong_payload", "payload_verify");
                    notTested.put("wrong_payload_rejection");
                }
            }
        } catch (FastLaneException failure) {
            String platformReason = FastLaneDv01Contract.classifyFailure(failure.reason());
            entry.put("result", "rejected");
            entry.put("failure_code", "PAYLOAD_VERIFY_FAILED");
            entry.put("platform_reason", platformReason);
            observeFailure("PAYLOAD_VERIFY_FAILED", "wrong_payload", "platform_start");
        } catch (Throwable failure) {
            entry.put("result", "rejected");
            entry.put("failure_code", "PAYLOAD_VERIFY_FAILED");
            entry.put("exception", exceptionName(failure));
            observeFailure("PAYLOAD_VERIFY_FAILED", "wrong_payload", "platform_start");
        } finally {
            boolean settled = session == null;
            if (session != null) {
                FastLaneStopResult stopResult = session.stopRuntime(stopDeadlineMs);
                session.close();
                settled = stopResult != FastLaneStopResult.UNCONFIRMED;
                if (!settled) {
                    observeFailure("STOP_UNCONFIRMED", "wrong_payload", "stop");
                    notTested.put("wrong_payload_vm_delete_skipped");
                }
            }
            if (settled) {
                deleteAndResidual(name, null);
            }
        }
        negativeCases.put(entry);
    }

    private String waitForExitReason(FastLaneHandle handle, int timeoutMs) {
        ExecutorService executor = newDaemonExecutor();
        Future<String> future = executor.submit(() -> {
            try {
                handle.waitForExit();
                return "FASTLANE_UNKNOWN";
            } catch (FastLaneException failure) {
                return FastLaneDv01Contract.classifyFailure(failure.reason());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return "PAYLOAD_EXIT_INTERRUPTED";
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return "PAYLOAD_VERIFY_NOT_OBSERVED";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "PAYLOAD_VERIFY_INTERRUPTED";
        } catch (ExecutionException failure) {
            return "PAYLOAD_VERIFY_FAILED";
        } finally {
            executor.shutdownNow();
        }
    }

    private JSONObject protectedModeProbe(boolean value) throws JSONException {
        JSONObject probe = new JSONObject();
        probe.put("value", value);
        try {
            Class<?> builderClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachineConfig$Builder");
            Object builder = builderClass.getConstructor(Context.class).newInstance(target);
            builderClass.getMethod("setProtectedVm", boolean.class).invoke(builder, value);
            probe.put("result", "accepted");
        } catch (InvocationTargetException wrapped) {
            probe.put("result", "rejected");
            probe.put("exception", exceptionName(wrapped.getCause()));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            probe.put("result", "rejected");
            probe.put("exception", exceptionName(failure));
        }
        return probe;
    }

    private FastLaneLaunchSpec spec(String instanceId) {
        return new FastLaneLaunchSpec(
                instanceId,
                FastLaneDv01Contract.PAYLOAD_NAME,
                FastLaneDv01Contract.MEMORY_MB,
                FastLaneLaunchSpec.CpuTopology.ONE_CPU,
                FastLaneDv01Contract.ENCRYPTED_STORAGE_BYTES,
                FastLaneLaunchSpec.DebugLevel.NONE,
                controlPort,
                terminalPort,
                proxyPort,
                distccPort,
                toolboxSha);
    }

    private void openManager() {
        try {
            managerClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachineManager");
            Object candidate = target.getSystemService(managerClass);
            manager = managerClass.isInstance(candidate) ? candidate : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            manager = null;
            managerClass = null;
        }
    }

    private int readCapabilities() {
        try {
            if (managerClass == null) {
                openManager();
            }
            if (managerClass == null || manager == null) {
                return -1;
            }
            Object value = managerClass.getMethod("getCapabilities").invoke(manager);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            return -1;
        }
    }

    private boolean hasProtectedBit() {
        int capabilities = readCapabilities();
        return capabilities >= 0 && (capabilities & CAPABILITY_PROTECTED_VM) != 0;
    }

    private JSONObject finishReport() {
        try {
            JSONObject report = new JSONObject();
            report.put("schema", FastLaneDv01Contract.SCHEMA);
            report.put("device_alias", deviceAlias);
            report.put("sdk_int", Build.VERSION.SDK_INT);
            report.put("build_id", safeToken(Build.ID));
            report.put("apk_sha256", apkSha);
            report.put("payload_sha256", payloadSha);
            report.put("toolbox_manifest_sha256", toolboxSha == null ? "not-tested" : toolboxSha);
            report.put("auth_token", FastLaneDv01Contract.AUTH_TOKEN_EMPTY);
            report.put("stage_results", stages);
            report.put("cycles", cycles);
            report.put("stop_timings_ms", stopTimings);
            report.put("failure_codes", failureCodes);
            report.put("negative_cases", negativeCases);
            report.put("not_tested", notTested);
            report.put("outcome", outcome);
            report.put("started_at_utc", instant(startedAtMs));
            report.put("finished_at_utc", instant(System.currentTimeMillis()));
            report.put("device_facts", deviceFacts());
            report.put("qualification", qualification == null ? new JSONObject() : qualification);
            report.put("payload_ready", payloadReadyStatus);
            report.put("terminal", terminalStatus);
            report.put("storage_persistence", persistenceStatus);
            report.put("vsock_control", controlDiagnostic);
            report.put("vsock_terminal", terminalDiagnostic);
            report.put("core_pass", corePass);
            report.put("cycles_pass", cyclesPass);

            Set<String> keys = new HashSet<>();
            java.util.Iterator<String> iterator = report.keys();
            while (iterator.hasNext()) {
                keys.add(iterator.next());
            }
            try {
                FastLaneDv01Contract.requireReportKeys(keys);
                stages.put("report_schema", passed("REPORT_KEYS_COMPLETE"));
            } catch (IllegalArgumentException incomplete) {
                stages.put("report_schema", failed("REPORT_KEYS_INCOMPLETE"));
                outcome = "FAILED";
                report.put("outcome", outcome);
            }
            return report;
        } catch (JSONException failure) {
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("schema", FastLaneDv01Contract.SCHEMA);
                fallback.put("outcome", "FAILED");
                fallback.put("failure_codes", new JSONObject()
                        .put("REPORT_JSON_FAILED", new JSONObject()
                                .put("status", "observed")));
            } catch (JSONException ignored) {
                // The report cannot be constructed at all.
            }
            return fallback;
        }
    }

    private JSONObject deviceFacts() throws JSONException {
        JSONObject facts = new JSONObject();
        facts.put("sdk_int", Build.VERSION.SDK_INT);
        JSONArray abis = new JSONArray();
        for (String abi : Build.SUPPORTED_ABIS) {
            abis.put(abi);
        }
        facts.put("abis", abis);
        facts.put("virtualization_framework", target.getPackageManager().hasSystemFeature(
                "android.software.virtualization_framework"));
        facts.put("build_id", safeToken(Build.ID));
        facts.put("release", safeToken(Build.VERSION.RELEASE));
        facts.put("fingerprint_sha256",
                sha256(Build.FINGERPRINT == null ? "unknown" : Build.FINGERPRINT));
        return facts;
    }

    private void prepareFailureCodes() throws JSONException {
        for (String code : REQUIRED_FAILURE_CODES) {
            failureCodes.put(code, new JSONObject().put("status", "not-tested"));
        }
    }

    private void observeFailure(String code, String stage, String layer) {
        if (code == null || code.isEmpty()) {
            code = "FASTLANE_UNKNOWN";
        }
        try {
            JSONObject entry = new JSONObject();
            entry.put("status", "observed");
            entry.put("stage", stage);
            entry.put("layer", layer == null ? "unknown" : layer);
            failureCodes.put(code, entry);
        } catch (JSONException ignored) {
            // A failed diagnostic write must not hide the lifecycle result.
        }
    }

    private void observeTerminalFailure(int fln1Code) {
        String code = switch (fln1Code) {
            case 7 -> "TOOLBOX_EXEC_FAILED";
            case 8, 9 -> "STORAGE_FAILED";
            default -> "TERMINAL_PROTOCOL_FAILED";
        };
        observeFailure(code, "terminal_pty", "fln1_" + fln1Code);
    }

    private String optionalSha(String key) throws JSONException {
        String value = arguments.getString(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String trimmed = value.trim();
        FastLaneDv01Contract.requireSha256(trimmed, key);
        return trimmed;
    }

    private int boundedInt(String value, int fallback, int minimum, int maximum) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
            throw new NumberFormatException("out of range");
        }
        return parsed;
    }

    private File apkFile() {
        try {
            String path = target.getApplicationInfo().publicSourceDir;
            if (path == null) {
                path = target.getApplicationInfo().sourceDir;
            }
            return path == null ? null : new File(path);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static String sha256ZipEntry(File apk, String entryName) {
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return sha256Stream(input);
            }
        } catch (IOException failure) {
            return null;
        }
    }

    private static boolean zipEntryExists(File apk, String entryName) {
        try (ZipFile zip = new ZipFile(apk)) {
            return zip.getEntry(entryName) != null;
        } catch (IOException failure) {
            return false;
        }
    }

    private static String sha256File(File file) {
        try (InputStream input = new FileInputStream(file)) {
            return sha256Stream(input);
        } catch (IOException failure) {
            return "not-tested";
        }
    }

    private static String sha256Stream(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "not-tested";
        }
    }

    private static String safeToken(String value) {
        if (value == null || !SAFE_TOKEN.matcher(value).matches()) {
            return "unknown";
        }
        return value;
    }

    private static String instant(long epochMs) {
        return Instant.ofEpochMilli(epochMs).toString();
    }

    private static String romBinding() {
        return safeToken(Build.ID) + "/" + safeToken(Build.VERSION.INCREMENTAL);
    }

    private static ExecutorService newDaemonExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dv01-blocking-read");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // The caller has already classified the failure.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof InvocationTargetException wrapped) {
            return wrapped.getCause() == null ? failure : wrapped.getCause();
        }
        return failure;
    }

    private static String exceptionName(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause == null) {
            return "Other";
        }
        String name = cause.getClass().getSimpleName();
        switch (name) {
            case "UnsupportedOperationException":
            case "SecurityException":
            case "NoSuchMethodException":
            case "ClassNotFoundException":
            case "NoClassDefFoundError":
            case "IllegalArgumentException":
            case "IllegalStateException":
            case "VirtualMachineException":
                return name;
            default:
                return "Other";
        }
    }

    /**
     * Stable, non-sensitive create failure detail: exception class and the
     * platform's integer error code, never the platform message or a path.
     */
    private static String diagnostic(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; depth < 4 && cause != null; depth++) {
            if (cause instanceof InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
                continue;
            }
            if (cause instanceof FastLaneException && cause.getCause() != null) {
                cause = cause.getCause();
                continue;
            }
            break;
        }
        String className = cause == null ? "none" : cause.getClass().getSimpleName();
        String messageClass = messageClass(cause);
        int code = -1;
        if (cause != null) {
            try {
                Object value = cause.getClass().getMethod("getErrorCode").invoke(cause);
                if (value instanceof Integer) {
                    code = (Integer) value;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Not every OEM VirtualMachineException exposes getErrorCode().
            }
        }
        return "cause=" + className + " code=" + code + " message_class=" + messageClass;
    }

    /** Classifies a platform message without storing the message itself. */
    private static String messageClass(Throwable cause) {
        if (cause == null || cause.getMessage() == null) {
            return "none";
        }
        String message = cause.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("verif") || message.contains("payload changed")) {
            return "payload_verification";
        }
        if (message.contains("vsock")) {
            return "vsock";
        }
        if (message.contains("connect")) {
            return "connect";
        }
        if (message.contains("payload")) {
            return "payload";
        }
        if (message.contains("signature") || message.contains("signing")) {
            return "signature";
        }
        if (message.contains("permission") || message.contains("denied")) {
            return "permission";
        }
        if (message.contains("config") || message.contains("invalid")) {
            return "config";
        }
        if (message.contains("hypervisor") || message.contains("kvm")
                || message.contains("pkvm") || message.contains("gunyah")) {
            return "hypervisor";
        }
        if (message.contains("secretkeeper")) {
            return "secretkeeper";
        }
        if (message.contains("memory") || message.contains("allocation")) {
            return "memory";
        }
        if (message.contains("unsupported") || message.contains("not supported")) {
            return "unsupported";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "timeout";
        }
        return "other";
    }

    private static JSONObject passed(String code) throws JSONException {
        return new JSONObject().put("status", "pass").put("reason_code", code);
    }

    private static JSONObject passed(String code, String detail) throws JSONException {
        return new JSONObject().put("status", "pass").put("reason_code", code)
                .put("detail", detail);
    }

    private static JSONObject failed(String code) throws JSONException {
        return new JSONObject().put("status", "fail").put("reason_code", code);
    }

    private static JSONObject failed(String code, String detail) throws JSONException {
        return new JSONObject().put("status", "fail").put("reason_code", code)
                .put("detail", detail);
    }

    private static JSONObject blocked(String code) throws JSONException {
        return new JSONObject().put("status", "blocked").put("reason_code", code);
    }

    private static JSONObject blocked(String code, String detail) throws JSONException {
        return new JSONObject().put("status", "blocked").put("reason_code", code)
                .put("detail", detail);
    }

    private static final class SessionRun {
        final FastLaneSession session;
        final FastLaneHandle handle;

        SessionRun(FastLaneSession session, FastLaneHandle handle) {
            this.session = session;
            this.handle = handle;
        }
    }

    private static final class Wait {
        final boolean observed;
        final long elapsedMs;

        Wait(boolean observed, long elapsedMs) {
            this.observed = observed;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class StopOutcome {
        final FastLaneStopResult result;
        final long elapsedMs;

        StopOutcome(FastLaneStopResult result, long elapsedMs) {
            this.result = result;
            this.elapsedMs = elapsedMs;
        }
    }

    private static final class ControlRead {
        final boolean ok;
        final String failureCode;
        final int errorCode;
        final Fln1Payloads.HelloOk hello;

        private ControlRead(boolean ok, String failureCode, int errorCode,
                Fln1Payloads.HelloOk hello) {
            this.ok = ok;
            this.failureCode = failureCode;
            this.errorCode = errorCode;
            this.hello = hello;
        }

        static ControlRead success(Fln1Payloads.HelloOk hello) {
            return new ControlRead(true, null, -1, hello);
        }

        static ControlRead failure(String code, int errorCode) {
            return new ControlRead(false, code, errorCode, null);
        }
    }

    private static final class ControlResult {
        final boolean ok;
        final String failureCode;
        final int errorCode;
        final Fln1Payloads.HelloOk hello;
        final long elapsedMs;
        final String layer;

        ControlResult(boolean ok, String failureCode, int errorCode,
                Fln1Payloads.HelloOk hello, long elapsedMs, String layer) {
            this.ok = ok;
            this.failureCode = failureCode;
            this.errorCode = errorCode;
            this.hello = hello;
            this.elapsedMs = elapsedMs;
            this.layer = layer;
        }
    }

    private static final class TerminalResult {
        final boolean ok;
        final String failureCode;
        final int exitCode;
        final int outputBytes;
        final boolean terminalMarker;
        final boolean storageMarker;
        final int fln1Code;
        final String detail;

        TerminalResult(boolean ok, String failureCode, int exitCode, int outputBytes,
                boolean terminalMarker, boolean storageMarker) {
            this(ok, failureCode, exitCode, outputBytes, terminalMarker, storageMarker, -1, null);
        }

        TerminalResult(boolean ok, String failureCode, int exitCode, int outputBytes,
                boolean terminalMarker, boolean storageMarker, int fln1Code, String detail) {
            this.ok = ok;
            this.failureCode = failureCode;
            this.exitCode = exitCode;
            this.outputBytes = outputBytes;
            this.terminalMarker = terminalMarker;
            this.storageMarker = storageMarker;
            this.fln1Code = fln1Code;
            this.detail = detail;
        }
    }

    private static final class TerminalCollector implements TerminalMux.Listener {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile Integer exitCode;
        private volatile TerminalErrorCode errorCode;
        private volatile Integer fln1Code;
        private volatile String fln1Detail = "unclassified";
        private volatile TerminalCloseReason closeReason;
        private volatile String pumpFailure;
        private volatile int bytes;

        @Override
        public void onSessionOpened(int sessionId) {
            opened.countDown();
        }

        @Override
        public void onData(int sessionId, byte[] data) {
            synchronized (output) {
                bytes += data.length;
                if (output.size() < MAX_TERMINAL_OUTPUT) {
                    int remaining = MAX_TERMINAL_OUTPUT - output.size();
                    output.write(data, 0, Math.min(remaining, data.length));
                }
            }
        }

        @Override
        public void onEof(int sessionId, int code) {
            exitCode = code;
        }

        @Override
        public void onSessionClosed(int sessionId, TerminalCloseReason reason) {
            closeReason = reason;
            closed.countDown();
        }

        @Override
        public void onError(int sessionId, TerminalErrorCode code, String message) {
            errorCode = code;
            if (message != null) {
                java.util.regex.Matcher matcher =
                        Pattern.compile("fln1=(\\d+)").matcher(message);
                if (matcher.find()) {
                    try {
                        fln1Code = Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException ignored) {
                        // The host code above remains the fallback.
                    }
                }
                fln1Detail = FastLaneDv01Contract.terminalDetail(message);
            }
        }

        String output() {
            synchronized (output) {
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        int outputSize() {
            return bytes;
        }
    }
}
