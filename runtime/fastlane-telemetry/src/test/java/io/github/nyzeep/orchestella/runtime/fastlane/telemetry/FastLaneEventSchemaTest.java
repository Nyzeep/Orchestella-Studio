package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class FastLaneEventSchemaTest {
    @Test
    public void schemaGoldenLinesAreByteStable() throws IOException {
        String[] golden = goldenLines();
        assertEquals(2, golden.length);
        assertEquals(golden[0], vmCreatedLine());
        assertEquals(golden[1], stopUnconfirmedLine());
    }

    @Test
    public void stopUnconfirmedCarriesDiagnosticsAndNeverSaysStopped() {
        String line = stopUnconfirmedLine();
        assertTrue(line.contains("\"event\":\"STOP_UNCONFIRMED\""));
        assertTrue(line.contains("\"status\":\"FAILED\""));
        assertTrue(line.contains("\"stop\":{\"stage\":\"PROCESS_EXIT_WAIT\",\"pid\":4242,"
                + "\"graceMs\":5000,\"exitCode\":null}"));
        assertFalse(line.contains("STOPPED"));
    }

    @Test
    public void eventHasNoFreeTextFields() {
        for (String line : new String[] {vmCreatedLine(), stopUnconfirmedLine()}) {
            for (String forbidden : new String[] {
                    "\"message\"", "\"path\"", "\"token\"", "\"command\"", "\"source\"",
                    "\"keystrokes\"", "\"url\""}) {
                assertFalse(line, line.contains(forbidden));
            }
        }
    }

    @Test
    public void fullDebugMarksProtectionAsWeakened() {
        FastLaneEvent event = new FastLaneEvent(
                FastLaneEventType.VM_CREATED,
                "i-abc123",
                1L,
                FastLaneEventStatus.OK,
                FastLaneDebugLevel.FULL,
                null,
                null);
        String line = event.toJsonLine();
        assertTrue(line.contains("\"debugLevel\":\"FULL\""));
        assertTrue(line.contains("\"protectionWeakened\":true"));
    }

    @Test
    public void productionLevelDoesNotClaimWeakenedProtection() {
        assertTrue(vmCreatedLine().contains("\"debugLevel\":\"NONE\""));
        assertTrue(vmCreatedLine().contains("\"protectionWeakened\":false"));
    }

    static FastLaneEvent vmCreated() {
        FastLaneMetrics metrics = new FastLaneMetrics();
        metrics.setStartupMs(1234L);
        metrics.setPayloadReadyMs(900L);
        metrics.addTerminalBytes(10L);
        metrics.addProxyBytes(20L);
        metrics.recordDistccJob(150L, true);
        metrics.recordDistccJob(300L, false);
        metrics.sampleResources(42, 1048576L);
        return new FastLaneEvent(
                FastLaneEventType.VM_CREATED,
                "i-abc123",
                12L,
                FastLaneEventStatus.OK,
                FastLaneDebugLevel.NONE,
                metrics,
                null);
    }

    static FastLaneEvent stopUnconfirmed() {
        return new FastLaneEvent(
                FastLaneEventType.STOP_UNCONFIRMED,
                "i-abc123",
                5000L,
                FastLaneEventStatus.FAILED,
                FastLaneDebugLevel.NONE,
                null,
                new FastLaneStopDiagnostics(FastLaneStopStage.PROCESS_EXIT_WAIT, 4242L, 5000L, null));
    }

    static String vmCreatedLine() {
        return vmCreated().toJsonLine();
    }

    static String stopUnconfirmedLine() {
        return stopUnconfirmed().toJsonLine();
    }

    static String[] goldenLines() throws IOException {
        try (InputStream stream = FastLaneEventSchemaTest.class.getResourceAsStream(
                "/golden/fastlane-events-v1.jsonl")) {
            if (stream == null) {
                throw new IOException("golden resource is missing");
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return content.split("\n");
        }
    }
}
