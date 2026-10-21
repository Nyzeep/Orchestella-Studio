package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class TerminalMuxTest {
    private static final int MAX = TerminalProtocol.MAX_SESSIONS;

    @Test
    public void hostOpenGuestAcceptThenDataFlowsBothWays() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);

        assertEquals(1, wire.guestRecorder.openRequests);
        assertEquals(80, wire.guestRecorder.openRequest.columns());
        assertEquals(TerminalSessionState.OPENING, wire.guest.state(id));
        assertEquals(TerminalSessionState.OPENING, wire.host.state(id));

        wire.guest.acceptSession(id, 2048);
        assertEquals(TerminalSessionState.OPEN, wire.host.state(id));
        assertEquals(TerminalSessionState.OPEN, wire.guest.state(id));
        assertEquals(2048, wire.host.sendCredit(id));
        assertEquals(1024, wire.host.receiveCredit(id));
        assertEquals(1024, wire.guest.sendCredit(id));
        assertEquals(2048, wire.guest.receiveCredit(id));

        assertEquals(3, wire.host.sendData(id, "ls\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("ls\n", wire.guestRecorder.text(id));
        assertEquals(3, wire.guest.sendData(id, "out".getBytes(StandardCharsets.UTF_8)));
        assertEquals("out", wire.hostRecorder.text(id));

        wire.host.sendResize(id, 100, 30);
        assertEquals(100, wire.guestRecorder.resize.columns());
        assertEquals(30, wire.guestRecorder.resize.rows());

        wire.host.sendSignal(id, 2);
        assertEquals(2, wire.guestRecorder.signal);

        assertEquals(1, wire.host.metrics().sessionsOpened());
        assertEquals(1, wire.guest.metrics().sessionsOpened());
    }

    @Test
    public void creditExhaustionIsObservableAndRecovers() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 4);
        wire.guest.acceptSession(id, 4);

        byte[] first = "abcd".getBytes(StandardCharsets.UTF_8);
        byte[] rest = "efghij".getBytes(StandardCharsets.UTF_8);
        assertEquals(4, wire.host.sendData(id, first));
        assertEquals(0, wire.host.sendData(id, rest));
        assertEquals(0, wire.host.sendCredit(id));
        assertEquals(1, wire.host.metrics().creditStalls());

        wire.guest.grantCredit(id, 6);
        assertEquals(6, wire.host.sendCredit(id));
        assertEquals(6, wire.host.sendData(id, rest));
        assertEquals("abcdefghij", wire.guestRecorder.text(id));
        assertEquals(0, wire.guest.receiveCredit(id));
    }

    @Test
    public void receiveCreditExceededResetsBothEnds() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 4);
        wire.guest.acceptSession(id, 4);

        byte[] tooMuch = new byte[8];
        wire.host.onFrame(new TerminalFrame(TerminalFrameType.DATA, id, tooMuch));

        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
        assertTrue(wire.hostRecorder.hasError(id, TerminalErrorCode.CREDIT_EXCEEDED));
        assertTrue(wire.host.metrics().protocolErrors() >= 1);
    }

    @Test
    public void hostCloseThenGuestEofConverges() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);

        wire.host.sendClose(id);
        assertEquals(TerminalSessionState.HALF_CLOSED_LOCAL, wire.host.state(id));
        assertEquals(TerminalSessionState.HALF_CLOSED_REMOTE, wire.guest.state(id));
        assertEquals(1, wire.guestRecorder.closeRequests);

        wire.guest.sendEof(id, 0);
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(0, wire.hostRecorder.lastExitCode);
        assertTrue(wire.hostRecorder.closedWith(id, TerminalCloseReason.EOF));
    }

    @Test
    public void guestEofThenHostCloseConverges() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);

        wire.guest.sendEof(id, 3);
        assertEquals(TerminalSessionState.HALF_CLOSED_REMOTE, wire.host.state(id));
        assertEquals(3, wire.hostRecorder.lastExitCode);

        wire.host.sendClose(id);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
        assertTrue(wire.hostRecorder.closedWith(id, TerminalCloseReason.LOCAL_CLOSE));
        assertTrue(wire.guestRecorder.closedWith(id, TerminalCloseReason.REMOTE_CLOSE));
    }

    @Test
    public void writesAfterLocalHalfCloseAreRejectedButRemoteDataStillArrives() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);

        wire.host.sendClose(id);
        assertEquals(TerminalSessionState.HALF_CLOSED_LOCAL, wire.host.state(id));
        assertEquals(TerminalSessionState.HALF_CLOSED_REMOTE, wire.guest.state(id));

        TerminalProtocolException write = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.sendData(id, new byte[] {1}));
        assertEquals(TerminalErrorCode.SESSION_CLOSED, write.code());

        assertEquals(5, wire.guest.sendData(id, "later".getBytes(StandardCharsets.UTF_8)));
        assertEquals("later", wire.hostRecorder.text(id));

        wire.guest.sendEof(id, 0);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
    }

    @Test
    public void creditOverflowResetsBothEnds() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);

        wire.host.onFrame(new TerminalFrame(
                TerminalFrameType.CREDIT,
                id,
                TerminalProtocol.encodeCredit(TerminalProtocol.MAX_CREDIT_BYTES)));

        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
        assertTrue(wire.hostRecorder.hasError(id, TerminalErrorCode.CREDIT_OVERFLOW));
    }

    @Test
    public void sessionIdsIncreaseAndAreNotReused() throws Exception {
        Wire wire = Wire.create(MAX);
        int first = wire.host.openSession(80, 24, "one", 1024);
        wire.guest.acceptSession(first, 1024);
        wire.host.sendClose(first);
        wire.guest.sendEof(first, 0);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(first));

        int second = wire.host.openSession(80, 24, "two", 1024);
        assertTrue(second > first);
        wire.guest.acceptSession(second, 1024);
        assertEquals(TerminalSessionState.OPEN, wire.host.state(second));
    }

    @Test
    public void duplicateAndRetiredSessionIdsAreRejected() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);

        wire.guest.onFrame(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, id, openPtyPayload()));
        assertTrue(wire.guestRecorder.hasError(id, TerminalErrorCode.SESSION_EXISTS));

        Wire second = Wire.create(MAX);
        int retired = second.host.openSession(80, 24, "main", 1024);
        second.guest.acceptSession(retired, 1024);
        second.host.sendClose(retired);
        second.guest.sendEof(retired, 0);
        assertEquals(TerminalSessionState.CLOSED, second.guest.state(retired));

        second.guest.onFrame(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, retired, openPtyPayload()));
        assertTrue(second.guestRecorder.hasError(
                retired, TerminalErrorCode.SESSION_RETIRED));
    }

    @Test
    public void sessionLimitIsEnforced() throws Exception {
        Wire wire = Wire.create(2);
        wire.host.openSession(80, 24, "one", 1024);
        wire.host.openSession(80, 24, "two", 1024);
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.openSession(80, 24, "three", 1024));
        assertEquals(TerminalErrorCode.SESSION_LIMIT, failure.code());
        assertEquals(2, wire.host.activeSessions());
    }

    @Test
    public void vmStopClosesEverySessionAndRejectsFurtherWork() throws Exception {
        Wire wire = Wire.create(MAX);
        int first = wire.host.openSession(80, 24, "one", 1024);
        int second = wire.host.openSession(80, 24, "two", 1024);
        wire.guest.acceptSession(first, 1024);
        wire.guest.acceptSession(second, 1024);

        wire.host.vmStopped();
        wire.guest.vmStopped();
        assertEquals(0, wire.host.activeSessions());
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(first));
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(second));
        assertTrue(wire.hostRecorder.closedWith(first, TerminalCloseReason.VM_STOPPED));
        assertTrue(wire.hostRecorder.closedWith(second, TerminalCloseReason.VM_STOPPED));

        TerminalProtocolException send = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.sendData(first, new byte[] {1}));
        assertEquals(TerminalErrorCode.VM_STOPPED, send.code());

        TerminalProtocolException receive = assertThrows(
                TerminalProtocolException.class,
                () -> wire.guest.onFrame(new TerminalFrame(
                        TerminalFrameType.PING, 0, TerminalProtocol.encodePing(1L))));
        assertEquals(TerminalErrorCode.VM_STOPPED, receive.code());
    }

    @Test
    public void multiSessionTrafficNeverCrosses() throws Exception {
        Wire wire = Wire.create(MAX);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int id = wire.host.openSession(80, 24, "term-" + i, 4096);
            wire.guest.acceptSession(id, 4096);
            ids.add(id);
        }
        for (int i = 0; i < ids.size(); i++) {
            byte[] output = ("out-" + i).getBytes(StandardCharsets.UTF_8);
            assertEquals(output.length, wire.guest.sendData(ids.get(i), output));
            byte[] input = ("in-" + i).getBytes(StandardCharsets.UTF_8);
            assertEquals(input.length, wire.host.sendData(ids.get(i), input));
        }
        for (int i = 0; i < ids.size(); i++) {
            assertEquals("out-" + i, wire.hostRecorder.text(ids.get(i)));
            assertEquals("in-" + i, wire.guestRecorder.text(ids.get(i)));
        }
    }

    @Test
    public void pingMetricsAndHelloAreConnectionLevel() throws Exception {
        Wire wire = Wire.create(MAX);
        wire.host.sendPing(42L);
        assertEquals(42L, wire.hostRecorder.pong);
        assertEquals(1, wire.guest.metrics().framesIn());

        wire.host.sendMetrics();
        assertNotNull(wire.hostRecorder.metrics);
        assertEquals(0, wire.hostRecorder.metrics.sessionsOpened());

        assertFalse(wire.host.helloReceived());
        wire.guest.sendHello(new TerminalProtocol.Hello(4096, 4096,
                TerminalProtocol.FEATURE_RESIZE | TerminalProtocol.FEATURE_EXIT_CODE));
        assertTrue(wire.host.helloReceived());
        assertEquals(4096, wire.hostRecorder.hello.maxFrameBytes());
    }

    @Test
    public void openSessionWorksBeforeOptionalHello() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);
        assertEquals(TerminalSessionState.OPEN, wire.host.state(id));
        assertFalse(wire.host.helloReceived());
    }

    @Test
    public void reentrantAcceptDuringOpenRequestIsNotLost() throws Exception {
        TerminalMux[] hostRef = new TerminalMux[1];
        TerminalMux[] guestRef = new TerminalMux[1];
        TerminalMux guest = new TerminalMux(
                TerminalMux.Role.GUEST,
                MAX,
                frame -> hostRef[0].onFrame(frame),
                new TerminalMux.Listener() {
                    @Override
                    public void onOpenRequest(
                            int sessionId, TerminalProtocol.OpenPty request) {
                        try {
                            guestRef[0].acceptSession(sessionId, 1024);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                });
        TerminalMux host = new TerminalMux(
                TerminalMux.Role.HOST, MAX, frame -> guestRef[0].onFrame(frame), null);
        hostRef[0] = host;
        guestRef[0] = guest;

        int id = host.openSession(80, 24, "main", 1024);
        assertEquals(TerminalSessionState.OPEN, host.state(id));
        assertEquals(TerminalSessionState.OPEN, guest.state(id));
    }

    @Test
    public void partialDataSendIsBackpressureAndHonorsPeerMaxFrame() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);
        wire.guest.sendHello(new TerminalProtocol.Hello(4, 1024, 0));

        assertEquals(4, wire.host.sendData(id, new byte[8]));
        assertEquals(1, wire.host.metrics().creditStalls());
        assertEquals(4, wire.host.sendData(id, new byte[8]));
        assertEquals(2, wire.host.metrics().creditStalls());
        assertEquals(8, wire.guest.metrics().bytesIn());
    }

    @Test
    public void writesAfterLocalEofAreRejected() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);

        wire.host.sendEof(id, 0);
        assertEquals(TerminalSessionState.HALF_CLOSED_LOCAL, wire.host.state(id));
        assertEquals(TerminalSessionState.HALF_CLOSED_REMOTE, wire.guest.state(id));
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.sendData(id, new byte[] {1}));
        assertEquals(TerminalErrorCode.SESSION_CLOSED, failure.code());

        wire.guest.sendClose(id);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
    }

    @Test
    public void unknownSessionProducesTypedErrorFrame() throws Exception {
        RecordingSink sink = new RecordingSink();
        TerminalMux host = new TerminalMux(TerminalMux.Role.HOST, sink);
        host.onFrame(new TerminalFrame(
                TerminalFrameType.DATA, 99, new byte[] {1, 2, 3}));
        assertEquals(1, sink.frames.size());
        TerminalFrame error = sink.frames.get(0);
        assertEquals(TerminalFrameType.ERROR, error.type());
        assertEquals(99, error.sessionId());
        assertEquals(TerminalErrorCode.UNKNOWN_SESSION,
                TerminalProtocol.parseError(error.payload()).code());
    }

    @Test
    public void wrongRoleIsRejected() {
        Wire wire = Wire.create(MAX);
        TerminalProtocolException open = assertThrows(
                TerminalProtocolException.class,
                () -> wire.guest.openSession(80, 24, "x", 1024));
        assertEquals(TerminalErrorCode.WRONG_ROLE, open.code());
        TerminalProtocolException accept = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.acceptSession(1, 1024));
        assertEquals(TerminalErrorCode.WRONG_ROLE, accept.code());
    }

    @Test
    public void emptyDataAndInvalidCreditAreRejected() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);
        assertThrows(IllegalArgumentException.class,
                () -> wire.host.sendData(id, new byte[0]));
        TerminalProtocolException credit = assertThrows(
                TerminalProtocolException.class,
                () -> wire.guest.grantCredit(id, 0));
        assertEquals(TerminalErrorCode.INVALID_PAYLOAD, credit.code());
    }

    @Test
    public void sendAfterCloseIsTypedAndIdempotent() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);
        wire.host.sendClose(id);
        wire.host.sendClose(id);
        wire.guest.sendEof(id, 0);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        wire.host.sendClose(id);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));

        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.sendData(id, new byte[] {1}));
        assertEquals(TerminalErrorCode.SESSION_CLOSED, failure.code());
    }

    @Test
    public void closeAllWritesCloseAndDropsActiveSessions() throws Exception {
        Wire wire = Wire.create(MAX);
        int id = wire.host.openSession(80, 24, "main", 1024);
        wire.guest.acceptSession(id, 1024);
        wire.host.closeAll(TerminalCloseReason.TRANSPORT_CLOSED);
        assertEquals(0, wire.host.activeSessions());
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.HALF_CLOSED_REMOTE, wire.guest.state(id));
        wire.guest.abortAll(TerminalCloseReason.TRANSPORT_CLOSED);
        assertEquals(0, wire.guest.activeSessions());
    }

    private static byte[] openPtyPayload() {
        return TerminalProtocol.encodeOpenPty(new TerminalProtocol.OpenPty(
                80, 24, 1024, "dup"));
    }

    private static final class Wire {
        private final TerminalMux host;
        private final TerminalMux guest;
        private final Recorder hostRecorder;
        private final Recorder guestRecorder;

        private Wire(TerminalMux host, TerminalMux guest,
                Recorder hostRecorder, Recorder guestRecorder) {
            this.host = host;
            this.guest = guest;
            this.hostRecorder = hostRecorder;
            this.guestRecorder = guestRecorder;
        }

        private static Wire create(int maxSessions) {
            Recorder hostRecorder = new Recorder();
            Recorder guestRecorder = new Recorder();
            Wire[] holder = new Wire[1];
            TerminalMux host = new TerminalMux(
                    TerminalMux.Role.HOST,
                    maxSessions,
                    frame -> holder[0].guest.onFrame(frame),
                    hostRecorder);
            TerminalMux guest = new TerminalMux(
                    TerminalMux.Role.GUEST,
                    maxSessions,
                    frame -> holder[0].host.onFrame(frame),
                    guestRecorder);
            Wire wire = new Wire(host, guest, hostRecorder, guestRecorder);
            holder[0] = wire;
            return wire;
        }
    }

    private static final class RecordingSink implements TerminalMux.FrameSink {
        private final List<TerminalFrame> frames = new ArrayList<>();

        @Override
        public void write(TerminalFrame frame) {
            frames.add(frame);
        }
    }

    private static final class Recorder implements TerminalMux.Listener {
        private final Map<Integer, ByteArrayOutputStream> output = new HashMap<>();
        private final Map<Integer, TerminalErrorCode> errors = new HashMap<>();
        private final Map<Integer, TerminalCloseReason> closed = new HashMap<>();
        private int openRequests;
        private TerminalProtocol.OpenPty openRequest;
        private TerminalProtocol.Resize resize;
        private int signal;
        private int closeRequests;
        private int lastExitCode = Integer.MIN_VALUE;
        private long pong = Long.MIN_VALUE;
        private TerminalMuxMetrics metrics;
        private TerminalProtocol.Hello hello;

        @Override
        public void onHello(TerminalProtocol.Hello hello) {
            this.hello = hello;
        }

        @Override
        public void onOpenRequest(int sessionId, TerminalProtocol.OpenPty request) {
            openRequests++;
            openRequest = request;
        }

        @Override
        public void onData(int sessionId, byte[] data) {
            output.computeIfAbsent(sessionId, ignored -> new ByteArrayOutputStream())
                    .writeBytes(data);
        }

        @Override
        public void onResize(int sessionId, TerminalProtocol.Resize resize) {
            this.resize = resize;
        }

        @Override
        public void onSignal(int sessionId, int signal) {
            this.signal = signal;
        }

        @Override
        public void onCloseRequested(int sessionId) {
            closeRequests++;
        }

        @Override
        public void onEof(int sessionId, int exitCode) {
            lastExitCode = exitCode;
        }

        @Override
        public void onSessionClosed(int sessionId, TerminalCloseReason reason) {
            closed.put(sessionId, reason);
        }

        @Override
        public void onError(int sessionId, TerminalErrorCode code, String message) {
            errors.put(sessionId, code);
        }

        @Override
        public void onPong(long nonce) {
            pong = nonce;
        }

        @Override
        public void onMetrics(TerminalMuxMetrics metrics) {
            this.metrics = metrics;
        }

        private String text(int sessionId) {
            ByteArrayOutputStream bytes = output.get(sessionId);
            return bytes == null ? "" : bytes.toString(StandardCharsets.UTF_8);
        }

        private boolean hasError(int sessionId, TerminalErrorCode code) {
            return errors.get(sessionId) == code;
        }

        private boolean closedWith(int sessionId, TerminalCloseReason reason) {
            return closed.get(sessionId) == reason;
        }
    }
}
