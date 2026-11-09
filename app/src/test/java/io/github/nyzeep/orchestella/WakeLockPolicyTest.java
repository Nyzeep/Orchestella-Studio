package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.WakeLockPolicy.UmlSessionDecision;
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

    // --- UML face session layer (C lane): renewal + the honest ceiling -------------

    private static final long HELD_SINCE = 10_000L;

    @Test
    public void shippedUmlSessionConstantsMatchTheProjectPolicy() {
        assertEquals(2L * 60 * 60 * 1000, WakeLockPolicy.UML_GRANT_TIMEOUT_MS);
        assertEquals(60L * 60 * 1000, WakeLockPolicy.UML_RENEW_THRESHOLD_MS);
        assertEquals(12, WakeLockPolicy.UML_MAX_RENEWALS);
        assertTrue(WakeLockPolicy.UML_CHECK_INTERVAL_MS > 0
                && WakeLockPolicy.UML_CHECK_INTERVAL_MS < WakeLockPolicy.UML_RENEW_THRESHOLD_MS);
    }

    @Test
    public void umlSessionHoldsWhileTheGrantHasTimeLeft() {
        // 110 minutes into a 2h grant: plenty left.
        assertEquals(UmlSessionDecision.HOLD, WakeLockPolicy.decideSession(
                HELD_SINCE + 10L * 60 * 1000, HELD_SINCE, 0));
    }

    @Test
    public void umlSessionRenewsInsideTheMargin() {
        // 90 minutes in: below the 60-minute remaining threshold.
        assertEquals(UmlSessionDecision.RENEW, WakeLockPolicy.decideSession(
                HELD_SINCE + 90L * 60 * 1000, HELD_SINCE, 0));
    }

    @Test
    public void umlSessionRenewsAnExpiredGrant() {
        assertEquals(UmlSessionDecision.RENEW, WakeLockPolicy.decideSession(
                HELD_SINCE + TIMEOUT_MS + 1L, HELD_SINCE, 3));
    }

    @Test
    public void umlSessionRetakesALostGrant() {
        // The platform dropped the lock (or the process raced): the session
        // re-takes and counts it — it never pretends protection it lacks.
        assertEquals(UmlSessionDecision.RENEW, WakeLockPolicy.decideSession(
                HELD_SINCE + 1000L, WakeLockPolicy.NOT_HELD, 2));
    }

    @Test
    public void umlSessionStopsAtTheRenewalCeiling() {
        assertEquals(UmlSessionDecision.STOP, WakeLockPolicy.decideSession(
                HELD_SINCE + 1000L, HELD_SINCE, WakeLockPolicy.UML_MAX_RENEWALS));
        assertEquals(UmlSessionDecision.STOP, WakeLockPolicy.decideSession(
                HELD_SINCE + 1000L, HELD_SINCE, WakeLockPolicy.UML_MAX_RENEWALS + 5));
    }

    @Test
    public void umlSessionKeepsRenewingJustBelowTheCeiling() {
        assertEquals(UmlSessionDecision.RENEW, WakeLockPolicy.decideSession(
                HELD_SINCE + 90L * 60 * 1000, HELD_SINCE,
                WakeLockPolicy.UML_MAX_RENEWALS - 1));
    }

    @Test
    public void umlSessionCeilingWinsOverALostGrantToo() {
        // Past the budget even a lost grant stops the session honestly.
        assertEquals(UmlSessionDecision.STOP, WakeLockPolicy.decideSession(
                HELD_SINCE + 1000L, WakeLockPolicy.NOT_HELD, WakeLockPolicy.UML_MAX_RENEWALS));
    }

    @Test
    public void parameterizedSessionConfigIsHonoured() {
        long timeout = 100L;
        long threshold = 40L;
        assertEquals(UmlSessionDecision.HOLD,
                WakeLockPolicy.decideSession(60L, 0L, 0, timeout, threshold, 2));
        assertEquals(UmlSessionDecision.RENEW,
                WakeLockPolicy.decideSession(61L, 0L, 0, timeout, threshold, 2));
        // Two renewals spent: the budget is out.
        assertEquals(UmlSessionDecision.STOP,
                WakeLockPolicy.decideSession(10L, 0L, 2, timeout, threshold, 2));
    }

    @Test
    public void parameterizedSessionConfigRejectsInconsistentKnobs() {
        try {
            WakeLockPolicy.decideSession(0L, 0L, 0, 100L, 100L, 2);
            fail("a threshold at the full grant is malformed");
        } catch (IllegalArgumentException expected) {
            // Fixed policy contract.
        }
        try {
            WakeLockPolicy.decideSession(0L, 0L, 0, 100L, 0L, 2);
            fail("a non-positive threshold is malformed");
        } catch (IllegalArgumentException expected) {
            // Fixed policy contract.
        }
        try {
            WakeLockPolicy.decideSession(0L, 0L, 0, 100L, 40L, 0);
            fail("zero renewals means no renewal path at all");
        } catch (IllegalArgumentException expected) {
            // Fixed policy contract.
        }
    }
}
