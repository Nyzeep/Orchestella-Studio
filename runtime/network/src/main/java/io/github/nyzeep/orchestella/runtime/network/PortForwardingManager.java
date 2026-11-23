package io.github.nyzeep.orchestella.runtime.network;

import io.github.nyzeep.orchestella.runtime.contract.PortConflictException;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages port forwarding rules for a Full Linux instance.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Conflict rejection: rejects overlapping host ports via {@link PortConflictDetector}.</li>
 *   <li>Atomic persistence: synchronizes changes to {@link PortForwardingStore}.</li>
 *   <li>Hot reconciliation: dynamically propagates rule additions/removals to attached
 *       {@link PortForwardingEngine} without restarting the instance (Ticket 29 AC-2).</li>
 * </ul>
 */
public final class PortForwardingManager {
    private record StoreLocation(Path root, String instanceId) {}
    private static final Map<StoreLocation, WeakReference<PortForwardingManager>> OPEN_MANAGERS =
            new HashMap<>();

    private final String instanceId;
    private final PortForwardingStore store;
    private final PortConflictDetector detector;
    private final Object lock = new Object();

    private final List<PortForwardingRule> activeRules = new ArrayList<>();
    private PortForwardingEngine engine;

    public PortForwardingManager(
            String instanceId,
            PortForwardingStore store,
            PortConflictDetector detector) throws IOException {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.store = Objects.requireNonNull(store, "store");
        this.detector = Objects.requireNonNull(detector, "detector");

        synchronized (lock) {
            activeRules.addAll(store.loadRules(instanceId));
        }
    }

    /**
     * The two UI entry points share one manager for the instance while either
     * page is open, so edits use the same state and live mutation lock.
     */
    public static PortForwardingManager forInstance(String instanceId, Path netRoot)
            throws IOException {
        Path directory = Objects.requireNonNull(netRoot, "netRoot").toAbsolutePath().normalize();
        StoreLocation location = new StoreLocation(directory, Objects.requireNonNull(instanceId, "instanceId"));
        synchronized (OPEN_MANAGERS) {
            OPEN_MANAGERS.values().removeIf(reference -> reference.get() == null);
            WeakReference<PortForwardingManager> reference = OPEN_MANAGERS.get(location);
            PortForwardingManager existing = reference == null ? null : reference.get();
            if (existing != null) return existing;
            Files.createDirectories(directory);
            PortForwardingManager manager = new PortForwardingManager(
                    instanceId, new FilePortForwardingStore(directory), new PortConflictDetector());
            OPEN_MANAGERS.put(location, new WeakReference<>(manager));
            return manager;
        }
    }

    /**
     * Adds a new port forwarding rule, verifies conflict constraints, persists it,
     * and hot-applies it if an engine is currently running.
     *
     * @throws PortConflictException if the rule conflicts with an existing rule
     * @throws IOException if persisting to store or engine communication fails
     */
    public PortForwardingRule addRule(PortForwardingRule rule) throws IOException {
        synchronized (lock) {
            return addRule(rule, engine);
        }
    }

    /** Applies through the caller's current session without attaching or replaying it. */
    public PortForwardingRule addRule(PortForwardingRule rule, PortForwardingEngine candidate)
            throws IOException {
        Objects.requireNonNull(rule, "rule");
        requireUserEndpoint(rule);

        synchronized (lock) {
            PortForwardingEngine running = runningEngine(candidate);
            if (getRule(rule.id()).isPresent()) {
                throw new IOException("a rule with this id already exists");
            }
            detector.detectConflict(rule, activeRules);

            List<PortForwardingRule> updated = new ArrayList<>(activeRules);
            updated.add(rule);
            if (running == null) {
                commit(updated, null);
            } else {
                requireSupported(running, rule);
                running.addRule(rule);
                commit(updated, () -> running.removeRule(rule));
            }
            return rule;
        }
    }

    /** Applies an already-saved rule on the caller's live session engine under the manager lock. */
    public void applyRule(String ruleId, PortForwardingEngine.Live candidate) throws IOException {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(candidate, "candidate");
        synchronized (lock) {
            if (!candidate.isRunning()) {
                throw new IOException("forwarding engine is not running");
            }
            PortForwardingRule rule = getRule(ruleId).orElseThrow(
                    () -> new IOException("forwarding rule no longer exists"));
            requireUserEndpoint(rule);
            requireSupported(candidate, rule);
            candidate.addRule(rule);
        }
    }

    /** Replaces the saved configuration once its live change has succeeded. */
    public boolean updateRule(PortForwardingRule replacement, PortForwardingEngine.Live candidate)
            throws IOException {
        Objects.requireNonNull(replacement, "replacement");
        requireUserEndpoint(replacement);
        synchronized (lock) {
            PortForwardingRule previous = getRule(replacement.id()).orElse(null);
            if (previous == null) return false;
            detector.detectConflict(replacement, activeRules);
            List<PortForwardingRule> updated = new ArrayList<>(activeRules);
            updated.set(updated.indexOf(previous), replacement);
            if (candidate == null || !candidate.isRunning()) {
                commit(updated, null);
            } else {
                requireSupported(candidate, replacement);
                if (!isApplied(candidate, previous)) {
                    candidate.addRule(replacement);
                    commit(updated, () -> candidate.removeRule(replacement));
                } else {
                    candidate.replaceRule(previous, replacement);
                    commit(updated, () -> candidate.replaceRule(replacement, previous));
                }
            }
            return true;
        }
    }

    /**
     * Removes an existing port forwarding rule by ID, persists the change, and hot-removes
     * it from the running engine.
     *
     * @return true if the rule was found and removed, false otherwise
     * @throws IOException if persisting or engine communication fails
     */
    public boolean removeRule(String ruleId) throws IOException {
        synchronized (lock) {
            return removeRule(ruleId, engine);
        }
    }

    public boolean removeRule(String ruleId, PortForwardingEngine candidate) throws IOException {
        Objects.requireNonNull(ruleId, "ruleId");
        synchronized (lock) {
            PortForwardingRule target = getRule(ruleId).orElse(null);
            if (target == null) {
                return false;
            }

            List<PortForwardingRule> updated = new ArrayList<>(activeRules);
            updated.remove(target);

            PortForwardingEngine running = runningEngine(candidate);
            if (running != null && isApplied(running, target)) {
                running.removeRule(target);
                commit(updated, () -> running.addRule(target));
            } else {
                commit(updated, null);
            }
            return true;
        }
    }

    public List<PortForwardingRule> listRules() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(activeRules));
        }
    }

    public Optional<PortForwardingRule> getRule(String ruleId) {
        Objects.requireNonNull(ruleId, "ruleId");
        synchronized (lock) {
            for (PortForwardingRule r : activeRules) {
                if (Objects.equals(r.id(), ruleId)) {
                    return Optional.of(r);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Attaches a running or newly booted engine and reconciles all active rules with it.
     */
    public void attachEngine(PortForwardingEngine engine) throws IOException {
        synchronized (lock) {
            this.engine = engine;
            if (engine != null && engine.isRunning()) {
                for (PortForwardingRule rule : activeRules) {
                    if (canApply(engine, rule)) engine.addRule(rule);
                }
            }
        }
    }

    public void detachEngine() {
        synchronized (lock) {
            this.engine = null;
        }
    }

    private static PortForwardingEngine runningEngine(PortForwardingEngine candidate) {
        return candidate != null && candidate.isRunning() ? candidate : null;
    }

    private static void requireUserEndpoint(PortForwardingRule rule) throws IOException {
        if (PortForwardingRule.isReservedHostPort(rule.hostPort())) {
            throw new IOException("host port is reserved for the compiler bridge");
        }
    }

    private static void requireSupported(PortForwardingEngine engine, PortForwardingRule rule)
            throws IOException {
        if (!canApply(engine, rule)) throw new IOException("unsupported user forwarding rule");
    }

    private static boolean canApply(PortForwardingEngine engine, PortForwardingRule rule) {
        return !PortForwardingRule.isReservedHostPort(rule.hostPort())
                && (!(engine instanceof PortForwardingEngine.Live live) || live.supportsRule(rule));
    }

    private static boolean isApplied(PortForwardingEngine engine, PortForwardingRule rule)
            throws IOException {
        if (!canApply(engine, rule)) return false;
        if (engine instanceof PortForwardingEngine.Live live) {
            live.refresh();
            return live.isApplied(rule);
        }
        return true;
    }

    private void commit(List<PortForwardingRule> updated, Rollback rollback) throws IOException {
        try {
            store.saveRules(instanceId, updated);
        } catch (IOException failure) {
            if (rollback != null) {
                try {
                    rollback.run();
                } catch (IOException undoFailure) {
                    failure.addSuppressed(undoFailure);
                }
            }
            throw failure;
        }
        activeRules.clear();
        activeRules.addAll(updated);
    }

    @FunctionalInterface
    private interface Rollback {
        void run() throws IOException;
    }
}
