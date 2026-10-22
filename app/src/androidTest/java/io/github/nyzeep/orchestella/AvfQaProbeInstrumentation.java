package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformFacts;
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfPlatformAccess;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * QA-only AVF qualification and launch probe.
 *
 * <p>This class lives in androidTest, is declared by the test APK manifest and
 * never ships in the application APK. It writes one small JSON document to the
 * QA app's private files directory; the host pulls it with run-as.
 *
 * <p>Four QA modes exist:
 *
 * <ul>
 *   <li>{@code qualification} only observes platform facts and never creates
 *       a VM;</li>
 *   <li>{@code protected-microdroid} runs a system Microdroid payload in a
 *       protected VM; it does not load a custom kernel or root filesystem and
 *       never enables networking;</li>
 *   <li>{@code custom-linux} runs a QA-private custom image on a device that
 *       advertises the non-protected capability bit; it refuses otherwise;</li>
 *   <li>{@code fastlane-dv01} runs the protected Fast Lane payload lifecycle,
 *       terminal PTY, encrypted-storage persistence and 20-cycle stop gate. It
 *       requires a package id containing '.qa.dv01'.</li>
 * </ul>
 *
 * <p>The probe refuses to run unless the target package is a QA channel
 * (contains '.qa.'). It records no serial, IMEI, MAC, account, token, user path
 * or screen content; the ROM fingerprint is stored only as SHA-256.
 */
public final class AvfQaProbeInstrumentation extends Instrumentation {
    private static final String QA_DIRECTORY = "avf-qa";
    private static final String MODE_QUALIFICATION = "qualification";
    private static final String MODE_PROTECTED_MICRODROID = "protected-microdroid";
    private static final String MODE_CUSTOM_LINUX = "custom-linux";
    private static final String MODE_FASTLANE_DV01 = "fastlane-dv01";
    private static final String[] LAUNCH_STAGES = {
            "create", "run_returned", "vsock_handshake", "stop_cycles"
    };
    private static final String[] OPTIONAL_STAGES = {
            "network", "share_display", "app_death", "qemu_comparison"
    };
    private static final long MICRODROID_MEMORY_BYTES = 256L * 1024L * 1024L;
    private static final int DEFAULT_STOP_DEADLINE_MS = 15000;
    private static final int MAX_STOP_DEADLINE_MS = 60000;
    private static final int DEFAULT_CUSTOM_MEMORY_MB = 1024;
    private static final int DEFAULT_CONTROL_VSOCK_PORT = 9100;
    private static final int DEFAULT_TERMINAL_VSOCK_PORT = 9101;
    private static final int DEFAULT_VSOCK_TIMEOUT_MS = 120000;
    private static final int MAX_VSOCK_TIMEOUT_MS = 300000;
    private static final int STOPPED_STATUS = 0;
    private static final String DEFAULT_CUSTOM_KERNEL = "files/avf-qa/custom/kernel";
    private static final String DEFAULT_CUSTOM_INITRD = "files/avf-qa/custom/initrd";
    private static final String DEFAULT_CUSTOM_ROOT = "files/avf-qa/custom/root.img";

    private Bundle arguments;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? Bundle.EMPTY : arguments;
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            Context target = getTargetContext();
            if (target == null) {
                fail(result, "QA_TARGET_CONTEXT_MISSING");
                return;
            }
            String packageName = target.getPackageName();
            if (!packageName.contains(".qa.")) {
                fail(result, "QA_PACKAGE_REQUIRED");
                return;
            }
            String token = arguments.getString("qaRunToken");
            if (token == null || !token.matches("[0-9a-f]{16,64}")) {
                fail(result, "QA_RUN_TOKEN_REJECTED");
                return;
            }
            String mode = arguments.getString("qaMode", MODE_QUALIFICATION);
            if (!MODE_QUALIFICATION.equals(mode)
                    && !MODE_PROTECTED_MICRODROID.equals(mode)
                    && !MODE_CUSTOM_LINUX.equals(mode)
                    && !MODE_FASTLANE_DV01.equals(mode)) {
                fail(result, "QA_MODE_REJECTED");
                return;
            }
            int stopDeadlineMs;
            try {
                stopDeadlineMs = boundedInt(arguments.getString("qaStopDeadlineMs"),
                        DEFAULT_STOP_DEADLINE_MS, 1000, MAX_STOP_DEADLINE_MS);
            } catch (NumberFormatException rejected) {
                fail(result, "QA_STOP_DEADLINE_REJECTED");
                return;
            }

            // The app normally installs this from attachBaseContext; repeating
            // it is idempotent and keeps the probe correct if it is launched
            // before the Application hook on an unusual ROM.
            AvfHiddenApiExemption.installOnce();

            if (MODE_FASTLANE_DV01.equals(mode)) {
                if (!packageName.contains(".qa.dv01")) {
                    fail(result, "QA_DV01_PACKAGE_REQUIRED");
                    return;
                }
                runFastlaneDv01(target, arguments, token, stopDeadlineMs, result);
                return;
            }

            ReflectiveAvfPlatformAccess access = new ReflectiveAvfPlatformAccess(
                    target, target.getClassLoader(), AvfHiddenApiExemption::installOnce,
                    romBinding());
            AvfPlatformFacts facts = access.observe();

            JSONObject root = new JSONObject();
            root.put("schema_version", 1);
            root.put("kind", "avf_qa_probe");
            root.put("qa_package", packageName);
            root.put("mode", mode);
            root.put("device_facts", deviceFacts(target));
            root.put("qualification", qualification(target, access, facts));
            root.put("stage_results", stageResults(
                    mode, target, access, facts, arguments, stopDeadlineMs));

            File directory = new File(target.getFilesDir(), QA_DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                fail(result, "QA_DIRECTORY_UNAVAILABLE");
                return;
            }
            File destination = new File(directory, "probe-" + token + ".json");
            writeAtomically(destination, root.toString().getBytes(StandardCharsets.UTF_8));
            result.putString("qa_probe_path",
                    "files/" + QA_DIRECTORY + "/probe-" + token + ".json");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // No exception text: it can contain a path or a platform message.
            fail(result, "QA_PROBE_FAILED");
        }
    }

    private void runFastlaneDv01(Context target, Bundle args, String token,
            int stopDeadlineMs, Bundle result) {
        try {
            JSONObject report = new FastLaneDv01Probe(target, args, stopDeadlineMs).run();
            File directory = new File(target.getFilesDir(), QA_DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                fail(result, "QA_DIRECTORY_UNAVAILABLE");
                return;
            }
            String name = "fastlane-dv01-" + token + ".json";
            File destination = new File(directory, name);
            writeAtomically(destination, report.toString(2).getBytes(StandardCharsets.UTF_8));
            result.putString("qa_probe_path", "files/" + QA_DIRECTORY + "/" + name);
            result.putString("qa_outcome", report.optString("outcome", "unknown"));
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // No exception text: it can contain a path or a platform message.
            fail(result, "QA_FASTLANE_PROBE_FAILED");
        }
    }

    private void fail(Bundle result, String reasonCode) {
        result.putString("error", reasonCode);
        finish(Activity.RESULT_CANCELED, result);
    }

    private static int boundedInt(String value, int fallback, int minimum, int maximum) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        int parsed = Integer.parseInt(value);
        if (parsed < minimum || parsed > maximum) {
            throw new NumberFormatException("out of range");
        }
        return parsed;
    }

    private static JSONObject deviceFacts(Context target) throws JSONException {
        JSONObject facts = new JSONObject();
        facts.put("sdk_int", Build.VERSION.SDK_INT);
        JSONArray abis = new JSONArray();
        for (String abi : Build.SUPPORTED_ABIS) {
            abis.put(abi);
        }
        facts.put("abis", abis);
        facts.put("feature", target.getPackageManager().hasSystemFeature(
                "android.software.virtualization_framework"));
        facts.put("build_id", safeToken(Build.ID, 96));
        facts.put("release", safeToken(Build.VERSION.RELEASE, 32));
        facts.put("fingerprint_sha256",
                sha256(Build.FINGERPRINT == null ? "unknown" : Build.FINGERPRINT));
        return facts;
    }

    private static JSONObject qualification(Context target, ReflectiveAvfPlatformAccess access,
            AvfPlatformFacts facts) throws JSONException {
        JSONObject qualification = new JSONObject();
        qualification.put("access_state", facts.accessState().name());
        qualification.put("exemption_state", facts.exemptionState().name());
        qualification.put("manager_present", facts.managerPresent());
        AvfCapabilityEvaluator.AvfModeFacts mode = facts.modeFacts();
        qualification.put("non_protected",
                mode.nonProtectedVm() == AvfCapabilityEvaluator.VmSupport.SUPPORTED);
        qualification.put("protected",
                mode.protectedVm() == AvfCapabilityEvaluator.VmSupport.SUPPORTED);
        qualification.put("capabilities_raw", readRawCapabilities(target, access));
        qualification.put("manage_permission", permission(target,
                ReflectiveAvfPlatformAccess.MANAGE_PERMISSION));
        qualification.put("custom_permission", permission(target,
                ReflectiveAvfPlatformAccess.CUSTOM_PERMISSION));
        ProtectedMode protectedMode = protectedMode(target);
        qualification.put("protected_mode", protectedMode.state);
        qualification.put("protected_mode_exception",
                protectedMode.exception == null ? JSONObject.NULL : protectedMode.exception);
        return qualification;
    }

    /** The raw bit mask is diagnostic only; a failed read is -1, never a guess. */
    private static int readRawCapabilities(Context target, ReflectiveAvfPlatformAccess access) {
        try {
            ReflectiveAvfPlatformAccess.PlatformShape shape = access.loadPlatformShape();
            Object manager = target.getSystemService(shape.manager());
            if (!shape.manager().isInstance(manager)) {
                return -1;
            }
            Object value = shape.manager().getMethod("getCapabilities").invoke(manager);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable unreadable) {
            return -1;
        }
    }

    private static String permission(Context target, String permission) {
        try {
            return target.checkSelfPermission(permission) == 0 ? "GRANTED" : "DENIED";
        } catch (RuntimeException failure) {
            return "UNKNOWN";
        }
    }

    private static ProtectedMode protectedMode(Context target) {
        try {
            Class<?> builderClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachineConfig$Builder");
            Object builder = builderClass.getConstructor(Context.class).newInstance(target);
            builderClass.getMethod("setProtectedVm", boolean.class).invoke(builder, false);
            return new ProtectedMode("ACCEPTED", null);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof UnsupportedOperationException) {
                return new ProtectedMode("REJECTED", "UnsupportedOperationException");
            }
            return new ProtectedMode("UNREADABLE", exceptionName(cause));
        } catch (Throwable failure) {
            return new ProtectedMode("UNREADABLE", exceptionName(failure));
        }
    }

    private static JSONObject stageResults(String mode, Context target,
            ReflectiveAvfPlatformAccess access, AvfPlatformFacts facts, Bundle args,
            int stopDeadlineMs) throws JSONException {
        if (MODE_PROTECTED_MICRODROID.equals(mode)) {
            return protectedMicrodroidStages(target, access, facts, args, stopDeadlineMs);
        }
        if (MODE_CUSTOM_LINUX.equals(mode)) {
            return customLinuxStages(target, access, facts, args, stopDeadlineMs);
        }
        return qualificationStages();
    }

    private static JSONObject qualificationStages() throws JSONException {
        JSONObject results = defaultStages("QA_LAUNCH_NOT_REQUESTED");
        for (String stage : OPTIONAL_STAGES) {
            results.put(stage, blocked("QA_STAGE_NOT_IMPLEMENTED"));
        }
        return results;
    }

    private static JSONObject protectedMicrodroidStages(Context target,
            ReflectiveAvfPlatformAccess access, AvfPlatformFacts facts, Bundle args,
            int stopDeadlineMs) throws JSONException {
        JSONObject results = defaultStages("QA_LAUNCH_NOT_REQUESTED");
        if (!protectedAvailable(facts)) {
            return failStages(results, "QA_PROTECTED_VM_UNAVAILABLE");
        }
        if (!permissionsGranted(facts)) {
            return failStages(results, "QA_PERMISSION_DENIED");
        }
        String payload = args.getString("qaMicrodroidPayload");
        if (!safePayloadName(payload)) {
            return failStages(results, "QA_MICRODROID_PAYLOAD_MISSING");
        }
        int vsockPort = optionalPort(args.getString("qaVsockPort"));
        try {
            ReflectiveAvfPlatformAccess.PlatformShape shape = access.loadPlatformShape();
            Object manager = target.getSystemService(shape.manager());
            if (!shape.manager().isInstance(manager)) {
                return failStages(results, "QA_VM_MANAGER_UNAVAILABLE");
            }
            Class<?> vmClass = Class.forName("android.system.virtualmachine.VirtualMachine");
            Object config = microdroidConfig(target, payload);
            String name = qaInstanceName("qa-pvm-", args.getString("qaRunToken"));
            return executeLifecycle(results, manager, shape.manager(), vmClass, config, name,
                    vsockPort, 0, null, stopDeadlineMs, stopDeadlineMs,
                    "QA_MICRODROID_CONFIG_FAILED");
        } catch (Throwable failure) {
            return failStages(results, "QA_MICRODROID_CONFIG_FAILED");
        }
    }

    private static JSONObject customLinuxStages(Context target,
            ReflectiveAvfPlatformAccess access, AvfPlatformFacts facts, Bundle args,
            int stopDeadlineMs) throws JSONException {
        JSONObject results = defaultStages("QA_LAUNCH_NOT_REQUESTED");
        if (!nonProtectedAvailable(facts)) {
            return failStages(results, "NO_NON_PROTECTED_VM");
        }
        if (!permissionsGranted(facts)) {
            return failStages(results, "QA_PERMISSION_DENIED");
        }
        int controlPort = optionalPort(args.getString("qaVsockPort"));
        if (controlPort <= 0) {
            controlPort = DEFAULT_CONTROL_VSOCK_PORT;
        }
        int terminalPort = optionalPort(args.getString("qaVsockTerminalPort"));
        if (terminalPort <= 0) {
            terminalPort = DEFAULT_TERMINAL_VSOCK_PORT;
        }
        int memoryMb;
        int vsockTimeoutMs;
        try {
            memoryMb = boundedInt(args.getString("qaCustomMemoryMb"),
                    DEFAULT_CUSTOM_MEMORY_MB, 512, 4096);
            vsockTimeoutMs = boundedInt(args.getString("qaVsockTimeoutMs"),
                    DEFAULT_VSOCK_TIMEOUT_MS, 1000, MAX_VSOCK_TIMEOUT_MS);
        } catch (NumberFormatException rejected) {
            return failStages(results, "QA_CUSTOM_ARGUMENTS_REJECTED");
        }
        String bridgeToken = args.getString("qaBridgeToken");
        File kernel;
        File initrd;
        File root;
        try {
            kernel = artifact(target, args.getString("qaCustomKernel", DEFAULT_CUSTOM_KERNEL));
            initrd = artifact(target, args.getString("qaCustomInitrd", DEFAULT_CUSTOM_INITRD));
            root = artifact(target, args.getString("qaCustomRoot", DEFAULT_CUSTOM_ROOT));
        } catch (IOException rejected) {
            return failStages(results, "QA_CUSTOM_ARTIFACTS_MISSING");
        }
        if (!kernel.isFile() || !initrd.isFile() || !root.isFile()) {
            return failStages(results, "QA_CUSTOM_ARTIFACTS_MISSING");
        }
        try {
            ReflectiveAvfPlatformAccess.PlatformShape shape = access.loadPlatformShape();
            Object manager = target.getSystemService(shape.manager());
            if (!shape.manager().isInstance(manager)) {
                return failStages(results, "QA_VM_MANAGER_UNAVAILABLE");
            }
            Class<?> vmClass = Class.forName("android.system.virtualmachine.VirtualMachine");
            Object config = customConfig(target, kernel, initrd, root,
                    controlPort, terminalPort, memoryMb);
            String name = qaInstanceName("qa-custom-", args.getString("qaRunToken"));
            return executeLifecycle(results, manager, shape.manager(), vmClass, config, name,
                    controlPort, terminalPort, bridgeToken, vsockTimeoutMs, stopDeadlineMs,
                    "QA_CUSTOM_CONFIG_FAILED");
        } catch (Throwable failure) {
            return failStages(results, "QA_CUSTOM_CONFIG_FAILED");
        }
    }

    private static JSONObject executeLifecycle(JSONObject results, Object manager,
            Class<?> managerClass, Class<?> vmClass, Object config, String name, int vsockPort,
            int terminalPort, String bridgeToken, int vsockTimeoutMs, int stopDeadlineMs,
            String createFailure) throws JSONException {
        Object vm;
        try {
            Class<?> configClass = Class.forName(
                    "android.system.virtualmachine.VirtualMachineConfig");
            vm = managerClass.getMethod("create", String.class, configClass)
                    .invoke(manager, name, config);
        } catch (Throwable failure) {
            JSONObject create = blocked(createFailure);
            results.put("create", create);
            results.put("run_returned", blocked("PRIOR_STAGE_BLOCKED"));
            results.put("vsock_handshake", blocked("PRIOR_STAGE_BLOCKED"));
            results.put("stop_cycles", blockedStop("PRIOR_STAGE_BLOCKED"));
            return results;
        }
        if (vm == null || !vmClass.isInstance(vm)) {
            results.put("create", blocked(createFailure));
            results.put("run_returned", blocked("PRIOR_STAGE_BLOCKED"));
            results.put("vsock_handshake", blocked("PRIOR_STAGE_BLOCKED"));
            results.put("stop_cycles", blockedStop("PRIOR_STAGE_BLOCKED"));
            return results;
        }
        results.put("create", passed("VM_CREATED"));

        try {
            vmClass.getMethod("run").invoke(vm);
        } catch (Throwable failure) {
            results.put("run_returned", failed("RUN_FAILED"));
            results.put("vsock_handshake", blocked("PRIOR_STAGE_FAILED"));
            bestEffortStop(vmClass, vm, stopDeadlineMs);
            results.put("stop_cycles", blockedStop("PRIOR_STAGE_FAILED"));
            return results;
        }
        results.put("run_returned", passed("RUN_RETURNED"));

        if (vsockPort <= 0) {
            results.put("vsock_handshake", blocked("VSOCK_NOT_REQUESTED"));
        } else {
            String failure = attemptVsock(vmClass, vm, vsockPort, terminalPort,
                    bridgeToken, vsockTimeoutMs);
            results.put("vsock_handshake", failure == null
                    ? passed(terminalPort > 0 ? "VSOCK_AUTHENTICATED" : "VSOCK_CONNECTED")
                    : failed(failure));
        }

        boolean stopped = requestStopAndWait(vmClass, vm, stopDeadlineMs);
        results.put("stop_cycles", stopped ? stopped(true, "STOP_CONFIRMED")
                : stopped(false, "STOP_UNCONFIRMED"));
        if (stopped) {
            deleteQuietly(managerClass, manager, name);
        }
        return results;
    }

    private static Object microdroidConfig(Context target, String payload)
            throws ReflectiveOperationException {
        Class<?> builderClass = Class.forName(
                "android.system.virtualmachine.VirtualMachineConfig$Builder");
        Object builder = builderClass.getConstructor(Context.class).newInstance(target);
        selectMicrodroidOs(builderClass, builder);
        builderClass.getMethod("setProtectedVm", boolean.class).invoke(builder, true);
        builderClass.getMethod("setPayloadBinaryName", String.class).invoke(builder, payload);
        builderClass.getMethod("setMemoryBytes", long.class)
                .invoke(builder, MICRODROID_MEMORY_BYTES);
        try {
            builderClass.getMethod("setCpuTopology", int.class)
                    .invoke(builder, cpuTopologyOne());
        } catch (NoSuchMethodException ignored) {
            // Older revisions have a one-CPU default; the config stays honest.
        }
        return builderClass.getMethod("build").invoke(builder);
    }

    private static Object customConfig(Context target, File kernel, File initrd, File root,
            int controlPort, int terminalPort, int memoryMb)
            throws ReflectiveOperationException {
        Class<?> imageBuilderClass = Class.forName(
                "android.system.virtualmachine.VirtualMachineCustomImageConfig$Builder");
        Object imageBuilder = imageBuilderClass.getConstructor().newInstance();
        try {
            imageBuilderClass.getMethod("setName", String.class)
                    .invoke(imageBuilder, "qa-custom-image");
        } catch (NoSuchMethodException ignored) {
            // Optional on older revisions.
        }
        imageBuilderClass.getMethod("setKernelPath", String.class)
                .invoke(imageBuilder, kernel.getAbsolutePath());
        imageBuilderClass.getMethod("setInitrdPath", String.class)
                .invoke(imageBuilder, initrd.getAbsolutePath());
        // The guest initramfs selects the vsock data plane only when the port
        // announcements are present in /proc/cmdline. Without them it expects
        // virtio-serial devices that an AVF custom VM does not provide.
        addParam(imageBuilderClass, imageBuilder, "console=hvc0");
        addParam(imageBuilderClass, imageBuilder, "rdinit=/init");
        addParam(imageBuilderClass, imageBuilder, "panic=-1");
        addParam(imageBuilderClass, imageBuilder, "droidlayer.root_device=/dev/vda");
        addParam(imageBuilderClass, imageBuilder, "droidlayer.input_tag=droidlayer-input");
        addParam(imageBuilderClass, imageBuilder,
                "droidlayer.vsock_control_port=" + controlPort);
        addParam(imageBuilderClass, imageBuilder,
                "droidlayer.vsock_terminal_port=" + terminalPort);
        try {
            imageBuilderClass.getMethod("useNetwork", boolean.class)
                    .invoke(imageBuilder, false);
        } catch (NoSuchMethodException ignored) {
            // The default is no network; the explicit switch is optional.
        }
        Class<?> diskClass = Class.forName(
                "android.system.virtualmachine.VirtualMachineCustomImageConfig$Disk");
        Object disk = diskClass.getMethod("RWDisk", String.class)
                .invoke(null, root.getAbsolutePath());
        imageBuilderClass.getMethod("addDisk", diskClass).invoke(imageBuilder, disk);
        Object image = imageBuilderClass.getMethod("build").invoke(imageBuilder);

        Class<?> builderClass = Class.forName(
                "android.system.virtualmachine.VirtualMachineConfig$Builder");
        Object builder = builderClass.getConstructor(Context.class).newInstance(target);
        builderClass.getMethod("setCustomImageConfig",
                Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig"))
                .invoke(builder, image);
        builderClass.getMethod("setProtectedVm", boolean.class).invoke(builder, false);
        builderClass.getMethod("setMemoryBytes", long.class)
                .invoke(builder, memoryMb * 1024L * 1024L);
        try {
            builderClass.getMethod("setCpuTopology", int.class)
                    .invoke(builder, cpuTopologyOne());
        } catch (NoSuchMethodException ignored) {
            // Optional on older revisions.
        }
        return builderClass.getMethod("build").invoke(builder);
    }

    private static void addParam(Class<?> imageBuilderClass, Object imageBuilder, String value)
            throws ReflectiveOperationException {
        imageBuilderClass.getMethod("addParam", String.class).invoke(imageBuilder, value);
    }

    private static void selectMicrodroidOs(Class<?> builderClass, Object builder)
            throws ReflectiveOperationException {
        for (String methodName : new String[] {"setOsName", "setOs"}) {
            try {
                builderClass.getMethod(methodName, String.class).invoke(builder, "microdroid");
                return;
            } catch (NoSuchMethodException absent) {
                // Try the next revision name; the AOSP default is microdroid.
            }
        }
    }

    private static int cpuTopologyOne() {
        try {
            return Class.forName("android.system.virtualmachine.VirtualMachineConfig")
                    .getField("CPU_TOPOLOGY_ONE_CPU").getInt(null);
        } catch (ReflectiveOperationException absent) {
            return 0;
        }
    }

    private static boolean requestStopAndWait(Class<?> vmClass, Object vm, int deadlineMs) {
        try {
            vmClass.getMethod("stop").invoke(vm);
        } catch (Throwable stopFailed) {
            // Polling still decides whether the VM actually reached STOPPED.
        }
        long deadline = SystemClock.uptimeMillis() + deadlineMs;
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                Object status = vmClass.getMethod("getStatus").invoke(vm);
                if (status instanceof Integer && (Integer) status == STOPPED_STATUS) {
                    return true;
                }
            } catch (Throwable unreadable) {
                return false;
            }
            SystemClock.sleep(100);
        }
        return false;
    }

    private static void bestEffortStop(Class<?> vmClass, Object vm, int deadlineMs) {
        requestStopAndWait(vmClass, vm, deadlineMs);
    }

    private static String attemptVsock(Class<?> vmClass, Object vm, int controlPort,
            int terminalPort, String bridgeToken, int timeoutMs) {
        boolean authenticated = terminalPort > 0;
        if (authenticated && !validBridgeToken(bridgeToken)) {
            return "VSOCK_TOKEN_MISSING";
        }
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            String failure = attemptVsockOnce(vmClass, vm, controlPort, terminalPort,
                    authenticated ? bridgeToken : null, timeoutMs);
            if (failure == null) {
                return null;
            }
            if ("VSOCK_AUTH_FAILED".equals(failure) || "VSOCK_TOKEN_MISSING".equals(failure)) {
                return failure;
            }
            if (!"VSOCK_CONNECT_FAILED".equals(failure)
                    && !"VSOCK_TIMEOUT".equals(failure)
                    && !"VSOCK_TERMINAL_NO_BYTES".equals(failure)) {
                return failure;
            }
            SystemClock.sleep(500);
        }
        return "VSOCK_TIMEOUT";
    }

    private static String attemptVsockOnce(Class<?> vmClass, Object vm, int controlPort,
            int terminalPort, String bridgeToken, int timeoutMs) {
        ParcelFileDescriptor control = null;
        ParcelFileDescriptor terminal = null;
        ExecutorService executor = newDaemonExecutor();
        try {
            Object raw = connectVsock(executor, vmClass, vm, controlPort, timeoutMs);
            if (!(raw instanceof ParcelFileDescriptor)) {
                return "VSOCK_CONNECT_FAILED";
            }
            control = (ParcelFileDescriptor) raw;
            if (bridgeToken != null) {
                InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(control);
                OutputStream output = new FileOutputStream(control.getFileDescriptor());
                output.write(("DROIDLAYER/HELLO 1 1 " + bridgeToken + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.flush();
                Future<String> reply = executor.submit(() -> {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input, StandardCharsets.US_ASCII));
                    return reader.readLine();
                });
                String line = reply.get(
                        Math.max(1000, Math.min(10000, timeoutMs)), TimeUnit.MILLISECONDS);
                if (!"DROIDLAYER/READY 1".equals(line)) {
                    return "VSOCK_AUTH_FAILED";
                }
            }
            if (terminalPort > 0) {
                Object terminalRaw = connectVsock(
                        executor, vmClass, vm, terminalPort, timeoutMs);
                if (!(terminalRaw instanceof ParcelFileDescriptor)) {
                    return "VSOCK_TERMINAL_NO_BYTES";
                }
                terminal = (ParcelFileDescriptor) terminalRaw;
                InputStream terminalInput = new ParcelFileDescriptor.AutoCloseInputStream(terminal);
                Future<Integer> firstByte = executor.submit(
                        (Callable<Integer>) terminalInput::read);
                int value = firstByte.get(
                        Math.max(1000, Math.min(10000, timeoutMs)), TimeUnit.MILLISECONDS);
                if (value < 0) {
                    return "VSOCK_TERMINAL_NO_BYTES";
                }
            }
            return null;
        } catch (TimeoutException timeout) {
            return "VSOCK_TIMEOUT";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "VSOCK_INTERRUPTED";
        } catch (ExecutionException failure) {
            return "VSOCK_CONNECT_FAILED";
        } catch (IOException failure) {
            return "VSOCK_CONNECT_FAILED";
        } finally {
            closeQuietly(terminal);
            closeQuietly(control);
            executor.shutdownNow();
        }
    }

    private static Object connectVsock(ExecutorService executor, Class<?> vmClass, Object vm,
            int port, int timeoutMs) throws InterruptedException, TimeoutException,
            ExecutionException {
        Future<Object> future = executor.submit(() -> {
            try {
                return vmClass.getMethod("connectVsock", long.class)
                        .invoke(vm, (long) port);
            } catch (NoSuchMethodException absent) {
                return vmClass.getMethod("connectToVsockServer", int.class)
                        .invoke(vm, port);
            }
        });
        try {
            return future.get(
                    Math.max(1000, Math.min(10000, timeoutMs)), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw timeout;
        }
    }

    private static ExecutorService newDaemonExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "avf-qa-vsock");
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
            // The handshake result is already decided.
        }
    }

    private static boolean validBridgeToken(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void deleteQuietly(Class<?> managerClass, Object manager, String name) {
        try {
            managerClass.getMethod("delete", String.class).invoke(manager, name);
        } catch (Throwable ignored) {
            // Cleanup is best effort; the stopped state is already recorded.
        }
    }

    private static boolean protectedAvailable(AvfPlatformFacts facts) {
        return facts.modeFacts() != null
                && facts.modeFacts().protectedVm() == AvfCapabilityEvaluator.VmSupport.SUPPORTED;
    }

    private static boolean nonProtectedAvailable(AvfPlatformFacts facts) {
        return facts.modeFacts() != null
                && facts.modeFacts().nonProtectedVm()
                        == AvfCapabilityEvaluator.VmSupport.SUPPORTED;
    }

    private static boolean permissionsGranted(AvfPlatformFacts facts) {
        return facts.managePermission() == AvfCapabilityEvaluator.PermissionState.GRANTED
                && facts.customPermission() == AvfCapabilityEvaluator.PermissionState.GRANTED;
    }

    private static JSONObject defaultStages(String launchReason) throws JSONException {
        JSONObject results = new JSONObject();
        for (String stage : LAUNCH_STAGES) {
            results.put(stage, "stop_cycles".equals(stage)
                    ? blockedStop(launchReason) : blocked(launchReason));
        }
        for (String stage : OPTIONAL_STAGES) {
            results.put(stage, blocked("QA_STAGE_NOT_IMPLEMENTED"));
        }
        return results;
    }

    private static JSONObject failStages(JSONObject results, String reason) throws JSONException {
        for (String stage : LAUNCH_STAGES) {
            results.put(stage, "stop_cycles".equals(stage)
                    ? blockedStop(reason) : blocked(reason));
        }
        return results;
    }

    private static JSONObject passed(String reason) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("status", "pass");
        entry.put("reason_code", reason);
        return entry;
    }

    private static JSONObject failed(String reason) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("status", "fail");
        entry.put("reason_code", reason);
        return entry;
    }

    private static JSONObject blocked(String reason) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("status", "blocked");
        entry.put("reason_code", reason);
        return entry;
    }

    private static JSONObject blockedStop(String reason) throws JSONException {
        JSONObject entry = blocked(reason);
        entry.put("stop_confirmed", false);
        return entry;
    }

    private static JSONObject stopped(boolean confirmed, String reason) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("status", confirmed ? "pass" : "fail");
        entry.put("reason_code", reason);
        entry.put("stop_confirmed", confirmed);
        return entry;
    }

    private static File artifact(Context target, String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9_./-]{1,200}")
                || value.contains("..") || value.startsWith("/")) {
            throw new IOException("artifact path rejected");
        }
        String relative = value.startsWith("files/") ? value.substring("files/".length()) : value;
        File base = target.getFilesDir().getCanonicalFile();
        File artifact = new File(base, relative).getCanonicalFile();
        if (!artifact.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("artifact escaped the QA root");
        }
        return artifact;
    }

    private static String qaInstanceName(String prefix, String token) {
        String suffix = token == null ? "unknown" : token.substring(0, Math.min(12, token.length()));
        return prefix + suffix;
    }

    private static boolean safePayloadName(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                && value.endsWith(".so");
    }

    private static int optionalPort(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String exceptionName(Throwable failure) {
        if (failure == null) {
            return "Other";
        }
        String name = failure.getClass().getSimpleName();
        switch (name) {
            case "UnsupportedOperationException":
            case "SecurityException":
            case "NoSuchMethodException":
            case "ClassNotFoundException":
            case "NoClassDefFoundError":
                return name;
            default:
                return "Other";
        }
    }

    private static String safeToken(String value, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            return "unknown";
        }
        String candidate = value.trim();
        if (candidate.length() > maxLength
                || !candidate.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            return "unknown";
        }
        return candidate;
    }

    private static String romBinding() {
        return safeToken(Build.ID, 96) + "/" + safeToken(Build.VERSION.INCREMENTAL, 96);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xf, 16));
                builder.append(Character.forDigit(b & 0xf, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void writeAtomically(File destination, byte[] bytes) throws IOException {
        File temporary = new File(destination.getParentFile(),
                "." + destination.getName() + ".partial");
        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(bytes);
            stream.getFD().sync();
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("probe rename failed");
        }
    }

    private static final class ProtectedMode {
        final String state;
        final String exception;

        ProtectedMode(String state, String exception) {
            this.state = state;
            this.exception = exception;
        }
    }
}
