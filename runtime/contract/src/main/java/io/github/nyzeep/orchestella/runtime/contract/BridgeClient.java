package io.github.nyzeep.orchestella.runtime.contract;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Strategy-neutral host-side Bridge v1 client. The host initiates the
 * handshake and the command lines; the guest agent answers READY / PONG /
 * OK resize, or {@code DROIDLAYER/ERROR} with a rejection kind. Transport
 * adapters supply plain byte streams and stay out of the protocol.
 */
public final class BridgeClient {
    /** Initial image verification (1800s), unpacking (1800s), and boot reserve (300s). */
    public static final long DEFAULT_STARTUP_TIMEOUT_MS = 3_900_000L;
    /** One first control transaction drains queued boot HELLO replies under TCG load. */
    public static final long DEFAULT_STARTUP_SETTLE_TIMEOUT_MS = 30_000L;
    private static final int MAX_RESPONSE_LINE_BYTES = 512 * 1024;

    private final InputStream inbound;
    private final OutputStream outbound;
    private final Object writeLock = new Object();
    private final java.util.concurrent.atomic.AtomicReference<PendingResponse> pending =
            new java.util.concurrent.atomic.AtomicReference<>();
    private volatile boolean pumpEnabled;
    private volatile boolean ready;
    private volatile boolean reauthenticating;
    // Session lifetime only. Never persisted, returned by an API, or included in diagnostics.
    private volatile String authenticationToken;
    private final java.util.concurrent.CountDownLatch readySignal = new java.util.concurrent.CountDownLatch(1);
    private volatile IOException transportFailure;
    private volatile Thread readerThread;
    private long responseTimeoutMs = 5000L;
    private long handshakeTimeoutMs = DEFAULT_STARTUP_TIMEOUT_MS;
    private long handshakeRetryIntervalMs = 1000L;
    private long startupSettleTimeoutMs = DEFAULT_STARTUP_SETTLE_TIMEOUT_MS;
    private boolean startupSettlePending;
    private final java.util.concurrent.atomic.AtomicBoolean failureReported = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger helloResponseBudget = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger handshakeAttempts = new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.util.function.Consumer<String> diagnosticListener = ignored -> {};

    /** Diagnostics contain state and attempt counts only, never request text or tokens. */
    public void setDiagnosticListener(java.util.function.Consumer<String> listener) {
        diagnosticListener = listener == null ? ignored -> {} : listener;
    }

    public String readinessState() {
        if (transportFailure != null) return "failed";
        if (reauthenticating) return "reauthenticating";
        if (ready) return "ready";
        PendingResponse waiting = pending.get();
        return waiting != null && "ready".equals(waiting.expected) ? "handshaking" : "not-ready";
    }

    private void diagnostic(String message) {
        try { diagnosticListener.accept(message); } catch (Exception ignored) { }
    }

    private static final class PendingResponse {
        final String expected;
        final String stage;
        final java.util.concurrent.CompletableFuture<String> response = new java.util.concurrent.CompletableFuture<>();
        PendingResponse(String expected, String stage) { this.expected = expected; this.stage = stage; }
    }

    private static final class ControlFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final String causeCode;
        final String stage;
        ControlFailure(String causeCode, String stage) {
            super("Bridge control failed stage=" + stage + " causeCode=" + causeCode);
            this.causeCode = causeCode; this.stage = stage;
        }
    }

    /** True only after this client has authenticated with the guest. */
    public boolean isReady() { return ready && !reauthenticating && transportFailure == null; }

    /** Bounded readiness wait for another UI entering while the session is handshaking. */
    public boolean awaitReady(long timeoutMs) throws IOException, InterruptedException {
        if (!isReady() && !readySignal.await(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS)) return false;
        if (transportFailure != null) throw transportFailure;
        return isReady();
    }

    /**
     * Takes exclusive read ownership. All host commands then await responses from this one
     * reader; guest requests are dispatched separately and cannot consume a host response.
     */
    public synchronized void startResponsePump(java.util.function.Function<String, String> guestRequestHandler) {
        startResponsePump(guestRequestHandler, 5000L, DEFAULT_STARTUP_TIMEOUT_MS, 1000L, DEFAULT_STARTUP_SETTLE_TIMEOUT_MS);
    }

    /** Test seam: use a short bounded deadline for both handshake and ordinary requests. */
    public synchronized void startResponsePump(java.util.function.Function<String, String> guestRequestHandler,
            long timeoutMs) {
        startResponsePump(guestRequestHandler, timeoutMs, timeoutMs);
    }

    public synchronized void startResponsePump(java.util.function.Function<String, String> guestRequestHandler,
            long timeoutMs, long startupTimeoutMs) {
        startResponsePump(guestRequestHandler, timeoutMs, startupTimeoutMs, 1000L);
    }

    public synchronized void startResponsePump(java.util.function.Function<String, String> guestRequestHandler,
            long timeoutMs, long startupTimeoutMs, long retryIntervalMs) {
        // Existing short test seams keep the first transaction just as strict.
        startResponsePump(guestRequestHandler, timeoutMs, startupTimeoutMs, retryIntervalMs, timeoutMs);
    }

    public synchronized void startResponsePump(java.util.function.Function<String, String> guestRequestHandler,
            long timeoutMs, long startupTimeoutMs, long retryIntervalMs, long settleTimeoutMs) {
        if (pumpEnabled) return;
        if (transportFailure != null) return;
        pumpEnabled = true;
        responseTimeoutMs = Math.max(1L, timeoutMs);
        handshakeTimeoutMs = Math.max(1L, startupTimeoutMs);
        handshakeRetryIntervalMs = Math.max(1L, retryIntervalMs);
        startupSettleTimeoutMs = Math.max(responseTimeoutMs, settleTimeoutMs);
        readerThread = new Thread(() -> {
            try {
                while (transportFailure == null) {
                    String line = readResponseLine();
                    String normalized = line.replaceFirst("^DROIDLAYER/", "ORCHESTELLA/");
                    if (normalized.startsWith("ORCHESTELLA/READY ") || normalized.startsWith("ORCHESTELLA/PONG ")
                            || normalized.startsWith("ORCHESTELLA/OK ") || normalized.startsWith("ORCHESTELLA/ERROR ")) {
                        PendingResponse waiting = pending.get();
                        Result classified = classify(normalized);
                        if ("ready".equals(classified.responseKind())) {
                            int owed = helloResponseBudget.getAndUpdate(value -> value > 0 ? value - 1 : value);
                            if (owed <= 0) throw new ControlFailure("unexpected_ready", waiting == null ? "reader" : waiting.stage);
                            // A startup retransmission can be buffered before the guest opens
                            // its port. Its later READY is never a PING/RESIZE acknowledgement.
                            if (ready && (waiting == null || !"ready".equals(waiting.expected))) {
                                int remainingReplies = Math.max(0, owed - 1);
                                if (remainingReplies <= 2 || remainingReplies % 30 == 0)
                                    diagnostic("control duplicate_ready stage=" + (waiting == null ? "reader" : waiting.stage)
                                            + " remaining=" + remainingReplies);
                                continue;
                            }
                        }
                        String responseStage = waiting == null ? "reader" : waiting.stage;
                        if (waiting == null) throw new ControlFailure("unsolicited_response", responseStage);
                        if (!classified.accepted() && "malformed-response".equals(classified.responseKind()))
                            throw new ControlFailure("malformed_response", responseStage);
                        if (classified.accepted() && !waiting.expected.equals(classified.responseKind()))
                            throw new ControlFailure("expected_mismatch", responseStage);
                        if (pending.compareAndSet(waiting, null)) {
                            if ("ready".equals(waiting.expected)) ready = classified.accepted();
                            waiting.response.complete(normalized);
                        }
                    } else {
                        String response = normalized.length() <= 2048 && guestRequestHandler != null
                                ? guestRequestHandler.apply(normalized) : "ORCHESTELLA/ERROR malformed-request\n";
                        if (response == null) response = "ORCHESTELLA/ERROR unsupported-command\n";
                        write(response);
                    }
                }
            } catch (Exception failure) {
                failTransport(failure instanceof IOException ? (IOException) failure : new IOException("Bridge reader failed", failure));
            }
        }, "bridge-control-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Bounded retirement check for a failed reader, called outside transport-owner locks. */
    public boolean awaitDisconnectedReader(long timeoutMs) throws InterruptedException {
        if (transportFailure == null) return false;
        Thread reader = readerThread;
        if (reader == Thread.currentThread()) return false;
        if (reader == null) return true;
        long boundedTimeout = Math.max(1L, Math.min(5000L, timeoutMs));
        reader.join(boundedTimeout);
        return !reader.isAlive();
    }

    public void awaitResponsePump() throws IOException, InterruptedException {
        Thread reader = readerThread;
        if (reader != null) reader.join();
        if (transportFailure != null) throw transportFailure;
    }

    private void failTransport(IOException failure) {
        if (!failureReported.compareAndSet(false, true)) return;
        transportFailure = failure;
        ready = false;
        reauthenticating = false;
        authenticationToken = null;
        PendingResponse active = pending.get();
        String stage = failure instanceof ControlFailure ? ((ControlFailure) failure).stage
                : active == null ? "reader" : active.stage;
        String causeCode = failure instanceof ControlFailure ? ((ControlFailure) failure).causeCode
                : failure instanceof EOFException ? "eof" : "io";
        // Only fixed internal codes are emitted. Exception messages may contain foreign
        // transport data, and must never be interpolated into diagnostics.
        diagnostic("control state=failed stage=" + stage + " causeCode=" + causeCode);
        readySignal.countDown();
        PendingResponse waiting = pending.getAndSet(null);
        if (waiting != null) waiting.response.completeExceptionally(failure);
        try { inbound.close(); } catch (IOException ignored) { }
        try { outbound.close(); } catch (IOException ignored) { }
    }

    private Result exchange(String request, String expected, boolean handshake) throws IOException {
        if (!pumpEnabled) { write(request); return classify(readResponseLine()); }
        if (transportFailure != null) throw transportFailure;
        if (handshake) return exchangePending(request, expected, deadline(handshakeTimeoutMs), true, "startup_hello");
        String token = authenticationToken;
        if (!isReady() || token == null) throw new IOException("Bridge has not authenticated");
        // Recovery and systemd agents may have different authentication contexts on the
        // same open transport. Revalidate each command under the existing method lock.
        // Only the first command can still be behind a cold-boot HELLO backlog.
        // Consume this allowance once; all subsequent transactions remain ordinary.
        boolean settling = startupSettlePending;
        startupSettlePending = false;
        long timeoutMs = settling ? startupSettleTimeoutMs : responseTimeoutMs;
        long commandDeadline = deadline(timeoutMs);
        if (settling) diagnostic("control phase=startup_settle timeoutMs=" + timeoutMs);
        reauthenticate(token, commandDeadline);
        return exchangePending(request, expected, commandDeadline, false, "request");
    }

    private static long deadline(long timeoutMs) {
        return System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    private void reauthenticate(String token, long commandDeadline) throws IOException {
        reauthenticating = true;
        try {
            Result hello = exchangePending("ORCHESTELLA/HELLO 1 1 " + token + "\n", "ready", commandDeadline, true, "reauth_hello");
            if (!hello.accepted()) throw new ControlFailure("auth_rejected", "reauth_hello");
            // Protocol v1 READY has no request id. A queued reply from an earlier HELLO
            // must not certify this agent. Ordered PING/PONG forms a read barrier after
            // the new HELLO and keeps the real command behind successful authentication.
            Result proof = exchangePending("ORCHESTELLA/PING\n", "pong", commandDeadline, false, "reauth_ping");
            if (!proof.accepted() || transportFailure != null)
                throw new ControlFailure("auth_unconfirmed", "reauth_ping");
            ready = true;
        } catch (IOException failure) {
            failTransport(failure);
            throw failure;
        } finally { reauthenticating = false; }
    }

    private Result exchangePending(String request, String expected, long requestDeadline,
            boolean retryHandshake, String stage) throws IOException {
        if (transportFailure != null) throw transportFailure;
        PendingResponse waiting = new PendingResponse(expected, stage);
        if (!pending.compareAndSet(null, waiting)) throw new ControlFailure("request_in_flight", stage);
        long started = System.nanoTime();
        diagnostic("control request stage=" + stage + " state=waiting");
        try {
            long remaining = requestDeadline - System.nanoTime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Bridge command deadline expired");
            String response;
            if (retryHandshake) response = awaitHandshake(request, waiting, requestDeadline);
            else {
                write(request);
                remaining = requestDeadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Bridge command deadline expired");
                response = waiting.response.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            }
            Result result = classify(response);
            diagnostic("control request stage=" + stage + " state=" + (result.accepted() ? "accepted" : "rejected")
                    + " elapsedMs=" + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return result;
        } catch (java.util.concurrent.TimeoutException timeout) {
            IOException failure = new ControlFailure("timeout", stage);
            failTransport(failure);
            throw failure;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IOException failure = new ControlFailure("interrupted", stage);
            failTransport(failure);
            throw failure;
        } catch (java.util.concurrent.ExecutionException failure) {
            throw new IOException("Bridge disconnected", failure.getCause());
        } catch (IOException failure) {
            failTransport(failure);
            throw failure;
        } finally { pending.compareAndSet(waiting, null); }
    }

    private String awaitHandshake(String request, PendingResponse waiting, long requestDeadline)
            throws IOException, InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
        long retryInterval = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(handshakeRetryIntervalMs);
        writeHandshake(request, waiting);
        while (true) {
            long remaining = requestDeadline - System.nanoTime();
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Handshake deadline expired");
            try {
                return waiting.response.get(Math.min(remaining, retryInterval), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException silence) {
                if (System.nanoTime() >= requestDeadline) throw silence;
                // Only HELLO is idempotent and retried. Normal commands remain one shot.
                writeHandshake(request, waiting);
            }
        }
    }

    private void writeHandshake(String request, PendingResponse waiting) throws IOException {
        int attempt;
        synchronized (writeLock) {
            if (transportFailure != null) throw transportFailure;
            if (pending.get() != waiting || waiting.response.isDone()) return;
            helloResponseBudget.incrementAndGet();
            attempt = handshakeAttempts.incrementAndGet();
            write(request);
        }
        if (attempt <= 3 || attempt % 30 == 0) diagnostic("handshake state=waiting attempt=" + attempt);
    }

    public BridgeClient(InputStream inbound, OutputStream outbound) {
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    /** Sends the v1 handshake and classifies the agent's single response line. */
    public synchronized Result handshake(String token) throws IOException {
        if (!BridgeHandshake.isCanonicalToken(token)) {
            throw new IllegalArgumentException("bridge token must be 64 lowercase hexadecimal characters");
        }
        if (pumpEnabled && authenticationToken != null) {
            if (transportFailure != null) throw transportFailure;
            reauthenticate(token, deadline(responseTimeoutMs));
            authenticationToken = token;
            return Result.accepted("ready");
        }
        Result result = exchange("ORCHESTELLA/HELLO 1 1 " + token + "\n", "ready", true);
        ready = result.accepted() && transportFailure == null;
        authenticationToken = ready ? token : null;
        startupSettlePending = ready;
        if (!result.accepted()) helloResponseBudget.set(0);
        diagnostic("handshake state=" + (ready ? "ready" : "rejected") + " attempts=" + handshakeAttempts.get());
        readySignal.countDown();
        return result;
    }

    /** Sends one keepalive ping and classifies the agent's response. */
    public synchronized Result ping() throws IOException {
        return exchange("ORCHESTELLA/PING\n", "pong", false);
    }

    /** Requests a terminal grid size and classifies the agent's response. */
    public synchronized Result resize(int columns, int rows) throws IOException {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("terminal size must be positive");
        }
        return exchange("ORCHESTELLA/RESIZE " + columns + " " + rows + "\n", "ok-resize", false);
    }

    /** Requests a power action (shutdown/poweroff) and classifies the agent's response. */
    public synchronized Result power(BridgeControl.PowerAction action) throws IOException {
        Objects.requireNonNull(action, "action");
        return exchange("ORCHESTELLA/POWER " + action.wireValue() + "\n", "ok-power", false);
    }

    /** Requests a power action by wire name ("shutdown" or "poweroff"). */
    public synchronized Result power(String action) throws IOException {
        BridgeControl.PowerAction parsed = BridgeControl.PowerAction.fromWire(action);
        if (parsed == null) {
            throw new IllegalArgumentException("unsupported power action: " + action);
        }
        return power(parsed);
    }

    /** Requests showing an Android system notification and classifies the agent's response. */
    public synchronized Result notify(String title, String message) throws IOException {
        if (title == null || title.isEmpty() || message == null || message.isEmpty()) {
            throw new IllegalArgumentException("title and message must not be empty");
        }
        String encodedTitle = java.net.URLEncoder.encode(title, StandardCharsets.UTF_8);
        String encodedMessage = java.net.URLEncoder.encode(message, StandardCharsets.UTF_8);
        return exchange("ORCHESTELLA/NOTIFY " + encodedTitle + " " + encodedMessage + "\n", "ok-notify", false);
    }

    /** Requests opening an HTTP/HTTPS URL on the host and classifies the agent's response. */
    public synchronized Result openUrl(String url) throws IOException {
        if (url == null || url.length() > 2048 || !url.matches("^[hH][tT][tT][pP][sS]?://.+")) {
            throw new IllegalArgumentException("invalid url: " + url);
        }
        return exchange("ORCHESTELLA/OPEN-URL " + url + "\n", "ok-open-url", false);
    }

    /** Requests setting the display mode (resolution and scale) and classifies the agent's response. */
    public synchronized Result displayMode(int width, int height, double scale) throws IOException {
        if (width < 100 || width > 4096 || height < 100 || height > 4096
                || (long) width * height > 4_194_304L) {
            throw new IllegalArgumentException("display dimensions exceed the supported frame bounds");
        }
        if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0.1 || scale > 5.0) {
            throw new IllegalArgumentException("display scale must be between 0.1 and 5.0: " + scale);
        }
        return exchange("ORCHESTELLA/DISPLAY MODE " + width + " " + height + " " + scale + "\n", "ok-display", false);
    }

    /** Requests dynamic display resize and classifies the agent's response. */
    public synchronized Result displayResize(int width, int height) throws IOException {
        if (width < 100 || width > 4096 || height < 100 || height > 4096
                || (long) width * height > 4_194_304L) {
            throw new IllegalArgumentException("display dimensions exceed the supported frame bounds");
        }
        return exchange("ORCHESTELLA/DISPLAY RESIZE " + width + " " + height + "\n", "ok-display", false);
    }

    /** Requests setting the target display frame rate and classifies the agent's response. */
    public synchronized Result displayFps(int fps) throws IOException {
        if (fps < 1 || fps > 240) {
            throw new IllegalArgumentException("fps must be between 1 and 240: " + fps);
        }
        return exchange("ORCHESTELLA/DISPLAY FPS " + fps + "\n", "ok-display", false);
    }

    /** Requests launching or ensuring the graphical desktop compositor on the guest. */
    public synchronized Result desktopLaunch() throws IOException {
        return exchange("ORCHESTELLA/DISPLAY LAUNCH\n", "ok-display", false);
    }

    /** Versioned optional feature extension; legacy agents can reject it without ending the session. */
    public synchronized Result feature(String action, String profile) throws IOException {
        if (action == null || !java.util.Set.of("PROBE", "INSTALL", "STATUS", "START", "STOP", "CANCEL", "EXPOSE", "UNEXPOSE").contains(action)
                || profile == null || !java.util.Set.of("desktop-labwc", "podman", "code-server", "docker-cli").contains(profile)) {
            throw new IllegalArgumentException("unsupported feature operation");
        }
        if (!isReady()) throw new IOException("Bridge has not authenticated");
        return exchange("ORCHESTELLA/FEATURES 1 " + action + " " + profile + "\n", "ok-features", false);
    }

    /** Confirms a presented frame only for the currently probed guest start generation. */
    public synchronized Result confirmDesktopFrame(String generation) throws IOException {
        if (generation == null || !generation.matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("invalid desktop generation");
        if (!isReady()) throw new IOException("Bridge has not authenticated");
        return exchange("ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc " + generation + "\n", "ok-features", false);
    }

    /** Requests sending clipboard text to the agent and classifies the agent's response. */
    public synchronized Result clipboardPut(String text) throws IOException {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > BridgeControl.MAX_CLIPBOARD_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("clipboard payload exceeds 256KB limit: " + utf8Bytes.length + " bytes");
        }
        String base64 = Base64.getEncoder().encodeToString(utf8Bytes);
        return exchange("ORCHESTELLA/CLIPBOARD PUT base64:" + base64 + "\n", "ok-clipboard", false);
    }

    /** Requests retrieving current clipboard text from the agent and classifies the response. */
    public synchronized Result clipboardGet() throws IOException {
        return exchange("ORCHESTELLA/CLIPBOARD GET\n", "ok-clipboard", false);
    }

    private void write(String line) throws IOException {
        synchronized (writeLock) {
            outbound.write(line.getBytes(StandardCharsets.US_ASCII));
            outbound.flush();
        }
    }

    private String readResponseLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() < MAX_RESPONSE_LINE_BYTES) {
            int value = inbound.read();
            if (value == -1) {
                if (buffer.size() == 0) {
                    throw new EOFException("bridge response stream ended");
                }
                return buffer.toString(StandardCharsets.US_ASCII);
            }
            buffer.write(value);
            if (value == '\n') {
                return buffer.toString(StandardCharsets.US_ASCII);
            }
        }
        // Never treat the remainder of an oversized frame as a new guest command.
        // Closing the pump fails closed and wakes any waiting host request.
        throw new ControlFailure("oversized_frame", "reader");
    }

    private static Result classify(String line) {
        if (line == null) {
            return Result.rejected("malformed-response");
        }
        String normalized = line;
        if (normalized.startsWith("DROIDLAYER/")) {
            normalized = "ORCHESTELLA/" + normalized.substring("DROIDLAYER/".length());
        }
        if ("ORCHESTELLA/READY 1\n".equals(normalized)) {
            return Result.accepted("ready");
        }
        if ("ORCHESTELLA/PONG 1\n".equals(normalized)) {
            return Result.accepted("pong");
        }
        if ("ORCHESTELLA/OK resize\n".equals(normalized)) {
            return Result.accepted("ok-resize");
        }
        if ("ORCHESTELLA/OK power\n".equals(normalized)) {
            return Result.accepted("ok-power");
        }
        if ("ORCHESTELLA/OK notify\n".equals(normalized)) {
            return Result.accepted("ok-notify");
        }
        if ("ORCHESTELLA/OK open-url\n".equals(normalized)) {
            return Result.accepted("ok-open-url");
        }
        if ("ORCHESTELLA/OK display\n".equals(normalized)) {
            return Result.accepted("ok-display");
        }
        if (normalized.startsWith("ORCHESTELLA/OK features ") && normalized.endsWith("\n")) {
            String json = normalized.substring("ORCHESTELLA/OK features ".length(), normalized.length() - 1);
            if (json.length() < 2 || json.length() > 4096 || !json.startsWith("{") || !json.endsWith("}"))
                return Result.rejected("malformed-response");
            for (int index = 0; index < json.length(); index++) {
                if (json.charAt(index) < 0x20) return Result.rejected("malformed-response");
            }
            return Result.accepted("ok-features", json);
        }
        if ("ORCHESTELLA/OK clipboard\n".equals(normalized)) {
            return Result.accepted("ok-clipboard");
        }
        if (normalized.startsWith("ORCHESTELLA/OK clipboard base64:") && normalized.endsWith("\n")) {
            String base64 = normalized.substring("ORCHESTELLA/OK clipboard base64:".length(), normalized.length() - 1);
            if (base64.isEmpty()) {
                return Result.accepted("ok-clipboard", "");
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(base64);
                String text = new String(decoded, StandardCharsets.UTF_8);
                return Result.accepted("ok-clipboard", text);
            } catch (IllegalArgumentException e) {
                return Result.rejected("malformed-response");
            }
        }
        if (normalized.startsWith("ORCHESTELLA/ERROR ") && normalized.endsWith("\n")) {
            String kind = normalized.substring("ORCHESTELLA/ERROR ".length(), normalized.length() - 1);
            return Result.rejected(kind);
        }
        return Result.rejected("malformed-response");
    }

    /** One classified agent response. */
    public static final class Result {
        private final boolean accepted;
        private final String responseKind;
        private final String payload;

        private Result(boolean accepted, String responseKind) {
            this(accepted, responseKind, null);
        }

        private Result(boolean accepted, String responseKind, String payload) {
            this.accepted = accepted;
            this.responseKind = responseKind;
            this.payload = payload;
        }

        static Result accepted(String responseKind) {
            return new Result(true, responseKind, null);
        }

        static Result accepted(String responseKind, String payload) {
            return new Result(true, responseKind, payload);
        }

        static Result rejected(String responseKind) {
            return new Result(false, responseKind, null);
        }

        public boolean accepted() {
            return accepted;
        }

        /** Accepted kinds: ready, pong, ok-resize, ok-clipboard. Rejected kinds: the agent's error kind. */
        public String responseKind() {
            return responseKind;
        }

        /** Optional decoded response payload (e.g. text for clipboard get), or null if not applicable. */
        public String payload() {
            return payload;
        }
    }
}
