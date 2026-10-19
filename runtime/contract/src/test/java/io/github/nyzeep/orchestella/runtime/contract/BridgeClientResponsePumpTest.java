package io.github.nyzeep.orchestella.runtime.contract;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone JVM contract tests for single-reader control routing and failure bounds. */
public final class BridgeClientResponsePumpTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final class Pair implements AutoCloseable {
        final PipedInputStream hostIn = new PipedInputStream(8192);
        final PipedOutputStream guestOut;
        final PipedInputStream guestIn = new PipedInputStream(65536);
        final PipedOutputStream hostOut;
        final BufferedReader requests;
        final BridgeClient client;
        final java.util.Set<Thread> readers = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        Pair() throws IOException {
            guestOut = new PipedOutputStream(hostIn);
            hostOut = new PipedOutputStream(guestIn);
            requests = new BufferedReader(new InputStreamReader(guestIn, StandardCharsets.US_ASCII));
            client = new BridgeClient(new java.io.FilterInputStream(hostIn) {
                @Override public int read() throws IOException {
                    readers.add(Thread.currentThread());
                    return super.read();
                }
            }, hostOut);
        }
        void reply(String line) throws IOException { guestOut.write(line.getBytes(StandardCharsets.US_ASCII)); guestOut.flush(); }
        @Override public void close() throws IOException { hostIn.close(); guestOut.close(); guestIn.close(); hostOut.close(); }
    }

    public static void main(String[] args) throws Exception {
        initialReadyBacklogSettlesBeforeOrdinaryDeadlineApplies();
        serverRestartRequiresFreshAuthenticationBeforeResize();
        guestRequestCannotStealHostResponse();
        timeoutFreezesLateResponses();
        disconnectWakesWaitingCommand();
        mismatchedResponseIsRejected();
        unauthenticatedCommandWritesNothing();
        concurrentCommandsAreSerialized();
        gracefulCallerShutdownPreservesInFlightResize();
        oversizedLineNeverDispatchesItsCommandTail();
        desktopFirstThenTerminalUsesSameAuthenticatedReader();
        startupHandshakeHasItsOwnLongerBoundedDeadline();
        droppedInitialHelloIsRetriedWithoutASecondReader();
        bufferedHelloRepliesNeverAcknowledgePing();
        unsolicitedReadyWithoutRetryIsRejected();
        startupRetryHasAnOverallDeadlineAndNoTokenDiagnostics();
        rejectedCachedTokenPreventsControlAndClearsThePrivateReference();
        changingToAWrongTokenCannotReuseCachedReady();
        droppedReauthHelloRetriesWithinTheOrdinaryDeadline();
        reauthSilenceUsesTheOrdinaryDeadlineAndNeverSendsTheRealCommand();
        staleReadyCannotBypassNativeAgentAuthentication();
        allControlOperationsRefreshAuthenticationOnTheSameClient();
        diagnosticsDistinguishControlStagesWithoutForeignMessages();
        failedReaderMustRetireBeforeTransportRecovery();
        featureRejectionRetainsTheAuthenticatedSingleReader();
        System.out.println("BRIDGE_RESPONSE_PUMP_OK tests=25");
    }

    private static void initialReadyBacklogSettlesBeforeOrdinaryDeadlineApplies() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 100L, 5000L, 1L, 2000L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                // Reproduce cold boot: 161 HELLOs queued before the agent is ready.
                for (int i = 0; i < 161; i++) require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "queued hello missing");
                pair.reply("ORCHESTELLA/READY 1\n");
                // The real C agent processes read_line_fd/poll/reply sequentially.
                // Scale expensive TCG processing into a deterministic millisecond replay.
                for (int i = 0; i < 160; i++) { Thread.sleep(2L); pair.reply("ORCHESTELLA/READY 1\n"); }
                String next = pair.requests.readLine();
                while (next != null && next.startsWith("ORCHESTELLA/HELLO")) {
                    pair.reply("ORCHESTELLA/READY 1\n");
                    next = pair.requests.readLine();
                }
                require("ORCHESTELLA/PING".equals(next), "startup proof ping missing");
                pair.reply("ORCHESTELLA/PONG 1\n");
                require("ORCHESTELLA/PING".equals(pair.requests.readLine()), "first actual ping missing");
                pair.reply("ORCHESTELLA/PONG 1\n");
                require(readAuthenticatedCommand(pair).equals("ORCHESTELLA/RESIZE 41 16"), "ordinary resize missing");
                // Withhold a later real command's reply: only the first transaction settles.
            });
            require(pair.client.handshake(TOKEN).accepted(), "first READY rejected");
            try { require(pair.client.ping().accepted(), "first control after queued READY failed"); }
            catch (IOException failure) { throw new AssertionError("first control froze at ordinary deadline with startup READY backlog", failure); }
            long started = System.nanoTime();
            expectIo(() -> pair.client.resize(41, 16));
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 600L, "ordinary operation retained startup settle deadline");
            require(pair.readers.size() == 1, "settling spawned a second control reader");
            join(guest, failed);
        }
    }

    private static void serverRestartRequiresFreshAuthenticationBeforeResize() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 500L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean freshlyAuthenticated = new java.util.concurrent.atomic.AtomicBoolean();
            Thread guest = guest(failed, () -> {
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "recovery handshake missing");
                pair.reply("ORCHESTELLA/READY 1\n");
                // Recovery agent exits; native systemd agent has a new auth context
                // over the same live character stream, so only HELLO is legal first.
                String firstNativeRequest = pair.requests.readLine();
                if (!firstNativeRequest.equals("ORCHESTELLA/HELLO 1 1 " + TOKEN)) {
                    pair.reply("ORCHESTELLA/ERROR malformed-handshake\n");
                    return;
                }
                freshlyAuthenticated.set(true);
                pair.reply("ORCHESTELLA/READY 1\n");
                String command = pair.requests.readLine();
                if ("ORCHESTELLA/PING".equals(command)) {
                    pair.reply("ORCHESTELLA/PONG 1\n");
                    command = pair.requests.readLine();
                }
                require("ORCHESTELLA/RESIZE 41 16".equals(command), "resize was not sent after native authentication");
                pair.reply("ORCHESTELLA/OK resize\n");
            });
            require(pair.client.handshake(TOKEN).accepted(), "recovery handshake rejected");
            require(pair.client.isReady(), "cached recovery readiness missing");
            require(pair.client.resize(41, 16).accepted(), "cached READY skipped native agent authentication");
            require(freshlyAuthenticated.get(), "native agent never received HELLO");
            require(pair.readers.size() == 1, "native handoff created a second reader");
            join(guest, failed);
        }
    }

    private static void guestRequestCannotStealHostResponse() throws Exception {
        try (Pair pair = new Pair()) {
            AtomicReference<Throwable> failed = new AtomicReference<>();
            pair.client.startResponsePump(line -> "ORCHESTELLA/OK notify\n", 1000);
            Thread guest = guest(failed, () -> {
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "hello missing");
                pair.reply("ORCHESTELLA/READY 1\n");
                require("ORCHESTELLA/RESIZE 42 18".equals(readAuthenticatedCommand(pair)), "resize missing");
                pair.reply("ORCHESTELLA/NOTIFY build complete\n");
                require("ORCHESTELLA/OK notify".equals(pair.requests.readLine()), "guest dispatch ack missing");
                pair.reply("ORCHESTELLA/OK resize\n");
            });
            require(pair.client.handshake(TOKEN).accepted(), "handshake rejected");
            require(pair.client.resize(42, 18).accepted(), "interleaved resize rejected");
            join(guest, failed);
        }
    }

    private static void timeoutFreezesLateResponses() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 100);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                readAuthenticatedCommand(pair); // Deliberately withhold the actual resize ACK.
            });
            pair.client.handshake(TOKEN);
            long start = System.nanoTime();
            expectIo(() -> pair.client.resize(40, 10));
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500, "timeout was unbounded");
            require(!pair.client.isReady(), "timed out channel still ready");
            expectIo(() -> pair.client.ping());
            join(guest, failed);
        }
    }

    private static void disconnectWakesWaitingCommand() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 2000);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                readAuthenticatedCommand(pair); pair.guestOut.close();
            });
            pair.client.handshake(TOKEN);
            long start = System.nanoTime();
            expectIo(() -> pair.client.ping());
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500, "disconnect did not wake request");
            join(guest, failed);
        }
    }

    private static void mismatchedResponseIsRejected() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 1000);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                readAuthenticatedCommand(pair); pair.reply("ORCHESTELLA/OK clipboard\n");
            });
            pair.client.handshake(TOKEN);
            expectIo(() -> pair.client.resize(40, 10));
            require(!pair.client.isReady(), "mismatched channel remained ready");
            join(guest, failed);
        }
    }

    private static void unauthenticatedCommandWritesNothing() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 1000);
            expectIo(() -> pair.client.resize(40, 10));
            require(pair.guestIn.available() == 0, "unauthenticated resize reached guest");
        }
    }

    private static void concurrentCommandsAreSerialized() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 1000);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(2);
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                for (int i = 0; i < 2; i++) {
                    String line = readAuthenticatedCommand(pair);
                    require(line.startsWith("ORCHESTELLA/RESIZE") || line.equals("ORCHESTELLA/POWER shutdown"), "transactions interleaved");
                    pair.reply(line.startsWith("ORCHESTELLA/RESIZE") ? "ORCHESTELLA/OK resize\n" : "ORCHESTELLA/OK power\n");
                }
            });
            pair.client.handshake(TOKEN);
            Thread resize = guest(failed, () -> { require(pair.client.resize(80, 24).accepted(), "concurrent resize failed"); done.countDown(); });
            Thread power = guest(failed, () -> { require(pair.client.power("shutdown").accepted(), "concurrent power failed"); done.countDown(); });
            require(done.await(3, TimeUnit.SECONDS), "concurrent requests deadlocked");
            join(resize, failed); join(power, failed); join(guest, failed);
        }
    }

    private static void gracefulCallerShutdownPreservesInFlightResize() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 1000);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch resizeReceived = new CountDownLatch(1);
            CountDownLatch acknowledge = new CountDownLatch(1);
            java.util.concurrent.ExecutorService activityQueue = java.util.concurrent.Executors.newSingleThreadExecutor();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                require(readAuthenticatedCommand(pair).startsWith("ORCHESTELLA/RESIZE"), "resize missing");
                resizeReceived.countDown();
                require(acknowledge.await(2, TimeUnit.SECONDS), "resize ack was not released");
                pair.reply("ORCHESTELLA/OK resize\n");
                require("ORCHESTELLA/PING".equals(readAuthenticatedCommand(pair)), "channel lost after caller destruction");
                pair.reply("ORCHESTELLA/PONG 1\n");
            });
            try {
                pair.client.handshake(TOKEN);
                java.util.concurrent.Future<BridgeClient.Result> resize = activityQueue.submit(() -> pair.client.resize(70, 20));
                require(resizeReceived.await(2, TimeUnit.SECONDS), "resize not in flight");
                activityQueue.shutdown(); // Same lifecycle action as Activity.onDestroy.
                acknowledge.countDown();
                require(resize.get(2, TimeUnit.SECONDS).accepted(), "destroy interrupted resize");
                require(pair.client.isReady(), "destroy closed persistent control channel");
                require(pair.client.ping().accepted(), "next activity cannot reuse client");
                join(guest, failed);
            } finally { acknowledge.countDown(); activityQueue.shutdownNow(); }
        }
    }

    private static void oversizedLineNeverDispatchesItsCommandTail() throws Exception {
        try (Pair pair = new Pair()) {
            java.util.concurrent.atomic.AtomicInteger effects = new java.util.concurrent.atomic.AtomicInteger();
            pair.client.startResponsePump(line -> { effects.incrementAndGet(); return "ORCHESTELLA/OK open-url\n"; }, 1000);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch sendPayload = new CountDownLatch(1);
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                require(sendPayload.await(2, TimeUnit.SECONDS), "payload gate timed out");
                try { pair.reply("A".repeat(512 * 1024) + "ORCHESTELLA/OPEN-URL https://example.com\n"); }
                catch (IOException expectedClosedPipe) { }
            });
            pair.client.handshake(TOKEN);
            sendPayload.countDown();
            java.util.concurrent.ExecutorService observer = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<Boolean> stopped = observer.submit(() -> {
                    try { pair.client.awaitResponsePump(); return false; } catch (IOException expected) { return true; }
                });
                try { require(stopped.get(3, TimeUnit.SECONDS), "oversized line did not fail closed"); }
                catch (java.util.concurrent.TimeoutException timeout) {
                    throw new AssertionError("Oversized frame not closed; guest effects=" + effects.get(), timeout);
                }
                require(effects.get() == 0, "oversized frame tail executed a guest action");
                require(!pair.client.isReady(), "oversized channel remained ready");
                expectIo(() -> pair.client.resize(80, 24));
                join(guest, failed);
            } finally { observer.shutdownNow(); }
        }
    }

    private static void desktopFirstThenTerminalUsesSameAuthenticatedReader() throws Exception {
        try (Pair pair = new Pair()) {
            BridgeClient desktop = pair.client;
            desktop.startResponsePump(line -> null, 1000);
            require(!desktop.awaitReady(1), "unhandshaken desktop was ready");
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "desktop hello missing");
                pair.reply("ORCHESTELLA/READY 1\n");
                require("ORCHESTELLA/DISPLAY LAUNCH".equals(readAuthenticatedCommand(pair)), "desktop launch missing");
                pair.reply("ORCHESTELLA/OK display\n");
                require(readAuthenticatedCommand(pair).startsWith("ORCHESTELLA/RESIZE"), "terminal resize missing");
                pair.reply("ORCHESTELLA/OK resize\n");
                require("ORCHESTELLA/POWER shutdown".equals(readAuthenticatedCommand(pair)), "power command missing");
                pair.reply("ORCHESTELLA/OK power\n");
            });
            desktop.handshake(TOKEN);
            require(desktop.awaitReady(100), "authenticated desktop did not become ready");
            require(desktop.desktopLaunch().accepted(), "desktop launch rejected");
            BridgeClient terminal = pair.client; // ExecutionSession's cached instance.
            terminal.startResponsePump(line -> null, 1000); // Idempotent, never a second reader.
            require(terminal.resize(44, 16).accepted(), "terminal reuse could not resize");
            require(terminal.power("shutdown").accepted(), "main could not reuse client for power");
            require(pair.readers.size() == 1, "desktop/terminal spawned competing control readers");
            join(guest, failed);
        }
    }

    private static void startupHandshakeHasItsOwnLongerBoundedDeadline() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 100L, 1000L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine();
                Thread.sleep(200L); // First provisioning outlasts the normal request deadline.
                pair.reply("ORCHESTELLA/READY 1\n");
                readAuthenticatedCommand(pair); // Withhold the normal resize response after reauth.
            });
            require(pair.client.handshake(TOKEN).accepted(), "startup used the short ordinary request timeout");
            long start = System.nanoTime();
            expectIo(() -> pair.client.resize(80, 24));
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500L,
                    "ordinary resize inherited the longer startup timeout");
            join(guest, failed);
        }
    }

    private static void droppedInitialHelloIsRetriedWithoutASecondReader() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 100L, 1000L, 40L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "first hello missing");
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "lost hello was not retransmitted");
                pair.reply("ORCHESTELLA/READY 1\n");
                require("ORCHESTELLA/PING".equals(readAuthenticatedCommand(pair)), "next command did not reauthenticate");
                pair.reply("ORCHESTELLA/PONG 1\n");
            });
            require(pair.client.handshake(TOKEN).accepted(), "retried hello rejected");
            require(pair.client.ping().accepted(), "authenticated client could not ping");
            require(pair.readers.size() == 1, "retry created another reader");
            join(guest, failed);
        }
    }

    private static void bufferedHelloRepliesNeverAcknowledgePing() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 500L, 1000L, 75L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            CountDownLatch pingReceived = new CountDownLatch(1);
            CountDownLatch pongAllowed = new CountDownLatch(1);
            Thread guest = guest(failed, () -> {
                for (int i = 0; i < 3; i++) require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "buffered hello missing");
                pair.reply("ORCHESTELLA/READY 1\n");
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "reauth HELLO missing");
                // Two queued recovery replies, followed by this agent's fresh READY.
                pair.reply("ORCHESTELLA/READY 1\nORCHESTELLA/READY 1\nORCHESTELLA/READY 1\n");
                require("ORCHESTELLA/PING".equals(pair.requests.readLine()), "reauth PING proof missing");
                pingReceived.countDown();
                require(pongAllowed.await(1, TimeUnit.SECONDS), "pong gate expired");
                pair.reply("ORCHESTELLA/PONG 1\n");
                require("ORCHESTELLA/PING".equals(pair.requests.readLine()), "actual ping missing");
                pair.reply("ORCHESTELLA/PONG 1\n");
            });
            pair.client.handshake(TOKEN);
            java.util.concurrent.ExecutorService caller = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<BridgeClient.Result> ping = caller.submit(pair.client::ping);
                require(pingReceived.await(1, TimeUnit.SECONDS), "ping not received");
                require(!ping.isDone(), "duplicate READY incorrectly completed ping");
                pongAllowed.countDown();
                require(ping.get(1, TimeUnit.SECONDS).accepted(), "duplicate READY poisoned ping");
                join(guest, failed);
            } finally { pongAllowed.countDown(); caller.shutdownNow(); }
        }
    }

    private static void unsolicitedReadyWithoutRetryIsRejected() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 1000L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                require("ORCHESTELLA/PING".equals(pair.requests.readLine()), "proof PING missing");
                pair.reply("ORCHESTELLA/READY 1\n");
            });
            pair.client.handshake(TOKEN);
            expectIo(pair.client::ping);
            require(!pair.client.isReady(), "unsolicited READY kept transport ready");
            join(guest, failed);
        }
    }

    private static void startupRetryHasAnOverallDeadlineAndNoTokenDiagnostics() throws Exception {
        try (Pair pair = new Pair()) {
            java.util.List<String> diagnostics = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            pair.client.setDiagnosticListener(diagnostics::add);
            pair.client.startResponsePump(line -> null, 50L, 140L, 20L);
            java.util.concurrent.atomic.AtomicInteger helloCount = new java.util.concurrent.atomic.AtomicInteger();
            Thread guest = new Thread(() -> {
                try { while (pair.requests.readLine() != null) helloCount.incrementAndGet(); }
                catch (IOException expectedDisconnect) { }
            });
            guest.setDaemon(true); guest.start();
            long start = System.nanoTime();
            expectIo(() -> pair.client.handshake(TOKEN));
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000, "retry extended overall startup deadline");
            require(helloCount.get() >= 2 && helloCount.get() <= 8, "unbounded or missing hello retry");
            require("failed".equals(pair.client.readinessState()), "deadline did not fail closed");
            for (String diagnostic : diagnostics) require(!diagnostic.contains(TOKEN), "diagnostics exposed authentication token");
        }
    }

    private static void rejectedCachedTokenPreventsControlAndClearsThePrivateReference() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 250L, 5000L, 50L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                require(("ORCHESTELLA/HELLO 1 1 " + TOKEN).equals(pair.requests.readLine()), "cached token missing from reauth");
                pair.reply("ORCHESTELLA/ERROR unauthorized\n");
            });
            pair.client.handshake(TOKEN);
            expectIo(() -> pair.client.power("shutdown"));
            join(guest, failed);
            require(!pair.client.isReady(), "denied native token retained cached READY");
            require(pair.guestIn.available() == 0, "control bytes were sent after token rejection");
            require(privateTokenIsCleared(pair.client), "rejected token remained referenced by the client");
            expectIo(() -> pair.client.resize(41, 16));
        }
    }

    private static void changingToAWrongTokenCannotReuseCachedReady() throws Exception {
        try (Pair pair = new Pair()) {
            String wrongToken = "f".repeat(64);
            pair.client.startResponsePump(line -> null, 250L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                require(("ORCHESTELLA/HELLO 1 1 " + wrongToken).equals(pair.requests.readLine()), "wrong token bypassed fresh authentication");
                pair.reply("ORCHESTELLA/ERROR unauthorized\n");
            });
            pair.client.handshake(TOKEN);
            expectIo(() -> pair.client.handshake(wrongToken));
            require(!pair.client.isReady(), "wrong token inherited prior READY");
            require(privateTokenIsCleared(pair.client), "failed handshake retained private token");
            join(guest, failed);
        }
    }

    private static void droppedReauthHelloRetriesWithinTheOrdinaryDeadline() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 350L, 5000L, 30L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "reauth HELLO missing");
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "lost reauth HELLO not retried");
                pair.reply("ORCHESTELLA/READY 1\n");
                require("ORCHESTELLA/PING".equals(pair.requests.readLine()), "native proof missing");
                pair.reply("ORCHESTELLA/PONG 1\n");
                require("ORCHESTELLA/RESIZE 41 16".equals(pair.requests.readLine()), "command missing after recovered reauth");
                pair.reply("ORCHESTELLA/OK resize\n");
            });
            pair.client.handshake(TOKEN);
            long started = System.nanoTime();
            require(pair.client.resize(41, 16).accepted(), "short reauth retry failed");
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 350L, "reauth used startup deadline");
            require(pair.readers.size() == 1, "reauth retry spawned a competing reader");
            join(guest, failed);
        }
    }

    private static void reauthSilenceUsesTheOrdinaryDeadlineAndNeverSendsTheRealCommand() throws Exception {
        try (Pair pair = new Pair()) {
            java.util.List<String> diagnostics = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            pair.client.setDiagnosticListener(diagnostics::add);
            pair.client.startResponsePump(line -> null, 140L, 5000L, 30L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger retries = new java.util.concurrent.atomic.AtomicInteger();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                String line;
                while ((line = pair.requests.readLine()) != null) {
                    require(line.startsWith("ORCHESTELLA/HELLO"), "unauthenticated native agent received a real command");
                    retries.incrementAndGet();
                }
            });
            pair.client.handshake(TOKEN);
            long started = System.nanoTime();
            expectIo(() -> pair.client.resize(41, 16));
            require(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000L, "normal reauth blocked for the startup deadline");
            guest.join(2000L);
            require(!guest.isAlive(), "reauth deadline did not terminate guest fixture");
            if (failed.get() != null && !(failed.get() instanceof IOException)) throw new AssertionError("fixture failed", failed.get());
            require(retries.get() >= 2 && retries.get() <= 6, "missing or unbounded normal reauth retries");
            require(privateTokenIsCleared(pair.client), "timed-out token remained referenced");
            for (String diagnostic : diagnostics) require(!diagnostic.contains(TOKEN), "reauth diagnostics exposed token");
        }
    }

    private static void staleReadyCannotBypassNativeAgentAuthentication() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 250L, 1000L, 40L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                for (int i = 0; i < 2; i++) require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "startup retry missing");
                pair.reply("ORCHESTELLA/READY 1\n");
                require(pair.requests.readLine().startsWith("ORCHESTELLA/HELLO"), "new native agent did not receive HELLO");
                pair.reply("ORCHESTELLA/READY 1\n"); // Queued recovery reply; NOT proof of native auth.
                require("ORCHESTELLA/PING".equals(pair.requests.readLine()), "old READY bypassed PING barrier");
                pair.reply("ORCHESTELLA/ERROR unauthorized\n");
            });
            pair.client.handshake(TOKEN);
            expectIo(() -> pair.client.power("shutdown"));
            join(guest, failed);
            require(pair.guestIn.available() == 0, "POWER was sent before native authentication");
            require(!pair.client.isReady(), "stale READY certified the unauthenticated native agent");
            require(privateTokenIsCleared(pair.client), "failed proof retained token");
        }
    }

    private static void allControlOperationsRefreshAuthenticationOnTheSameClient() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 500L);
            String[][] expected = {
                {"ORCHESTELLA/PING", "ORCHESTELLA/PONG 1\n"},
                {"ORCHESTELLA/RESIZE 41 16", "ORCHESTELLA/OK resize\n"},
                {"ORCHESTELLA/POWER shutdown", "ORCHESTELLA/OK power\n"},
                {"ORCHESTELLA/NOTIFY title message", "ORCHESTELLA/OK notify\n"},
                {"ORCHESTELLA/OPEN-URL https://example.com", "ORCHESTELLA/OK open-url\n"},
                {"ORCHESTELLA/DISPLAY MODE 1080 1920 1.0", "ORCHESTELLA/OK display\n"},
                {"ORCHESTELLA/DISPLAY RESIZE 1080 1920", "ORCHESTELLA/OK display\n"},
                {"ORCHESTELLA/DISPLAY FPS 60", "ORCHESTELLA/OK display\n"},
                {"ORCHESTELLA/DISPLAY LAUNCH", "ORCHESTELLA/OK display\n"},
                {"ORCHESTELLA/FEATURES 1 STATUS desktop-labwc", "ORCHESTELLA/OK features {\"state\":\"awaiting-frame\"}\n"},
                {"ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc " + "a".repeat(32), "ORCHESTELLA/OK features {\"state\":\"ready\"}\n"},
                {"ORCHESTELLA/CLIPBOARD PUT base64:c2FmZQ==", "ORCHESTELLA/OK clipboard\n"},
                {"ORCHESTELLA/CLIPBOARD GET", "ORCHESTELLA/OK clipboard base64:c2FmZQ==\n"}
            };
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                for (String[] operation : expected) {
                    require(operation[0].equals(readAuthenticatedCommand(pair)), "control operation escaped fresh auth");
                    pair.reply(operation[1]);
                }
            });
            pair.client.handshake(TOKEN);
            require(pair.client.ping().accepted(), "ping failed");
            require(pair.client.resize(41, 16).accepted(), "resize failed");
            require(pair.client.power("shutdown").accepted(), "power failed");
            require(pair.client.notify("title", "message").accepted(), "notify failed");
            require(pair.client.openUrl("https://example.com").accepted(), "open-url failed");
            require(pair.client.displayMode(1080, 1920, 1.0).accepted(), "display mode failed");
            require(pair.client.displayResize(1080, 1920).accepted(), "display resize failed");
            require(pair.client.displayFps(60).accepted(), "display fps failed");
            require(pair.client.desktopLaunch().accepted(), "desktop launch failed");
            require(pair.client.feature("STATUS", "desktop-labwc").accepted(), "feature status failed");
            require(pair.client.confirmDesktopFrame("a".repeat(32)).accepted(), "frame confirmation failed");
            require(pair.client.clipboardPut("safe").accepted(), "clipboard put failed");
            require("safe".equals(pair.client.clipboardGet().payload()), "clipboard get failed");
            require(pair.readers.size() == 1, "control operations used competing readers");
            join(guest, failed);
        }
    }

    private static boolean privateTokenIsCleared(BridgeClient client) throws Exception {
        java.lang.reflect.Field tokenField = BridgeClient.class.getDeclaredField("authenticationToken");
        require(java.lang.reflect.Modifier.isPrivate(tokenField.getModifiers()), "token storage is not private");
        tokenField.setAccessible(true);
        return tokenField.get(client) == null;
    }

    private static void diagnosticsDistinguishControlStagesWithoutForeignMessages() throws Exception {
        for (String scenario : new String[]{"timeout", "eof", "expected_mismatch"}) {
            try (Pair pair = new Pair()) {
                java.util.List<String> diagnostics = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                pair.client.setDiagnosticListener(diagnostics::add);
                pair.client.startResponsePump(line -> null, 100L);
                AtomicReference<Throwable> failed = new AtomicReference<>();
                Thread guest = guest(failed, () -> {
                    pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                    if (scenario.equals("timeout")) {
                        pair.requests.readLine(); // Do not answer reauth HELLO.
                    } else if (scenario.equals("eof")) {
                        readAuthenticatedCommand(pair); pair.guestOut.close();
                    } else {
                        readAuthenticatedCommand(pair); pair.reply("ORCHESTELLA/OK clipboard\n");
                    }
                });
                pair.client.handshake(TOKEN);
                expectIo(() -> pair.client.resize(41, 16));
                join(guest, failed);
                String logs = String.join("\n", diagnostics);
                if (scenario.equals("timeout")) require(logs.contains("stage=reauth_hello causeCode=timeout"), "timeout stage missing");
                else if (scenario.equals("eof")) require(logs.contains("stage=request causeCode=eof"), "EOF stage missing");
                else require(logs.contains("stage=request causeCode=expected_mismatch"), "response mismatch stage missing");
                require(!logs.contains(TOKEN), "diagnostics leaked a token");
            }
        }
        java.util.List<String> diagnostics = new java.util.ArrayList<>();
        String foreign = "FOREIGN_SECRET_SHOULD_NEVER_APPEAR";
        BridgeClient broken = new BridgeClient(new java.io.InputStream() {
            @Override public int read() throws IOException { throw new IOException(foreign); }
        }, new java.io.ByteArrayOutputStream());
        broken.setDiagnosticListener(diagnostics::add);
        broken.startResponsePump(line -> null, 100L);
        try { broken.awaitResponsePump(); } catch (IOException expected) { }
        require(String.join("\n", diagnostics).contains("causeCode=io"), "I/O code missing");
        require(!String.join("\n", diagnostics).contains(foreign), "foreign exception message escaped into logs");
    }

    private static void failedReaderMustRetireBeforeTransportRecovery() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 100L);
            require(!pair.client.awaitDisconnectedReader(1L), "healthy control reader can be retired");
            pair.guestOut.close();
            try { pair.client.awaitResponsePump(); } catch (IOException expected) { }
            require(pair.client.awaitDisconnectedReader(100L), "failed completed reader was not retired");
        }
        java.util.concurrent.atomic.AtomicBoolean selfRetirement = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<BridgeClient> owner = new java.util.concurrent.atomic.AtomicReference<>();
        BridgeClient failing = new BridgeClient(new java.io.InputStream() {
            @Override public int read() throws IOException { throw new IOException("synthetic failure"); }
        }, new java.io.ByteArrayOutputStream());
        owner.set(failing);
        failing.setDiagnosticListener(message -> {
            if (message.contains("state=failed")) {
                try { selfRetirement.set(owner.get().awaitDisconnectedReader(10L)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        failing.startResponsePump(line -> null, 100L);
        try { failing.awaitResponsePump(); } catch (IOException expected) { }
        require(!selfRetirement.get(), "reader retired itself from a failure callback");
        require(failing.awaitDisconnectedReader(100L), "terminated reader could not be retired by its owner");
    }

    private static void featureRejectionRetainsTheAuthenticatedSingleReader() throws Exception {
        try (Pair pair = new Pair()) {
            pair.client.startResponsePump(line -> null, 1000L);
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread guest = guest(failed, () -> {
                pair.requests.readLine(); pair.reply("ORCHESTELLA/READY 1\n");
                require("ORCHESTELLA/FEATURES 1 PROBE desktop-labwc".equals(readAuthenticatedCommand(pair)),
                        "feature probe bypassed the authentication barrier");
                pair.reply("ORCHESTELLA/ERROR unsupported-command\n");
                require(("ORCHESTELLA/FEATURES 1 CONFIRM-FRAME desktop-labwc " + "b".repeat(32))
                        .equals(readAuthenticatedCommand(pair)), "confirmation bypassed the authentication barrier");
                pair.reply("ORCHESTELLA/ERROR display-confirm-unavailable\n");
                require("ORCHESTELLA/PING".equals(readAuthenticatedCommand(pair)), "rejection lost the control stream");
                pair.reply("ORCHESTELLA/PONG 1\n");
            });
            require(pair.client.handshake(TOKEN).accepted(), "handshake rejected");
            require(!pair.client.feature("PROBE", "desktop-labwc").accepted(), "unsupported extension was accepted");
            require(!pair.client.confirmDesktopFrame("b".repeat(32)).accepted(), "rejected frame was accepted");
            require(pair.client.ping().accepted(), "rejected extension prevented PING");
            require(pair.readers.size() == 1, "extension introduced a second control reader");
            join(guest, failed);
        }
    }

    /** Each ordinary command must prove auth to this server context before it can act. */
    private static String readAuthenticatedCommand(Pair pair) throws Exception {
        String line = pair.requests.readLine();
        // A handshake retry that races the guest's READY is a legal duplicate
        // HELLO (the real agent answers every one). Accept those retransmissions,
        // but still require the PING/PONG proof before releasing the command.
        while (line != null && line.startsWith("ORCHESTELLA/HELLO")) {
            require(line.equals("ORCHESTELLA/HELLO 1 1 " + TOKEN), "command omitted fresh HELLO");
            pair.reply("ORCHESTELLA/READY 1\n");
            line = pair.requests.readLine();
        }
        require("ORCHESTELLA/PING".equals(line), "reauthentication proof missing");
        pair.reply("ORCHESTELLA/PONG 1\n");
        return pair.requests.readLine();
    }

    private interface Checked { void run() throws Exception; }
    private static Thread guest(AtomicReference<Throwable> failed, Checked action) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable error) { failed.set(error); } });
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void join(Thread thread, AtomicReference<Throwable> failed) throws Exception {
        thread.join(2000); require(!thread.isAlive(), "guest thread hung");
        if (failed.get() != null) throw new AssertionError("guest test failed", failed.get());
    }
    private static void expectIo(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("expected IOException"); } catch (IOException expected) { }
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
