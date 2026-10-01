package io.github.nyzeep.droidlayer.runtime.network;

import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule;
import java.io.IOException;
import java.util.List;

/**
 * Storage contract for persisting port forwarding rules across application restarts (Ticket 29 AC-3).
 */
public interface PortForwardingStore {
    /**
     * Loads all persistent port forwarding rules for the given instance.
     * Returns an empty list if no rules are stored.
     */
    List<PortForwardingRule> loadRules(String instanceId) throws IOException;

    /**
     * Saves the given list of port forwarding rules for the given instance atomically.
     */
    void saveRules(String instanceId, List<PortForwardingRule> rules) throws IOException;

    /**
     * Deletes all stored rules for the given instance.
     */
    void clearRules(String instanceId) throws IOException;
}
