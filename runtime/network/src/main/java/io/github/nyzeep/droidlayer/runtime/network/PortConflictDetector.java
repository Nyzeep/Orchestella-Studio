package io.github.nyzeep.droidlayer.runtime.network;

import io.github.nyzeep.droidlayer.runtime.contract.PortConflictException;
import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule;
import java.util.Collection;
import java.util.Objects;

/**
 * Validates port conflicts among port forwarding rules (ADR-0008, ADR-0010, Ticket 29 AC-5).
 *
 * <p>Two rules conflict if:
 * <ul>
 *   <li>They share the same transport {@link io.github.nyzeep.droidlayer.runtime.contract.Protocol}
 *       and host port.</li>
 *   <li>AND their binding scopes overlap (either identical bind addresses, or one binds to
 *       {@value PortForwardingRule#LAN_EXPOSED_ADDRESS} which encompasses all host interfaces).</li>
 * </ul>
 */
public final class PortConflictDetector {
    public PortConflictDetector() {}

    /**
     * Checks if the candidate rule conflicts with any existing rule in the collection.
     *
     * @throws PortConflictException if an active rule already claims the candidate host port
     */
    public void detectConflict(PortForwardingRule candidate, Collection<PortForwardingRule> existingRules) {
        Objects.requireNonNull(candidate, "candidate rule");
        if (existingRules == null || existingRules.isEmpty()) {
            return;
        }

        for (PortForwardingRule existing : existingRules) {
            // Ignore same rule (e.g. self-update)
            if (Objects.equals(candidate.id(), existing.id())) {
                continue;
            }

            if (candidate.protocol() == existing.protocol()
                    && candidate.hostPort() == existing.hostPort()) {
                if (isAddressOverlap(candidate.bindAddress(), existing.bindAddress())) {
                    throw new PortConflictException(
                            candidate.protocol(),
                            candidate.hostPort(),
                            candidate.bindAddress(),
                            existing.id());
                }
            }
        }
    }

    private static boolean isAddressOverlap(String addr1, String addr2) {
        if (Objects.equals(addr1, addr2)) {
            return true;
        }
        // 0.0.0.0 overlaps with any other IPv4 address on the host
        return PortForwardingRule.LAN_EXPOSED_ADDRESS.equals(addr1)
                || PortForwardingRule.LAN_EXPOSED_ADDRESS.equals(addr2);
    }
}
