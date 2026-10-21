package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public final class LegacyStageAdapterTest {
    /** The ticket-27 LaunchTimeline.Stage names, copied read-only for the cross-check. */
    private static final Set<String> TICKET_27_STAGES = Set.of(
            "LAUNCH_START",
            "VM_CREATED",
            "RUN_RETURNED",
            "ASSETS_VERIFIED",
            "INITRAMFS_BUILT",
            "GATEWAY_READY",
            "QEMU_STARTED",
            "SESSION_REGISTERED",
            "STOP_REQUESTED",
            "STOP_CONFIRMED",
            "STOP_UNCONFIRMED",
            "EXIT_OBSERVED");

    @Test
    public void onlyExactSemanticEquivalentsAreMapped() {
        assertEquals(Optional.of("LAUNCH_START"),
                LegacyStageAdapter.legacyStage(FastLaneEventType.FASTLANE_REQUESTED));
        assertEquals(Optional.of("VM_CREATED"),
                LegacyStageAdapter.legacyStage(FastLaneEventType.VM_CREATED));
        assertEquals(Optional.of("STOP_REQUESTED"),
                LegacyStageAdapter.legacyStage(FastLaneEventType.STOP_REQUESTED));
        assertEquals(Optional.of("STOP_CONFIRMED"),
                LegacyStageAdapter.legacyStage(FastLaneEventType.STOP_CONFIRMED));
        assertEquals(Optional.of("STOP_UNCONFIRMED"),
                LegacyStageAdapter.legacyStage(FastLaneEventType.STOP_UNCONFIRMED));

        for (FastLaneEventType type : new FastLaneEventType[] {
                FastLaneEventType.PAYLOAD_READY,
                FastLaneEventType.TERMINAL_READY,
                FastLaneEventType.PROXY_READY,
                FastLaneEventType.EXPORT_READY,
                FastLaneEventType.DISTCC_READY}) {
            assertFalse(type.name(), LegacyStageAdapter.legacyStage(type).isPresent());
        }
    }

    @Test
    public void mappedStagesAreAllValidTicket27Stages() {
        assertTrue(TICKET_27_STAGES.containsAll(LegacyStageAdapter.mappedStages()));
        assertEquals(5, LegacyStageAdapter.mappedStages().size());
    }

    @Test
    public void forwardPassesElapsedMsAndSkipsUnmappedEvents() {
        List<String> calls = new ArrayList<>();
        FastLaneEvent mapped = new FastLaneEvent(
                FastLaneEventType.VM_CREATED,
                "i-1",
                42L,
                FastLaneEventStatus.OK,
                FastLaneDebugLevel.NONE,
                null,
                null);
        assertTrue(LegacyStageAdapter.forward(mapped, (stage, elapsed) ->
                calls.add(stage + ":" + elapsed)));
        assertEquals(List.of("VM_CREATED:42"), calls);

        FastLaneEvent unmapped = new FastLaneEvent(
                FastLaneEventType.PAYLOAD_READY,
                "i-1",
                50L,
                FastLaneEventStatus.OK,
                FastLaneDebugLevel.NONE,
                null,
                null);
        assertFalse(LegacyStageAdapter.forward(unmapped, (stage, elapsed) ->
                calls.add(stage + ":" + elapsed)));
        assertEquals(1, calls.size());
    }
}
