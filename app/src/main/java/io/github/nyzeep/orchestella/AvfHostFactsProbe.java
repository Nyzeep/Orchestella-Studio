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
        Objects.requireNonNull(supportedAbis, "supportedAbis");
        return new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        sdkInt,
                        java.util.Collections.unmodifiableList(
                                new java.util.ArrayList<>(Arrays.asList(supportedAbis))),
                        readVirtualizationProperty(virtualizationPropertyReader),
                        isFeatureDeclared(featureChecker),
                        isPermissionGranted(permissionChecker))
                .socModel(socModel)
                .deviceModel(deviceModel)
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
                permission -> {
                    try {
                        return context.checkSelfPermission(permission)
                                == PackageManager.PERMISSION_GRANTED;
                    } catch (RuntimeException failure) {
                        return false;
                    }
                });
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

    private static boolean isPermissionGranted(Predicate<String> permissionChecker) {
        try {
            return permissionChecker.test(
                    AvfCapabilityEvaluator.MANAGE_VIRTUAL_MACHINE_PERMISSION);
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
