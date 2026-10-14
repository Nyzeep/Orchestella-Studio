package io.github.nyzeep.orchestella;

import static org.junit.Assert.*;
import org.junit.Test;

public class GuestRuntimeStatusTest {
    @Test public void newerFailureCannotKeepOldReadyState() {
        GuestRuntimeStatus state = GuestRuntimeStatus.latest(
                "ORCHESTELLA_GUEST_PROVISION status=ready stage=verified reason=none attempt=1\n"
                + "ORCHESTELLA_GUEST_PROVISION status=failed stage=repository-refresh reason=offline attempt=2\n");
        assertEquals("failed", state.status);
        assertTrue(state.description().contains("--retry"));
    }

    @Test public void aNewBootDoesNotInheritPreviousReadiness() {
        assertNull(GuestRuntimeStatus.latest(
                "ORCHESTELLA_GUEST_PROVISION status=ready stage=verified reason=none attempt=1\n"
                + "[ 2.0] Run /init as init process\n"));
    }

    @Test public void journaldPrefixDoesNotHideExplicitProgress() {
        GuestRuntimeStatus state = GuestRuntimeStatus.latest(
                "[ 20.0] firstboot.sh[373]: ORCHESTELLA_GUEST_PROVISION status=awaiting-init stage=native-handoff reason=services-not-yet-verified attempt=1\n");
        assertEquals("awaiting-init", state.status);
        assertTrue(state.description().contains("验证服务"));
    }

    @Test public void incompleteOrUnknownMarkersAreNotReadinessEvidence() {
        assertNull(GuestRuntimeStatus.latest("Ready!\nstatus=ready\n"));
        assertNull(GuestRuntimeStatus.latest(
                "ORCHESTELLA_GUEST_PROVISION status=invented stage=verified reason=none attempt=1"));
    }
}
