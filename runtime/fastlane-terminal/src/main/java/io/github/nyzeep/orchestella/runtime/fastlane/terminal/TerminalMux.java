package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Single-connection, multi-PTY state machine.
 *
 * <p>The mux owns no threads and stores no terminal bytes beyond the frame
 * currently being delivered. Credit is explicit in both directions, so a slow
 * consumer stops the peer instead of growing an unbounded queue.
 *
 * <p>Callbacks run while the mux lock is held; listeners must be short and
 * must not block on another thread that calls back into the mux.
 */
public final class TerminalMux {
    public enum Role {
        HOST,
        GUEST
    }

    @FunctionalInterface
    public interface FrameSink {
        void write(TerminalFrame frame) throws IOException;
    }

    /**
     * Optional consumer; every method is a no-op by default. Implementations
     * must not throw: a thrown exception aborts the current pump and may leave
     * the session unresumable.
     */
    public interface Listener {
        default void onHello(TerminalProtocol.Hello hello) {}

        default void onOpenRequest(int sessionId, TerminalProtocol.OpenPty request) {}

        default void onSessionOpened(int sessionId) {}

        default void onData(int sessionId, byte[] data) {}

        default void onResize(int sessionId, TerminalProtocol.Resize resize) {}

        default void onSignal(int sessionId, int signal) {}

        default void onCloseRequested(int sessionId) {}

        default void onEof(int sessionId, int exitCode) {}

        default void onSessionClosed(int sessionId, TerminalCloseReason reason) {}

        default void onError(int sessionId, TerminalErrorCode code, String message) {}

        default void onPong(long nonce) {}

        default void onMetrics(TerminalMuxMetrics metrics) {}
    }

    private static final Listener NO_LISTENER = new Listener() {};

    private final Role role;
    private final int maxSessions;
    private final FrameSink sink;
    private final Listener listener;
    private final Map<Integer, Session> sessions = new HashMap<>();
    private final Set<Integer> retired = new HashSet<>();

    private int nextSessionId = 1;
    private int activeSessions;
    private boolean vmStopped;
    private int peerVersion = -1;
    private int peerFeatures;
    private int peerMaxFrameBytes = TerminalProtocol.MAX_PAYLOAD_BYTES;

    private long sessionsOpened;
    private long sessionsClosed;
    private long framesIn;
    private long framesOut;
    private long bytesIn;
    private long bytesOut;
    private long creditStalls;
    private long protocolErrors;

    public TerminalMux(Role role, FrameSink sink) {
        this(role, TerminalProtocol.MAX_SESSIONS, sink, NO_LISTENER);
    }

    public TerminalMux(Role role, int maxSessions, FrameSink sink, Listener listener) {
        this.role = Objects.requireNonNull(role, "role");
        if (maxSessions <= 0 || maxSessions > TerminalProtocol.MAX_SESSIONS) {
            throw new IllegalArgumentException("maxSessions out of range");
        }
        this.maxSessions = maxSessions;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.listener = listener == null ? NO_LISTENER : listener;
    }

    /**
     * Host-only PTY allocation. Returns the new session id; the session stays
     * OPENING until the guest's OPEN_PTY acknowledgement.
     */
    public synchronized int openSession(
            int columns, int rows, String name, int outputCredit) throws IOException {
        requireRole(Role.HOST, "openSession");
        ensureRunning();
        if (activeSessions >= maxSessions) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_LIMIT, "session limit reached");
        }
        TerminalProtocol.OpenPty request =
                new TerminalProtocol.OpenPty(columns, rows, outputCredit, name);
        int sessionId = allocateSessionId();
        Session session = new Session(
                sessionId, 0, outputCredit, TerminalSessionState.OPENING);
        sessions.put(sessionId, session);
        activeSessions++;
        sessionsOpened++;
        TerminalFrame frame = new TerminalFrame(
                TerminalFrameType.OPEN_PTY, sessionId, TerminalProtocol.encodeOpenPty(request));
        try {
            writeFrame(frame);
        } catch (IOException failure) {
            finish(session, TerminalCloseReason.PROTOCOL_ERROR);
            throw failure;
        }
        return sessionId;
    }

    /** Guest-only acknowledgement; grants the host input credit. */
    public synchronized void acceptSession(int sessionId, int inputCredit) throws IOException {
        requireRole(Role.GUEST, "acceptSession");
        ensureRunning();
        Session session = requireSession(sessionId);
        if (session.state != TerminalSessionState.OPENING) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session is not opening");
        }
        if (inputCredit <= 0 || inputCredit > TerminalProtocol.MAX_CREDIT_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "input credit out of range");
        }
        session.receiveCredit = inputCredit;
        session.state = TerminalSessionState.OPEN;
        TerminalFrame frame = new TerminalFrame(
                TerminalFrameType.OPEN_PTY, sessionId, TerminalProtocol.encodeCredit(inputCredit));
        try {
            writeFrame(frame);
        } catch (IOException failure) {
            forceClose(sessionId, TerminalCloseReason.PROTOCOL_ERROR);
            throw failure;
        }
        listener.onSessionOpened(sessionId);
    }

    /** Guest-only rejection of an OPEN_PTY request. */
    public synchronized void rejectSession(
            int sessionId, TerminalErrorCode code, String message) throws IOException {
        requireRole(Role.GUEST, "rejectSession");
        Session session = requireSession(sessionId);
        if (session.state != TerminalSessionState.OPENING) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session is not opening");
        }
        sendError(sessionId, code, message);
        forceClose(sessionId, TerminalCloseReason.REJECTED);
    }

    /**
     * Sends as much of {@code data} as the current credit allows.
     *
     * @return accepted byte count, from 0 to {@code data.length}
     */
    public synchronized int sendData(int sessionId, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }
        ensureRunning();
        Session session = requireActive(sessionId);
        if (!session.state.canSendData()) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot send data");
        }
        int allowed = Math.min(
                data.length,
                Math.min(peerMaxFrameBytes, Math.min(TerminalProtocol.MAX_PAYLOAD_BYTES,
                        session.sendCredit)));
        if (allowed == 0) {
            creditStalls++;
            return 0;
        }
        byte[] chunk = Arrays.copyOf(data, allowed);
        session.sendCredit -= allowed;
        try {
            writeFrame(new TerminalFrame(TerminalFrameType.DATA, sessionId, chunk));
        } catch (IOException failure) {
            forceClose(sessionId, TerminalCloseReason.PROTOCOL_ERROR);
            throw failure;
        }
        bytesOut += allowed;
        if (allowed < data.length) {
            creditStalls++;
        }
        return allowed;
    }

    public synchronized void sendResize(int sessionId, int columns, int rows) throws IOException {
        ensureRunning();
        Session session = requireOpen(sessionId);
        if (!session.state.canSendData()) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot resize");
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.RESIZE,
                sessionId,
                TerminalProtocol.encodeResize(new TerminalProtocol.Resize(columns, rows))));
    }

    public synchronized void sendSignal(int sessionId, int signal) throws IOException {
        ensureRunning();
        Session session = requireOpen(sessionId);
        if (!session.state.canSendData()) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot signal");
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.SIGNAL, sessionId, TerminalProtocol.encodeSignal(signal)));
    }

    /** Half-closes the local side; idempotent once already half-closed locally. */
    public synchronized void sendClose(int sessionId) throws IOException {
        ensureRunning();
        Session session = requireSession(sessionId);
        if (session.state == TerminalSessionState.CLOSED || session.localHalfClosed) {
            return;
        }
        writeFrame(new TerminalFrame(TerminalFrameType.CLOSE, sessionId, new byte[0]));
        markLocalHalfClosed(session, TerminalCloseReason.LOCAL_CLOSE);
    }

    /** Half-closes the remote side (normally the guest's PTY exit). */
    public synchronized void sendEof(int sessionId, int exitCode) throws IOException {
        ensureRunning();
        Session session = requireSession(sessionId);
        if (session.state == TerminalSessionState.CLOSED || session.localHalfClosed) {
            return;
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.EOF, sessionId, TerminalProtocol.encodeExitCode(exitCode)));
        listener.onEof(sessionId, exitCode);
        markLocalHalfClosed(session, TerminalCloseReason.EOF);
    }

    public synchronized void sendPing(long nonce) throws IOException {
        ensureRunning();
        writeFrame(new TerminalFrame(
                TerminalFrameType.PING, 0, TerminalProtocol.encodePing(nonce)));
    }

    public synchronized void sendHello(TerminalProtocol.Hello hello) throws IOException {
        ensureRunning();
        writeFrame(new TerminalFrame(
                TerminalFrameType.HELLO, 0, TerminalProtocol.encodeHello(hello)));
    }

    public synchronized void sendMetrics() throws IOException {
        ensureRunning();
        writeFrame(new TerminalFrame(
                TerminalFrameType.METRICS, 0, new byte[0]));
    }

    /** Grants the peer more credit to send DATA to this endpoint. */
    public synchronized void grantCredit(int sessionId, int bytes) throws IOException {
        ensureRunning();
        Session session = requireActive(sessionId);
        if (!session.state.canReceiveData()) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot receive data");
        }
        session.receiveCredit = addCredit(session.receiveCredit, bytes);
        try {
            writeFrame(new TerminalFrame(
                    TerminalFrameType.CREDIT, sessionId, TerminalProtocol.encodeCredit(bytes)));
        } catch (IOException failure) {
            forceClose(sessionId, TerminalCloseReason.PROTOCOL_ERROR);
            throw failure;
        }
    }

    /** Dispatches one decoded frame. */
    public synchronized void onFrame(TerminalFrame frame) throws IOException {
        Objects.requireNonNull(frame, "frame");
        ensureRunning();
        framesIn++;
        switch (frame.type()) {
            case HELLO -> {
                TerminalProtocol.Hello hello = TerminalProtocol.parseHello(frame.payload());
                peerVersion = TerminalProtocol.VERSION;
                peerFeatures = hello.features();
                peerMaxFrameBytes = Math.min(
                        TerminalProtocol.MAX_PAYLOAD_BYTES, hello.maxFrameBytes());
                listener.onHello(hello);
            }
            case OPEN_PTY -> handleOpenPty(frame);
            case DATA -> handleData(frame);
            case RESIZE -> {
                TerminalProtocol.Resize resize = TerminalProtocol.parseResize(frame.payload());
                Session session = requireActive(frame.sessionId());
                if (!session.state.canReceiveData()) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.SESSION_CLOSED, "session cannot receive resize");
                }
                listener.onResize(frame.sessionId(), resize);
            }
            case SIGNAL -> {
                int signal = TerminalProtocol.parseSignal(frame.payload());
                requireOpen(frame.sessionId());
                listener.onSignal(frame.sessionId(), signal);
            }
            case CLOSE -> handleClose(frame);
            case EOF -> handleEof(frame);
            case PING -> {
                long nonce = TerminalProtocol.parsePing(frame.payload());
                writeFrame(new TerminalFrame(
                        TerminalFrameType.PONG, 0, TerminalProtocol.encodePing(nonce)));
            }
            case PONG -> listener.onPong(TerminalProtocol.parsePing(frame.payload()));
            case METRICS -> {
                if (frame.payloadLength() == 0) {
                    writeFrame(new TerminalFrame(
                            TerminalFrameType.METRICS, 0, TerminalProtocol.encodeMetrics(metrics())));
                } else {
                    listener.onMetrics(TerminalProtocol.parseMetrics(frame.payload()));
                }
            }
            case ERROR -> handleError(frame);
            case CREDIT -> handleCredit(frame);
        }
    }

    /** Stops every session without writing frames; the VM is already gone. */
    public synchronized void vmStopped() {
        if (vmStopped) {
            return;
        }
        vmStopped = true;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.state != TerminalSessionState.CLOSED) {
                finish(session, TerminalCloseReason.VM_STOPPED);
            }
        }
    }

    /** Best-effort CLOSE for every open session, then closes the local state. */
    public synchronized void closeAll(TerminalCloseReason reason) throws IOException {
        IOException first = null;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.state == TerminalSessionState.CLOSED) {
                continue;
            }
            try {
                writeFrame(new TerminalFrame(
                        TerminalFrameType.CLOSE, session.id, new byte[0]));
            } catch (IOException failure) {
                if (first == null) {
                    first = failure;
                }
            }
            finish(session, reason);
        }
        if (first != null) {
            throw first;
        }
    }

    /** Drops every session without writing; transport is already gone. */
    public synchronized void abortAll(TerminalCloseReason reason) {
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.state != TerminalSessionState.CLOSED) {
                finish(session, reason);
            }
        }
    }

    public synchronized TerminalSessionState state(int sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? null : session.state;
    }

    public synchronized int sendCredit(int sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? -1 : session.sendCredit;
    }

    public synchronized int receiveCredit(int sessionId) {
        Session session = sessions.get(sessionId);
        return session == null ? -1 : session.receiveCredit;
    }

    public synchronized int activeSessions() {
        return activeSessions;
    }

    public synchronized TerminalMuxMetrics metrics() {
        return new TerminalMuxMetrics(
                sessionsOpened,
                sessionsClosed,
                framesIn,
                framesOut,
                bytesIn,
                bytesOut,
                creditStalls,
                protocolErrors);
    }

    public synchronized boolean helloReceived() {
        return peerVersion >= 0;
    }

    public synchronized int peerFeatures() {
        return peerFeatures;
    }

    private void handleOpenPty(TerminalFrame frame) throws IOException {
        int sessionId = frame.sessionId();
        if (role == Role.HOST) {
            Session session = sessions.get(sessionId);
            if (session == null) {
                sendError(sessionId, TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
                return;
            }
            if (session.state != TerminalSessionState.OPENING) {
                sendError(sessionId, TerminalErrorCode.SESSION_EXISTS, "duplicate ack");
                return;
            }
            int credit;
            try {
                credit = TerminalProtocol.parseCredit(frame.payload());
            } catch (TerminalProtocolException failure) {
                sendError(sessionId, failure.code(), failure.getMessage());
                forceClose(sessionId, TerminalCloseReason.PROTOCOL_ERROR);
                return;
            }
            session.sendCredit = credit;
            session.state = TerminalSessionState.OPEN;
            listener.onSessionOpened(sessionId);
            return;
        }

        Session existing = sessions.get(sessionId);
        if (existing != null && existing.state != TerminalSessionState.CLOSED) {
            sendError(sessionId, TerminalErrorCode.SESSION_EXISTS, "session already exists");
            return;
        }
        if (retired.contains(sessionId)) {
            sendError(sessionId, TerminalErrorCode.SESSION_RETIRED, "session id retired");
            return;
        }
        if (activeSessions >= maxSessions) {
            sendError(sessionId, TerminalErrorCode.SESSION_LIMIT, "session limit reached");
            return;
        }
        TerminalProtocol.OpenPty request;
        try {
            request = TerminalProtocol.parseOpenPty(frame.payload());
        } catch (TerminalProtocolException failure) {
            sendError(sessionId, failure.code(), failure.getMessage());
            return;
        }
        sessions.put(sessionId, new Session(
                sessionId, request.outputCredit(), 0, TerminalSessionState.OPENING));
        activeSessions++;
        sessionsOpened++;
        listener.onOpenRequest(sessionId, request);
    }

    private void handleData(TerminalFrame frame) throws IOException {
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        if (!session.state.canReceiveData()) {
            sendError(frame.sessionId(), TerminalErrorCode.SESSION_CLOSED, "session is closed");
            return;
        }
        int length = frame.payloadLength();
        if (length == 0) {
            sendError(frame.sessionId(), TerminalErrorCode.INVALID_PAYLOAD, "empty data frame");
            return;
        }
        if (length > session.receiveCredit) {
            sendError(frame.sessionId(), TerminalErrorCode.CREDIT_EXCEEDED, "credit exceeded");
            forceClose(frame.sessionId(), TerminalCloseReason.PROTOCOL_ERROR);
            return;
        }
        session.receiveCredit -= length;
        bytesIn += length;
        listener.onData(frame.sessionId(), frame.payload());
    }

    private void handleClose(TerminalFrame frame) throws IOException {
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        if (session.state == TerminalSessionState.CLOSED || session.remoteHalfClosed) {
            return;
        }
        boolean wasLocal = session.localHalfClosed;
        markRemoteHalfClosed(session, TerminalCloseReason.REMOTE_CLOSE);
        if (!wasLocal) {
            listener.onCloseRequested(frame.sessionId());
        }
    }

    private void handleEof(TerminalFrame frame) throws IOException {
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        if (session.state == TerminalSessionState.CLOSED || session.remoteHalfClosed) {
            return;
        }
        int exitCode;
        try {
            exitCode = TerminalProtocol.parseExitCode(frame.payload());
        } catch (TerminalProtocolException failure) {
            sendError(frame.sessionId(), failure.code(), failure.getMessage());
            forceClose(frame.sessionId(), TerminalCloseReason.PROTOCOL_ERROR);
            return;
        }
        listener.onEof(frame.sessionId(), exitCode);
        markRemoteHalfClosed(session, TerminalCloseReason.EOF);
    }

    private void handleError(TerminalFrame frame) throws IOException {
        TerminalProtocol.ErrorPayload error = TerminalProtocol.parseError(frame.payload());
        listener.onError(frame.sessionId(), error.code(), error.message());
        if (frame.sessionId() > 0) {
            forceClose(frame.sessionId(), TerminalCloseReason.PROTOCOL_ERROR);
        }
    }

    private void handleCredit(TerminalFrame frame) throws IOException {
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        if (session.state == TerminalSessionState.CLOSED) {
            return;
        }
        if (!session.state.canSendData()) {
            return;
        }
        try {
            int credit = TerminalProtocol.parseCredit(frame.payload());
            session.sendCredit = addCredit(session.sendCredit, credit);
        } catch (TerminalProtocolException failure) {
            sendError(frame.sessionId(), failure.code(), failure.getMessage());
            forceClose(frame.sessionId(), TerminalCloseReason.PROTOCOL_ERROR);
        }
    }

    private int allocateSessionId() throws TerminalProtocolException {
        while (nextSessionId > 0
                && (sessions.containsKey(nextSessionId) || retired.contains(nextSessionId))) {
            if (nextSessionId == Integer.MAX_VALUE) {
                throw new TerminalProtocolException(
                        TerminalErrorCode.SESSION_LIMIT, "session id space exhausted");
            }
            nextSessionId++;
        }
        if (nextSessionId <= 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_LIMIT, "session id space exhausted");
        }
        return nextSessionId++;
    }

    private Session requireSession(int sessionId) throws TerminalProtocolException {
        Session session = sessions.get(sessionId);
        if (session == null) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.UNKNOWN_SESSION, "unknown session: " + sessionId);
        }
        return session;
    }

    private Session requireOpen(int sessionId) throws TerminalProtocolException {
        Session session = requireSession(sessionId);
        if (session.state == TerminalSessionState.CLOSED) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session is closed");
        }
        return session;
    }

    private Session requireActive(int sessionId) throws TerminalProtocolException {
        return requireOpen(sessionId);
    }

    private void sendError(int sessionId, TerminalErrorCode code, String message)
            throws IOException {
        protocolErrors++;
        writeFrame(new TerminalFrame(
                TerminalFrameType.ERROR,
                sessionId,
                TerminalProtocol.encodeError(new TerminalProtocol.ErrorPayload(code, message))));
        listener.onError(sessionId, code, message);
    }

    private void writeFrame(TerminalFrame frame) throws IOException {
        sink.write(frame);
        framesOut++;
    }

    private void markLocalHalfClosed(Session session, TerminalCloseReason reason) {
        session.localHalfClosed = true;
        if (session.remoteHalfClosed) {
            finish(session, reason);
        } else {
            session.state = TerminalSessionState.HALF_CLOSED_LOCAL;
        }
    }

    private void markRemoteHalfClosed(Session session, TerminalCloseReason reason) {
        session.remoteHalfClosed = true;
        if (session.localHalfClosed) {
            finish(session, reason);
        } else {
            session.state = TerminalSessionState.HALF_CLOSED_REMOTE;
        }
    }

    private void finish(Session session, TerminalCloseReason reason) {
        if (session.state == TerminalSessionState.CLOSED) {
            return;
        }
        session.state = TerminalSessionState.CLOSED;
        session.localHalfClosed = true;
        session.remoteHalfClosed = true;
        session.sendCredit = 0;
        session.receiveCredit = 0;
        activeSessions--;
        sessionsClosed++;
        retired.add(session.id);
        listener.onSessionClosed(session.id, reason);
    }

    private void forceClose(int sessionId, TerminalCloseReason reason) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            finish(session, reason);
        }
    }

    private static int addCredit(int current, int delta) throws TerminalProtocolException {
        if (delta <= 0 || delta > TerminalProtocol.MAX_CREDIT_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "credit out of range");
        }
        long next = (long) current + delta;
        if (next > TerminalProtocol.MAX_CREDIT_BYTES) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.CREDIT_OVERFLOW, "credit window overflow");
        }
        return (int) next;
    }

    private void requireRole(Role expected, String operation) throws TerminalProtocolException {
        if (role != expected) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.WRONG_ROLE, operation + " is not valid for " + role);
        }
    }

    private void ensureRunning() throws TerminalProtocolException {
        if (vmStopped) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.VM_STOPPED, "VM is stopped");
        }
    }

    private static final class Session {
        private final int id;
        private int sendCredit;
        private int receiveCredit;
        private TerminalSessionState state;
        private boolean localHalfClosed;
        private boolean remoteHalfClosed;

        private Session(int id, int sendCredit, int receiveCredit, TerminalSessionState state) {
            this.id = id;
            this.sendCredit = sendCredit;
            this.receiveCredit = receiveCredit;
            this.state = state;
        }
    }
}
