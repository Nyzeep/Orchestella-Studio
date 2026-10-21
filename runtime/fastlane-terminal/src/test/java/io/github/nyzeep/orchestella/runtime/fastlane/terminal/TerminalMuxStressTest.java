package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class TerminalMuxStressTest {

    @Test
    public void hundredSessionsOpenTransferAndClose() throws Exception {
        Fln1Wire wire = Fln1Wire.create(TerminalMux.MAX_SESSIONS);
        List<Integer> ids = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            int id = wire.open("term-" + index);
            wire.accept(id);
            ids.add(id);
        }
        assertEquals(100, wire.host.activeSessions());
        for (int index = 0; index < ids.size(); index++) {
            byte[] output = ("out-" + index).getBytes(StandardCharsets.UTF_8);
            assertEquals(output.length, wire.guest.sendData(ids.get(index), output));
            assertEquals("out-" + index, wire.hostRecorder.text(ids.get(index)));
        }
        for (int id : ids) {
            wire.host.sendClose(id);
            wire.guest.sendClosed(id);
        }
        assertEquals(0, wire.host.activeSessions());
        assertEquals(0, wire.guest.activeSessions());
    }

    @Test
    public void sessionLimitIsOneHundredTwentyEight() throws Exception {
        Fln1Wire wire = Fln1Wire.create(TerminalMux.MAX_SESSIONS);
        for (int index = 0; index < TerminalMux.MAX_SESSIONS; index++) {
            int id = wire.open("term-" + index);
            wire.accept(id);
        }
        assertEquals(TerminalMux.MAX_SESSIONS, wire.host.activeSessions());
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, () -> wire.open("overflow"));
        assertEquals(TerminalErrorCode.SESSION_LIMIT, failure.code());
    }

    @Test
    public void guestStallsWhenTheHostDoesNotGrantCredit() throws Exception {
        Fln1Wire wire = Fln1Wire.create(TerminalMux.MAX_SESSIONS);
        int id = wire.open("slow");
        wire.accept(id, 1024);

        assertEquals(1024, wire.guest.sendData(id, new byte[2048]));
        assertEquals(0, wire.guest.sendData(id, new byte[1]));
        assertEquals(2, wire.guest.metrics().creditStalls());
        wire.host.grantCredit(id, 1024);
        assertEquals(1024, wire.guest.sendData(id, new byte[2048]));
        assertEquals(2048, wire.hostRecorder.bytes(id));
    }

    @Test
    public void tenThousandDataFramesKeepOrder() throws Exception {
        Fln1Wire wire = Fln1Wire.create(TerminalMux.MAX_SESSIONS);
        int id = wire.open("burst");
        wire.accept(id);

        for (int index = 0; index < 10_000; index++) {
            assertEquals(1, wire.host.sendData(id, new byte[] {(byte) (index & 0xFF)}));
        }
        assertEquals(10_000, wire.guestRecorder.bytes(id));
        byte[] received = wire.guestRecorder.bytesCopy(id);
        for (int index = 0; index < 10_000; index++) {
            assertEquals((byte) (index & 0xFF), received[index]);
        }
        assertTrue(wire.host.metrics().framesOut() >= 10_000);
    }
}
