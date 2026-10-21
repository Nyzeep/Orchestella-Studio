package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Single-connection, multi-PTY state machine over the canonical {@code FLN1}
 * wire contract.
 *
 * <p>The mux owns no threads and stores no terminal bytes beyond the frame
 * currently being delivered. Flow control is one-directional in {@code FLN1}:
 * guest-to-host output is windowed (the payload announces
 * {@code initial_window} in {@code PTY_OPENED} and the host returns credit with
 * {@code WINDOW}), while host-to-guest input has no window at all. Host input
 * is bounded only by the 32 KiB {@code PTY_DATA} frame limit and by the
 * synchronous sink / payload PTY write, which is where backpressure lives.
 * {@code sendCredit} and {@code receiveCredit} therefore track one direction
 * each; they are not two halves of a bidirectional window.
 *
 * <p>The default session limit ({@value #DEFAULT_MAX_SESSIONS}) matches the
 * payload's default {@code FASTLANE_MAX_SESSIONS}; {@link #MAX_SESSIONS} is a
 * hard ceiling for explicit configuration, not a production default.
 *
 * <p>Callbacks run while the mux lock is held; listeners must be short and
 * must not block on another thread that calls back into the mux.
 */
public final class TerminalMux {
    public enum Role {
        HOST,
        GUEST
    }

    /**
     * Host default, matching the payload's {@code FASTLANE_MAX_SESSIONS}
     * default. A host that opens more than this would be rejected by the
     * payload as {@code SESSION_LIMIT} at the ninth PTY.
     */
    public static final int DEFAULT_MAX_SESSIONS = 8;

    /** Hard ceiling accepted by the constructor; not the production default. */
    public static final int MAX_SESSIONS = 128;

    public static final int MAX_CREDIT_BYTES = 4_194_304;

    @FunctionalInterface
    public interface FrameSink {
        void write(TerminalFrame frame) throws IOException;

        /**
         * Called once when a session reaches CLOSED so a codec can drop its
         * wire mapping. The default keeps stateless sinks unchanged.
         */
        default void releaseSession(int sessionId) {}
    }

    /**
     * Optional consumer; every method is a no-op by default. Implementations
     * must not throw: a thrown exception aborts the current pump and may leave
     * the session unresumable.
     */
    public interface Listener {
        default void onHello(Fln1Payloads.HelloOk hello) {}

        default void onHelloRequest(Fln1Payloads.Hello hello) {}

        default void onOpenRequest(int sessionId, Fln1Payloads.OpenPty request) {}

        default void onSessionOpened(int sessionId) {}

        default void onData(int sessionId, byte[] data) {}

        default void onResize(int sessionId, Fln1Payloads.Resize resize) {}

        default void onSignal(int sessionId, int signal) {}

        default void onCloseRequested(int sessionId) {}

        default void onEof(int sessionId, int exitCode) {}

        default void onSessionClosed(int sessionId, TerminalCloseReason reason) {}

        default void onError(int sessionId, TerminalErrorCode code, String message) {}

        default void onPong(long nonce) {}
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
    private boolean helloSent;
    private int peerVersion = -1;
    private int peerFeatures;
    private int peerMaxFrameBytes = Fln1Payloads.MAX_PTY_DATA;

    private long sessionsOpened;
    private long sessionsClosed;
    private long framesIn;
    private long framesOut;
    private long bytesIn;
    private long bytesOut;
    private long creditStalls;
    private long protocolErrors;

    public TerminalMux(Role role, FrameSink sink) {
        this(role, DEFAULT_MAX_SESSIONS, sink, NO_LISTENER);
    }

    public TerminalMux(Role role, int maxSessions, FrameSink sink, Listener listener) {
        this.role = Objects.requireNonNull(role, "role");
        if (maxSessions <= 0 || maxSessions > MAX_SESSIONS) {
            throw new IllegalArgumentException("maxSessions out of range");
        }
        this.maxSessions = maxSessions;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.listener = listener == null ? NO_LISTENER : listener;
    }

    /** Host-only HELLO; must be sent before any other host frame. */
    public synchronized void sendHello(Fln1Payloads.Hello hello) throws IOException {
        requireRole(Role.HOST, "sendHello");
        ensureRunning();
        writeFrame(new TerminalFrame(
                TerminalFrameType.HELLO, 0, Fln1Payloads.encodeHello(hello)));
        helloSent = true;
    }

    /** Guest-only HELLO_OK; production guests are the C payload. */
    public synchronized void sendHelloOk(Fln1Payloads.HelloOk hello) throws IOException {
        requireRole(Role.GUEST, "sendHelloOk");
        ensureRunning();
        writeFrame(new TerminalFrame(
                TerminalFrameType.HELLO, 0, Fln1Payloads.encodeHelloOk(hello)));
    }

    /**
     * Host-only PTY allocation. Returns the local session id; the session stays
     * OPENING until the payload's {@code PTY_OPENED}.
     */
    public synchronized int openSession(int columns, int rows, String... argv)
            throws IOException {
        requireRole(Role.HOST, "openSession");
        ensureRunning();
        if (!helloSent) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_VERSION, "HELLO must be sent before OPEN_PTY");
        }
        if (activeSessions >= maxSessions) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_LIMIT, "session limit reached");
        }
        Fln1Payloads.OpenPty request = new Fln1Payloads.OpenPty(columns, rows, argv);
        int sessionId = allocateSessionId();
        Session session = new Session(sessionId, TerminalSessionState.OPENING);
        sessions.put(sessionId, session);
        activeSessions++;
        sessionsOpened++;
        TerminalFrame frame = new TerminalFrame(
                TerminalFrameType.OPEN_PTY, sessionId, Fln1Payloads.encodeOpenPty(request));
        try {
            writeFrame(frame);
        } catch (IOException failure) {
            finish(session, TerminalCloseReason.PROTOCOL_ERROR);
            throw failure;
        }
        return sessionId;
    }

    /** Guest-only acknowledgement: reports the pid and grants output credit. */
    public synchronized void acceptSession(int sessionId, int pid, int initialWindow)
            throws IOException {
        requireRole(Role.GUEST, "acceptSession");
        ensureRunning();
        Session session = requireSession(sessionId);
        if (session.state != TerminalSessionState.OPENING) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session is not opening");
        }
        if (initialWindow < Fln1Payloads.MIN_INITIAL_WINDOW
                || initialWindow > Fln1Payloads.MAX_INITIAL_WINDOW) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "initial window out of range");
        }
        session.receiveCredit = MAX_CREDIT_BYTES;
        session.sendCredit = initialWindow;
        session.windowCap = windowCapFor(initialWindow);
        session.state = TerminalSessionState.OPEN;
        TerminalFrame frame = new TerminalFrame(
                TerminalFrameType.OPEN_PTY, sessionId,
                Fln1Payloads.encodePtyOpened(pid, initialWindow));
        try {
            writeFrame(frame);
        } catch (IOException failure) {
            forceClose(sessionId, TerminalCloseReason.PROTOCOL_ERROR);
            throw failure;
        }
        listener.onSessionOpened(sessionId);
    }

    /**
     * Sends as much of {@code data} as the frame limit and credit allow.
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
        if (!session.state.canSendData() || session.remoteTerminated) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot send data");
        }
        int allowed = Math.min(
                data.length, Math.min(peerMaxFrameBytes, Fln1Payloads.MAX_PTY_DATA));
        if (role == Role.GUEST) {
            // FLN1 windows only guest->host output. Host input has no WINDOW
            // frame; the payload's synchronous PTY write is the backpressure.
            allowed = Math.min(allowed, session.sendCredit);
        }
        if (allowed == 0) {
            creditStalls++;
            return 0;
        }
        byte[] chunk = Arrays.copyOf(data, allowed);
        if (role == Role.GUEST) {
            session.sendCredit -= allowed;
        }
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
        if (!session.state.canSendData() || session.remoteTerminated) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot resize");
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.RESIZE,
                sessionId,
                Fln1Payloads.encodeResize(new Fln1Payloads.Resize(columns, rows))));
    }

    public synchronized void sendSignal(int sessionId, int signal) throws IOException {
        ensureRunning();
        Session session = requireOpen(sessionId);
        if (!session.state.canSendData() || session.remoteTerminated) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot signal");
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.SIGNAL, sessionId, Fln1Payloads.encodeSignal(signal)));
    }

    /** Half-closes the local side; idempotent once already half-closed locally. */
    public synchronized void sendClose(int sessionId) throws IOException {
        sendClose(sessionId, false);
    }

    /** {@code force=true} maps to a payload KILL instead of HUP-then-KILL. */
    public synchronized void sendClose(int sessionId, boolean force) throws IOException {
        ensureRunning();
        Session session = requireSession(sessionId);
        if (session.state == TerminalSessionState.CLOSED || session.localHalfClosed) {
            return;
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.CLOSE, sessionId, Fln1Payloads.encodeClose(force)));
        markLocalHalfClosed(session, TerminalCloseReason.LOCAL_CLOSE);
    }

    /**
     * Guest-only: reports {@code EOF} then {@code EXIT} for the child and
     * half-closes the local side. The real payload emits these from its PTY
     * reader; the host must never call this.
     */
    public synchronized void sendEof(int sessionId, int exitCode) throws IOException {
        requireRole(Role.GUEST, "sendEof");
        ensureRunning();
        Session session = requireSession(sessionId);
        if (session.state == TerminalSessionState.CLOSED || session.localHalfClosed) {
            return;
        }
        writeFrame(new TerminalFrame(TerminalFrameType.EOF, sessionId, new byte[0]));
        writeFrame(new TerminalFrame(
                TerminalFrameType.EXIT, sessionId, Fln1Payloads.encodeExit(
                        new Fln1Payloads.Exit(exitCode, 0))));
        // The payload erases the PTY once EXIT is sent; the session is terminal.
        finish(session, TerminalCloseReason.EOF);
    }

    /** Guest-only: replies to a host CLOSE. Reason 1 is never produced by the payload. */
    public synchronized void sendClosed(int sessionId) throws IOException {
        sendClosed(sessionId, 0);
    }

    /** Guest-only: reports CLOSED with reason 0 exited, 1 signalled, 2 forced. */
    public synchronized void sendClosed(int sessionId, int reason) throws IOException {
        requireRole(Role.GUEST, "sendClosed");
        ensureRunning();
        Session session = requireSession(sessionId);
        if (session.state == TerminalSessionState.CLOSED) {
            return;
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.CLOSED, sessionId, Fln1Payloads.encodeClosed(reason)));
        finish(session, TerminalCloseReason.LOCAL_CLOSE);
    }

    public synchronized void sendPing(long nonce) throws IOException {
        ensureRunning();
        writeFrame(new TerminalFrame(
                TerminalFrameType.PING, 0, encodeNonce(nonce)));
    }

    /** Host-only: returns output credit to the payload. */
    public synchronized void grantCredit(int sessionId, int bytes) throws IOException {
        requireRole(Role.HOST, "grantCredit");
        ensureRunning();
        Session session = requireActive(sessionId);
        if (!session.state.canReceiveData()) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.SESSION_CLOSED, "session cannot receive data");
        }
        if (bytes <= 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "credit out of range");
        }
        int allowed = Math.min(bytes, session.windowCap - session.receiveCredit);
        if (allowed <= 0) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.CREDIT_OVERFLOW, "credit window is full");
        }
        session.receiveCredit += allowed;
        try {
            writeFrame(new TerminalFrame(
                    TerminalFrameType.WINDOW, sessionId, Fln1Payloads.encodeWindow(allowed)));
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
            case HELLO -> handleHello(frame);
            case OPEN_PTY -> handleOpenPty(frame);
            case DATA -> handleData(frame);
            case RESIZE -> {
                Fln1Payloads.Resize resize = Fln1Payloads.parseResize(frame.payload());
                Session session = requireActive(frame.sessionId());
                if (!session.state.canReceiveData()) {
                    throw new TerminalProtocolException(
                            TerminalErrorCode.SESSION_CLOSED, "session cannot receive resize");
                }
                listener.onResize(frame.sessionId(), resize);
            }
            case RESIZED -> {
                // FLN1 acknowledges RESIZE; the mux has no local window state to update.
            }
            case SIGNAL -> {
                int signal = Fln1Payloads.parseSignal(frame.payload());
                requireOpen(frame.sessionId());
                listener.onSignal(frame.sessionId(), signal);
            }
            case CLOSE -> handleClose(frame);
            case CLOSED -> handleClosed(frame);
            case EOF -> handleExit(frame, -1);
            case EXIT -> handleExit(frame, 0);
            case PING -> {
                long nonce = parseNonce(frame.payload());
                writeFrame(new TerminalFrame(
                        TerminalFrameType.PONG, 0, encodeNonce(nonce)));
            }
            case PONG -> listener.onPong(parseNonce(frame.payload()));
            case WINDOW -> handleWindow(frame);
            case ERROR -> handleError(frame);
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
            if (role == Role.HOST
                    && helloSent
                    && session.state != TerminalSessionState.OPENING) {
                try {
                    writeFrame(new TerminalFrame(
                            TerminalFrameType.CLOSE, session.id, Fln1Payloads.encodeClose(false)));
                } catch (IOException failure) {
                    if (first == null) {
                        first = failure;
                    }
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

    public synchronized boolean helloSent() {
        return helloSent;
    }

    public synchronized int peerFeatures() {
        return peerFeatures;
    }

    private void handleHello(TerminalFrame frame) throws IOException {
        if (peerVersion >= 0) {
            // FLN1: exactly one HELLO per framed connection. Re-handshaking
            // mid-connection would silently change max_frame and capabilities.
            sendError(0, TerminalErrorCode.UNKNOWN_TYPE, "duplicate hello");
            return;
        }
        if (role == Role.HOST) {
            Fln1Payloads.HelloOk hello = Fln1Payloads.parseHelloOk(frame.payload());
            peerVersion = hello.version();
            peerFeatures = hello.caps();
            peerMaxFrameBytes = Math.min(Fln1Payloads.MAX_PTY_DATA, hello.maxFrameBytes());
            listener.onHello(hello);
            return;
        }
        Fln1Payloads.Hello hello = Fln1Payloads.parseHello(frame.payload());
        if (hello.minVersion() > Fln1TerminalCodec.VERSION
                || hello.maxVersion() < Fln1TerminalCodec.VERSION) {
            sendError(0, TerminalErrorCode.PROTOCOL_VERSION, "unsupported version range");
            return;
        }
        peerVersion = Fln1TerminalCodec.VERSION;
        peerFeatures = Fln1Payloads.CAP_TERMINAL;
        listener.onHelloRequest(hello);
        sendHelloOk(new Fln1Payloads.HelloOk(
                Fln1TerminalCodec.VERSION,
                Fln1Payloads.CAP_TERMINAL,
                Fln1Payloads.MAX_FRAME_PAYLOAD,
                Fln1Payloads.DEFAULT_INITIAL_WINDOW));
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
            Fln1Payloads.PtyOpened opened;
            try {
                opened = Fln1Payloads.parsePtyOpened(frame.payload());
            } catch (TerminalProtocolException failure) {
                forceClose(sessionId, TerminalCloseReason.PROTOCOL_ERROR);
                sendError(sessionId, failure.code(), failure.getMessage());
                return;
            }
            session.receiveCredit = opened.initialWindow();
            session.windowCap = windowCapFor(opened.initialWindow());
            session.sendCredit = MAX_CREDIT_BYTES;
            session.state = TerminalSessionState.OPEN;
            listener.onSessionOpened(sessionId);
            return;
        }

        Session existing = sessions.get(sessionId);
        if (existing != null && existing.state != TerminalSessionState.CLOSED) {
            sendError(sessionId, TerminalErrorCode.SESSION_EXISTS, "session already exists");
            return;
        }
        if (activeSessions >= maxSessions) {
            sendError(sessionId, TerminalErrorCode.SESSION_LIMIT, "session limit reached");
            return;
        }
        Fln1Payloads.OpenPty request;
        try {
            request = Fln1Payloads.parseOpenPty(frame.payload());
        } catch (TerminalProtocolException failure) {
            sendError(sessionId, failure.code(), failure.getMessage());
            return;
        }
        sessions.put(sessionId, new Session(sessionId, TerminalSessionState.OPENING));
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
        if (role == Role.HOST) {
            // The host consumes the payload's output window; the guest sees
            // host input through a synchronous sink and never debits credit.
            if (length > session.receiveCredit) {
                forceClose(frame.sessionId(), TerminalCloseReason.PROTOCOL_ERROR);
                sendError(frame.sessionId(), TerminalErrorCode.CREDIT_EXCEEDED, "credit exceeded");
                return;
            }
            session.receiveCredit -= length;
        }
        bytesIn += length;
        listener.onData(frame.sessionId(), frame.payload());
    }

    private void handleClose(TerminalFrame frame) throws IOException {
        Fln1Payloads.parseClose(frame.payload());
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        boolean wasLocal = session.localHalfClosed;
        markRemoteHalfClosed(session, TerminalCloseReason.REMOTE_CLOSE);
        if (!wasLocal) {
            listener.onCloseRequested(frame.sessionId());
        }
    }

    private void handleClosed(TerminalFrame frame) throws IOException {
        Fln1Payloads.parseClosed(frame.payload());
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        markRemoteHalfClosed(session, TerminalCloseReason.REMOTE_CLOSE);
    }

    private void handleExit(TerminalFrame frame, int fallbackCode) throws IOException {
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        if (session.state == TerminalSessionState.CLOSED) {
            return;
        }
        int exitCode = fallbackCode;
        if (frame.type() == TerminalFrameType.EXIT) {
            Fln1Payloads.Exit exit = Fln1Payloads.parseExit(frame.payload());
            exitCode = exit.termSignal() == 0 ? exit.exitCode() : 128 + exit.termSignal();
        }
        session.remoteTerminated = true;
        listener.onEof(frame.sessionId(), exitCode);
        // EOF means "PTY output ended"; EXIT carries the status and ends the
        // session. FLN1 has no guest CLOSE, and keeping the session writable
        // would let the UI send DATA to a reaped child, which the payload
        // answers with a connection-fatal SESSION_NOT_FOUND.
        if (frame.type() == TerminalFrameType.EXIT) {
            finish(session, TerminalCloseReason.EOF);
        }
    }

    private void handleError(TerminalFrame frame) throws IOException {
        Fln1Payloads.Error error = Fln1Payloads.parseError(frame.payload());
        listener.onError(frame.sessionId(), Fln1TerminalCodec.hostCode(error.code()),
                "fln1=" + error.code() + " " + Fln1TerminalCodec.wireCodeName(error.code())
                        + ": " + error.message());
        if (frame.sessionId() > 0) {
            forceClose(frame.sessionId(), TerminalCloseReason.PROTOCOL_ERROR);
            return;
        }
        // FLN1 errors are connection-scoped: the payload reports a failed
        // OPEN_PTY (and every other connection-level failure) with session 0.
        // The codec already dropped the unmatchable FIFO entry, so an OPENING
        // session can never receive its PTY_OPENED. OPEN sessions are left to
        // the transport EOF / abortAll, which owns that decision.
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.state == TerminalSessionState.OPENING) {
                finish(session, TerminalCloseReason.PROTOCOL_ERROR);
            }
        }
    }

    private void handleWindow(TerminalFrame frame) throws IOException {
        requireRole(Role.GUEST, "WINDOW");
        Session session = sessions.get(frame.sessionId());
        if (session == null) {
            sendError(frame.sessionId(), TerminalErrorCode.UNKNOWN_SESSION, "unknown session");
            return;
        }
        if (session.state == TerminalSessionState.CLOSED || !session.state.canSendData()) {
            return;
        }
        int credit = Fln1Payloads.parseWindow(frame.payload());
        // The payload clamps WINDOW at 2 x initial_window; mirror the clamp so
        // a chatty host cannot diverge from the peer's accounting.
        session.sendCredit = (int) Math.min(
                (long) session.sendCredit + credit, session.windowCap);
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

    /**
     * FLN1 has no host-to-guest {@code ERROR}: the payload treats it as an
     * unknown command and drops the connection. A host-side violation is
     * therefore raised to the channel, which closes the stream.
     */
    private void sendError(int sessionId, TerminalErrorCode code, String message)
            throws IOException {
        protocolErrors++;
        listener.onError(sessionId, code, message);
        if (role == Role.HOST) {
            throw new TerminalProtocolException(code, "peer protocol violation: " + message);
        }
        writeFrame(new TerminalFrame(
                TerminalFrameType.ERROR,
                sessionId,
                Fln1Payloads.encodeError(new Fln1Payloads.Error(
                        Fln1TerminalCodec.wireCode(code), message))));
    }

    private void writeFrame(TerminalFrame frame) throws IOException {
        if (role == Role.HOST && !helloSent && frame.type() != TerminalFrameType.HELLO) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.PROTOCOL_VERSION,
                    "HELLO must be sent before " + frame.type());
        }
        sink.write(frame);
        framesOut++;
    }

    private void markLocalHalfClosed(Session session, TerminalCloseReason reason) {
        if (session.state == TerminalSessionState.CLOSED) {
            return;
        }
        session.localHalfClosed = true;
        if (session.remoteHalfClosed) {
            finish(session, reason);
        } else {
            session.state = TerminalSessionState.HALF_CLOSED_LOCAL;
        }
    }

    private void markRemoteHalfClosed(Session session, TerminalCloseReason reason) {
        if (session.state == TerminalSessionState.CLOSED || session.remoteHalfClosed) {
            return;
        }
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
        sink.releaseSession(session.id);
        listener.onSessionClosed(session.id, reason);
    }

    private void forceClose(int sessionId, TerminalCloseReason reason) {
        Session session = sessions.get(sessionId);
        if (session != null) {
            finish(session, reason);
        }
    }

    private static int windowCapFor(int initialWindow) {
        long doubled = 2L * initialWindow;
        return (int) Math.min(doubled, MAX_CREDIT_BYTES);
    }

    private static byte[] encodeNonce(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    private static long parseNonce(byte[] payload) throws TerminalProtocolException {
        if (payload.length != 8) {
            throw new TerminalProtocolException(
                    TerminalErrorCode.INVALID_PAYLOAD, "PING/PONG nonce must be 8 bytes");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong();
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
        private int windowCap = MAX_CREDIT_BYTES;
        private TerminalSessionState state;
        private boolean localHalfClosed;
        private boolean remoteHalfClosed;
        private boolean remoteTerminated;

        private Session(int id, TerminalSessionState state) {
            this.id = id;
            this.state = state;
        }
    }
}
