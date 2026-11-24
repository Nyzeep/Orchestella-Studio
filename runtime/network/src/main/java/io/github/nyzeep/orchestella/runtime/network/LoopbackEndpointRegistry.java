package io.github.nyzeep.orchestella.runtime.network;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks explicit process-local ownership of host forwarding endpoints by
 * {@code (instanceId, generation, ruleId)} so concurrent sessions cannot steal or
 * release each other's active forwarding ports.
 */
public final class LoopbackEndpointRegistry {
    private static final LoopbackEndpointRegistry DEFAULT_INSTANCE = new LoopbackEndpointRegistry();

    private record EndpointKey(String bindAddress, int hostPort) {}

    public static final class EndpointLease {
        private final String bindAddress;
        private final int hostPort;
        private final String instanceId;
        private final String generation;
        private final String ruleId;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private EndpointLease(
                String bindAddress,
                int hostPort,
                String instanceId,
                String generation,
                String ruleId) {
            this.bindAddress = bindAddress;
            this.hostPort = hostPort;
            this.instanceId = instanceId;
            this.generation = generation;
            this.ruleId = ruleId;
        }

        public String bindAddress() {
            return bindAddress;
        }

        public int hostPort() {
            return hostPort;
        }

        public String instanceId() {
            return instanceId;
        }

        public String generation() {
            return generation;
        }

        public String ruleId() {
            return ruleId;
        }

        public boolean isReleased() {
            return released.get();
        }

        boolean markReleased() {
            return released.compareAndSet(false, true);
        }
    }

    private final Object lock = new Object();
    private final Map<EndpointKey, EndpointLease> leases = new LinkedHashMap<>();

    public static LoopbackEndpointRegistry defaultRegistry() {
        return DEFAULT_INSTANCE;
    }

    public EndpointLease acquire(
            String bindAddress,
            int hostPort,
            String instanceId,
            String generation,
            String ruleId)
            throws IOException {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IOException("bindAddress must not be blank");
        }
        if (hostPort < 1 || hostPort > 65535) {
            throw new IOException("hostPort out of range: " + hostPort);
        }
        if (instanceId == null
                || instanceId.isBlank()
                || generation == null
                || generation.isBlank()
                || ruleId == null
                || ruleId.isBlank()) {
            throw new IOException("endpoint owner identity must not be blank");
        }
        if (PortForwardingRule.isReservedHostPort(hostPort)) {
            throw new IOException("host port is reserved for the compiler bridge");
        }

        EndpointKey key = new EndpointKey(bindAddress, hostPort);
        synchronized (lock) {
            EndpointLease existing = leases.get(key);
            if (existing != null) {
                if (Objects.equals(existing.instanceId(), instanceId)
                        && Objects.equals(existing.generation(), generation)
                        && Objects.equals(existing.ruleId(), ruleId)
                        && !existing.isReleased()) {
                    return existing;
                }
                throw new IOException("loopback endpoint already owned by another session");
            }
            for (Map.Entry<EndpointKey, EndpointLease> entry : leases.entrySet()) {
                if (entry.getKey().hostPort() == hostPort
                        && (PortForwardingRule.LAN_EXPOSED_ADDRESS.equals(bindAddress)
                                || PortForwardingRule.LAN_EXPOSED_ADDRESS.equals(
                                        entry.getKey().bindAddress()))) {
                    throw new IOException("loopback endpoint already owned by another session");
                }
            }
            EndpointLease lease =
                    new EndpointLease(bindAddress, hostPort, instanceId, generation, ruleId);
            leases.put(key, lease);
            return lease;
        }
    }

    public boolean release(EndpointLease lease) {
        if (lease == null) {
            return false;
        }
        EndpointKey key = new EndpointKey(lease.bindAddress(), lease.hostPort());
        synchronized (lock) {
            if (leases.get(key) != lease) {
                return false;
            }
            if (!lease.markReleased()) {
                return false;
            }
            leases.remove(key, lease);
            return true;
        }
    }

    public EndpointLease ownerOf(String bindAddress, int hostPort) {
        if (bindAddress == null) {
            return null;
        }
        synchronized (lock) {
            return leases.get(new EndpointKey(bindAddress, hostPort));
        }
    }

    public int activeEndpointCount() {
        synchronized (lock) {
            return leases.size();
        }
    }
}
