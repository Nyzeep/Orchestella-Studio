package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class UmlInstanceBudgetTest {

    @Test
    public void acquiresMultipleDistinctInstancesWithinBudgetAndTracksTotals() throws Exception {
        AtomicInteger rideAlongMb = new AtomicInteger(512);
        UmlInstanceBudget budget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(6144, 3072, false),
                rideAlongMb::get,
                new UmlInstanceBudget.BudgetPolicy(2048, 512, 2048));

        UmlInstanceBudget.MemoryLease leaseA = budget.acquire("inst-a", "gen-a1", 512);
        UmlInstanceBudget.MemoryLease leaseB = budget.acquire("inst-b", "gen-b1", 512);

        assertEquals(1024, budget.committedStandaloneMb());
        assertEquals(2, budget.activeLeaseCount());
        assertTrue(budget.hasActiveLease("inst-a", "gen-a1"));
        assertTrue(budget.hasActiveLease("inst-b", "gen-b1"));

        UmlInstanceBudget.BudgetSnapshot snap = budget.snapshot();
        assertEquals(1024, snap.committedStandaloneMb());
        assertEquals(512, snap.rideAlongRamMb());
        assertEquals(2, snap.activeLeaseCount());
        assertEquals(2048, snap.effectiveCeilingMb());

        assertTrue(budget.release(leaseA));
        assertFalse(budget.release(leaseA));
        assertTrue(leaseA.isReleased());
        assertEquals(512, budget.committedStandaloneMb());
        assertEquals(1, budget.activeLeaseCount());

        assertTrue(budget.release(leaseB));
        assertEquals(0, budget.committedStandaloneMb());
        assertEquals(0, budget.activeLeaseCount());
    }

    @Test
    public void rejectsDuplicateActiveInstanceWithoutMutatingExistingLease() throws Exception {
        UmlInstanceBudget budget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(8192, 4096, false),
                () -> 0,
                UmlInstanceBudget.BudgetPolicy.defaults());

        UmlInstanceBudget.MemoryLease original = budget.acquire("inst-dup", "gen-1", 512);
        UmlInstanceBudget.BudgetRefusalException refusal = assertThrows(
                UmlInstanceBudget.BudgetRefusalException.class,
                () -> budget.acquire("inst-dup", "gen-2", 512));

        assertEquals(UmlInstanceBudget.RefusalReason.INSTANCE_ALREADY_ACTIVE, refusal.reason());
        assertTrue(budget.hasActiveLease("inst-dup", "gen-1"));
        assertFalse(budget.hasActiveLease("inst-dup", "gen-2"));
        assertEquals(512, budget.committedStandaloneMb());

        assertTrue(budget.release(original));
        UmlInstanceBudget.MemoryLease replacement = budget.acquire("inst-dup", "gen-2", 512);
        assertTrue(budget.hasActiveLease("inst-dup", "gen-2"));
        assertTrue(budget.release(replacement));
    }

    @Test
    public void rejectsWhenTotalRamCeilingOrRideAlongCapacityIsExceeded() throws Exception {
        AtomicInteger rideAlongMb = new AtomicInteger(512);
        UmlInstanceBudget budget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(4096, 2048, false),
                rideAlongMb::get,
                UmlInstanceBudget.BudgetPolicy.defaults());
        // totalRamMb=4096, hostReserve=2048 -> effectiveCeiling=2048.
        // rideAlong=512 + instA=1024 = 1536; instB=513 exceeds 2048.
        UmlInstanceBudget.MemoryLease leaseA = budget.acquire("inst-a", "gen-a", 1024);
        UmlInstanceBudget.BudgetRefusalException refusal = assertThrows(
                UmlInstanceBudget.BudgetRefusalException.class,
                () -> budget.acquire("inst-b", "gen-b", 513));

        assertEquals(UmlInstanceBudget.RefusalReason.TOTAL_RAM_BUDGET_EXCEEDED, refusal.reason());
        assertEquals(1024, refusal.committedStandaloneMb());
        assertEquals(512, refusal.rideAlongRamMb());
        assertEquals(2048, refusal.effectiveCeilingMb());
        assertEquals(1, budget.activeLeaseCount());

        // Stopping ride-along frees 512 MB of ceiling headroom.
        rideAlongMb.set(0);
        UmlInstanceBudget.MemoryLease leaseB = budget.acquire("inst-b", "gen-b", 513);
        assertNotNull(leaseB);
        assertEquals(1537, budget.committedStandaloneMb());
        budget.release(leaseA);
        budget.release(leaseB);
    }

    @Test
    public void rejectsWhenHostLowMemoryOrAvailableHeadroomInsufficient() {
        UmlInstanceBudget lowMemBudget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(8192, 4096, true),
                () -> 0,
                UmlInstanceBudget.BudgetPolicy.defaults());

        UmlInstanceBudget.BudgetRefusalException lowMem = assertThrows(
                UmlInstanceBudget.BudgetRefusalException.class,
                () -> lowMemBudget.acquire("inst-low", "gen-1", 512));
        assertEquals(UmlInstanceBudget.RefusalReason.HOST_LOW_MEMORY, lowMem.reason());
        assertEquals(0, lowMemBudget.activeLeaseCount());

        UmlInstanceBudget tightAvailBudget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(8192, 900, false),
                () -> 0,
                new UmlInstanceBudget.BudgetPolicy(2048, 512, Integer.MAX_VALUE));

        UmlInstanceBudget.BudgetRefusalException tightAvail = assertThrows(
                UmlInstanceBudget.BudgetRefusalException.class,
                () -> tightAvailBudget.acquire("inst-avail", "gen-1", 512));
        assertEquals(
                UmlInstanceBudget.RefusalReason.HOST_AVAILABLE_RAM_INSUFFICIENT,
                tightAvail.reason());
        assertEquals(900, tightAvail.availableRamMb());
        assertEquals(0, tightAvailBudget.activeLeaseCount());
    }

    @Test
    public void rejectsInvalidRequestsAndProtectsNewGenerationFromLateOldRelease() throws Exception {
        UmlInstanceBudget budget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(8192, 4096, false),
                () -> 0,
                UmlInstanceBudget.BudgetPolicy.defaults());

        UmlInstanceBudget.BudgetRefusalException tooSmall = assertThrows(
                UmlInstanceBudget.BudgetRefusalException.class,
                () -> budget.acquire("inst-x", "gen-1", UmlLaunchSpec.MIN_MEMORY_MB - 1));
        assertEquals(UmlInstanceBudget.RefusalReason.INVALID_REQUEST, tooSmall.reason());

        UmlInstanceBudget.MemoryLease gen1 = budget.acquire("inst-x", "gen-1", 512);
        assertTrue(budget.release(gen1));
        UmlInstanceBudget.MemoryLease gen2 = budget.acquire("inst-x", "gen-2", 512);

        // Late release of gen1 must be ignored and leave gen2 intact.
        assertFalse(budget.release(gen1));
        assertTrue(budget.hasActiveLease("inst-x", "gen-2"));
        assertEquals(512, budget.committedStandaloneMb());
        assertTrue(budget.release(gen2));
    }

    @Test
    public void concurrentContentionAllowsExactlyOneWinnerWithinCeiling() throws Exception {
        UmlInstanceBudget budget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(8192, 4096, false),
                () -> 0,
                new UmlInstanceBudget.BudgetPolicy(2048, 512, 512));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<UmlInstanceBudget.MemoryLease>> futures = new ArrayList<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        try {
            for (int i = 0; i < 4; i++) {
                final String id = "inst-" + i;
                final String gen = "gen-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return budget.acquire(id, gen, 512);
                    } catch (UmlInstanceBudget.BudgetRefusalException refused) {
                        assertEquals(
                                UmlInstanceBudget.RefusalReason.TOTAL_RAM_BUDGET_EXCEEDED,
                                refused.reason());
                        return null;
                    } catch (Throwable other) {
                        unexpected.compareAndSet(null, other);
                        throw other;
                    }
                }));
            }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            go.countDown();

            List<UmlInstanceBudget.MemoryLease> winners = new ArrayList<>();
            for (Future<UmlInstanceBudget.MemoryLease> future : futures) {
                UmlInstanceBudget.MemoryLease lease = future.get();
                if (lease != null) {
                    winners.add(lease);
                }
            }
            if (unexpected.get() != null) {
                throw new AssertionError(unexpected.get());
            }
            assertEquals(1, winners.size());
            assertEquals(512, budget.committedStandaloneMb());
            assertTrue(budget.release(winners.get(0)));
            assertEquals(0, budget.committedStandaloneMb());
        } finally {
            pool.shutdownNow();
        }
    }
}
