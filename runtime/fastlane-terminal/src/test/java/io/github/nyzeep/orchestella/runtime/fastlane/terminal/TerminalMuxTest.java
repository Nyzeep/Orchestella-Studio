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
    private static final int MAX = TerminalMux.MAX_SESSIONS;

    @Test
    public void hostOpenGuestAcceptThenDataFlowsBothWays() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");

        assertEquals(1, wire.guestRecorder.openRequests);
        assertEquals(80, wire.guestRecorder.openRequest.columns());
        assertEquals("/mnt/apk/bin/sh", wire.guestRecorder.openRequest.argv()[0]);
        assertEquals(TerminalSessionState.OPENING, wire.guest.state(id));
        assertEquals(TerminalSessionState.OPENING, wire.host.state(id));

        wire.accept(id, 2048);
        assertEquals(TerminalSessionState.OPEN, wire.host.state(id));
        assertEquals(TerminalSessionState.OPEN, wire.guest.state(id));
        assertEquals(TerminalMux.MAX_CREDIT_BYTES, wire.host.sendCredit(id));
        assertEquals(2048, wire.host.receiveCredit(id));
        assertEquals(2048, wire.guest.sendCredit(id));
        assertEquals(TerminalMux.MAX_CREDIT_BYTES, wire.guest.receiveCredit(id));

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
    public void guestOutputCreditIsWindowedAndRecovers() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id, 1024);

        byte[] first = new byte[1024];
        assertEquals(1024, wire.guest.sendData(id, first));
        assertEquals(0, wire.guest.sendCredit(id));
        assertEquals(0, wire.guest.sendData(id, new byte[] {1}));
        assertEquals(1, wire.guest.metrics().creditStalls());
        assertEquals(0, wire.host.receiveCredit(id));

        wire.host.grantCredit(id, 1024);
        assertEquals(1024, wire.guest.sendCredit(id));
        assertEquals(1024, wire.host.receiveCredit(id));
        assertEquals(4, wire.guest.sendData(id, "tail".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1024 + 4, wire.hostRecorder.bytes(id));
    }

    @Test
    public void hostDataIsFrameLimitedButNotWindowed() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id, 1024);

        assertEquals(32_768, wire.host.sendData(id, new byte[40_000]));
        assertEquals(1, wire.host.metrics().creditStalls());
        assertEquals(7_232, wire.host.sendData(id, new byte[40_000 - 32_768]));
        assertEquals(40_000, wire.guestRecorder.bytes(id));
    }

    @Test
    public void hostRejectsInboundDataBeyondWindow() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id, 1024);

        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.onFrame(new TerminalFrame(
                        TerminalFrameType.DATA, id, new byte[1025])));
        assertEquals(TerminalErrorCode.CREDIT_EXCEEDED, failure.code());
        assertTrue(wire.hostRecorder.hasError(id, TerminalErrorCode.CREDIT_EXCEEDED));
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
    }

    @Test
    public void hostCloseThenGuestClosedConverges() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);

        wire.host.sendClose(id);
        assertEquals(TerminalSessionState.HALF_CLOSED_LOCAL, wire.host.state(id));
        assertEquals(TerminalSessionState.HALF_CLOSED_REMOTE, wire.guest.state(id));
        assertEquals(1, wire.guestRecorder.closeRequests);

        wire.guest.sendClosed(id);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertTrue(wire.hostRecorder.closedWith(id, TerminalCloseReason.REMOTE_CLOSE));
    }

    @Test
    public void guestExitClosesTheSessionWithItsStatusCode() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);

        wire.guest.sendEof(id, 3);
        assertEquals(3, wire.hostRecorder.lastExitCode);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
        assertTrue(wire.hostRecorder.closedWith(id, TerminalCloseReason.EOF));
    }

    @Test
    public void hostCloseThenGuestExitConverges() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);

        wire.host.sendClose(id);
        wire.guest.sendEof(id, 0);
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.CLOSED, wire.guest.state(id));
        assertEquals(0, wire.hostRecorder.lastExitCode);
    }

    @Test
    public void writesAfterLocalHalfCloseAreRejectedButRemoteDataStillArrives() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);

        wire.host.sendClose(id);
        TerminalProtocolException write = assertThrows(
                TerminalProtocolException.class,
                () -> wire.host.sendData(id, new byte[] {1}));
        assertEquals(TerminalErrorCode.SESSION_CLOSED, write.code());

        assertEquals(5, wire.guest.sendData(id, "later".getBytes(StandardCharsets.UTF_8)));
        assertEquals("later", wire.hostRecorder.text(id));
    }

    @Test
    public void duplicateOpenPtyForAnActiveSessionIsRejected() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);

        wire.guest.onFrame(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, id, openPtyPayload()));
        assertTrue(wire.guestRecorder.hasError(id, TerminalErrorCode.SESSION_EXISTS));
    }

    @Test
    public void sessionLimitIsEnforced() throws Exception {
        Fln1Wire wire = Fln1Wire.create(2);
        wire.open("one");
        wire.open("two");
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, () -> wire.open("three"));
        assertEquals(TerminalErrorCode.SESSION_LIMIT, failure.code());
        assertEquals(2, wire.host.activeSessions());
    }

    @Test
    public void vmStopClosesEverySessionAndRejectsFurtherWork() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int first = wire.open("one");
        int second = wire.open("two");
        wire.accept(first);
        wire.accept(second);

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
                        TerminalFrameType.PING, 0, nonce(1))));
        assertEquals(TerminalErrorCode.VM_STOPPED, receive.code());
    }

    @Test
    public void multiSessionTrafficNeverCrosses() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        List<Integer> ids = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            int id = wire.open("term-" + index);
            wire.accept(id);
            ids.add(id);
        }
        for (int index = 0; index < ids.size(); index++) {
            byte[] output = ("out-" + index).getBytes(StandardCharsets.UTF_8);
            assertEquals(output.length, wire.guest.sendData(ids.get(index), output));
            byte[] input = ("in-" + index).getBytes(StandardCharsets.UTF_8);
            assertEquals(input.length, wire.host.sendData(ids.get(index), input));
        }
        for (int index = 0; index < ids.size(); index++) {
            assertEquals("out-" + index, wire.hostRecorder.text(ids.get(index)));
            assertEquals("in-" + index, wire.guestRecorder.text(ids.get(index)));
        }
    }

    @Test
    public void helloHandshakeAndPingAreConnectionLevel() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        assertTrue(wire.host.helloReceived());
        assertEquals(Fln1Payloads.CAP_TERMINAL, wire.host.peerFeatures());
        assertNotNull(wire.hostRecorder.hello);

        wire.host.sendPing(42L);
        assertEquals(42L, wire.hostRecorder.pong);
    }

    @Test
    public void openSessionRequiresHello() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX, null, null, false);
        assertFalse(wire.host.helloSent());
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, () -> wire.open("main"));
        assertEquals(TerminalErrorCode.PROTOCOL_VERSION, failure.code());
    }

    @Test
    public void reentrantAcceptDuringOpenRequestIsNotLost() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX, null, null, true);
        int id = wire.open("main");
        wire.accept(id);
        assertEquals(TerminalSessionState.OPEN, wire.host.state(id));
        assertEquals(TerminalSessionState.OPEN, wire.guest.state(id));
    }

    @Test
    public void reentrantAcceptInsideTheCallbackIsNotLost() throws Exception {
        TerminalMux[] hostRef = new TerminalMux[1];
        TerminalMux[] guestRef = new TerminalMux[1];
        Fln1TerminalCodec hostCodec = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        Fln1TerminalCodec guestCodec = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
        TerminalMux guest = new TerminalMux(
                TerminalMux.Role.GUEST,
                MAX,
                frame -> hostRef[0].onFrame(hostCodec.decode(guestCodec.encode(frame))),
                new TerminalMux.Listener() {
                    @Override
                    public void onOpenRequest(int sessionId, Fln1Payloads.OpenPty request) {
                        try {
                            guestRef[0].acceptSession(sessionId, 1, 1024);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                });
        TerminalMux host = new TerminalMux(
                TerminalMux.Role.HOST,
                MAX,
                frame -> guestRef[0].onFrame(guestCodec.decode(hostCodec.encode(frame))),
                null);
        hostRef[0] = host;
        guestRef[0] = guest;

        host.sendHello(new Fln1Payloads.Hello(1, 1, ""));
        int id = host.openSession(80, 24, "sh");
        assertEquals(TerminalSessionState.OPEN, host.state(id));
        assertEquals(TerminalSessionState.OPEN, guest.state(id));
    }

    @Test
    public void peerMaxFrameFromHelloOkLimitsData() throws Exception {
        TerminalMux host = new TerminalMux(TerminalMux.Role.HOST, frame -> {});
        host.sendHello(new Fln1Payloads.Hello(1, 1, ""));
        host.onFrame(new TerminalFrame(
                TerminalFrameType.HELLO, 0,
                Fln1Payloads.encodeHelloOk(new Fln1Payloads.HelloOk(
                        1, Fln1Payloads.CAP_TERMINAL, 4, 1024))));
        int id = host.openSession(80, 24, "sh");
        host.onFrame(new TerminalFrame(
                TerminalFrameType.OPEN_PTY, id,
                Fln1Payloads.encodePtyOpened(4242, 1024)));

        assertEquals(4, host.sendData(id, new byte[8]));
        assertEquals(1, host.metrics().creditStalls());
        assertEquals(4, host.sendData(id, new byte[8]));
        assertEquals(2, host.metrics().creditStalls());
    }

    @Test
    public void duplicateHelloOkIsRejected() throws Exception {
        TerminalMux host = new TerminalMux(TerminalMux.Role.HOST, frame -> {});
        host.sendHello(new Fln1Payloads.Hello(1, 1, ""));
        TerminalFrame helloOk = new TerminalFrame(
                TerminalFrameType.HELLO, 0,
                Fln1Payloads.encodeHelloOk(new Fln1Payloads.HelloOk(
                        1, Fln1Payloads.CAP_TERMINAL, 65_536, 262_144)));
        host.onFrame(helloOk);
        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, () -> host.onFrame(helloOk));
        assertEquals(TerminalErrorCode.UNKNOWN_TYPE, failure.code());
    }

    @Test
    public void wrongRoleIsRejected() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        TerminalProtocolException open = assertThrows(
                TerminalProtocolException.class, () -> wire.guest.openSession(80, 24, "sh"));
        assertEquals(TerminalErrorCode.WRONG_ROLE, open.code());
        TerminalProtocolException accept = assertThrows(
                TerminalProtocolException.class, () -> wire.host.acceptSession(1, 1, 1024));
        assertEquals(TerminalErrorCode.WRONG_ROLE, accept.code());
        TerminalProtocolException credit = assertThrows(
                TerminalProtocolException.class, () -> wire.guest.grantCredit(1, 1024));
        assertEquals(TerminalErrorCode.WRONG_ROLE, credit.code());
        TerminalProtocolException eof = assertThrows(
                TerminalProtocolException.class, () -> wire.host.sendEof(1, 0));
        assertEquals(TerminalErrorCode.WRONG_ROLE, eof.code());
    }

    @Test
    public void emptyDataAndInvalidCreditAreRejected() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);
        assertThrows(IllegalArgumentException.class,
                () -> wire.host.sendData(id, new byte[0]));
        TerminalProtocolException credit = assertThrows(
                TerminalProtocolException.class, () -> wire.host.grantCredit(id, 0));
        assertEquals(TerminalErrorCode.INVALID_PAYLOAD, credit.code());
    }

    @Test
    public void sendAfterCloseIsTypedAndIdempotent() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);
        wire.host.sendClose(id);
        wire.host.sendClose(id);
        wire.guest.sendClosed(id);
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
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id);
        wire.host.closeAll(TerminalCloseReason.TRANSPORT_CLOSED);
        assertEquals(0, wire.host.activeSessions());
        assertEquals(TerminalSessionState.CLOSED, wire.host.state(id));
        assertEquals(TerminalSessionState.HALF_CLOSED_REMOTE, wire.guest.state(id));
        wire.guest.abortAll(TerminalCloseReason.TRANSPORT_CLOSED);
        assertEquals(0, wire.guest.activeSessions());
    }

    @Test
    public void failedOpenWithConnectionScopedErrorIsClosedAndFifoIsReset() throws Exception {
        Fln1TerminalCodec codec = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        TerminalMux host = new TerminalMux(TerminalMux.Role.HOST, MAX, codec::encode, null);
        host.sendHello(new Fln1Payloads.Hello(1, 1, ""));

        int failed = host.openSession(80, 24, "sh");
        assertEquals(1, failed);
        assertEquals(TerminalSessionState.OPENING, host.state(failed));

        host.onFrame(codec.decode(fln1Frame(
                Fln1TerminalCodec.OP_ERROR, 0,
                Fln1Payloads.encodeError(new Fln1Payloads.Error(6, "session limit")))));
        assertEquals(TerminalSessionState.CLOSED, host.state(failed));
        assertEquals(0, host.activeSessions());

        int next = host.openSession(80, 24, "sh");
        assertEquals(2, next);
        TerminalFrame ack = codec.decode(fln1Frame(
                Fln1TerminalCodec.OP_PTY_OPENED, 9,
                Fln1Payloads.encodePtyOpened(123, 262_144)));
        assertEquals(2, ack.sessionId());
        host.onFrame(ack);
        assertEquals(TerminalSessionState.OPEN, host.state(next));
        assertEquals(1, host.activeSessions());
    }

    @Test
    public void connectionScopedErrorClosesOpeningSessionsButNotOpenOnes() throws Exception {
        Fln1TerminalCodec codec = new Fln1TerminalCodec(TerminalMux.Role.HOST);
        TerminalMux host = new TerminalMux(TerminalMux.Role.HOST, MAX, codec::encode, null);
        host.sendHello(new Fln1Payloads.Hello(1, 1, ""));

        int open = host.openSession(80, 24, "sh");
        host.onFrame(codec.decode(fln1Frame(
                Fln1TerminalCodec.OP_PTY_OPENED, 3,
                Fln1Payloads.encodePtyOpened(11, 1024))));
        int opening = host.openSession(80, 24, "sh");

        host.onFrame(codec.decode(fln1Frame(
                Fln1TerminalCodec.OP_ERROR, 0,
                Fln1Payloads.encodeError(new Fln1Payloads.Error(6, "session limit")))));

        // OPEN sessions belong to the transport; ERROR 0 only fails pending opens.
        assertEquals(TerminalSessionState.OPEN, host.state(open));
        assertEquals(TerminalSessionState.CLOSED, host.state(opening));
        assertEquals(1, host.activeSessions());
    }

    @Test
    public void defaultSessionLimitMatchesThePayloadDefault() throws Exception {
        assertEquals(8, TerminalMux.DEFAULT_MAX_SESSIONS);
        TerminalMux host = new TerminalMux(TerminalMux.Role.HOST, frame -> {});
        host.sendHello(new Fln1Payloads.Hello(1, 1, ""));
        for (int index = 0; index < TerminalMux.DEFAULT_MAX_SESSIONS; index++) {
            host.openSession(80, 24, "sh");
        }
        assertEquals(TerminalMux.DEFAULT_MAX_SESSIONS, host.activeSessions());

        TerminalProtocolException failure = assertThrows(
                TerminalProtocolException.class, () -> host.openSession(80, 24, "sh"));
        assertEquals(TerminalErrorCode.SESSION_LIMIT, failure.code());
    }

    @Test
    public void hostToGuestDataIsFrameLimitedButNeverWindowed() throws Exception {
        Fln1Wire wire = Fln1Wire.create(MAX);
        int id = wire.open("main");
        wire.accept(id, 1024);

        byte[] chunk = new byte[Fln1Payloads.MAX_PTY_DATA];
        int total = 0;
        for (int index = 0; index < 160; index++) {
            assertEquals(chunk.length, wire.host.sendData(id, chunk));
            total += chunk.length;
        }
        assertEquals(5 * 1024 * 1024, total);
        assertEquals(total, wire.guestRecorder.bytes(id));
        // Host input consumes neither counter; the guest's output window is
        // still the 1024 bytes advertised in PTY_OPENED.
        assertEquals(TerminalMux.MAX_CREDIT_BYTES, wire.guest.receiveCredit(id));
        assertEquals(1024, wire.guest.sendCredit(id));

        // guest -> host keeps the advertised window and still stalls.
        assertEquals(1024, wire.guest.sendData(id, chunk));
        assertEquals(0, wire.guest.sendData(id, chunk));
        assertEquals(2, wire.guest.metrics().creditStalls());
    }

    private static byte[] fln1Frame(int opcode, int session, byte[] payload) {
        return java.nio.ByteBuffer.allocate(Fln1TerminalCodec.HEADER_BYTES + payload.length)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .put(Fln1TerminalCodec.MAGIC)
                .put((byte) Fln1TerminalCodec.VERSION)
                .put((byte) opcode)
                .putInt(session)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    private static byte[] openPtyPayload() {
        return Fln1Payloads.encodeOpenPty(
                new Fln1Payloads.OpenPty(80, 24, new String[] {"sh"}));
    }

    private static byte[] nonce(long value) {
        return java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    static final class Recorder implements TerminalMux.Listener {
        private final Map<Integer, ByteArrayOutputStream> output = new HashMap<>();
        private final Map<Integer, TerminalErrorCode> errors = new HashMap<>();
        private final Map<Integer, TerminalCloseReason> closed = new HashMap<>();
        private int openRequests;
        private Fln1Payloads.OpenPty openRequest;
        private Fln1Payloads.Resize resize;
        private int signal;
        private int closeRequests;
        private int lastExitCode = Integer.MIN_VALUE;
        private long pong = Long.MIN_VALUE;
        private Fln1Payloads.HelloOk hello;

        @Override
        public void onHello(Fln1Payloads.HelloOk hello) {
            this.hello = hello;
        }

        @Override
        public void onOpenRequest(int sessionId, Fln1Payloads.OpenPty request) {
            openRequests++;
            openRequest = request;
        }

        @Override
        public void onData(int sessionId, byte[] data) {
            output.computeIfAbsent(sessionId, ignored -> new ByteArrayOutputStream())
                    .writeBytes(data);
        }

        @Override
        public void onResize(int sessionId, Fln1Payloads.Resize resize) {
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

        String text(int sessionId) {
            ByteArrayOutputStream bytes = output.get(sessionId);
            return bytes == null ? "" : bytes.toString(StandardCharsets.UTF_8);
        }

        int bytes(int sessionId) {
            ByteArrayOutputStream bytes = output.get(sessionId);
            return bytes == null ? 0 : bytes.size();
        }

        byte[] bytesCopy(int sessionId) {
            ByteArrayOutputStream bytes = output.get(sessionId);
            return bytes == null ? new byte[0] : bytes.toByteArray();
        }

        boolean hasError(int sessionId, TerminalErrorCode code) {
            return errors.get(sessionId) == code;
        }

        boolean closedWith(int sessionId, TerminalCloseReason reason) {
            return closed.get(sessionId) == reason;
        }
    }
}
