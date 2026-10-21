package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class FastLaneEventCodecTest {
    @Test
    public void goldenLinesRoundTrip() {
        FastLaneEvent decoded = FastLaneEventCodec.decode(FastLaneEventSchemaTest.vmCreatedLine());
        assertEquals(FastLaneEventType.VM_CREATED, decoded.type());
        assertEquals("i-abc123", decoded.instanceId());
        assertEquals(12L, decoded.elapsedMs());
        assertEquals(FastLaneEventStatus.OK, decoded.status());
        assertEquals(FastLaneDebugLevel.NONE, decoded.debugLevel());
        assertEquals(FastLaneEventSchemaTest.vmCreatedLine(), decoded.toJsonLine());

        FastLaneEvent stop = FastLaneEventCodec.decode(FastLaneEventSchemaTest.stopUnconfirmedLine());
        assertEquals(FastLaneEventType.STOP_UNCONFIRMED, stop.type());
        assertEquals(FastLaneStopStage.PROCESS_EXIT_WAIT, stop.stop().stage());
        assertEquals(4242L, stop.stop().pid());
        assertEquals(5000L, stop.stop().graceMs());
        assertNull(stop.stop().exitCode());
        assertEquals(FastLaneEventSchemaTest.stopUnconfirmedLine(), stop.toJsonLine());
    }

    @Test
    public void decodedMetricsKeepCountsAndGauges() {
        FastLaneMetrics metrics = FastLaneEventCodec
                .decode(FastLaneEventSchemaTest.vmCreatedLine())
                .metrics();
        assertEquals(1234L, metrics.startupMs());
        assertEquals(900L, metrics.payloadReadyMs());
        assertEquals(10L, metrics.terminalBytes());
        assertEquals(20L, metrics.proxyBytes());
        assertEquals(2L, metrics.distccJobs());
        assertEquals(1L, metrics.distccFailures());
        assertEquals(450L, metrics.distccTotalMs());
        assertEquals(42, metrics.cpuPercent());
        assertEquals(1048576L, metrics.rssBytes());
    }

    @Test
    public void unknownFieldsAreIgnored() {
        String line = FastLaneEventSchemaTest.vmCreatedLine();
        String withFuture = line.substring(0, line.length() - 1)
                + ",\"futureField\":{\"nested\":[1,2,\"x\"]},\"another\":true}";
        FastLaneEvent decoded = FastLaneEventCodec.decode(withFuture);
        assertEquals(FastLaneEventType.VM_CREATED, decoded.type());
        assertEquals("i-abc123", decoded.instanceId());
        assertFalse(decoded.toJsonLine().contains("futureField"));
    }

    @Test
    public void futureSchemaVersionIsRejectedNotReinterpreted() {
        String line = FastLaneEventSchemaTest.vmCreatedLine().replace("\"schema\":1", "\"schema\":2");
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(line));
    }

    @Test
    public void unknownEventTypeAndUnsafeInstanceAreRejected() {
        String unknown = FastLaneEventSchemaTest.vmCreatedLine()
                .replace("\"VM_CREATED\"", "\"FUTURE_EVENT\"");
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(unknown));

        String unsafe = FastLaneEventSchemaTest.vmCreatedLine()
                .replace("\"i-abc123\"", "\"/data/user/0/app\"");
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(unsafe));
    }

    @Test
    public void stopEventsMustCarryConsistentDiagnostics() {
        String noStop = "{\"schema\":1,\"event\":\"STOP_UNCONFIRMED\",\"instanceId\":\"i-1\","
                + "\"elapsedMs\":1,\"status\":\"FAILED\",\"debugLevel\":\"NONE\","
                + "\"protectionWeakened\":false}";
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(noStop));

        String wrongStatus = FastLaneEventSchemaTest.stopUnconfirmedLine()
                .replace("\"status\":\"FAILED\"", "\"status\":\"OK\"");
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(wrongStatus));
    }

    @Test
    public void outOfRangeCpuPercentIsRejectedNotTruncated() {
        String line = FastLaneEventSchemaTest.vmCreatedLine()
                .replace("\"cpuPercent\":42", "\"cpuPercent\":4294967341");
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(line));
    }

    @Test
    public void excessiveNestingIsRejectedAsIllegalArgument() {
        String line = FastLaneEventSchemaTest.vmCreatedLine();
        String future = "[".repeat(20) + "0" + "]".repeat(20);
        String withDeepField = line.substring(0, line.length() - 1)
                + ",\"futureField\":" + future + "}";
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(withDeepField));
    }

    @Test
    public void malformedJsonIsRejected() {
        for (String line : new String[] {"", "{", "{\"schema\":1}", "[]", "null"}) {
            assertThrows("accepted: " + line,
                    IllegalArgumentException.class, () -> FastLaneEventCodec.decode(line));
        }
    }

    @Test
    public void nonIntegralNumbersAreRejected() {
        String line = FastLaneEventSchemaTest.vmCreatedLine()
                .replace("\"elapsedMs\":12", "\"elapsedMs\":12.5");
        assertThrows(IllegalArgumentException.class, () -> FastLaneEventCodec.decode(line));
    }
}
