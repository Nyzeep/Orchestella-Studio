package io.github.nyzeep.droidlayer;

import android.app.ActivityManager;
import android.content.Context;
import java.util.Objects;

/**
 * Host physical hardware detector for Android (Ticket 50 / ADR-0019).
 *
 * <p>Probes physical CPU core count and hooks into Android's {@link ActivityManager#getMemoryInfo(ActivityManager.MemoryInfo)}
 * to capture total physical RAM, available RAM, and system low-memory thresholds into a decoupled
 * {@link HostHardwareProfile}.
 */
public final class HostHardwareDetector {

    /** Strategy-neutral seam for detecting hardware profile across production and test environments. */
    public interface Detector {
        HostHardwareProfile detect();
    }

    private HostHardwareDetector() {}

    /**
     * Probes current host hardware facts from Android system services and JVM runtime.
     *
     * @param context Android context for obtaining {@link ActivityManager}
     * @return populated {@link HostHardwareProfile}
     */
    public static HostHardwareProfile detect(Context context) {
        Objects.requireNonNull(context, "context");
        int cpuCores = detectCpuCores();

        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
        }

        return fromMemoryInfo(
                cpuCores,
                memoryInfo.totalMem,
                memoryInfo.availMem,
                memoryInfo.threshold,
                memoryInfo.lowMemory);
    }

    /**
     * Creates a {@link HostHardwareProfile} from explicit hardware parameters.
     */
    public static HostHardwareProfile fromMemoryInfo(
            int cpuCores,
            long totalMemBytes,
            long availMemBytes,
            long thresholdBytes,
            boolean lowMemory) {
        return new HostHardwareProfile(
                Math.max(1, cpuCores),
                totalMemBytes,
                availMemBytes,
                thresholdBytes,
                lowMemory);
    }

    /**
     * Probes available physical CPU cores from Java runtime.
     */
    public static int detectCpuCores() {
        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * Default Android framework detector bound to a specific {@link Context}.
     */
    public static final class AndroidDetector implements Detector {
        private final Context context;

        public AndroidDetector(Context context) {
            this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        }

        @Override
        public HostHardwareProfile detect() {
            return HostHardwareDetector.detect(context);
        }
    }
}
