package io.github.nyzeep.orchestella.runtime.engineuml;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * Process-wide memory budget gate and per-instance/generation lease table for standalone UML
 * instances, accounting for any active compile ride-along face memory.
 */
public final class UmlInstanceBudget {
    public static final int DEFAULT_HOST_RESERVED_RAM_MB = 2048;
    public static final int DEFAULT_MIN_HOST_AVAILABLE_HEADROOM_MB = 512;

    public enum RefusalReason {
        INSTANCE_ALREADY_ACTIVE,
        HOST_LOW_MEMORY,
        TOTAL_RAM_BUDGET_EXCEEDED,
        HOST_AVAILABLE_RAM_INSUFFICIENT,
        INVALID_REQUEST
    }

    public record HostMemorySnapshot(int totalRamMb, int availRamMb, boolean lowMemory) {
        public HostMemorySnapshot {
            if (totalRamMb < 0 || availRamMb < 0) {
                throw new IllegalArgumentException(
                        "host memory values must be non-negative: total="
                                + totalRamMb
                                + ", avail="
                                + availRamMb);
            }
        }
    }

    @FunctionalInterface
    public interface HostMemoryProvider {
        HostMemorySnapshot snapshot();
    }

    public record BudgetPolicy(
            int hostReservedRamMb, int minHostAvailableHeadroomMb, int maxTotalUmlRamMb) {
        public BudgetPolicy {
            if (hostReservedRamMb < 0) {
                throw new IllegalArgumentException(
                        "hostReservedRamMb must be non-negative: " + hostReservedRamMb);
            }
            if (minHostAvailableHeadroomMb < 0) {
                throw new IllegalArgumentException(
                        "minHostAvailableHeadroomMb must be non-negative: "
                                + minHostAvailableHeadroomMb);
            }
            if (maxTotalUmlRamMb <= 0) {
                throw new IllegalArgumentException(
                        "maxTotalUmlRamMb must be positive: " + maxTotalUmlRamMb);
            }
        }

        public static BudgetPolicy defaults() {
            return new BudgetPolicy(
                    DEFAULT_HOST_RESERVED_RAM_MB,
                    DEFAULT_MIN_HOST_AVAILABLE_HEADROOM_MB,
                    Integer.MAX_VALUE);
        }
    }

    public record BudgetSnapshot(
            int committedStandaloneMb,
            int rideAlongRamMb,
            int activeLeaseCount,
            int effectiveCeilingMb,
            int availableRamMb,
            boolean lowMemory) {}

    public static final class MemoryLease {
        private final String instanceId;
        private final String generation;
        private final int ramMb;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private MemoryLease(String instanceId, String generation, int ramMb) {
            this.instanceId = instanceId;
            this.generation = generation;
            this.ramMb = ramMb;
        }

        public String instanceId() {
            return instanceId;
        }

        public String generation() {
            return generation;
        }

        public int ramMb() {
            return ramMb;
        }

        public boolean isReleased() {
            return released.get();
        }

        boolean markReleased() {
            return released.compareAndSet(false, true);
        }
    }

    public static final class BudgetRefusalException extends IOException {
        private static final long serialVersionUID = 1L;

        private final RefusalReason reason;
        private final String instanceId;
        private final int requestedRamMb;
        private final BudgetSnapshot snapshot;

        public BudgetRefusalException(
                RefusalReason reason,
                String instanceId,
                int requestedRamMb,
                BudgetSnapshot snapshot,
                String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
            this.instanceId = instanceId;
            this.requestedRamMb = requestedRamMb;
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        }

        public RefusalReason reason() {
            return reason;
        }

        public String instanceId() {
            return instanceId;
        }

        public int requestedRamMb() {
            return requestedRamMb;
        }

        public BudgetSnapshot snapshot() {
            return snapshot;
        }

        public int committedStandaloneMb() {
            return snapshot.committedStandaloneMb();
        }

        public int rideAlongRamMb() {
            return snapshot.rideAlongRamMb();
        }

        public int effectiveCeilingMb() {
            return snapshot.effectiveCeilingMb();
        }

        public int availableRamMb() {
            return snapshot.availableRamMb();
        }
    }

    private static final HostMemoryProvider FALLBACK_MEMORY_PROVIDER =
            () -> new HostMemorySnapshot(8192, 4096, false);
    private static final IntSupplier ZERO_RIDE_ALONG = () -> 0;
    private static final UmlInstanceBudget DEFAULT_INSTANCE =
            new UmlInstanceBudget(
                    FALLBACK_MEMORY_PROVIDER, ZERO_RIDE_ALONG, BudgetPolicy.defaults());

    private final Object lock = new Object();
    private final Map<String, MemoryLease> activeLeases = new LinkedHashMap<>();
    private final AtomicReference<HostMemoryProvider> memoryProvider;
    private final AtomicReference<IntSupplier> rideAlongRamMb;
    private final AtomicReference<BudgetPolicy> policy;

    public UmlInstanceBudget(
            HostMemoryProvider memoryProvider,
            IntSupplier rideAlongRamMb,
            BudgetPolicy policy) {
        this.memoryProvider =
                new AtomicReference<>(Objects.requireNonNull(memoryProvider, "memoryProvider"));
        this.rideAlongRamMb =
                new AtomicReference<>(Objects.requireNonNull(rideAlongRamMb, "rideAlongRamMb"));
        this.policy = new AtomicReference<>(Objects.requireNonNull(policy, "policy"));
    }

    public static UmlInstanceBudget defaultBudget() {
        return DEFAULT_INSTANCE;
    }

    public static void configureDefault(
            HostMemoryProvider memoryProvider,
            IntSupplier rideAlongRamMb,
            BudgetPolicy policy) {
        DEFAULT_INSTANCE.reconfigure(memoryProvider, rideAlongRamMb, policy);
    }

    public void reconfigure(
            HostMemoryProvider memoryProvider,
            IntSupplier rideAlongRamMb,
            BudgetPolicy policy) {
        Objects.requireNonNull(memoryProvider, "memoryProvider");
        Objects.requireNonNull(rideAlongRamMb, "rideAlongRamMb");
        Objects.requireNonNull(policy, "policy");
        synchronized (lock) {
            this.memoryProvider.set(memoryProvider);
            this.rideAlongRamMb.set(rideAlongRamMb);
            this.policy.set(policy);
        }
    }

    public BudgetPolicy policy() {
        return policy.get();
    }

    public MemoryLease acquire(String instanceId, String generation, int requestedRamMb)
            throws BudgetRefusalException {
        synchronized (lock) {
            BudgetPolicy currentPolicy = policy.get();
            BudgetSnapshot snapshot = snapshotLocked(currentPolicy);

            if (instanceId == null
                    || instanceId.isBlank()
                    || generation == null
                    || generation.isBlank()
                    || requestedRamMb < UmlLaunchSpec.MIN_MEMORY_MB) {
                throw new BudgetRefusalException(
                        RefusalReason.INVALID_REQUEST,
                        instanceId,
                        requestedRamMb,
                        snapshot,
                        "invalid UML memory lease request: instanceId="
                                + instanceId
                                + ", requestedRamMb="
                                + requestedRamMb);
            }
            if (activeLeases.containsKey(instanceId)) {
                throw new BudgetRefusalException(
                        RefusalReason.INSTANCE_ALREADY_ACTIVE,
                        instanceId,
                        requestedRamMb,
                        snapshot,
                        "UML memory lease already active for instance " + instanceId);
            }
            if (snapshot.lowMemory()) {
                throw new BudgetRefusalException(
                        RefusalReason.HOST_LOW_MEMORY,
                        instanceId,
                        requestedRamMb,
                        snapshot,
                        "host is in low-memory state; refusing UML launch for " + instanceId);
            }
            long totalWithNew =
                    (long) snapshot.committedStandaloneMb()
                            + snapshot.rideAlongRamMb()
                            + requestedRamMb;
            if (totalWithNew > snapshot.effectiveCeilingMb()) {
                throw new BudgetRefusalException(
                        RefusalReason.TOTAL_RAM_BUDGET_EXCEEDED,
                        instanceId,
                        requestedRamMb,
                        snapshot,
                        "UML RAM budget exceeded: requested="
                                + requestedRamMb
                                + "MB, standaloneCommitted="
                                + snapshot.committedStandaloneMb()
                                + "MB, rideAlong="
                                + snapshot.rideAlongRamMb()
                                + "MB, ceiling="
                                + snapshot.effectiveCeilingMb()
                                + "MB");
            }
            long requiredAvailableMb =
                    (long) requestedRamMb + currentPolicy.minHostAvailableHeadroomMb();
            if (snapshot.availableRamMb() < requiredAvailableMb) {
                throw new BudgetRefusalException(
                        RefusalReason.HOST_AVAILABLE_RAM_INSUFFICIENT,
                        instanceId,
                        requestedRamMb,
                        snapshot,
                        "host available RAM insufficient: avail="
                                + snapshot.availableRamMb()
                                + "MB < required="
                                + requiredAvailableMb
                                + "MB");
            }
            MemoryLease lease = new MemoryLease(instanceId, generation, requestedRamMb);
            activeLeases.put(instanceId, lease);
            return lease;
        }
    }

    public boolean release(MemoryLease lease) {
        if (lease == null) {
            return false;
        }
        synchronized (lock) {
            MemoryLease current = activeLeases.get(lease.instanceId());
            if (current != lease) {
                return false;
            }
            if (!lease.markReleased()) {
                return false;
            }
            activeLeases.remove(lease.instanceId(), lease);
            return true;
        }
    }

    public int committedStandaloneMb() {
        synchronized (lock) {
            return sumCommittedLocked();
        }
    }

    public int activeLeaseCount() {
        synchronized (lock) {
            return activeLeases.size();
        }
    }

    public boolean hasActiveLease(String instanceId) {
        if (instanceId == null) {
            return false;
        }
        synchronized (lock) {
            MemoryLease lease = activeLeases.get(instanceId);
            return lease != null && !lease.isReleased();
        }
    }

    public boolean hasActiveLease(String instanceId, String generation) {
        if (instanceId == null || generation == null) {
            return false;
        }
        synchronized (lock) {
            MemoryLease lease = activeLeases.get(instanceId);
            return lease != null && !lease.isReleased() && generation.equals(lease.generation());
        }
    }

    public BudgetSnapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked(policy.get());
        }
    }

    private BudgetSnapshot snapshotLocked(BudgetPolicy currentPolicy) {
        HostMemorySnapshot host = memoryProvider.get().snapshot();
        int totalRamMb = host != null ? host.totalRamMb() : 0;
        int availRamMb = host != null ? host.availRamMb() : 0;
        boolean lowMemory = host == null || host.lowMemory();
        int effectiveCeilingMb =
                Math.min(
                        currentPolicy.maxTotalUmlRamMb(),
                        Math.max(0, totalRamMb - currentPolicy.hostReservedRamMb()));
        int rideAlongMb = Math.max(0, rideAlongRamMb.get().getAsInt());
        return new BudgetSnapshot(
                sumCommittedLocked(),
                rideAlongMb,
                activeLeases.size(),
                effectiveCeilingMb,
                availRamMb,
                lowMemory);
    }

    private int sumCommittedLocked() {
        int sum = 0;
        for (MemoryLease lease : activeLeases.values()) {
            sum += lease.ramMb();
        }
        return sum;
    }
}
