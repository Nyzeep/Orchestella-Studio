package io.github.nyzeep.orchestella;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Process-local lease table carried by foreground START/STOP intents, never a VM owner.
 * Supports multiple concurrent instances holding shared foreground protection so releasing
 * one instance never tears down the service or wake lock while another active lease remains.
 */
public final class ForegroundSessionBinding {
    public enum StopOutcome {
        IGNORED,
        LEASE_RELEASED_OTHERS_ACTIVE,
        LAST_LEASE_RELEASED
    }

    public record Lease(
            String instanceId,
            String generation,
            String distroTitle,
            String performanceBadge,
            BatteryPolicy batteryPolicy) {}

    private final Map<String, Lease> leases = new LinkedHashMap<>();

    public synchronized boolean start(String instanceId, String generation) {
        return start(instanceId, generation, "Linux", "完整 Linux", BatteryPolicy.BALANCED);
    }

    public synchronized boolean start(
            String instanceId,
            String generation,
            String distroTitle,
            String performanceBadge,
            BatteryPolicy batteryPolicy) {
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        String effectiveTitle = (distroTitle == null || distroTitle.isBlank()) ? "Linux" : distroTitle;
        String effectiveBadge =
                (performanceBadge == null || performanceBadge.isBlank()) ? "完整 Linux" : performanceBadge;
        BatteryPolicy effectivePolicy = batteryPolicy != null ? batteryPolicy : BatteryPolicy.BALANCED;
        leases.put(
                instanceId,
                new Lease(instanceId, generation, effectiveTitle, effectiveBadge, effectivePolicy));
        return true;
    }

    public synchronized boolean stop(String instanceId, String generation) {
        return stopLease(instanceId, generation) == StopOutcome.LAST_LEASE_RELEASED;
    }

    public synchronized StopOutcome stopLease(String instanceId, String generation) {
        if (leases.isEmpty()) {
            return StopOutcome.IGNORED;
        }
        if (instanceId != null) {
            Lease current = leases.get(instanceId);
            if (current == null || !matchesGeneration(current.generation(), generation)) {
                return StopOutcome.IGNORED;
            }
            leases.remove(instanceId);
        } else {
            if (generation != null) {
                return StopOutcome.IGNORED;
            }
            String legacyKey = null;
            for (Lease candidate : leases.values()) {
                if (candidate.generation() == null) {
                    legacyKey = candidate.instanceId();
                    break;
                }
            }
            if (legacyKey == null) {
                return StopOutcome.IGNORED;
            }
            leases.remove(legacyKey);
        }
        return leases.isEmpty()
                ? StopOutcome.LAST_LEASE_RELEASED
                : StopOutcome.LEASE_RELEASED_OTHERS_ACTIVE;
    }

    public synchronized boolean isActive() {
        return !leases.isEmpty();
    }

    public synchronized int activeCount() {
        return leases.size();
    }

    public synchronized boolean hasLease(String instanceId, String generation) {
        if (instanceId == null) {
            return false;
        }
        Lease lease = leases.get(instanceId);
        return lease != null && matchesGeneration(lease.generation(), generation);
    }

    public synchronized boolean hasAnyNonNullGenerationLease() {
        for (Lease lease : leases.values()) {
            if (lease.generation() != null) {
                return true;
            }
        }
        return false;
    }

    public synchronized Lease primaryLease() {
        for (Lease lease : leases.values()) {
            return lease;
        }
        return null;
    }

    public synchronized List<String> activeInstanceIds() {
        return Collections.unmodifiableList(new ArrayList<>(leases.keySet()));
    }

    public synchronized Map<String, String> generationByInstance() {
        Map<String, String> copy = new LinkedHashMap<>();
        for (Lease lease : leases.values()) {
            copy.put(lease.instanceId(), lease.generation());
        }
        return Collections.unmodifiableMap(copy);
    }

    public synchronized void clear() {
        leases.clear();
    }

    private static boolean matchesGeneration(String expected, String actual) {
        if (expected != null) {
            return Objects.equals(expected, actual);
        }
        return actual == null;
    }
}
