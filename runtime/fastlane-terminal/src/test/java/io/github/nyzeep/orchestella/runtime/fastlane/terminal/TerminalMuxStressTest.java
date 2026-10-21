package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class TerminalMuxStressTest {
    private static final int SESSIONS = 100;
    private static final int OUTPUT_BYTES = 64 * 1024;

    @Test
    public void hundredSessionsWithLongOutputStayIsolated() throws Exception {
        Wire wire = Wire.create(TerminalProtocol.MAX_SESSIONS);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < SESSIONS; i++) {
            int id = wire.host.openSession(120, 40, "term-" + i, TerminalProtocol.DEFAULT_CREDIT_BYTES);
            wire.guest.acceptSession(id, TerminalProtocol.DEFAULT_CREDIT_BYTES);
            ids.add(id);
        }
        assertEquals(SESSIONS, wire.host.activeSessions());
        assertEquals(SESSIONS, wire.guest.activeSessions());
        assertEquals(SESSIONS, wire.host.metrics().sessionsOpened());

        for (int i = 0; i < SESSIONS; i++) {
            byte value = (byte) (i + 1);
            byte[] output = filled(value, OUTPUT_BYTES);
            int id = ids.get(i);
            assertEquals(OUTPUT_BYTES, wire.guest.sendData(id, output));
            assertEquals(1, wire.hostListener.values.get(id).size());
            assertEquals(value, wire.hostListener.values.get(id).get(0).byteValue());
        }

        long expected = (long) SESSIONS * OUTPUT_BYTES;
        assertEquals(expected, wire.host.metrics().bytesIn());
        assertEquals(expected, wire.guest.metrics().bytesOut());
        assertEquals(0, wire.host.metrics().creditStalls());

        for (int i = 0; i < SESSIONS; i++) {
            wire.host.sendClose(ids.get(i));
            wire.guest.sendEof(ids.get(i), 0);
        }
        assertEquals(0, wire.host.activeSessions());
        assertEquals(0, wire.guest.activeSessions());
        assertEquals(SESSIONS, wire.host.metrics().sessionsClosed());
        assertEquals(SESSIONS, wire.guest.metrics().sessionsClosed());
    }

    @Test
    public void repeatedCreditStallsNeverBufferUnboundedOutput() throws Exception {
        Wire wire = Wire.create(TerminalProtocol.MAX_SESSIONS);
        int id = wire.host.openSession(80, 24, "slow", 1024);
        wire.guest.acceptSession(id, 1024);

        int total = 256 * 1024;
        byte[] chunk = filled((byte) 0x5A, 1024);
        int sent = 0;
        int chunks = total / chunk.length;
        for (int i = 0; i < chunks; i++) {
            int accepted = wire.guest.sendData(id, chunk);
            assertEquals(1024, accepted);
            sent += accepted;
            if (i < chunks - 1) {
                assertEquals(0, wire.guest.sendData(id, chunk));
                wire.host.grantCredit(id, accepted);
            }
        }
        assertEquals(total, sent);
        assertEquals(total, wire.host.metrics().bytesIn());
        assertEquals(chunks - 1, wire.guest.metrics().creditStalls());
        assertEquals(total, wire.guest.metrics().bytesOut());
    }

    @Test
    public void tenThousandDataFramesStayOrderedWithinCredit() throws Exception {
        Wire wire = Wire.create(TerminalProtocol.MAX_SESSIONS);
        int id = wire.host.openSession(
                80, 24, "burst", TerminalProtocol.DEFAULT_CREDIT_BYTES);
        wire.guest.acceptSession(id, TerminalProtocol.DEFAULT_CREDIT_BYTES);

        byte[] chunk = filled((byte) 0x42, 16);
        int frames = 10_000;
        for (int i = 0; i < frames; i++) {
            assertEquals(chunk.length, wire.guest.sendData(id, chunk));
        }

        long bytes = (long) frames * chunk.length;
        assertEquals(bytes, wire.host.metrics().bytesIn());
        assertEquals(bytes, wire.guest.metrics().bytesOut());
        assertEquals(frames + 1, wire.guest.metrics().framesOut());
        assertEquals(frames + 1, wire.host.metrics().framesIn());
        assertEquals(0, wire.guest.metrics().creditStalls());
        assertEquals(
                TerminalProtocol.DEFAULT_CREDIT_BYTES - bytes,
                wire.host.receiveCredit(id));
    }

    @Test
    public void hundredTwentyEightSessionsThenLimit() throws Exception {
        Wire wire = Wire.create(TerminalProtocol.MAX_SESSIONS);
        for (int i = 0; i < TerminalProtocol.MAX_SESSIONS; i++) {
            int id = wire.host.openSession(80, 24, "term-" + i, 4096);
            wire.guest.acceptSession(id, 4096);
        }
        assertEquals(TerminalProtocol.MAX_SESSIONS, wire.host.activeSessions());
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.openSession(80, 24, "overflow", 4096));
        assertEquals(TerminalErrorCode.SESSION_LIMIT, failure.code());
    }

    private static byte[] filled(byte value, int length) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return bytes;
    }

    private static final class Wire {
        private final TerminalMux host;
        private final TerminalMux guest;
        private final ValueListener hostListener;
        private final ValueListener guestListener;

        private Wire(TerminalMux host, TerminalMux guest,
                ValueListener hostListener, ValueListener guestListener) {
            this.host = host;
            this.guest = guest;
            this.hostListener = hostListener;
            this.guestListener = guestListener;
        }

        private static Wire create(int maxSessions) {
            ValueListener hostListener = new ValueListener();
            ValueListener guestListener = new ValueListener();
            Wire[] holder = new Wire[1];
            TerminalMux host = new TerminalMux(
                    TerminalMux.Role.HOST,
                    maxSessions,
                    frame -> holder[0].guest.onFrame(frame),
                    hostListener);
            TerminalMux guest = new TerminalMux(
                    TerminalMux.Role.GUEST,
                    maxSessions,
                    frame -> holder[0].host.onFrame(frame),
                    guestListener);
            Wire wire = new Wire(host, guest, hostListener, guestListener);
            holder[0] = wire;
            return wire;
        }
    }

    /** Verifies every byte in a session is the same value; catches cross-session bleed. */
    private static final class ValueListener implements TerminalMux.Listener {
        private final Map<Integer, List<Byte>> values = new HashMap<>();

        @Override
        public void onData(int sessionId, byte[] data) {
            List<Byte> seen = values.computeIfAbsent(sessionId, ignored -> new ArrayList<>());
            if (!seen.isEmpty() && seen.get(0) != data[0]) {
                throw new AssertionError("cross-session bleed on " + sessionId);
            }
            for (byte value : data) {
                if (value != data[0]) {
                    throw new AssertionError("mixed data on " + sessionId);
                }
            }
            if (seen.isEmpty()) {
                seen.add(data[0]);
            }
        }
    }
}
