package io.github.nyzeep.orchestella;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformFacts;
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfPlatformAccess;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * QA-only AVF qualification probe.
 *
 * <p>This class lives in androidTest, is declared by the test APK manifest and
 * never ships in the application APK. It writes one small JSON document to the
 * QA app's private files directory; the host pulls it with run-as. It performs
 * no VM create/run/stop: the launch stages are reported as blocked rather than
 * guessed. A later QA launch harness may replace those entries.
 *
 * <p>The probe refuses to run unless the target package is a QA channel
 * (contains '.qa.'). It records no serial, IMEI, MAC, account, token, user path
 * or screen content; the ROM fingerprint is stored only as SHA-256.
 */
public final class AvfQaProbeInstrumentation extends Instrumentation {
    private static final String QA_DIRECTORY = "avf-qa";
    private static final String[] LAUNCH_STAGES = {
            "create", "run_returned", "vsock_handshake", "stop_cycles"
    };
    private static final String[] OPTIONAL_STAGES = {
            "network", "share_display", "app_death", "qemu_comparison"
    };

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

            // The app normally installs this from attachBaseContext; repeating
            // it is idempotent and keeps the probe correct if it is launched
            // before the Application hook on an unusual ROM.
            AvfHiddenApiExemption.installOnce();

            ReflectiveAvfPlatformAccess access = new ReflectiveAvfPlatformAccess(
                    target, target.getClassLoader(), AvfHiddenApiExemption::installOnce,
                    romBinding());
            AvfPlatformFacts facts = access.observe();

            JSONObject root = new JSONObject();
            root.put("schema_version", 1);
            root.put("kind", "avf_qa_probe");
            root.put("qa_package", packageName);
            root.put("device_facts", deviceFacts(target));
            root.put("qualification", qualification(target, access, facts));
            root.put("stage_results", stageResults());

            File directory = new File(target.getFilesDir(), QA_DIRECTORY);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                fail(result, "QA_DIRECTORY_UNAVAILABLE");
                return;
            }
            File destination = new File(directory, "probe-" + token + ".json");
            writeAtomically(destination, root.toString().getBytes(StandardCharsets.UTF_8));
            result.putString("qa_probe_path", "files/" + QA_DIRECTORY + "/probe-" + token + ".json");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // No exception text: it can contain a path or a platform message.
            fail(result, "QA_PROBE_FAILED");
        }
    }

    private void fail(Bundle result, String reasonCode) {
        result.putString("error", reasonCode);
        finish(Activity.RESULT_CANCELED, result);
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
        facts.put("fingerprint_sha256", sha256(Build.FINGERPRINT == null ? "unknown" : Build.FINGERPRINT));
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
        qualification.put(
                "protected_mode_exception",
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

    private static JSONObject stageResults() throws JSONException {
        JSONObject results = new JSONObject();
        for (String stage : LAUNCH_STAGES) {
            JSONObject entry = new JSONObject();
            entry.put("status", "blocked");
            entry.put("reason_code", "QA_LAUNCH_ENTRY_NOT_IMPLEMENTED");
            if ("stop_cycles".equals(stage)) {
                entry.put("stop_confirmed", false);
            }
            results.put(stage, entry);
        }
        for (String stage : OPTIONAL_STAGES) {
            JSONObject entry = new JSONObject();
            entry.put("status", "blocked");
            entry.put("reason_code", "QA_STAGE_NOT_IMPLEMENTED");
            results.put(stage, entry);
        }
        return results;
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
        if (candidate.length() > maxLength || !candidate.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
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
