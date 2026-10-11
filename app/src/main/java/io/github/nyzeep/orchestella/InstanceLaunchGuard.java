package io.github.nyzeep.orchestella;

import java.util.Set;
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

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private InstanceLaunchGuard() {
    }

    public static void begin(String instanceId) {
        if (instanceId != null) {
            IN_FLIGHT.add(instanceId);
        }
    }

    public static void end(String instanceId) {
        if (instanceId != null) {
            IN_FLIGHT.remove(instanceId);
        }
    }

    public static boolean isInFlight(String instanceId) {
        return instanceId != null && IN_FLIGHT.contains(instanceId);
    }
}
