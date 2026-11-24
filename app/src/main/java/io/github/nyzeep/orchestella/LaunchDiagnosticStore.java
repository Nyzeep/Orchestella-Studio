package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Ephemeral in-memory store holding the latest per-instance {@link ExecutionPolicyContract.LaunchDiagnostic}
 * (including preflight reasons, atomic fallback outcomes, and dirty-disk/unconfirmed-stop recovery
 * guidance). Never persisted into instance metadata.
 */
public final class LaunchDiagnosticStore {
    private static final ConcurrentMap<String, ExecutionPolicyContract.LaunchDiagnostic> LATEST =
            new ConcurrentHashMap<>();

    private LaunchDiagnosticStore() {}

    public static void record(
            String instanceId, ExecutionPolicyContract.LaunchDiagnostic diagnostic) {
        if (instanceId == null || instanceId.isBlank() || diagnostic == null) {
            return;
        }
        LATEST.put(instanceId, diagnostic);
    }

    public static ExecutionPolicyContract.LaunchDiagnostic latest(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }
        return LATEST.get(instanceId);
    }

    public static void clear(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return;
        }
        LATEST.remove(instanceId);
    }

    public static void clearAll() {
        LATEST.clear();
    }
}
