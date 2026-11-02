package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class WakeLockPolicyTest {
    private static final long TIMEOUT_MS = 2L * 60 * 60 * 1000;
    private static final long RENEW_THRESHOLD_MS = 60L * 60 * 1000;

    @Test
    public void firstGrantAcquiresWhenLockIsNotHeld() {
        assertEquals(WakeLockPolicy.Decision.ACQUIRE,
                WakeLockPolicy.decide(1_000L, WakeLockPolicy.NOT_HELD, TIMEOUT_MS, RENEW_THRESHOLD_MS));
    }

    @Test
    public void heldGrantWithPlentyOfTimeIsLeftAlone() {
        long heldSince = 5_000L;
        long now = heldSince + 10L * 60 * 1000; // 110 minutes left
        assertEquals(WakeLockPolicy.Decision.NOOP,
                WakeLockPolicy.decide(now, heldSince, TIMEOUT_MS, RENEW_THRESHOLD_MS));
    }

    @Test
    public void heldGrantNearExpiryIsRenewed() {
        long heldSince = 5_000L;
        long now = heldSince + 90L * 60 * 1000; // 30 minutes left, below the 60 minute threshold
        assertEquals(WakeLockPolicy.Decision.RENEW,
                WakeLockPolicy.decide(now, heldSince, TIMEOUT_MS, RENEW_THRESHOLD_MS));
    }

    @Test
    public void expiredGrantIsAcquiredAgain() {
        long heldSince = 5_000L;
        long now = heldSince + TIMEOUT_MS + 1L;
        assertEquals(WakeLockPolicy.Decision.ACQUIRE,
                WakeLockPolicy.decide(now, heldSince, TIMEOUT_MS, RENEW_THRESHOLD_MS));
        assertEquals(WakeLockPolicy.Decision.ACQUIRE,
                WakeLockPolicy.decide(heldSince + TIMEOUT_MS, heldSince, TIMEOUT_MS, RENEW_THRESHOLD_MS));
    }
}
