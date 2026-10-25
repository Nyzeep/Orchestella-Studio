package io.github.nyzeep.orchestella;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfPlatformAccess;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridge;

/**
 * QA-only DV-PVM-01/02 probe: run one protected Microdroid payload VM and hand
 * timings and failure codes to the host driver through an evidence file.
 *
 * <p>Two modes share the VM lifecycle, the callback plumbing and the evidence
 * writer, because AGP keeps only one {@code <instrumentation>} declaration in
 * the androidTest manifest:
 *
 * <ul>
 *   <li>{@code qaMode=probe} (default) — the DV-PVM-01 payloads: the guest
 *       streams {@code PVM_PROBE} observations over vsock and the instrumentation
 *       only owns create/run/stop/delete.</li>
 *   <li>{@code qaMode=compiler} — DV-PVM-02: the payload is the PVM-03 compile
 *       service. It is a server, so the instrumentation waits for
 *       {@code payloadReady}, bridges its vsock port onto a loopback TCP port
 *       with the production {@link DistccTcpVsockBridge} (the host reaches it
 *       through {@code adb forward}), serves N jobs, then stops the VM.</li>
 * </ul>
 *
 * <p>No device identifiers are recorded; the run token is reduced to its SHA-256.
 */
public final class PvmProbeInstrumentation extends Instrumentation {

    private static final int DEFAULT_MEMORY_MB = 2048;
    private static final int MIN_MEMORY_MB = 1024;
    private static final int MAX_MEMORY_MB = 8192;
    private static final long DEFAULT_READY_DEADLINE_MS = 180_000L;
    private static final long MAX_READY_DEADLINE_MS = 600_000L;
    private static final long DEFAULT_FINISH_DEADLINE_MS = 900_000L;
    private static final long MAX_FINISH_DEADLINE_MS = 1_800_000L;
    private static final long STOP_DEADLINE_MS = 60_000L;
    // Instrumentation result codes are opaque ints; the driver reads the
    // evidence JSON, the code only feeds `am instrument` exit reporting.
    private static final int RESULT_PROBE_PASSED = 0;
    private static final int RESULT_PROBE_FAILED = 1;
    private static final String HEX = "0123456789" + "abcdef";
    private static final String MODE_COMPILER = "compiler";
    // Matches FastLaneDv01Contract.DEFAULT_DISTCC_PORT and the payload default.
    private static final int DEFAULT_PAYLOAD_VSOC_PORT = 5103;
    private static final long DEFAULT_JOB_DEADLINE_MS = 300_000L;

    private Bundle arguments = Bundle.EMPTY;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? Bundle.EMPTY : arguments;
        start();
    }

    @Override
    public void onStart() {
        final Bundle results = new Bundle();
        JSONObject evidence;
        int outcome = RESULT_PROBE_FAILED;
        try {
            evidence = MODE_COMPILER.equals(arguments.getString("qaMode"))
                    ? runCompiler(results)
                    : runProbe(results);
            outcome = evidence.optBoolean("status_passed", false)
                    ? RESULT_PROBE_PASSED
                    : RESULT_PROBE_FAILED;
        } catch (Throwable unexpected) {
            final JSONObject fallback = new JSONObject();
            try {
                fallback.put("kind", "pvm_probe");
                fallback.put("status_passed", false);
                fallback.put("failure_code", "QA_INTERNAL_ERROR");
                fallback.put("notes", new org.json.JSONArray()
                        .put("unexpected: " + describe(unexpected)));
            } catch (JSONException ignored) {
                // Evidence stays minimal; the code also rides in the bundle.
            }
            results.putString("qa_failure_code", "QA_INTERNAL_ERROR");
            writeEvidence(fallback, results);
            evidence = fallback;
        }
        finish(outcome, results);
    }

    // ------------------------------------------------------------------
    // DV-PVM-02: the compile-service payload is a server, not a probe. The VM
    // is only useful while its vsock port is bridged, so this mode publishes
    // the bridge port in the evidence file, serves the requested job count and
    // then owns stop/delete exactly like probe mode.
    // ------------------------------------------------------------------

    private JSONObject runCompiler(Bundle results) throws Exception {
        final Context target = getTargetContext();
        final String token = arguments.getString("qaRunToken");
        if (token == null || !token.matches("[0-9a-f]{32}")) {
            // The payload accepts only a 32-char lowercase hex token; anything
            // else makes it exit before payloadReady.
            return failureEvidence(results, "QA_RUN_TOKEN_REJECTED");
        }
        final String payloadName = arguments.getString("qaPayloadName");
        if (payloadName == null || !payloadName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || !payloadName.endsWith(".so")) {
            return failureEvidence(results, "QA_PAYLOAD_NAME_REJECTED");
        }
        final int memoryMb = boundedInt(arguments.getString("qaMemoryMb"),
                DEFAULT_MEMORY_MB, MIN_MEMORY_MB, MAX_MEMORY_MB);
        final long readyDeadlineMs = boundedLong(arguments.getString("qaReadyDeadlineMs"),
                DEFAULT_READY_DEADLINE_MS, 10_000L, MAX_READY_DEADLINE_MS);
        final long jobDeadlineMs = boundedLong(arguments.getString("qaJobDeadlineMs"),
                DEFAULT_JOB_DEADLINE_MS, 10_000L, MAX_FINISH_DEADLINE_MS);
        final int vsockPort = boundedInt(arguments.getString("qaVsockPort"),
                DEFAULT_PAYLOAD_VSOC_PORT, 1, 65535);
        final int jobs = boundedInt(arguments.getString("qaJobs"), 1, 1, 2);
        if (memoryMb < 0 || readyDeadlineMs < 0 || jobDeadlineMs < 0 || vsockPort < 0
                || jobs < 0) {
            return failureEvidence(results, "QA_ARGUMENTS_REJECTED");
        }

        AvfHiddenApiExemption.installOnce();

        final JSONObject evidence = new JSONObject();
        evidence.put("kind", "pvm_compiler");
        evidence.put("stage", "started");
        evidence.put("payload_name", payloadName);
        evidence.put("memory_mb", memoryMb);
        evidence.put("vsock_port", vsockPort);
        evidence.put("jobs_requested", jobs);
        evidence.put("run_token_sha256", sha256(token));
        final JSONObject device = new JSONObject();
        device.put("sdk_int", Build.VERSION.SDK_INT);
        device.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        evidence.put("device", device);
        final List<String> notes = new ArrayList<>();

        if (target.checkSelfPermission(ReflectiveAvfPlatformAccess.MANAGE_PERMISSION) != 0
                || target.checkSelfPermission(
                        ReflectiveAvfPlatformAccess.CUSTOM_PERMISSION) != 0) {
            return finishEvidence(evidence, results, "QA_PERMISSION_DENIED", notes, false);
        }

        final ReflectiveAvfPlatformAccess access =
                new ReflectiveAvfPlatformAccess(target, target.getClassLoader());
        final ReflectiveAvfPlatformAccess.PlatformShape shape = access.loadPlatformShape();
        final Object manager = target.getSystemService(shape.manager());
        if (manager == null || !shape.manager().isInstance(manager)) {
            return finishEvidence(evidence, results, "QA_VM_MANAGER_UNAVAILABLE", notes, false);
        }
        final int capabilities = readRawCapabilities(manager, shape.manager());
        device.put("capabilities_raw", capabilities);
        if (capabilities >= 0
                && (capabilities & ReflectiveAvfPlatformAccess.CAPABILITY_PROTECTED_VM) == 0) {
            return finishEvidence(evidence, results, "QA_PROTECTED_VM_UNAVAILABLE", notes, false);
        }

        final Object config = buildConfig(target, payloadName, memoryMb, notes);
        if (config == null) {
            return finishEvidence(evidence, results, "QA_CONFIG_FAILED", notes, false);
        }

        final Class<?> configClass = Class.forName(
                "android.system.virtualmachine.VirtualMachineConfig");
        final String name = "qa-csdl-" + token.substring(0, Math.min(12, token.length()));
        final long createStarted = SystemClock.uptimeMillis();
        final Object vm;
        try {
            vm = shape.manager().getMethod("create", String.class, configClass)
                    .invoke(manager, name, config);
        } catch (InvocationTargetException wrapped) {
            // The AVF error code (e.g. payload-signature or image failures)
            // survives in the cause; the driver maps it to a fixed code.
            notes.add("create: " + describe(wrapped.getCause()));
            return finishEvidence(evidence, results, "QA_CREATE_FAILED", notes, false);
        }
        evidence.put("create_ms", SystemClock.uptimeMillis() - createStarted);

        final CallbackState state = new CallbackState();
        installCallback(vm, state, notes);

        final long runStarted = SystemClock.uptimeMillis();
        try {
            Class.forName("android.system.virtualmachine.VirtualMachine")
                    .getMethod("run").invoke(vm);
        } catch (InvocationTargetException wrapped) {
            notes.add("run: " + describe(wrapped.getCause()));
            evidence.put("run_ms", SystemClock.uptimeMillis() - runStarted);
            final boolean stopped = requestStop(evidence, manager, shape.manager(), vm,
                    name, notes);
            return finishEvidence(evidence, results, "QA_RUN_FAILED", notes, stopped);
        }

        awaitPayloadReady(state, readyDeadlineMs);
        evidence.put("payload_ready_ms", state.payloadReady
                ? SystemClock.uptimeMillis() - runStarted
                : JSONObject.NULL);
        if (!state.payloadReady) {
            // A payload that exits before ready is a different defect from one
            // that silently never announces: keep them apart.
            String failureCode = state.errored ? "VM_ERROR"
                    : (state.payloadFinished ? "PAYLOAD_EXIT_BEFORE_READY"
                                             : "PAYLOAD_READY_TIMEOUT");
            if (state.payloadFinished) {
                evidence.put("exit_code", state.exitCode);
            }
            evidence.put("vm_error_code", state.errored ? state.errorCode : JSONObject.NULL);
            final boolean stopped = requestStop(evidence, manager, shape.manager(), vm,
                    name, notes);
            return finishEvidence(evidence, results, failureCode, notes, stopped);
        }

        final AtomicInteger completed = new AtomicInteger();
        final JSONArray connections = new JSONArray();
        DistccTcpVsockBridge bridge = null;
        try {
            bridge = new DistccTcpVsockBridge(
                    InetAddress.getByName("127.0.0.1"),
                    0,
                    () -> openVsockChannel(vm, vsockPort),
                    1,
                    0,
                    (int) Math.min(jobDeadlineMs, 120_000L),
                    stats -> {
                        synchronized (connections) {
                            try {
                                connections.put(connectionRecord(stats));
                            } catch (JSONException ignored) {
                                // A record we cannot serialise must not abort the run.
                            }
                        }
                        completed.incrementAndGet();
                    });
            bridge.start();
        } catch (IOException | RuntimeException bridgeFailure) {
            notes.add("bridge: " + exceptionName(bridgeFailure)
                    + "(" + safeMessage(bridgeFailure) + ")");
            final boolean stopped = requestStop(evidence, manager, shape.manager(), vm,
                    name, notes);
            return finishEvidence(evidence, results, "QA_BRIDGE_FAILED", notes, stopped);
        }

        // Publish the port before the host can need it: the driver polls this
        // file, sets up `adb forward` and only then starts the client.
        evidence.put("bridge_port", bridge.port());
        evidence.put("stage", "bridge_ready");
        writeEvidence(evidence, results);

        final long bridgeStarted = SystemClock.uptimeMillis();
        while (completed.get() < jobs
                && SystemClock.uptimeMillis() - bridgeStarted < jobDeadlineMs
                && !state.errored && !state.stopped && !state.payloadFinished) {
            SystemClock.sleep(200);
        }
        final long bridgeElapsedMs = SystemClock.uptimeMillis() - bridgeStarted;
        try {
            bridge.close();
        } catch (IOException closeFailure) {
            notes.add("bridge close: " + exceptionName(closeFailure));
        }
        evidence.put("bridge_elapsed_ms", bridgeElapsedMs);
        synchronized (connections) {
            evidence.put("connections", connections);
        }
        evidence.put("connections_completed", completed.get());

        // The payload is a server: it only finishes because we stop it, so its
        // exit code is recorded for completeness and never used as the verdict.
        final boolean stopped = requestStop(evidence, manager, shape.manager(), vm, name, notes);
        if (state.payloadFinished) {
            evidence.put("exit_code_after_stop", state.exitCode);
        }
        evidence.put("vm_error_code", state.errored ? state.errorCode : JSONObject.NULL);

        String failureCode = null;
        if (state.errored) {
            failureCode = "VM_ERROR";
        } else if (completed.get() < jobs) {
            failureCode = "BRIDGE_JOBS_INCOMPLETE";
        } else if (!stopped) {
            failureCode = "QA_STOP_UNCONFIRMED";
        }
        evidence.put("stage", "done");
        return finishEvidence(evidence, results, failureCode, notes, stopped);
    }

    /** One vsock connection as a byte channel for the production distcc bridge. */
    private static FastLaneChannel openVsockChannel(Object vm, int port) throws IOException {
        final Object descriptor;
        try {
            descriptor = Class.forName("android.system.virtualmachine.VirtualMachine")
                    .getMethod("connectVsock", long.class).invoke(vm, (long) port);
        } catch (InvocationTargetException wrapped) {
            throw new IOException("connectVsock: " + exceptionName(wrapped.getCause()));
        } catch (ReflectiveOperationException failure) {
            throw new IOException("connectVsock: " + exceptionName(failure));
        }
        if (!(descriptor instanceof ParcelFileDescriptor)) {
            throw new IOException("connectVsock returned no descriptor");
        }
        return new VsockChannel((ParcelFileDescriptor) descriptor);
    }

    /**
     * Read and write halves are separate dup'd descriptors on purpose: the
     * bridge closes the output half when the TCP client stops sending, and that
     * must not tear down the read half while the response is still arriving.
     */
    private static final class VsockChannel implements FastLaneChannel {
        private final ParcelFileDescriptor writeEnd;
        private final InputStream input;
        private final OutputStream output;

        VsockChannel(ParcelFileDescriptor descriptor) throws IOException {
            this.writeEnd = descriptor.dup();
            this.input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            this.output = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
        }

        @Override
        public InputStream inputStream() {
            return input;
        }

        @Override
        public OutputStream outputStream() {
            return output;
        }

        @Override
        public void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // Already closed by the bridge.
            }
            try {
                output.close();
            } catch (IOException ignored) {
                // Already closed by the bridge.
            }
        }
    }

    /** Counters only; the bridge never hands payload bytes to a stats sink. */
    private static JSONObject connectionRecord(
            DistccTcpVsockBridge.ConnectionStats stats) throws JSONException {
        final JSONObject record = new JSONObject();
        record.put("outcome", stats.outcome().name());
        record.put("failure_code", stats.failureCode());
        record.put("bytes_in", stats.bytesFromClient());
        record.put("bytes_out", stats.bytesToClient());
        record.put("duration_ms", stats.durationMillis());
        return record;
    }

    private static String safeMessage(Throwable failure) {
        final String message = failure.getMessage();
        return message == null ? "" : message.replaceAll("/data/[^\\s\"')]+", "<path>");
    }

    private JSONObject runProbe(Bundle results) throws Exception {
        // The target context is the QA app under test: the VM manager, the
        // payload APK and the VM data directory all belong to it. getContext()
        // would hand back the androidTest package's context, whose data dir is
        // owned by a different uid and is not where the payload lives.
        final Context target = getTargetContext();
        final String token = arguments.getString("qaRunToken");
        if (token == null || !token.matches("[0-9a-f]{16,64}")) {
            return failureEvidence(results, "QA_RUN_TOKEN_REJECTED");
        }
        final String payloadName = arguments.getString("qaPayloadName");
        if (payloadName == null || !payloadName.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || !payloadName.endsWith(".so")) {
            return failureEvidence(results, "QA_PAYLOAD_NAME_REJECTED");
        }
        final int memoryMb = boundedInt(arguments.getString("qaMemoryMb"),
                DEFAULT_MEMORY_MB, MIN_MEMORY_MB, MAX_MEMORY_MB);
        final long readyDeadlineMs = boundedLong(arguments.getString("qaReadyDeadlineMs"),
                DEFAULT_READY_DEADLINE_MS, 10_000L, MAX_READY_DEADLINE_MS);
        final long finishDeadlineMs = boundedLong(arguments.getString("qaFinishDeadlineMs"),
                DEFAULT_FINISH_DEADLINE_MS, 30_000L, MAX_FINISH_DEADLINE_MS);
        if (memoryMb < 0 || readyDeadlineMs < 0 || finishDeadlineMs < 0) {
            return failureEvidence(results, "QA_ARGUMENTS_REJECTED");
        }

        AvfHiddenApiExemption.installOnce();

        final JSONObject evidence = new JSONObject();
        evidence.put("kind", "pvm_probe");
        evidence.put("payload_name", payloadName);
        evidence.put("memory_mb", memoryMb);
        evidence.put("run_token_sha256", sha256(token));
        final JSONObject device = new JSONObject();
        device.put("sdk_int", Build.VERSION.SDK_INT);
        device.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        evidence.put("device", device);
        final List<String> notes = new ArrayList<>();

        if (target.checkSelfPermission(ReflectiveAvfPlatformAccess.MANAGE_PERMISSION) != 0
                || target.checkSelfPermission(
                        ReflectiveAvfPlatformAccess.CUSTOM_PERMISSION) != 0) {
            return finishEvidence(evidence, results, "QA_PERMISSION_DENIED", notes, false);
        }

        final ReflectiveAvfPlatformAccess access =
                new ReflectiveAvfPlatformAccess(target, target.getClassLoader());
        final ReflectiveAvfPlatformAccess.PlatformShape shape = access.loadPlatformShape();
        final Object manager = target.getSystemService(shape.manager());
        if (manager == null || !shape.manager().isInstance(manager)) {
            return finishEvidence(evidence, results, "QA_VM_MANAGER_UNAVAILABLE", notes, false);
        }

        final int capabilities = readRawCapabilities(manager, shape.manager());
        device.put("capabilities_raw", capabilities);
        if (capabilities >= 0
                && (capabilities & ReflectiveAvfPlatformAccess.CAPABILITY_PROTECTED_VM) == 0) {
            return finishEvidence(evidence, results, "QA_PROTECTED_VM_UNAVAILABLE", notes, false);
        }

        diagnoseDirCreation(target, notes);

        final Object config = buildConfig(target, payloadName, memoryMb, notes);
        if (config == null) {
            return finishEvidence(evidence, results, "QA_CONFIG_FAILED", notes, false);
        }

        final Class<?> configClass = Class.forName(
                "android.system.virtualmachine.VirtualMachineConfig");
        final String name = "qa-pvm-" + token.substring(0, Math.min(12, token.length()));
        final long createStarted = SystemClock.uptimeMillis();
        final Object vm;
        try {
            vm = shape.manager().getMethod("create", String.class, configClass)
                    .invoke(manager, name, config);
        } catch (InvocationTargetException wrapped) {
            notes.add("create: " + describe(wrapped.getCause()));
            return finishEvidence(evidence, results, "QA_CREATE_FAILED", notes, false);
        }
        evidence.put("create_ms", SystemClock.uptimeMillis() - createStarted);

        final CallbackState state = new CallbackState();
        installCallback(vm, state, notes);


        final long runStarted = SystemClock.uptimeMillis();
        try {
            Class.forName("android.system.virtualmachine.VirtualMachine")
                    .getMethod("run").invoke(vm);
        } catch (InvocationTargetException wrapped) {
            notes.add("run: " + describe(wrapped.getCause()));
            evidence.put("run_ms", SystemClock.uptimeMillis() - runStarted);
            final boolean stopped = requestStop(evidence, manager, shape.manager(), vm,
                    name, notes);
            return finishEvidence(evidence, results, "QA_RUN_FAILED", notes, stopped);
        }

        // Result channel: the guest streams its PVM_PROBE lines over vsock
        // (port matches pvmprobe::kResultPort) because the vendor debug
        // policy keeps guest logcat and the VM console from the host. The
        // production engine uses the same vm.connectVsock(long) surface.
        final VsockResultReader vsockReader = new VsockResultReader(vm);
        vsockReader.start();

        awaitPayloadReady(state, readyDeadlineMs);
        evidence.put("payload_ready_ms", state.payloadReady
                ? SystemClock.uptimeMillis() - runStarted
                : JSONObject.NULL);

        String failureCode;
        if (!state.payloadReady) {
            failureCode = state.errored ? "VM_ERROR" : "PAYLOAD_READY_TIMEOUT";
        } else {
            awaitPayloadFinished(state, finishDeadlineMs);
            if (state.payloadFinished) {
                evidence.put("payload_finished_ms",
                        SystemClock.uptimeMillis() - runStarted);
                evidence.put("exit_code", state.exitCode);
                failureCode = state.exitCode == 0 ? null : "PAYLOAD_EXIT_NONZERO";
            } else if (state.errored) {
                failureCode = "VM_ERROR";
            } else if (state.stopped) {
                failureCode = "PAYLOAD_STOPPED_EARLY";
            } else {
                failureCode = "PAYLOAD_FINISH_TIMEOUT";
            }
        }
        evidence.put("vm_error_code", state.errored ? state.errorCode : JSONObject.NULL);
        vsockReader.joinAndRecord(evidence, notes);


        final boolean stopConfirmed =
                requestStop(evidence, manager, shape.manager(), vm, name, notes);
        if (state.stopped) {
            evidence.put("stop_reason", state.stopReason);
        }
        if (!stopConfirmed) {
            // Never pretend a stop happened; the driver reports the VM as-is.
            notes.add("stop: unconfirmed, delete skipped");
            return finishEvidence(evidence, results,
                    failureCode == null ? "QA_STOP_UNCONFIRMED" : failureCode, notes, false);
        }
        return finishEvidence(evidence, results, failureCode, notes, true);
    }

    private Object buildConfig(Context target, String payloadName, int memoryMb,
            List<String> notes) {
        try {
            final Class<?> configClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachineConfig");
            final Class<?> builderClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachineConfig$Builder");
            final Object builder = builderClass.getConstructor(Context.class).newInstance(target);
            builderClass.getMethod("setProtectedVm", boolean.class).invoke(builder, true);
            builderClass.getMethod("setPayloadBinaryName", String.class)
                    .invoke(builder, payloadName);
            builderClass.getMethod("setMemoryBytes", long.class)
                    .invoke(builder, (long) memoryMb * 1024L * 1024L);
            final int cpuTopology = staticInt(configClass, "CPU_TOPOLOGY_ONE_CPU", 0, notes);
            try {
                builderClass.getMethod("setCpuTopology", int.class).invoke(builder, cpuTopology);
            } catch (NoSuchMethodException absent) {
                notes.add("setCpuTopology: unavailable, platform default");
            }
            try {
                final int debugFull = staticInt(configClass, "DEBUG_LEVEL_FULL", 2, notes);
                builderClass.getMethod("setDebugLevel", int.class).invoke(builder, debugFull);
            } catch (NoSuchMethodException absent) {
                notes.add("setDebugLevel: unavailable");
            }
            try {
                builderClass.getMethod("setVmOutputCaptured", boolean.class)
                        .invoke(builder, true);
            } catch (NoSuchMethodException absent) {
                notes.add("setVmOutputCaptured: unavailable, guest logcat may not export");
            }
            return builderClass.getMethod("build").invoke(builder);
        } catch (Throwable failure) {
            notes.add("config: " + exceptionName(failure));
            return null;
        }
    }

    private static int staticInt(Class<?> owner, String field, int fallback,
            List<String> notes) {
        try {
            return owner.getField(field).getInt(null);
        } catch (Throwable unreadable) {
            notes.add(field + ": unreadable, using " + fallback);
            return fallback;
        }
    }

    private static int readRawCapabilities(Object manager, Class<?> managerClass) {
        try {
            Object value = managerClass.getMethod("getCapabilities").invoke(manager);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable unreadable) {
            return -1;
        }
    }

    private static void installCallback(Object vm, CallbackState state, List<String> notes) {
        try {
            final Class<?> callbackClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachineCallback");
            final InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "onPayloadStarted" -> { }
                    case "onPayloadReady" -> state.payloadReady = true;
                    case "onPayloadFinished" -> {
                        state.payloadFinished = true;
                        state.exitCode = args == null || args.length < 2
                                ? -1 : (Integer) args[1];
                    }
                    case "onError" -> {
                        state.errored = true;
                        state.errorCode = args == null || args.length < 2
                                ? -1 : (Integer) args[1];
                    }
                    case "onStopped" -> {
                        state.stopped = true;
                        state.stopReason = args == null || args.length < 2
                                ? -1 : (Integer) args[1];
                    }
                    case "toString" -> {
                        return "PvmProbeCallback";
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    default -> {
                        return null;
                    }
                }
                return null;
            };
            final Object callback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(), new Class<?>[] {callbackClass}, handler);
            final Class<?> vmClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachine");
            vmClass.getMethod("setCallback", Executor.class, callbackClass)
                    .invoke(vm, (Executor) Runnable::run, callback);
        } catch (Throwable failure) {
            notes.add("callback: unavailable (" + exceptionName(failure) + ")");
        }
    }

    private static void awaitPayloadReady(CallbackState state, long deadlineMs) {
        final long started = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - started < deadlineMs) {
            if (state.payloadReady || state.errored || state.stopped) {
                return;
            }
            SystemClock.sleep(200);
        }
    }

    private static void awaitPayloadFinished(CallbackState state, long deadlineMs) {
        final long started = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - started < deadlineMs) {
            if (state.payloadFinished || state.errored || state.stopped) {
                return;
            }
            SystemClock.sleep(500);
        }
    }

    private static boolean requestStop(JSONObject evidence, Object manager,
            Class<?> managerClass, Object vm, String name, List<String> notes)
            throws JSONException {
        boolean confirmed = false;
        try {
            Class.forName("android.system.virtualmachine.VirtualMachine")
                    .getMethod("stop").invoke(vm);
        } catch (Throwable failure) {
            notes.add("stop: " + exceptionName(failure));
        }
        final long started = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - started < STOP_DEADLINE_MS) {
            try {
                Object value = Class.forName("android.system.virtualmachine.VirtualMachine")
                        .getMethod("getStatus").invoke(vm);
                final int status = value instanceof Integer ? (Integer) value : -1;
                if (status == 0 || status == 2) {
                    confirmed = true;
                    break;
                }
            } catch (Throwable unreadable) {
                notes.add("status: unreadable");
                break;
            }
            SystemClock.sleep(200);
        }
        evidence.put("stop_confirmed", confirmed);
        if (!confirmed) {
            return false;
        }
        try {
            managerClass.getMethod("delete", String.class).invoke(manager, name);
            evidence.put("deleted", true);
        } catch (Throwable failure) {
            notes.add("delete: " + exceptionName(failure));
            evidence.put("deleted", false);
        }
        return true;
    }

    private JSONObject failureEvidence(Bundle results, String failureCode)
            throws JSONException {
        final JSONObject evidence = new JSONObject();
        evidence.put("kind", evidencePrefix().replace('-', '_'));
        evidence.put("stage", "rejected");
        return finishEvidence(evidence, results, failureCode, new ArrayList<>(), false);
    }

    private JSONObject finishEvidence(JSONObject evidence, Bundle results,
            String failureCode, List<String> notes, boolean stopConfirmed)
            throws JSONException {
        evidence.put("status_passed", failureCode == null);
        evidence.put("failure_code", failureCode == null ? JSONObject.NULL : failureCode);
        evidence.put("stop_confirmed", stopConfirmed);
        evidence.put("notes", notes.isEmpty() ? JSONObject.NULL : String.join("; ", notes));
        writeEvidence(evidence, results);
        return evidence;
    }

    /** File stem per mode: the driver polls a name it knows before the run. */
    private String evidencePrefix() {
        return MODE_COMPILER.equals(arguments.getString("qaMode")) ? "pvm-compiler" : "pvm-probe";
    }

    private void writeEvidence(JSONObject evidence, Bundle results) {
        final String token = arguments.getString("qaRunToken");
        if (token == null) {
            return;
        }
        final File dir = new File(getTargetContext().getFilesDir(), "avf-qa");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        final String stem = evidencePrefix() + "-" + token;
        final File partial = new File(dir, stem + ".json.partial");
        final File target = new File(dir, stem + ".json");
        try (FileOutputStream stream = new FileOutputStream(partial)) {
            stream.write(evidence.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException writeFailure) {
            return;
        }
        if (partial.renameTo(target)) {
            results.putString("qa_evidence_path", target.getAbsolutePath());
        }
    }

        /** QA diagnostic: one probe mkdir at three data-dir levels, fixed names. */
    private static void diagnoseDirCreation(Context target, List<String> notes) {
        final File dataDir = target.getDataDir();
        final String[] probes = {"diag-root", "vm/diag-vm", "files/diag-files"};
        for (final String rel : probes) {
            final File probe = new File(dataDir, rel);
            try {
                java.nio.file.Files.createDirectory(probe.toPath());
                notes.add("mkdir-ok: " + rel);
                probe.delete();
            } catch (java.nio.file.FileAlreadyExistsException e) {
                notes.add("mkdir-exists: " + rel);
            } catch (IOException e) {
                notes.add("mkdir-denied: " + rel + " (" + exceptionName(e) + ")");
            }
        }
    }

    /** Reads the guest probe's vsock result stream (production transport). */
    private static final class VsockResultReader {
        private static final int RESULT_PORT = 45699;
        private final Object vm;
        private final Thread thread;
        private final List<String> lines = new ArrayList<>();
        private final List<String> notes = Collections.synchronizedList(new ArrayList<>());

        VsockResultReader(Object vm) {
            this.vm = vm;
            this.thread = new Thread(this::run, "pvm-probe-vsock");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        private void run() {
            notes.add("vsock reader started");
            Object descriptor = null;
            for (int attempt = 0; attempt < 40 && descriptor == null; attempt++) {
                try {
                    descriptor = vm.getClass().getMethod("connectVsock", long.class)
                            .invoke(vm, (long) RESULT_PORT);
                } catch (Throwable failure) {
                    notes.add("vsock connect try " + attempt + ": "
                            + exceptionName(failure));
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (!(descriptor instanceof android.os.ParcelFileDescriptor)) {
                notes.add("vsock: no connection");
                return;
            }
            notes.add("vsock connected, reading");
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                                    (android.os.ParcelFileDescriptor) descriptor),
                            StandardCharsets.UTF_8))) {
                String line;
                while (lines.size() < 200 && (line = reader.readLine()) != null) {
                    lines.add(line);
                    if (line.contains("PVM_PROBE_END")) {
                        break;
                    }
                }
            } catch (Throwable failure) {
                notes.add("vsock read: " + exceptionName(failure));
            }
            notes.add("vsock read finished with " + lines.size() + " lines");
        }

        void joinAndRecord(JSONObject evidence, List<String> sinkNotes) {
            try {
                thread.join(8000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sinkNotes.addAll(notes);
            try {
                final org.json.JSONArray array = new org.json.JSONArray();
                for (final String line : lines) {
                    array.put(line);
                }
                if (array.length() > 0) {
                    evidence.put("probe_lines", array);
                }
            } catch (JSONException ignored) {
                // Notes carry the state; the driver falls back to logcat.
            }
        }
    }

    /** Full cause chain with messages; absolute paths reduced to &lt;path&gt;. */
    private static String describe(Throwable failure) {
        final StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (depth > 0) {
                chain.append(" <- ");
            }
            chain.append(exceptionName(current));
            final String message = current.getMessage();
            if (message != null && !message.isEmpty()) {
                chain.append('(').append(message
                        .replaceAll("/data/[^\s\"')]+", "<path>")
                        .replaceAll("/storage/[^\s\"')]+", "<path>"))
                        .append(')');
            }
            current = current.getCause();
        }
        return chain.length() == 0 ? "unknown" : chain.toString();
    }

    private static String exceptionName(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        final String name = failure.getClass().getSimpleName();
        return name.isEmpty() ? failure.getClass().getName() : name;
    }

    private static int boundedInt(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            final int value = Integer.parseInt(raw.trim());
            return value >= min && value <= max ? value : -1;
        } catch (NumberFormatException rejected) {
            return -1;
        }
    }

    private static long boundedLong(String raw, long fallback, long min, long max) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            final long value = Long.parseLong(raw.trim());
            return value >= min && value <= max ? value : -1;
        } catch (NumberFormatException rejected) {
            return -1;
        }
    }

    private static String sha256(String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                hex.append(HEX.charAt((item >> 4) & 0xf));
                hex.append(HEX.charAt(item & 0xf));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            return "unavailable";
        }
    }

    private static final class CallbackState {
        volatile boolean payloadReady;
        volatile boolean payloadFinished;
        volatile boolean errored;
        volatile boolean stopped;
        volatile int exitCode = -1;
        volatile int errorCode = -1;
        volatile int stopReason = -1;
    }
}
