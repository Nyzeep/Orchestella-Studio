package io.github.nyzeep.orchestella;

import android.content.Context;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorImageManifest;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * AX-08: the Android production source of {@link AcceleratorFacts}.
 *
 * <p>This class only <em>maps</em>; it owns no capability detection of its own.
 * The AVF verdict comes from {@link AvfHostFactsProbe} plus
 * {@link AvfCapabilityEvaluator}, the compiler identity comes from the
 * accelerator image manifest, and thermal/battery come from the platform
 * services. Every source is a narrow seam so the mapping is unit-testable
 * without Android.
 *
 * <p>Unknown never enables. A source that throws, returns null, or that the
 * platform cannot answer collapses to the conservative value: no AVF, no image,
 * no vsock, {@link ThermalState#UNKNOWN}, {@link BatteryState#UNKNOWN}. The
 * policy then refuses the lane with a fixed reason code.
 */
public final class AndroidAcceleratorFactProvider implements AcceleratorFactProvider {
    /**
     * Asset name of the accelerator image manifest. It follows the shipped
     * {@code QEMU-BUILD-INPUTS} convention; reading the file is the only place
     * a location appears and it never enters the facts.
     */
    public static final String MANIFEST_ASSET = "ACCELERATOR-IMAGE-MANIFEST";

    /** Channel under which the Linux kernel exposes its vsock transport. */
    public static final String VSOCK_DEVICE = "/dev/vsock";

    /** Below this capacity the accelerator lane is refused. */
    public static final int BATTERY_OK_PERCENT = 20;

    /** Below this capacity the accelerator lane reports CRITICAL. */
    public static final int BATTERY_CRITICAL_PERCENT = 5;

    /** A manifest larger than this is not a manifest; it is refused unread. */
    public static final int MANIFEST_MAX_BYTES = 16 * 1024;

    /** Source of the accelerator image manifest; empty means "not installed". */
    @FunctionalInterface
    public interface ManifestSource {
        Optional<AcceleratorImageManifest> read();
    }

    /** Kernel-level vsock evidence. No evidence means false. */
    @FunctionalInterface
    public interface VsockSource {
        boolean exposed();
    }

    /** Platform thermal budget. */
    @FunctionalInterface
    public interface ThermalSource {
        ThermalState read();
    }

    /** Platform battery budget. */
    @FunctionalInterface
    public interface BatterySource {
        BatteryState read();
    }

    private final Supplier<AvfCapabilityEvaluator.AvfHostFacts> avfHostFacts;
    private final ManifestSource manifest;
    private final VsockSource vsock;
    private final ThermalSource thermal;
    private final BatterySource battery;

    public AndroidAcceleratorFactProvider(
            Supplier<AvfCapabilityEvaluator.AvfHostFacts> avfHostFacts,
            ManifestSource manifest,
            VsockSource vsock,
            ThermalSource thermal,
            BatterySource battery) {
        this.avfHostFacts = Objects.requireNonNull(avfHostFacts, "avfHostFacts");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.vsock = Objects.requireNonNull(vsock, "vsock");
        this.thermal = Objects.requireNonNull(thermal, "thermal");
        this.battery = Objects.requireNonNull(battery, "battery");
    }

    /** Production wiring: every source is an existing platform seam. */
    public static AndroidAcceleratorFactProvider create(Context context) {
        Objects.requireNonNull(context, "context");
        Context application = context.getApplicationContext();
        return new AndroidAcceleratorFactProvider(
                () -> AvfHostFactsProbe.probe(application),
                () -> readManifest(application),
                AndroidAcceleratorFactProvider::kernelExposesVsock,
                () -> readThermal(application),
                () -> readBattery(application));
    }

    @Override
    public AcceleratorFacts read() {
        AvfCapabilityEvaluator.AvfReadiness readiness;
        try {
            AvfCapabilityEvaluator.AvfHostFacts hostFacts = avfHostFacts.get();
            readiness = hostFacts == null
                    ? null
                    : AvfCapabilityEvaluator.evaluate(hostFacts);
        } catch (RuntimeException failure) {
            readiness = null;
        }
        return fromReadiness(
                readiness,
                safeManifest(),
                safeVsock(),
                safeThermal(),
                safeBattery());
    }

    /**
     * Maps one evaluated AVF verdict plus the remaining observations to facts.
     * A null readiness (the read itself failed) is treated like an unsupported
     * platform: nothing is claimed.
     */
    static AcceleratorFacts fromReadiness(
            AvfCapabilityEvaluator.AvfReadiness readiness,
            Optional<AcceleratorImageManifest> manifest,
            boolean vsockExposed,
            ThermalState thermalState,
            BatteryState batteryState) {
        return fromStatus(
                readiness == null ? null : readiness.status(),
                manifest,
                vsockExposed,
                thermalState,
                batteryState);
    }

    /**
     * The whole verdict mapping, kept pure so tests cover every status without
     * Android. Only {@code READY} claims a non-protected VM; every other status
     * either reports a protected-only platform or claims nothing at all.
     */
    static AcceleratorFacts fromStatus(
            AvfCapabilityEvaluator.AvfStatus status,
            Optional<AcceleratorImageManifest> manifest,
            boolean vsockExposed,
            ThermalState thermalState,
            BatteryState batteryState) {
        boolean avfPresent = false;
        boolean nonProtectedSupported = false;
        boolean protectedOnly = false;
        boolean protectedVmSupported = false;
        if (status != null) {
            switch (status) {
                case READY -> {
                    avfPresent = true;
                    nonProtectedSupported = true;
                }
                case MODE_UNSUPPORTED -> {
                    // The platform answered: a VM exists, but only a protected
                    // one. For the custom-VM backend that is a pool/control-plane
                    // device, never an accelerator; for the payload backend it is
                    // the compute plane. The payload compiler facts stay unset
                    // until a payload manifest reader exists, so the payload
                    // backend still refuses on missing compiler, not on the VM.
                    avfPresent = true;
                    protectedOnly = true;
                    protectedVmSupported = true;
                }
                case NEEDS_AUTHORIZATION, HIDDEN_API_BLOCKED -> {
                    // The platform is demonstrably present but non-protected use
                    // is unproven; authorization is a user step, not a capability.
                    avfPresent = true;
                }
                default -> {
                    // UNSUPPORTED, REFLECTION_UNAVAILABLE, MODE_UNKNOWN: nothing
                    // is claimed and the lane is refused.
                }
            }
        }
        Optional<AcceleratorImageManifest> present = manifest == null
                ? Optional.empty()
                : manifest;
        return AcceleratorFacts.builder()
                .avfPresent(avfPresent)
                .nonProtectedSupported(nonProtectedSupported)
                .protectedOnly(protectedOnly)
                .acceleratorImagePresent(present.isPresent())
                .vsockSupported(vsockExposed)
                .compilerTriple(present.map(AcceleratorImageManifest::compilerTriple).orElse(""))
                .thermalState(thermalState == null ? ThermalState.UNKNOWN : thermalState)
                .batteryState(batteryState == null ? BatteryState.UNKNOWN : batteryState)
                .protectedVmSupported(protectedVmSupported)
                .build();
    }

    /** Thermal status mapping; below API 29 the platform has no answer. */
    static ThermalState mapThermalStatus(int status) {
        return switch (status) {
            case PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL;
            case PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.LIGHT;
            case PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.MODERATE;
            case PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SEVERE;
            case PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL;
            default -> ThermalState.UNKNOWN;
        };
    }

    /** Battery mapping: charging always allows, otherwise capacity decides. */
    static BatteryState mapBattery(boolean charging, int capacityPercent) {
        if (charging) {
            return BatteryState.CHARGING;
        }
        if (capacityPercent < 0) {
            return BatteryState.UNKNOWN;
        }
        if (capacityPercent < BATTERY_CRITICAL_PERCENT) {
            return BatteryState.CRITICAL;
        }
        if (capacityPercent < BATTERY_OK_PERCENT) {
            return BatteryState.LOW;
        }
        return BatteryState.OK;
    }

    private Optional<AcceleratorImageManifest> safeManifest() {
        try {
            Optional<AcceleratorImageManifest> read = manifest.read();
            return read == null ? Optional.empty() : read;
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    private boolean safeVsock() {
        try {
            return vsock.exposed();
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private ThermalState safeThermal() {
        try {
            ThermalState read = thermal.read();
            return read == null ? ThermalState.UNKNOWN : read;
        } catch (RuntimeException failure) {
            return ThermalState.UNKNOWN;
        }
    }

    private BatteryState safeBattery() {
        try {
            BatteryState read = battery.read();
            return read == null ? BatteryState.UNKNOWN : read;
        } catch (RuntimeException failure) {
            return BatteryState.UNKNOWN;
        }
    }

    private static Optional<AcceleratorImageManifest> readManifest(Context context) {
        try (InputStream stream = context.getAssets().open(MANIFEST_ASSET)) {
            // Bounded, allocation-light read: the manifest is a few key=value lines.
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(256);
            byte[] chunk = new byte[256];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                if (buffer.size() + read > MANIFEST_MAX_BYTES) {
                    return Optional.empty();
                }
                buffer.write(chunk, 0, read);
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            return AcceleratorImageManifest.parse(text);
        } catch (IOException | RuntimeException absentOrUnreadable) {
            // Not shipped, unreadable or malformed: the image is not installed.
            return Optional.empty();
        }
    }

    private static boolean kernelExposesVsock() {
        try {
            return new File(VSOCK_DEVICE).exists();
        } catch (SecurityException blocked) {
            return false;
        }
    }

    private static ThermalState readThermal(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ThermalState.UNKNOWN;
        }
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power == null) {
                return ThermalState.UNKNOWN;
            }
            return mapThermalStatus(power.getCurrentThermalStatus());
        } catch (RuntimeException failure) {
            return ThermalState.UNKNOWN;
        }
    }

    private static BatteryState readBattery(Context context) {
        try {
            BatteryManager battery = context.getSystemService(BatteryManager.class);
            if (battery == null) {
                return BatteryState.UNKNOWN;
            }
            int capacity = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return mapBattery(battery.isCharging(), capacity);
        } catch (RuntimeException failure) {
            return BatteryState.UNKNOWN;
        }
    }
}
