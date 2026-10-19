package io.github.nyzeep.orchestella;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Gathers the pre-read host facts {@link AvfCapabilityEvaluator} consumes.
 * Every Android-side read is tolerantly guarded: a failed read degrades to
 * the honest "unsupported" default instead of guessing a capability.
 */
public final class AvfHostFactsProbe {
    /** AOSP VirtualMachineManager capability bits. */
    public static final int CAPABILITY_PROTECTED_VM = 1;
    public static final int CAPABILITY_NON_PROTECTED_VM = 2;

    private AvfHostFactsProbe() {}

    /**
     * Builds the evaluator input from raw facts. The three suppliers isolate
     * the Android-boundary reads (system property, system feature, runtime
     * permission) so the pure evaluation stays JVM-testable.
     */
    public static AvfCapabilityEvaluator.AvfHostFacts probe(
            int sdkInt,
            String[] supportedAbis,
            String socModel,
            String deviceModel,
            Supplier<String> virtualizationPropertyReader,
            Predicate<String> featureChecker,
            Predicate<String> permissionChecker) {
        return probe(sdkInt, supportedAbis, socModel, deviceModel,
                virtualizationPropertyReader, featureChecker, permissionChecker, null);
    }

    /**
     * Same assembly with an explicit VM-mode observation. A null observation
     * is the legacy path; production always passes one (possibly UNKNOWN).
     */
    public static AvfCapabilityEvaluator.AvfHostFacts probe(
            int sdkInt,
            String[] supportedAbis,
            String socModel,
            String deviceModel,
            Supplier<String> virtualizationPropertyReader,
            Predicate<String> featureChecker,
            Predicate<String> permissionChecker,
            AvfCapabilityEvaluator.AvfModeFacts modeFacts) {
        Objects.requireNonNull(supportedAbis, "supportedAbis");
        return new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        sdkInt,
                        java.util.Collections.unmodifiableList(
                                new java.util.ArrayList<>(Arrays.asList(supportedAbis))),
                        readVirtualizationProperty(virtualizationPropertyReader),
                        isFeatureDeclared(featureChecker),
                        readPermission(permissionChecker, AvfCapabilityEvaluator.MANAGE_VIRTUAL_MACHINE_PERMISSION),
                        readPermission(permissionChecker, AvfCapabilityEvaluator.USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION))
                .socModel(socModel)
                .deviceModel(deviceModel)
                .modeFacts(modeFacts)
                .build();
    }

    /** Assembles the probe from one live Android context (production wiring). */
    public static AvfCapabilityEvaluator.AvfHostFacts probe(Context context) {
        String socModel = readSocModel();
        return probe(
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS,
                socModel,
                Build.MODEL,
                () -> {
                    String prop = readSystemProperty("ro.virtualization.supported");
                    if (prop == null || prop.isBlank() || "0".equals(prop.trim()) || "false".equalsIgnoreCase(prop.trim())) {
                        String bootProp = readSystemProperty("ro.boot.hypervisor.vm.supported");
                        if (bootProp != null && !bootProp.isBlank()) {
                            return bootProp;
                        }
                    }
                    return prop;
                },
                feature -> {
                    try {
                        if (context.getPackageManager().hasSystemFeature(feature)) {
                            return true;
                        }
                        // Some OEM ROMs omit declaring the feature string while VirtualMachineManager is functional
                        Class<?> vmmClass = Class.forName("android.system.virtualmachine.VirtualMachineManager");
                        return context.getSystemService(vmmClass) != null;
                    } catch (Throwable failure) {
                        return false;
                    }
                },
                permission -> context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
                readModeFacts(context));
    }

    /**
     * Reads the platform's declared VM modes once. The capability call is
     * read-only; a missing manager, hidden field, permission failure, or
     * unexpected shape degrades to UNKNOWN rather than a guessed false.
     */
    static AvfCapabilityEvaluator.AvfModeFacts readModeFacts(Context context) {
        String source = "VirtualMachineManager.getCapabilities";
        String actor = "app";
        long sampledAt = System.currentTimeMillis();
        String romBinding = romBinding();
        try {
            Class<?> vmmClass = Class.forName("android.system.virtualmachine.VirtualMachineManager");
            Object manager = context.getSystemService(vmmClass);
            if (manager == null) {
                return AvfCapabilityEvaluator.AvfModeFacts.unknown(source, actor, sampledAt, romBinding);
            }
            Object capabilities = vmmClass.getMethod("getCapabilities").invoke(manager);
            if (!(capabilities instanceof Number)) {
                return AvfCapabilityEvaluator.AvfModeFacts.unknown(source, actor, sampledAt, romBinding);
            }
            int bits = ((Number) capabilities).intValue();
            int protectedBit = vmmClass.getField("CAPABILITY_PROTECTED_VM").getInt(null);
            int nonProtectedBit = vmmClass.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null);
            return new AvfCapabilityEvaluator.AvfModeFacts(
                    (bits & protectedBit) != 0
                            ? AvfCapabilityEvaluator.VmSupport.SUPPORTED
                            : AvfCapabilityEvaluator.VmSupport.UNSUPPORTED,
                    (bits & nonProtectedBit) != 0
                            ? AvfCapabilityEvaluator.VmSupport.SUPPORTED
                            : AvfCapabilityEvaluator.VmSupport.UNSUPPORTED,
                    source, actor, sampledAt, romBinding);
        } catch (Throwable failure) {
            return AvfCapabilityEvaluator.AvfModeFacts.unknown(source, actor, sampledAt, romBinding);
        }
    }

    /** Maps capability bits without touching Android; used by the JVM tests. */
    public static AvfCapabilityEvaluator.AvfModeFacts modeFactsFromCapabilities(
            int capabilities, String source, String actor, long sampledAtMillis, String romBinding) {
        return new AvfCapabilityEvaluator.AvfModeFacts(
                (capabilities & CAPABILITY_PROTECTED_VM) != 0
                        ? AvfCapabilityEvaluator.VmSupport.SUPPORTED
                        : AvfCapabilityEvaluator.VmSupport.UNSUPPORTED,
                (capabilities & CAPABILITY_NON_PROTECTED_VM) != 0
                        ? AvfCapabilityEvaluator.VmSupport.SUPPORTED
                        : AvfCapabilityEvaluator.VmSupport.UNSUPPORTED,
                source, actor, sampledAtMillis, romBinding);
    }

    /** Coarse, non-fingerprint ROM binding for the mode observation. */
    private static String romBinding() {
        String id = Build.ID == null || Build.ID.isBlank() ? "unknown-id" : Build.ID;
        String incremental = Build.VERSION.INCREMENTAL == null || Build.VERSION.INCREMENTAL.isBlank()
                ? "unknown-incremental"
                : Build.VERSION.INCREMENTAL;
        return id + "/" + incremental;
    }

    public static String modeSummary(AvfCapabilityEvaluator.AvfModeFacts modeFacts) {
        return modeFacts == null ? "未读取（旧调用方）" : modeFacts.summary();
    }

    /**
     * Reflective {@code SystemProperties.get} read; hidden API surface, so a
     * failed read returns null and the evaluator falls back to the feature
     * flag, then to "unsupported".
     */
    public static String readSystemProperty(String name) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties
                    .getMethod("get", String.class)
                    .invoke(null, name);
            String text = value == null ? null : value.toString();
            return text == null || text.isEmpty() ? null : text;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** {@code Build.SOC_MODEL} exists from API 31; older devices report null. */
    private static String readSocModel() {
        if (Build.VERSION.SDK_INT < 31) {
            return null;
        }
        return Build.SOC_MODEL;
    }

    private static String readVirtualizationProperty(Supplier<String> reader) {
        try {
            return reader.get();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static boolean isFeatureDeclared(Predicate<String> featureChecker) {
        try {
            return featureChecker.test(AvfCapabilityEvaluator.VIRTUALIZATION_FEATURE);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static AvfCapabilityEvaluator.PermissionState readPermission(
            Predicate<String> permissionChecker, String permission) {
        try {
            return permissionChecker.test(permission)
                    ? AvfCapabilityEvaluator.PermissionState.GRANTED
                    : AvfCapabilityEvaluator.PermissionState.DENIED;
        } catch (RuntimeException | LinkageError failure) {
            return AvfCapabilityEvaluator.PermissionState.UNKNOWN;
        }
    }

    public static String permissionLabel(AvfCapabilityEvaluator.PermissionState permission) {
        return switch (permission) {
            case GRANTED -> "已授权";
            case DENIED -> "未授权";
            case UNKNOWN -> "读取失败或尚未确认";
        };
    }
}
