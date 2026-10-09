package io.github.nyzeep.orchestella.runtime.network;

import io.github.nyzeep.orchestella.runtime.contract.PortConflictException;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
     * Adds a new port forwarding rule, verifies conflict constraints, persists it,
     * and hot-applies it if an engine is currently running.
     *
     * @throws PortConflictException if the rule conflicts with an existing rule
     * @throws IOException if persisting to store or engine communication fails
     */
    public PortForwardingRule addRule(PortForwardingRule rule) throws IOException {
        Objects.requireNonNull(rule, "rule");

        synchronized (lock) {
            detector.detectConflict(rule, activeRules);

            List<PortForwardingRule> updated = new ArrayList<>(activeRules);
            updated.add(rule);

            store.saveRules(instanceId, updated);
            activeRules.clear();
            activeRules.addAll(updated);

            if (engine != null && engine.isRunning()) {
                engine.addRule(rule);
            }
            return rule;
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
        Objects.requireNonNull(ruleId, "ruleId");

        synchronized (lock) {
            PortForwardingRule target = null;
            for (PortForwardingRule r : activeRules) {
                if (Objects.equals(r.id(), ruleId)) {
                    target = r;
                    break;
                }
            }

            if (target == null) {
                return false;
            }

            List<PortForwardingRule> updated = new ArrayList<>(activeRules);
            updated.remove(target);

            store.saveRules(instanceId, updated);
            activeRules.clear();
            activeRules.addAll(updated);

            if (engine != null && engine.isRunning()) {
                engine.removeRule(target);
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
                    engine.addRule(rule);
                }
            }
        }
    }

    public void detachEngine() {
        synchronized (lock) {
            this.engine = null;
        }
    }
}
