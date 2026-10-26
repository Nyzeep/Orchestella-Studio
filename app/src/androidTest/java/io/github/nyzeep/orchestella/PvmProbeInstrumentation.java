package io.github.nyzeep.orchestella;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry;
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfPlatformAccess;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.Axp1DistccChannelFactory;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridge;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEvent;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStarter;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;

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
 *   <li>{@code qaMode=compiler-crossfire} — DV-PVM-02b: the same payload VM,
 *       but the loopback bridge binds the contract port 5103 and both lanes
 *       live under a production {@link DualLaneCoordinator}: the QEMU lane is
 *       a real distro guest launched through {@link QemuInstanceLauncher}, and
 *       {@code awaitAcceleratorReady} is exercised exactly as compile work
 *       calls it in the product. The guest dials the bridge through gvproxy's
 *       hostIP mapping, so the console socket of the QEMU guest is published
 *       in the evidence for the host driver.</li>
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
    // File channel: the request and the response travel as app-private files
    // (adb push / run-as cat) instead of a forwarded TCP port. Same production
    // AXP1 client, same payload; only the host->device byte carrier changes.
    private static final String MODE_COMPILER_FILE = "compiler-file";
    // DV-PVM-02b: the production gate and bridge meet a real QEMU guest. The
    // mode keeps the compiler VM shape but binds the contract port, because
    // the guest dials it through gvproxy's hostIP mapping (guest
    // 192.168.127.254:5103 -> host 127.0.0.1:5103).
    private static final String MODE_COMPILER_CROSSFIRE = "compiler-crossfire";
    // Matches FastLaneDv01Contract.DEFAULT_DISTCC_PORT and the payload default.
    private static final int DEFAULT_PAYLOAD_VSOC_PORT = 5103;
    // The bridge must bind the port the guest dials; gvproxy's hostIP mapping
    // is 1:1 on the host port, so the ephemeral port 0 the compiler mode uses
    // would never be reachable from the guest.
    private static final int DEFAULT_BRIDGE_PORT = 5103;
    // Default ceiling for the production gate. DV-PVM-02a measured ~2.5 s from
    // create to payloadReady, but the crossfire gate also covers the VM create
    // inside the accel starter, so 120 s stays generous without hiding a stall.
    private static final long DEFAULT_GATE_DEADLINE_MS = 120_000L;
    private static final long DEFAULT_JOB_DEADLINE_MS = 300_000L;
    private static final int AXP1_MAX_FRAME = 1 << 20;
    // Must match the payload's compiled-in triple: the AXP1 handshake refuses a
    // mismatched compiler identity with COMPILER_MISMATCH.
    private static final String REQUIRED_TRIPLE = "aarch64-unknown-linux-android";
    private static final String REQUEST_FILE = "csdl-request.bin";
    private static final String RESPONSE_FILE = "csdl-response.bin";
    private static final int MAX_REQUEST_BYTES = 32 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 96 * 1024 * 1024;

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
            final String mode = arguments.getString("qaMode");
            if (MODE_COMPILER.equals(mode)) {
                evidence = runCompiler(results);
            } else if (MODE_COMPILER_FILE.equals(mode)) {
                evidence = runCompileFile(results);
            } else if (MODE_COMPILER_CROSSFIRE.equals(mode)) {
                evidence = runCompilerCrossfire(results);
            } else {
                evidence = runProbe(results);
            }
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

    // ------------------------------------------------------------------
    // DV-PVM-02 file channel: identical VM lifecycle, but the distcc request
    // and the response travel as app-private files instead of a forwarded TCP
    // port. The AXP1 client is the production one (Axp1DistccChannelFactory);
    // only the carrier between host and app changes.
    // ------------------------------------------------------------------

    private JSONObject runCompileFile(Bundle results) throws Exception {
        final Context target = getTargetContext();
        final String token = arguments.getString("qaRunToken");
        if (token == null || !token.matches("[0-9a-f]{32}")) {
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
        final int vsockPort = boundedInt(arguments.getString("qaVsockPort"),
                DEFAULT_PAYLOAD_VSOC_PORT, 1, 65535);
        if (memoryMb < 0 || readyDeadlineMs < 0 || vsockPort < 0) {
            return failureEvidence(results, "QA_ARGUMENTS_REJECTED");
        }

        AvfHiddenApiExemption.installOnce();

        final JSONObject evidence = new JSONObject();
        evidence.put("kind", "pvm_compiler");
        evidence.put("stage", "started");
        evidence.put("channel", "file");
        evidence.put("payload_name", payloadName);
        evidence.put("memory_mb", memoryMb);
        evidence.put("vsock_port", vsockPort);
        evidence.put("run_token_sha256", sha256(token));
        final JSONObject device = new JSONObject();
        device.put("sdk_int", Build.VERSION.SDK_INT);
        device.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        evidence.put("device", device);
        final List<String> notes = new ArrayList<>();
        trace("runCompileFile start");

        final File requestFile = new File(target.getFilesDir(), REQUEST_FILE);
        if (!requestFile.isFile()) {
            notes.add("request file missing: " + REQUEST_FILE);
            return finishEvidence(evidence, results, "QA_REQUEST_MISSING", notes, false);
        }
        final byte[] request = readBounded(requestFile, MAX_REQUEST_BYTES);
        evidence.put("request_bytes", request.length);

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
        final String name = "qa-csdlf-" + token.substring(0, Math.min(12, token.length()));
        final long createStarted = SystemClock.uptimeMillis();
        Object createdVm;
        try {
            createdVm = shape.manager().getMethod("create", String.class, configClass)
                    .invoke(manager, name, config);
        } catch (InvocationTargetException wrapped) {
            notes.add("create retry after: " + describe(wrapped.getCause()));
            try {
                shape.manager().getMethod("delete", String.class).invoke(manager, name);
                createdVm = shape.manager().getMethod("create", String.class, configClass)
                        .invoke(manager, name, config);
            } catch (InvocationTargetException secondFailure) {
                notes.add("create: " + describe(secondFailure.getCause()));
                return finishEvidence(evidence, results, "QA_CREATE_FAILED", notes, false);
            } catch (ReflectiveOperationException unavailable) {
                notes.add("create retry unavailable: " + exceptionName(unavailable));
                return finishEvidence(evidence, results, "QA_CREATE_FAILED", notes, false);
            }
        }
        final Object vm = createdVm;
        evidence.put("create_ms", SystemClock.uptimeMillis() - createStarted);

        final CallbackState state = new CallbackState();
        installCallback(vm, state, notes);
        final long runStarted = SystemClock.uptimeMillis();
        try {
            Class.forName("android.system.virtualmachine.VirtualMachine")
                    .getMethod("run").invoke(vm);
        } catch (InvocationTargetException wrapped) {
            notes.add("run: " + describe(wrapped.getCause()));
            final boolean stopped = requestStop(evidence, manager, shape.manager(), vm, name, notes);
            return finishEvidence(evidence, results, "QA_RUN_FAILED", notes, stopped);
        }

        awaitPayloadReady(state, readyDeadlineMs);
        evidence.put("payload_ready_ms", state.payloadReady
                ? SystemClock.uptimeMillis() - runStarted : JSONObject.NULL);
        trace("payload_ready=" + state.payloadReady);
        if (!state.payloadReady) {
            String failureCode = state.errored ? "VM_ERROR"
                    : (state.payloadFinished ? "PAYLOAD_EXIT_BEFORE_READY"
                                             : "PAYLOAD_READY_TIMEOUT");
            if (state.payloadFinished) {
                evidence.put("exit_code", state.exitCode);
            }
            final boolean stopped = requestStop(evidence, manager, shape.manager(), vm, name, notes);
            return finishEvidence(evidence, results, failureCode, notes, stopped);
        }

        String failureCode = null;
        try {
            final Axp1DistccChannelFactory factory = new Axp1DistccChannelFactory(
                    () -> openVsockChannel(vm, vsockPort),
                    token,
                    Axp1Capabilities.builder()
                            .distcc(true)
                            .compilerTriple(REQUIRED_TRIPLE)
                            .maxFrame(AXP1_MAX_FRAME)
                            .build(),
                    REQUIRED_TRIPLE,
                    new Axp1Limits(3_000L, 4 * AXP1_MAX_FRAME, AXP1_MAX_FRAME));
            final long handshakeStarted = SystemClock.uptimeMillis();
            final FastLaneChannel channel = factory.open();
            evidence.put("axp1_handshake_ms", SystemClock.uptimeMillis() - handshakeStarted);
            trace("axp1 handshake ok");

            final long transferStarted = SystemClock.uptimeMillis();
            final OutputStream outgoing = channel.outputStream();
            outgoing.write(request);
            outgoing.flush();
            // close() on the AXP1 data stream sends the EOF frame and leaves the
            // read half open — the distcc v1 request has no length prefix.
            outgoing.close();
            final ByteArrayOutputStream response = new ByteArrayOutputStream();
            final InputStream incoming = channel.inputStream();
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = incoming.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (response.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("response exceeds " + MAX_RESPONSE_BYTES + " bytes");
                }
                response.write(buffer, 0, read);
            }
            evidence.put("transfer_ms", SystemClock.uptimeMillis() - transferStarted);
            final byte[] responseBytes = response.toByteArray();
            evidence.put("response_bytes", responseBytes.length);
            final File responseFile = new File(target.getFilesDir(), RESPONSE_FILE);
            try (FileOutputStream stream = new FileOutputStream(responseFile)) {
                stream.write(responseBytes);
            }
            trace("response bytes=" + responseBytes.length);
            channel.close();
        } catch (Throwable exchangeFailure) {
            notes.add("exchange: " + exceptionName(exchangeFailure)
                    + "(" + safeMessage(exchangeFailure) + ")");
            failureCode = "QA_EXCHANGE_FAILED";
        }

        final boolean stopped = requestStop(evidence, manager, shape.manager(), vm, name, notes);
        if (state.payloadFinished) {
            evidence.put("exit_code_after_stop", state.exitCode);
        }
        evidence.put("vm_error_code", state.errored ? state.errorCode : JSONObject.NULL);
        if (failureCode == null && !stopped) {
            failureCode = "QA_STOP_UNCONFIRMED";
        }
        evidence.put("stage", "done");
        return finishEvidence(evidence, results, failureCode, notes, stopped);
    }

    // ------------------------------------------------------------------
    // DV-PVM-02b crossfire: the same payload VM shape as compiler mode, but
    // both lanes live under a production DualLaneCoordinator. The QEMU lane
    // launches a real distro guest through the product launcher, so the
    // compile path under test is guest -> gvproxy -> bridge -> vsock, and the
    // gate (`awaitAcceleratorReady`) is called exactly as compile work calls
    // it in the product: once, before any connection, timeout = fallback.
    // ------------------------------------------------------------------

    private JSONObject runCompilerCrossfire(Bundle results) throws Exception {
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
        final long payloadReadyDeadlineMs = boundedLong(arguments.getString("qaReadyDeadlineMs"),
                DEFAULT_READY_DEADLINE_MS, 10_000L, MAX_READY_DEADLINE_MS);
        final long jobDeadlineMs = boundedLong(arguments.getString("qaJobDeadlineMs"),
                DEFAULT_JOB_DEADLINE_MS, 10_000L, MAX_FINISH_DEADLINE_MS);
        // `readyDeadlineMs` is the driver-facing gate deadline: it bounds
        // awaitAcceleratorReady, not the payload's own readiness callback
        // (that stays qaReadyDeadlineMs, shared with the compiler modes).
        final long gateDeadlineMs = boundedLong(arguments.getString("readyDeadlineMs"),
                DEFAULT_GATE_DEADLINE_MS, 10_000L, MAX_READY_DEADLINE_MS);
        final int vsockPort = boundedInt(arguments.getString("qaVsockPort"),
                DEFAULT_PAYLOAD_VSOC_PORT, 1, 65535);
        final int bridgePort = boundedInt(arguments.getString("bridge_port"),
                DEFAULT_BRIDGE_PORT, 1024, 65535);
        // qaDirectQemu=1: boot the guest with the product runtime pieces but
        // WITHOUT the product Session, whose terminal-socket ownership lasts
        // for the session lifetime and would starve the driver's console
        // automation (the driver then owns ttyAMA0 exclusively).
        final boolean directQemu = "1".equals(arguments.getString("qaDirectQemu"));
        final int jobs = boundedInt(arguments.getString("qaJobs"), 1, 1, 8);
        final String requestedInstanceId = arguments.getString("qaInstanceId");
        if (memoryMb < 0 || payloadReadyDeadlineMs < 0 || jobDeadlineMs < 0 || gateDeadlineMs < 0
                || vsockPort < 0 || bridgePort < 0 || jobs < 0) {
            return failureEvidence(results, "QA_ARGUMENTS_REJECTED");
        }

        AvfHiddenApiExemption.installOnce();

        final JSONObject evidence = new JSONObject();
        evidence.put("kind", "pvm_compiler");
        evidence.put("stage", "started");
        evidence.put("mode", MODE_COMPILER_CROSSFIRE);
        evidence.put("payload_name", payloadName);
        evidence.put("memory_mb", memoryMb);
        evidence.put("vsock_port", vsockPort);
        evidence.put("bridge_port", bridgePort);
        evidence.put("jobs_requested", jobs);
        evidence.put("run_token_sha256", sha256(token));
        final JSONObject device = new JSONObject();
        device.put("sdk_int", Build.VERSION.SDK_INT);
        device.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        evidence.put("device", device);
        final List<String> notes = new ArrayList<>();
        trace("runCompilerCrossfire start");

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

        // The QEMU guest is the only consumer of the bridge, so the run
        // refuses early (before any VM exists) when no guest is available.
        final InstanceLifecycle.Instance instance =
                pickQemuInstance(target, requestedInstanceId, notes);
        if (instance == null) {
            return finishEvidence(evidence, results, "QA_QEMU_INSTANCE_MISSING", notes, false);
        }
        // The instance id is an app-local UUID, never a device identifier.
        evidence.put("instance_id", instance.id());
        // The driver picks the guest package manager from this (no Alpine in
        // the product catalog; Arch/Debian/etc. land here instead).
        evidence.put("guest_distro", instance.distro().id());
        // Same socket naming the product launcher derives (QemuInstanceLauncher);
        // the driver forwards it with `adb forward tcp:0 localabstract:<name>`.
        evidence.put("terminal_socket", directQemu
                ? "qa-cxcf-term-" + token.substring(0, Math.min(12, token.length()))
                : "droidlayer-terminal-" + instance.id());
        evidence.put("direct_qemu", directQemu);

        // Mirror of MainActivity's launch parameter assembly (1531-1562): the
        // QEMU lane must boot through the same shapes a product launch uses.
        final String distroId = instance.distro().id();
        final DistroManifest manifest = DistroCatalogRegistry.getInstance().get(distroId);
        String rootfsFileName;
        if (manifest != null) {
            rootfsFileName = manifest.rootfsFileName();
        } else if ("alpine".equals(distroId)) {
            rootfsFileName = "alpine-minirootfs-3.24.1-aarch64.tar.gz";
        } else {
            rootfsFileName = distroId + "-rootfs.tar.xz";
        }
        final Path mirrorFile = target.getFilesDir().toPath()
                .resolve("instances").resolve(instance.id() + ".mirror");
        final Path firstbootFile = target.getFilesDir().toPath()
                .resolve("instances").resolve(instance.id() + ".firstboot");
        byte[] mirrorBytes = null;
        try {
            if (Files.isRegularFile(mirrorFile)) {
                mirrorBytes = Files.readAllBytes(mirrorFile);
            }
        } catch (IOException mirrorFailure) {
            notes.add("mirror read: " + exceptionName(mirrorFailure));
        }
        byte[] firstbootBytes;
        try {
            firstbootBytes = Files.isRegularFile(firstbootFile)
                    ? Files.readAllBytes(firstbootFile)
                    : FirstBootProvisionPipeline.generateScriptForDistro(distroId)
                            .getBytes(StandardCharsets.UTF_8);
        } catch (IOException firstbootFailure) {
            notes.add("firstboot read: " + exceptionName(firstbootFailure));
            return finishEvidence(evidence, results, "QA_QEMU_PARAMS_FAILED", notes, false);
        }
        final FirstBootProvisionPipeline.ProvisionPayload provisionPayload =
                new FirstBootProvisionPipeline.ProvisionPayload(
                        DistroMirrorProvisioner.getConfigFileName(distroId),
                        mirrorBytes, firstbootBytes);
        final InstanceStrategyLauncher.LaunchParameters parameters =
                new InstanceStrategyLauncher.LaunchParameters(
                        instance.id(),
                        instance.bridgeToken(),
                        rootfsFileName,
                        instance.rootVolume().toString(),
                        new File(target.getFilesDir(), "runtime/" + instance.id()).getAbsolutePath(),
                        new File(target.getFilesDir(), "distro-inputs").getAbsolutePath(),
                        instance.guestPassword(),
                        instance.hardwareConfig(),
                        provisionPayload,
                        instance.hostname(),
                        instance.defaultUser(),
                        instance.hasSudo());

        // Same envelope rule as AcceleratorLaunchDispatcher: the budget is
        // exactly what the two lanes request, so only the gate can refuse.
        final VmHardwareConfig hardware = instance.hardwareConfig();
        final LaneRequest qemuRequest = new LaneRequest(LaneId.QEMU, hardware.cpuCores(),
                hardware.ramMegabytes(), gibibytes(hardware.diskCapacityBytes()));
        // Kotlin object property: from Java the field is private and the
        // getter is the access path; the declared request stays the source.
        final LaneRequest accelRequest =
                AcceleratorLaunchDispatcher.INSTANCE.getACCEL_REQUEST();
        final LaneBudget budget = new LaneBudget(
                qemuRequest.cpuCores() + accelRequest.cpuCores(),
                qemuRequest.ramMiB() + accelRequest.ramMiB(),
                qemuRequest.diskGiB() + accelRequest.diskGiB(),
                true);

        final JSONArray laneEvents = new JSONArray();
        // Same log face as the product sink (MainActivity 1615-1620): one
        // line per event on the same tag, plus a counter-only evidence copy
        // so the driver does not have to parse logcat.
        final LaneEventSink eventSink = event -> {
            Log.i("OrchestellaAccelerator",
                    event.name() + " " + event.lane() + " " + event.code()
                            + " " + event.durationMs() + "ms");
            try {
                synchronized (laneEvents) {
                    laneEvents.put(eventRecord(event));
                }
            } catch (JSONException ignored) {
                // An unserialisable event must not break the lane it reports.
            }
            trace("lane_event " + event.name() + " " + event.lane() + " " + event.code());
        };

        final RuntimeOwnedSession[] qemuSession = new RuntimeOwnedSession[1];
        final LaneStarter qemuStarter = () -> {
            if (directQemu) {
                // Direct-QEMU path: the same product runtime pieces (asset
                // bootstrap, provisioned-volume bootstrapping, gvproxy, the
                // packaged QEMU binary, the same guest kernel command line),
                // minus the Session that would own the terminal socket.
                // Readiness is harness-level (process alive); the driver
                // verifies the guest shell through the console itself.
                final RuntimeArtifactManifest runtimeArtifactManifest;
                try (InputStream in = PackagedAssetStreams.openAsset(target,
                        RuntimeArtifactManifest.SCHEMA_FILE_NAME)) {
                    runtimeArtifactManifest = RuntimeArtifactManifest.parse(in);
                } catch (IOException | RuntimeException | Error failure) {
                    notes.add("direct manifest: " + exceptionName(failure));
                    throw new LaneStartException("QEMU_START_FAILED");
                }
                final QemuRuntimeBootstrapper bootstrapper =
                        new QemuRuntimeBootstrapper(runtimeArtifactManifest,
                                name -> {
                                    try {
                                        return PackagedAssetStreams.openAsset(target, name);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                name -> {
                                    try {
                                        return PackagedAssetStreams.openResource(target, name);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                Paths.get(target.getFilesDir().toString(),
                                        "runtime", instance.id()),
                                Paths.get(target.getFilesDir().toString(),
                                        "distro-inputs"));
                final QemuRuntimeBootstrapper.RuntimePaths paths;
                try {
                    paths = bootstrapper.bootstrap(
                            rootfsFileName, instance.bridgeToken(),
                            instance.guestPassword(),
                            Paths.get(instance.rootVolume().toString()),
                            parameters.mirrorConfigFileName,
                            parameters.mirrorConfigFileContent,
                            parameters.firstBootScript,
                            instance.hostname(), instance.defaultUser(),
                            instance.hasSudo(), null, false);
                } catch (IOException | RuntimeException | Error failure) {
                    notes.add("direct bootstrap: " + exceptionName(failure));
                    throw new LaneStartException("QEMU_START_FAILED");
                }
                final NetworkGatewayDaemon.Running gateway;
                try {
                    gateway = NetworkGatewayDaemon.start(target, instance.id(),
                            Paths.get(target.getFilesDir().toString(), "runtime",
                                    instance.id()).toFile());
                } catch (IOException | RuntimeException | Error failure) {
                    notes.add("direct gateway: " + exceptionName(failure));
                    throw new LaneStartException("QEMU_START_FAILED");
                }
                try {
                    final String guestKernelArgs =
                            "orchestella.guest_ip=" + NetworkGatewayDaemon.GUEST_IP
                                    + " orchestella.guest_gw=" + NetworkGatewayDaemon.GUEST_GATEWAY
                                    + " orchestella.guest_mask=" + NetworkGatewayDaemon.GUEST_NETMASK
                                    + " orchestella.guest_dns=" + NetworkGatewayDaemon.guestDnsFor(target)
                                    + " orchestella.proxy_enabled=0";
                    final String qemuBinary =
                            NativeExecutableStore.ensure(target, "libdroidlayer_qemu.so");
                    final String[] rawSpec = QemuLaunchSpec.buildWithPasst(
                            qemuBinary,
                            paths.kernel.toString(),
                            paths.initramfs.toString(),
                            instance.rootVolume().toString(),
                            "qa-cxcf-control-" + token.substring(0, Math.min(12, token.length())),
                            "qa-cxcf-term-" + token.substring(0, Math.min(12, token.length())),
                            hardware,
                            String.valueOf(gateway.tcpPort),
                            guestKernelArgs);
                    // Same two-step the product launcher applies: the packaged
                    // arm64 binary rejects virtio-gpu-gl-device, and the display
                    // rides a socket instead of a host window.
                    String[] spec = QemuInstanceLauncher
                            .sanitizeSpecForTargetBinary(rawSpec);
                    java.nio.file.Path displayDirectory = target.getFilesDir()
                            .toPath().resolve("display");
                    java.nio.file.Files.createDirectories(displayDirectory);
                    String displaySocketPath = displayDirectory.resolve(
                            java.util.UUID.nameUUIDFromBytes(
                                    instance.id().getBytes(StandardCharsets.UTF_8))
                                    .toString().substring(0, 16) + ".sock").toString();
                    spec = QemuLaunchSpec.withDisplayChannel(spec, displaySocketPath);
                    final Process process = new ProcessBuilder(spec)
                            .redirectErrorStream(true).start();
                    final Thread qemuLogger = new Thread(() -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                Log.d("OrchestellaQaQemu", line);
                            }
                        } catch (IOException ignored) {
                            // The VM is dead or the stream closed; nothing to log.
                        }
                    }, "qa-cxf-qemu-log");
                    qemuLogger.setDaemon(true);
                    qemuLogger.start();
                    trace("direct qemu started");
                    return new QaDirectQemuRuntime(process, gateway);
                } catch (IOException | RuntimeException | Error failure) {
                    notes.add("direct spawn: " + exceptionName(failure));
                    try {
                        gateway.stop(STOP_DEADLINE_MS);
                    } catch (RuntimeException ignored) {
                        // The QEMU stop is the confirmed part of the cleanup.
                    }
                    throw new LaneStartException("QEMU_START_FAILED");
                }
            }
            // Mirror of the dispatch qemuStarter (AcceleratorLaunchDispatcher):
            // the product launcher keeps asset bootstrap, gateway startup and
            // session registration, and the readiness probe observes the same
            // registered session's bridge control channel. Deliberately absent:
            // the UI-side launch guard and lifecycle transitions — a QA run owns
            // the whole device, and the store metadata must stay untouched.
            try {
                final ExecutionSession session =
                        new QemuInstanceLauncher(target).launch(parameters);
                if (!(session instanceof RuntimeOwnedSession)) {
                    throw new LaneStartException("QEMU_START_FAILED");
                }
                qemuSession[0] = (RuntimeOwnedSession) session;
                return AcceleratorLaneAssembly.qemuLane(qemuSession[0],
                        QemuReadinessProbe.ofSession(
                                () -> ExecutionSessionRegistry.get(instance.id())));
            } catch (IOException | InterruptedException failure) {
                notes.add("qemu launch: " + exceptionName(failure));
                throw new LaneStartException("QEMU_START_FAILED");
            }
        };

        final Class<?> configClass = Class.forName(
                "android.system.virtualmachine.VirtualMachineConfig");
        final String payloadVmName = "qa-cxcf-" + token.substring(0, Math.min(12, token.length()));
        final Object[] payloadVm = new Object[1];
        final CallbackState payloadState = new CallbackState();
        // Memoized so the coordinator's lane stop and the final teardown share
        // one stop/delete; a second call only reads back the recorded result.
        final AtomicBoolean payloadStopStarted = new AtomicBoolean();
        final boolean[] payloadStopConfirmed = new boolean[] {false};
        final BooleanSupplier stopPayloadVm = () -> {
            if (payloadStopStarted.compareAndSet(false, true) && payloadVm[0] != null) {
                try {
                    payloadStopConfirmed[0] = requestStop(evidence, manager, shape.manager(),
                            payloadVm[0], payloadVmName, notes);
                } catch (JSONException recordFailure) {
                    // The stop itself is untouched; only the evidence write
                    // failed, and the run must not report a false confirm.
                    notes.add("stop record: " + exceptionName(recordFailure));
                    payloadStopConfirmed[0] = false;
                }
            }
            return payloadStopConfirmed[0];
        };

        final LaneStarter accelStarter = () -> {
            // Accel-side selection, recorded for the reviewer: the product
            // PvmPayloadLaneStarter builds a ProtectedLaneRuntime whose
            // FastLaneSession stays private, so the product path exposes no VM
            // handle and no vsock factory — exactly what the bridge's channel
            // factory needs. This mode therefore reuses the compiler mode's QA
            // VM (same payload, memory and port spec) wrapped in a LaneRuntime
            // adapter; readiness semantics match ProtectedLaneRuntime
            // (isReady = payloadReady), only the handle visibility differs.
            final Object config = buildConfig(target, payloadName, memoryMb, notes);
            if (config == null) {
                throw new LaneStartException("ACCEL_START_FAILED");
            }
            final Object vm = createPayloadVm(manager, shape.manager(), configClass, config,
                    payloadVmName, notes);
            if (vm == null) {
                throw new LaneStartException("ACCEL_START_FAILED");
            }
            payloadVm[0] = vm;
            installCallback(vm, payloadState, notes);
            try {
                Class.forName("android.system.virtualmachine.VirtualMachine")
                        .getMethod("run").invoke(vm);
            } catch (InvocationTargetException wrapped) {
                notes.add("accel run: " + describe(wrapped.getCause()));
                throw new LaneStartException("ACCEL_START_FAILED");
            } catch (ReflectiveOperationException failure) {
                notes.add("accel run: " + exceptionName(failure));
                throw new LaneStartException("ACCEL_START_FAILED");
            }
            trace("accel vm started");
            return new QaPayloadLaneRuntime(payloadState, stopPayloadVm);
        };

        // The coordinator is built directly instead of through
        // AcceleratorLaneAssembly: this run wants the ACCEL lane armed
        // deterministically, and the policy verdict is not the gate under
        // test. Every adapter is still the production one.
        final DualLaneCoordinator coordinator = new DualLaneCoordinator(
                budget,
                qemuRequest,
                accelRequest,
                qemuStarter,
                accelStarter,
                new DualLaneCoordinator.Config(
                        AcceleratorLaunchDispatcher.QEMU_READY_TIMEOUT_MS,
                        AcceleratorLaunchDispatcher.STOP_GRACE_MS),
                LaneClock.system(),
                eventSink);
        // Mirror of MainActivity's decision line (1624-1627): a direct build
        // produces no AcceleratorDecision JSON, so the line names the factory
        // that served the lane instead.
        Log.i("OrchestellaAccelerator", "backend=qa-pvm-payload decision=coordinator-direct");
        evidence.put("backend", "qa-pvm-payload");
        trace("coordinator assembled; startAll");

        final DualLaneCoordinator.StartReport startReport;
        try {
            startReport = coordinator.startAll();
        } catch (RuntimeException startFailure) {
            notes.add("startAll: " + exceptionName(startFailure));
            stopPayloadVm.getAsBoolean();
            return finishEvidence(evidence, results, "QA_START_ALL_FAILED", notes,
                    payloadStopConfirmed[0]);
        }
        final JSONObject startStates = new JSONObject();
        startStates.put("qemu", startReport.qemu().name());
        startStates.put("accel", startReport.accel().name());
        startStates.put("reject_code", startReport.rejectCode().name());
        evidence.put("start_report", startStates);
        trace("startAll done qemu=" + startReport.qemu().name()
                + " accel=" + startReport.accel().name());

        // The production gate under test (AX-01): compile work calls this
        // before any connection; a timeout means "build locally".
        final long gateStarted = SystemClock.uptimeMillis();
        final boolean gateReady = coordinator.awaitAcceleratorReady(gateDeadlineMs);
        final long gateMs = SystemClock.uptimeMillis() - gateStarted;
        evidence.put("await_accelerator_ready", gateReady);
        evidence.put("gate_ms", gateMs);
        evidence.put("fallback_count", gateReady ? 0 : 1);
        trace("gate ready=" + gateReady + " ms=" + gateMs);
        if (!gateReady) {
            // Mirror of the production consumer: a gate timeout is the local
            // compile fallback, recorded as exactly that, then a clean stop in
            // the coordinator's own order (QEMU first, accelerator second).
            evidence.put("stage", "gate_fallback");
            writeEvidence(evidence, results);
            final DualLaneCoordinator.StopReport stopReport = coordinator.stopAll(
                    AcceleratorLaunchDispatcher.STOP_GRACE_MS);
            recordStopReport(evidence, stopReport);
            final boolean payloadStopped = stopPayloadVm.getAsBoolean();
            return finishEvidence(evidence, results, "ACCEL_GATE_TIMEOUT_FALLBACK", notes,
                    payloadStopped);
        }

        final AtomicInteger completed = new AtomicInteger();
        final JSONArray connections = new JSONArray();
        // Live wiring state, identical to compiler mode: without it a stalled
        // job is indistinguishable from a stalled bridge. Counters only.
        final long[] lastConnectMs = new long[] {-1L};
        final String[] lastConnectError = new String[] {null};
        final VsockChannel[] currentChannel = new VsockChannel[1];

        DistccTcpVsockBridge bridge = null;
        try {
            // The bridge's channel factory MUST be the AXP1 layer: the payload
            // expects the AXP1 HOST handshake (token + compiler identity) on
            // connect. A raw vsock factory makes the payload reject the first
            // bytes (CHANNEL_FAILED within milliseconds), and distcc then
            // silently falls back to local compilation.
            bridge = new DistccTcpVsockBridge(
                    InetAddress.getByName("127.0.0.1"),
                    bridgePort,
                    new Axp1DistccChannelFactory(
                            () -> {
                                final long openedAt = SystemClock.uptimeMillis();
                                try {
                                    final FastLaneChannel channel =
                                            openVsockChannel(payloadVm[0], vsockPort);
                                    lastConnectMs[0] = SystemClock.uptimeMillis() - openedAt;
                                    if (channel instanceof VsockChannel) {
                                        currentChannel[0] = (VsockChannel) channel;
                                    }
                                    return channel;
                                } catch (IOException | RuntimeException failure) {
                                    lastConnectError[0] =
                                            exceptionName(failure) + ":" + safeMessage(failure);
                                    throw failure;
                                }
                            },
                            token,
                            Axp1Capabilities.builder()
                                    .distcc(true)
                                    .compilerTriple(REQUIRED_TRIPLE)
                                    .maxFrame(AXP1_MAX_FRAME)
                                    .build(),
                            REQUIRED_TRIPLE,
                            new Axp1Limits(3_000L, 4 * AXP1_MAX_FRAME, AXP1_MAX_FRAME)),
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
            trace("bridge started port=" + bridgePort);
        } catch (IOException | RuntimeException bridgeFailure) {
            notes.add("bridge: " + exceptionName(bridgeFailure)
                    + "(" + safeMessage(bridgeFailure) + ")");
            final DualLaneCoordinator.StopReport stopReport = coordinator.stopAll(
                    AcceleratorLaunchDispatcher.STOP_GRACE_MS);
            recordStopReport(evidence, stopReport);
            final boolean payloadStopped = stopPayloadVm.getAsBoolean();
            return finishEvidence(evidence, results, "QA_BRIDGE_FAILED", notes, payloadStopped);
        }

        // Published before the guest needs it: the driver polls this file,
        // confirms the contract port and only then drives the guest console.
        evidence.put("stage", "bridge_ready");
        writeEvidence(evidence, results);
        trace("bridge_ready port=" + bridgePort);

        final long bridgeStarted = SystemClock.uptimeMillis();
        long lastPublish = 0L;
        int ticks = 0;
        evidence.put("stage", "waiting_jobs");
        writeEvidence(evidence, results);
        trace("waiting_jobs entered");
        // Same independent beater as compiler mode: lines that keep landing
        // while the wait loop does not mean only the instrumentation thread
        // is stuck; counters say which leg the bytes stop on.
        final AtomicBoolean beating = new AtomicBoolean(true);
        final Thread beater = new Thread(() -> {
            int beat = 0;
            while (beating.get() && beat < 900) {
                final VsockChannel open = currentChannel[0];
                trace("beat " + beat
                        + " completed=" + completed.get()
                        + " to_guest=" + (open == null ? -1L : open.bytesToGuest())
                        + " from_guest=" + (open == null ? -1L : open.bytesFromGuest())
                        + " finished=" + payloadState.payloadFinished
                        + " connect_ms=" + lastConnectMs[0]);
                beat++;
                SystemClock.sleep(2_000L);
            }
        }, "pvm-cxcf-beat");
        beater.setDaemon(true);
        beater.start();
        while (completed.get() < jobs
                && SystemClock.uptimeMillis() - bridgeStarted < jobDeadlineMs
                && !payloadState.errored && !payloadState.stopped
                && !payloadState.payloadFinished) {
            ticks++;
            final long elapsed = SystemClock.uptimeMillis() - bridgeStarted;
            if (elapsed - lastPublish >= 5_000L) {
                lastPublish = elapsed;
                final VsockChannel open = currentChannel[0];
                final JSONObject live = new JSONObject();
                live.put("elapsed_ms", elapsed);
                live.put("ticks", ticks);
                live.put("connections_completed", completed.get());
                live.put("vsock_connect_ms", lastConnectMs[0] < 0
                        ? JSONObject.NULL : lastConnectMs[0]);
                live.put("vsock_connect_error", lastConnectError[0] == null
                        ? JSONObject.NULL : lastConnectError[0]);
                live.put("to_guest_bytes", open == null ? 0L : open.bytesToGuest());
                live.put("from_guest_bytes", open == null ? 0L : open.bytesFromGuest());
                live.put("payload_finished", payloadState.payloadFinished);
                // Trace only; the JSON file is written at stage boundaries
                // (same reason as compiler mode).
                trace("heartbeat " + live.toString());
            }
            SystemClock.sleep(200);
        }
        beating.set(false);
        evidence.put("stage", "jobs_done");
        writeEvidence(evidence, results);
        trace("jobs_done completed=" + completed.get());
        final long bridgeElapsedMs = SystemClock.uptimeMillis() - bridgeStarted;
        try {
            bridge.close();
        } catch (RuntimeException closeFailure) {
            notes.add("bridge close: " + exceptionName(closeFailure));
        }
        evidence.put("bridge_elapsed_ms", bridgeElapsedMs);
        synchronized (connections) {
            evidence.put("connections", connections);
        }
        evidence.put("connections_completed", completed.get());

        // Coordinator stop is QEMU first, then ACCEL — the QA payload VM is
        // stopped through the lane adapter inside that order, and the
        // memoized backstop only fills the gap when the lane never held it.
        final DualLaneCoordinator.StopReport stopReport =
                coordinator.stopAll(AcceleratorLaunchDispatcher.STOP_GRACE_MS);
        recordStopReport(evidence, stopReport);
        trace("coordinator stopped");
        final boolean payloadStopped = stopPayloadVm.getAsBoolean();
        if (payloadState.payloadFinished) {
            evidence.put("exit_code_after_stop", payloadState.exitCode);
        }
        evidence.put("vm_error_code", payloadState.errored ? payloadState.errorCode : JSONObject.NULL);

        String failureCode = null;
        if (payloadState.errored) {
            failureCode = "VM_ERROR";
        } else if (completed.get() < jobs) {
            failureCode = "BRIDGE_JOBS_INCOMPLETE";
        } else if (!payloadStopped) {
            failureCode = "QA_STOP_UNCONFIRMED";
        }
        evidence.put("stage", "done");
        return finishEvidence(evidence, results, failureCode, notes, payloadStopped);
    }

    /**
     * Finds the provisioned Linux guest the crossfire run drives. The QA
     * environment provisions it before the run (app UI or coordinator prep);
     * the instrumentation never creates instances. An explicit id wins.
     *
     * <p>Alpine is preferred (the distro the 02a plan pinned), but the product
     * catalog (DistroCatalogRegistry) ships no Alpine entry, so any stopped
     * provisioned guest is accepted and its distro id is published — the
     * driver installs distcc/clang with the matching package manager and
     * still hard-checks the clang major against the payload.
     */
    private static InstanceLifecycle.Instance pickQemuInstance(
            Context target, String requestedInstanceId, List<String> notes) {
        try {
            final InstanceLifecycle.Store store = new InstanceLifecycle.FileStore(
                    target.getFilesDir().toPath().resolve("instances"));
            final List<InstanceLifecycle.Instance> instances = store.list();
            if (requestedInstanceId != null && !requestedInstanceId.isEmpty()) {
                for (InstanceLifecycle.Instance candidate : instances) {
                    if (candidate.id().equals(requestedInstanceId)) {
                        return candidate;
                    }
                }
                notes.add("requested instance not found");
                return null;
            }
            InstanceLifecycle.Instance recoverable = null;
            for (InstanceLifecycle.Instance candidate : instances) {
                final boolean alpine = "alpine".equals(candidate.distro().id());
                if (candidate.state() == InstanceLifecycle.State.STOPPED) {
                    if (alpine) {
                        return candidate;
                    }
                    if (recoverable == null) {
                        recoverable = candidate;
                    }
                    continue;
                }
                // Interrupted/crashed guests are cold-restorable in production;
                // a CREATED instance is not provisioned and stays out.
                if (recoverable == null && (candidate.state() == InstanceLifecycle.State.INTERRUPTED
                        || candidate.state() == InstanceLifecycle.State.CRASHED)) {
                    recoverable = candidate;
                }
            }
            if (recoverable != null) {
                notes.add("guest instance distro=" + recoverable.distro().id()
                        + " state=" + recoverable.state().name());
            }
            return recoverable;
        } catch (Exception storeFailure) {
            notes.add("instance store: " + exceptionName(storeFailure));
            return null;
        }
    }

    /**
     * Create + delete/retry, extracted from the shape the compiler modes use
     * verbatim: a leftover instance directory from a killed run is always
     * this same test's debris, because the name derives from the run token.
     */
    private static Object createPayloadVm(Object manager, Class<?> managerClass,
            Class<?> configClass, Object config, String name, List<String> notes) {
        try {
            return managerClass.getMethod("create", String.class, configClass)
                    .invoke(manager, name, config);
        } catch (InvocationTargetException wrapped) {
            notes.add("create retry after: " + describe(wrapped.getCause()));
            try {
                managerClass.getMethod("delete", String.class).invoke(manager, name);
                return managerClass.getMethod("create", String.class, configClass)
                        .invoke(manager, name, config);
            } catch (InvocationTargetException secondFailure) {
                notes.add("create: " + describe(secondFailure.getCause()));
                return null;
            } catch (ReflectiveOperationException unavailable) {
                notes.add("create retry unavailable: " + exceptionName(unavailable));
                return null;
            }
        } catch (ReflectiveOperationException failure) {
            notes.add("create: " + exceptionName(failure));
            return null;
        }
    }

    /** Stop outcomes per lane, in execution order; codes only, no session data. */
    private static void recordStopReport(JSONObject evidence,
            DualLaneCoordinator.StopReport report) throws JSONException {
        final JSONObject recorded = new JSONObject();
        recorded.put("qemu", report.qemu().name());
        recorded.put("accel", report.accel().name());
        evidence.put("coordinator_stop", recorded);
    }

    /** Same disk envelope rule as AcceleratorLaunchDispatcher.gibibytes. */
    private static long gibibytes(long bytes) {
        return bytes <= 0L ? 1L : Math.max(1L, (bytes + (1L << 30) - 1L) >> 30);
    }

    /** One lane event as name/code/duration only — the sink adds no payloads. */
    private static JSONObject eventRecord(LaneEvent event) throws JSONException {
        final JSONObject record = new JSONObject();
        record.put("name", event.name());
        record.put("lane", event.lane().name());
        record.put("code", event.code());
        record.put("duration_ms", event.durationMs());
        return record;
    }

    /**
     * ACCEL lane adapter over this mode's QA payload VM. Readiness and
     * liveness read the same callback state the compiler modes use, and stop
     * delegates to the shared memoized stop — the adapter adds no second stop
     * implementation, mirroring ProtectedLaneRuntime's discipline.
     */
    /** Lane runtime over a directly spawned QEMU process (qaDirectQemu=1). */
    private static final class QaDirectQemuRuntime implements LaneRuntime {
        private final Process process;
        private final NetworkGatewayDaemon.Running gateway;

        QaDirectQemuRuntime(Process process, NetworkGatewayDaemon.Running gateway) {
            this.process = process;
            this.gateway = gateway;
        }

        @Override
        public LaneId laneId() {
            return LaneId.QEMU;
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public boolean isReady() {
            // Harness-level readiness: the driver verifies the guest shell
            // through the console probes; the lane is usable while QEMU runs.
            return process.isAlive();
        }

        @Override
        public LaneStopOutcome stop(long graceMs) {
            process.destroy();
            try {
                process.waitFor(graceMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            boolean stopped = !process.isAlive();
            if (!stopped) {
                process.destroyForcibly();
                stopped = !process.isAlive();
            }
            try {
                gateway.stop(graceMs);
            } catch (RuntimeException ignored) {
                // The QEMU stop is the confirmed part of the cleanup.
            }
            return stopped ? LaneStopOutcome.CONFIRMED : LaneStopOutcome.UNCONFIRMED;
        }
    }

    private static final class QaPayloadLaneRuntime implements LaneRuntime {
        private final CallbackState state;
        private final BooleanSupplier stop;

        QaPayloadLaneRuntime(CallbackState state, BooleanSupplier stop) {
            this.state = state;
            this.stop = stop;
        }

        @Override
        public LaneId laneId() {
            return LaneId.ACCEL;
        }

        @Override
        public boolean isAlive() {
            // The payload is a server: an exit before the gate closes is
            // death, exactly like an error or a stop.
            return !state.errored && !state.stopped && !state.payloadFinished;
        }

        @Override
        public boolean isReady() {
            return state.payloadReady;
        }

        @Override
        public LaneStopOutcome stop(long graceMs) {
            // graceMs is accepted for the LaneRuntime face; the platform stop
            // inside requestStop is already bounded by STOP_DEADLINE_MS.
            return stop.getAsBoolean() ? LaneStopOutcome.CONFIRMED : LaneStopOutcome.UNCONFIRMED;
        }
    }

    /** Reads at most {@code limit} bytes; the payload's own limits are tighter. */
    private static byte[] readBounded(File file, int limit) throws IOException {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (java.io.FileInputStream stream = new java.io.FileInputStream(file)) {
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (sink.size() + read > limit) {
                    throw new IOException("request exceeds " + limit + " bytes");
                }
                sink.write(buffer, 0, read);
            }
        }
        return sink.toByteArray();
    }

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
        trace("runCompiler start");

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
        Object createdVm;
        try {
            createdVm = shape.manager().getMethod("create", String.class, configClass)
                    .invoke(manager, name, config);
        } catch (InvocationTargetException wrapped) {
            // A previous run that was killed leaves the instance directory
            // behind, and create() then refuses the name. Delete and retry once:
            // the name is derived from the run token, so the leftover is always
            // this same test's debris.
            notes.add("create retry after: " + describe(wrapped.getCause()));
            try {
                shape.manager().getMethod("delete", String.class).invoke(manager, name);
                createdVm = shape.manager().getMethod("create", String.class, configClass)
                        .invoke(manager, name, config);
            } catch (InvocationTargetException secondFailure) {
                notes.add("create: " + describe(secondFailure.getCause()));
                return finishEvidence(evidence, results, "QA_CREATE_FAILED", notes, false);
            } catch (ReflectiveOperationException unavailable) {
                notes.add("create retry unavailable: " + exceptionName(unavailable));
                return finishEvidence(evidence, results, "QA_CREATE_FAILED", notes, false);
            }
        }
        // The vsock connector and the bridge factory capture this reference.
        final Object vm = createdVm;
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
        trace("payload_ready=" + state.payloadReady + " ms=" + (SystemClock.uptimeMillis() - runStarted));
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
        // Live wiring state. Without it a stalled job is indistinguishable from
        // a stalled bridge: the host only sees an idle socket. Every number
        // here is a counter, never payload bytes.
        final long[] lastConnectMs = new long[] {-1L};
        final String[] lastConnectError = new String[] {null};
        final VsockChannel[] currentChannel = new VsockChannel[1];

        DistccTcpVsockBridge bridge = null;
        try {
            bridge = new DistccTcpVsockBridge(
                    InetAddress.getByName("127.0.0.1"),
                    0,
                    () -> {
                        final long openedAt = SystemClock.uptimeMillis();
                        try {
                            final FastLaneChannel channel = openVsockChannel(vm, vsockPort);
                            lastConnectMs[0] = SystemClock.uptimeMillis() - openedAt;
                            if (channel instanceof VsockChannel) {
                                currentChannel[0] = (VsockChannel) channel;
                            }
                            return channel;
                        } catch (IOException | RuntimeException failure) {
                            lastConnectError[0] =
                                    exceptionName(failure) + ":" + safeMessage(failure);
                            throw failure;
                        }
                    },
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
            trace("bridge started");
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
        trace("bridge_ready port=" + bridge.port());

        final long bridgeStarted = SystemClock.uptimeMillis();
        long lastPublish = 0L;
        int ticks = 0;
        // Stage markers, written before anything that could block: a stalled
        // run then says exactly which call it stopped on. bridge_active is
        // deliberately absent — it takes the bridge's connection lock, and a
        // diagnostic must never be the thing that hangs.
        evidence.put("stage", "waiting_jobs");
        writeEvidence(evidence, results);
        trace("waiting_jobs entered");
        // Independent beater. If these lines keep landing while the loop above
        // does not, the process is alive and only the instrumentation thread is
        // stuck; if they stop too, the whole process is being suspended. The
        // byte counters say which leg of the path the data stops on.
        final java.util.concurrent.atomic.AtomicBoolean beating =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        final Thread beater = new Thread(() -> {
            int beat = 0;
            while (beating.get() && beat < 900) {
                final VsockChannel open = currentChannel[0];
                trace("beat " + beat
                        + " completed=" + completed.get()
                        + " to_guest=" + (open == null ? -1L : open.bytesToGuest())
                        + " from_guest=" + (open == null ? -1L : open.bytesFromGuest())
                        + " finished=" + state.payloadFinished
                        + " connect_ms=" + lastConnectMs[0]);
                beat++;
                SystemClock.sleep(2_000L);
            }
        }, "pvm-csdl-beat");
        beater.setDaemon(true);
        beater.start();
        while (completed.get() < jobs
                && SystemClock.uptimeMillis() - bridgeStarted < jobDeadlineMs
                && !state.errored && !state.stopped && !state.payloadFinished) {
            ticks++;
            final long elapsed = SystemClock.uptimeMillis() - bridgeStarted;
            if (elapsed - lastPublish >= 5_000L) {
                lastPublish = elapsed;
                final VsockChannel open = currentChannel[0];
                final JSONObject live = new JSONObject();
                live.put("elapsed_ms", elapsed);
                live.put("ticks", ticks);
                live.put("connections_completed", completed.get());
                live.put("vsock_connect_ms", lastConnectMs[0] < 0
                        ? JSONObject.NULL : lastConnectMs[0]);
                live.put("vsock_connect_error", lastConnectError[0] == null
                        ? JSONObject.NULL : lastConnectError[0]);
                // Counter-only progress: where the bytes stop tells whether the
                // stall is host->bridge, bridge->guest, or guest->bridge.
                live.put("to_guest_bytes", open == null ? 0L : open.bytesToGuest());
                live.put("from_guest_bytes", open == null ? 0L : open.bytesFromGuest());
                live.put("payload_finished", state.payloadFinished);
                // Trace only. The JSON evidence file is written at stage
                // boundaries; re-serialising it every tick proved to be the one
                // thing that could stall the wait loop, and a diagnostic must
                // never be able to hang the job it is watching.
                trace("heartbeat " + live.toString());
            }
            SystemClock.sleep(200);
        }
        beating.set(false);
        evidence.put("stage", "jobs_done");
        writeEvidence(evidence, results);
        trace("jobs_done completed=" + completed.get());
        final long bridgeElapsedMs = SystemClock.uptimeMillis() - bridgeStarted;
        // DistccTcpVsockBridge.close() narrows Closeable and throws nothing
        // checked; a runtime failure here must not skip the VM teardown below.
        try {
            bridge.close();
        } catch (RuntimeException closeFailure) {
            notes.add("bridge close: " + exceptionName(closeFailure));
        }
        evidence.put("bridge_elapsed_ms", bridgeElapsedMs);
        synchronized (connections) {
            evidence.put("connections", connections);
        }
        evidence.put("connections_completed", completed.get());

        // The payload is a server: it only finishes because we stop it, so its
        // exit code is recorded for completeness and never used as the verdict.
        trace("stopping vm");
        final boolean stopped = requestStop(evidence, manager, shape.manager(), vm, name, notes);
        trace("vm stopped=" + stopped);
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
        private final java.util.concurrent.atomic.AtomicLong toGuest =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong fromGuest =
                new java.util.concurrent.atomic.AtomicLong();

        VsockChannel(ParcelFileDescriptor descriptor) throws IOException {
            this.writeEnd = descriptor.dup();
            final InputStream rawInput =
                    new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            this.input = new java.io.FilterInputStream(rawInput) {
                @Override
                public int read() throws IOException {
                    final int value = super.read();
                    if (value >= 0) {
                        fromGuest.incrementAndGet();
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    final int read = super.read(buffer, offset, length);
                    if (read > 0) {
                        fromGuest.addAndGet(read);
                    }
                    return read;
                }
            };
            this.output = new java.io.FilterOutputStream(
                    new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)) {
                // Write straight to the underlying stream: FilterOutputStream's
                // bulk write fans out through write(int), which would count every
                // byte twice.
                @Override
                public void write(int value) throws IOException {
                    out.write(value);
                    toGuest.incrementAndGet();
                }

                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    out.write(buffer, offset, length);
                    toGuest.addAndGet(length);
                }
            };
        }

        long bytesToGuest() {
            return toGuest.get();
        }

        long bytesFromGuest() {
            return fromGuest.get();
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
        final String mode = arguments.getString("qaMode");
        final boolean compiler = MODE_COMPILER.equals(mode) || MODE_COMPILER_FILE.equals(mode)
                || MODE_COMPILER_CROSSFIRE.equals(mode);
        return compiler ? "pvm-compiler" : "pvm-probe";
    }

    /**
     * Append-only plain-text progress log, independent of the evidence JSON.
     * If a run stalls where nothing else lands, these lines still say which
     * call it stopped on.
     */
    private void trace(String message) {
        final String token = arguments.getString("qaRunToken");
        if (token == null) {
            return;
        }
        final File dir = new File(getTargetContext().getFilesDir(), "avf-qa");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return;
        }
        final File trace = new File(dir, evidencePrefix() + "-" + token + ".trace.txt");
        try (FileOutputStream stream = new FileOutputStream(trace, true)) {
            final String line = (SystemClock.uptimeMillis() / 1000L) + "s " + message + "\n";
            stream.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Diagnostics must never fail the run.
        }
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
