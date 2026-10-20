package io.github.nyzeep.orchestella.runtime.engineavf;

/**
 * The Android 17 {@code crosvm_minimal} failure signature: a network-enabled
 * VM without the headless-GPU workaround stops shortly after run, with an empty
 * console and crosvm exit status 35 (the minimal binary has no {@code net}
 * feature and fails during argument parsing).
 *
 * <p>Status 35 alone is only evidence when a network was requested; the same
 * code on a network-less VM is an unrelated failure. The elapsed/console checks
 * keep a late guest crash from being mislabeled as a missing net feature.
 */
public final class AvfNetworkFailure {
    /** crosvm argument-parse exit status for a missing net feature. */
    public static final int NETWORK_BACKEND_EXIT_STATUS = 35;
    /** Upper bound for the startup-time failure, not a guest runtime failure. */
    public static final long EARLY_STOP_WINDOW_MS = 1500L;

    private AvfNetworkFailure() {}

    public static boolean isBackendUnavailable(boolean networkRequested, int exitStatus,
            long elapsedMs, boolean consoleOutputObserved) {
        return networkRequested
                && exitStatus == NETWORK_BACKEND_EXIT_STATUS
                && elapsedMs >= 0L
                && elapsedMs < EARLY_STOP_WINDOW_MS
                && !consoleOutputObserved;
    }
}
