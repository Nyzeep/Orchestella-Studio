package io.github.nyzeep.orchestella;

import android.content.Context;
import android.os.Build;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformFacts;
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfPlatformAccess;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Gathers the pre-read host facts {@link AvfCapabilityEvaluator} consumes.
 * This class owns only Android-visible facts (SDK, ABI, Build, package
 * feature, system property). Platform facts — VM modes, permissions, hidden
 * API/reflection failures — come from the one shared
 * {@link ReflectiveAvfPlatformAccess} adapter, so the probe and the AVF engine
 * can never disagree about class shape, capability bits or failure state.
 */
public final class AvfHostFactsProbe {
    private AvfHostFactsProbe() {}

    /**
     * Legacy assembly from raw facts with independently supplied permission
     * reads. Kept for callers/tests that do not have a platform adapter.
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
     * Legacy assembly with an explicit VM-mode observation. A null observation
     * is the legacy path; production passes a platform observation instead.
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
        Objects.requireNonNull(permissionChecker, "permissionChecker");
        return assemble(sdkInt, supportedAbis, socModel, deviceModel,
                virtualizationPropertyReader, featureChecker,
                readPermission(permissionChecker, AvfCapabilityEvaluator.MANAGE_VIRTUAL_MACHINE_PERMISSION),
                readPermission(permissionChecker, AvfCapabilityEvaluator.USE_CUSTOM_VIRTUAL_MACHINE_PERMISSION),
                modeFacts, null);
    }

    /**
     * Production-shaped assembly: permissions and VM modes are taken from the
     * one platform observation, never read a second time here.
     */
    public static AvfCapabilityEvaluator.AvfHostFacts probe(
            int sdkInt,
            String[] supportedAbis,
            String socModel,
            String deviceModel,
            Supplier<String> virtualizationPropertyReader,
            Predicate<String> featureChecker,
            AvfPlatformFacts platformFacts) {
        Objects.requireNonNull(platformFacts, "platformFacts");
        return assemble(sdkInt, supportedAbis, socModel, deviceModel,
                virtualizationPropertyReader, featureChecker,
                platformFacts.managePermission(), platformFacts.customPermission(),
                platformFacts.modeFacts(), platformFacts);
    }

    /** Assembles the probe from one live Android context (production wiring). */
    public static AvfCapabilityEvaluator.AvfHostFacts probe(Context context) {
        Objects.requireNonNull(context, "context");
        AvfPlatformFacts platformFacts = new ReflectiveAvfPlatformAccess(
                context,
                context.getClassLoader(),
                AvfHiddenApiExemption::installOnce,
                romBinding())
                .observe();
        return probe(
                Build.VERSION.SDK_INT,
                Build.SUPPORTED_ABIS,
                readSocModel(),
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
                feature -> hasFeature(context, feature),
                platformFacts);
    }

    private static AvfCapabilityEvaluator.AvfHostFacts assemble(
            int sdkInt,
            String[] supportedAbis,
            String socModel,
            String deviceModel,
            Supplier<String> virtualizationPropertyReader,
            Predicate<String> featureChecker,
            AvfCapabilityEvaluator.PermissionState managePermission,
            AvfCapabilityEvaluator.PermissionState customPermission,
            AvfCapabilityEvaluator.AvfModeFacts modeFacts,
            AvfPlatformFacts platformFacts) {
        Objects.requireNonNull(supportedAbis, "supportedAbis");
        return new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        sdkInt,
                        Collections.unmodifiableList(new java.util.ArrayList<>(Arrays.asList(supportedAbis))),
                        readVirtualizationProperty(virtualizationPropertyReader),
                        isFeatureDeclared(featureChecker),
                        managePermission,
                        customPermission)
                .socModel(socModel)
                .deviceModel(deviceModel)
                .modeFacts(modeFacts)
                .platformFacts(platformFacts)
                .build();
    }

    private static boolean hasFeature(Context context, String feature) {
        try {
            return context.getPackageManager().hasSystemFeature(feature);
        } catch (RuntimeException failure) {
            return false;
        }
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

    /** Null-safe platform summary for the status surfaces. */
    public static String platformSummary(AvfPlatformFacts platformFacts) {
        return platformFacts == null ? "未读取（旧调用方）" : platformFacts.summary();
    }

    /** Stable, actionable label for one typed platform access state. */
    public static String platformAccessLabel(AvfPlatformFacts.AccessState accessState) {
        return "平台访问：" + AvfPlatformFacts.accessLabel(accessState);
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
