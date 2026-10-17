package io.github.nyzeep.orchestella;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks instance launches that are in flight inside this process, so state
 * reconciliation never races a launch that has already transitioned metadata to
 * RUNNING but has not registered its execution session yet.
 *
 * <p>Process-local by design: if the host app dies mid-launch, the set vanishes and
 * orphan reconciliation proceeds as before.
 */
public final class InstanceLaunchGuard {

    private static final ConcurrentHashMap<String, String> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> LATEST = new ConcurrentHashMap<>();

    private InstanceLaunchGuard() {
    }

    public static String begin(String instanceId) {
        if (instanceId == null) return null;
        String generation = UUID.randomUUID().toString();
        // A refused launch must not replace the admitted owner's generation.
        if (IN_FLIGHT.putIfAbsent(instanceId, generation) != null) return null;
        LATEST.put(instanceId, generation);
        return generation;
    }

    public static void end(String instanceId) {
        if (instanceId != null) IN_FLIGHT.remove(instanceId);
    }

    public static boolean end(String instanceId, String generation) {
        return instanceId != null && generation != null && IN_FLIGHT.remove(instanceId, generation);
    }

    public static String generation(String instanceId) {
        return instanceId == null ? null : IN_FLIGHT.get(instanceId);
    }

    public static boolean isLatest(String instanceId, String generation) {
        return instanceId != null && generation != null && generation.equals(LATEST.get(instanceId));
    }

    public static boolean isSuperseded(String instanceId, String generation) {
        String latest = instanceId == null ? null : LATEST.get(instanceId);
        return latest != null && !latest.equals(generation);
    }

    public static boolean isInFlight(String instanceId) {
        return instanceId != null && IN_FLIGHT.containsKey(instanceId);
    }
}
