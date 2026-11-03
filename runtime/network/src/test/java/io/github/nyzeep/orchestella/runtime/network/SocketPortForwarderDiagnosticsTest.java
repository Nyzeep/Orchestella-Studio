package io.github.nyzeep.orchestella.runtime.network;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/**
 * Diagnostics contract for {@link SocketPortForwarder}: every silent failure the forwarder used
 * to swallow must now reach a {@link PortForwardingObserver}, without the observer ever being
 * able to break forwarding.
 *
 * <p>Runs both as a {@code JavaExec main} (this module's {@code test} task excludes everything,
 * see {@code socketForwarderDiagnosticsTest} in {@code build.gradle.kts}) and as a plain JUnit
 * test class.
 */
public final class SocketPortForwarderDiagnosticsTest {

    private static final long TIMEOUT_MILLIS = 5000L;

    public static void main(String[] args) throws Exception {
        SocketPortForwarderDiagnosticsTest suite = new SocketPortForwarderDiagnosticsTest();
        suite.throwingObserverCannotBreakForwarding();
        suite.guestPortWithoutListenerIsReported();
        suite.repeatedFailuresAreThrottled();
        suite.sameIdReplacesAndClosesThePreviousListener();
        suite.closeEmitsOneSummaryPerRule();
        System.out.println("SOCKET_FORWARDER_DIAGNOSTICS_TEST_OK");
    }

    @Test
    public void throwingObserverCannotBreakForwarding() throws Exception {
        int guestPort = freePort();
        int hostPort = freePort();
        PortForwardingObserver hostile = new PortForwardingObserver() {
            @Override
            public void onAcceptFailure(String ruleId, int port, IOException cause, boolean fatal) {
                throw new IllegalStateException("observer exploded");
            }

            @Override
            public void onClientFailure(String ruleId, int port, IOException cause) {
                throw new IllegalStateException("observer exploded");
            }

            @Override
            public void onRelayFailure(String ruleId, String direction, IOException cause) {
                throw new IllegalStateException("observer exploded");
            }

            @Override
            public void onListenerReplaced(String ruleId, int port) {
                throw new IllegalStateException("observer exploded");
            }

            @Override
            public void onSummary(String ruleId, long suppressed, long failures) {
                throw new IllegalStateException("observer exploded");
            }
        };
        SocketPortForwarder forwarder = new SocketPortForwarder(hostile);
        try {
            PortForwardingRule rule = PortForwardingRule.defaultRule("hostile", Protocol.TCP, hostPort, guestPort);
            forwarder.addRule(rule);
            // First connection hits a dead guest port, so onClientFailure throws inside the accept
            // loop's worker; the loop itself must survive and keep accepting.
            assertEquals(-1, readOneByteOrEof(hostPort), "dead guest port closes the client");

            try (ServerSocket guest = startEchoServer(guestPort)) {
                assertTrue(guest.isBound(), "guest fixture bound");
                assertEquals("PONG", readLine(hostPort), "accept loop still forwards after observer threw");
            }
        } finally {
            forwarder.close();
        }
    }

    @Test
    public void guestPortWithoutListenerIsReported() throws Exception {
        RecordingObserver observer = new RecordingObserver();
        int guestPort = freePort();
        int hostPort = freePort();
        SocketPortForwarder forwarder = new SocketPortForwarder(observer);
        try {
            forwarder.addRule(PortForwardingRule.defaultRule("dead-guest", Protocol.TCP, hostPort, guestPort));
            assertEquals(-1, readOneByteOrEof(hostPort), "dead guest port closes the client");
            await(() -> observer.clientFailures.get() >= 1, "onClientFailure must be reported");
        } finally {
            forwarder.close();
        }
    }

    @Test
    public void repeatedFailuresAreThrottled() throws Exception {
        RecordingObserver observer = new RecordingObserver();
        int guestPort = freePort();
        int hostPort = freePort();
        SocketPortForwarder forwarder = new SocketPortForwarder(observer);
        try {
            forwarder.addRule(PortForwardingRule.defaultRule("throttled", Protocol.TCP, hostPort, guestPort));
            for (int attempt = 0; attempt < 5; attempt++) {
                readOneByteOrEof(hostPort);
            }
            await(() -> observer.clientFailures.get() >= 1, "the first failure of a key must always be reported");
            assertTrue(observer.clientFailures.get() < 5,
                    "same key within one second must be throttled, saw " + observer.clientFailures.get());
            // Let the remaining handlers finish so close() sees their suppressed counters.
            Thread.sleep(250L);
        } finally {
            forwarder.close();
        }
        assertTrue(observer.suppressedReports.get() >= 1, "the summary must report suppressed events");
    }

    @Test
    public void sameIdReplacesAndClosesThePreviousListener() throws Exception {
        RecordingObserver observer = new RecordingObserver();
        int guestPort = freePort();
        int firstHostPort = freePort();
        int secondHostPort = freePort();
        SocketPortForwarder forwarder = new SocketPortForwarder(observer);
        try (ServerSocket guest = startEchoServer(guestPort)) {
            assertTrue(guest.isBound(), "guest fixture bound");
            forwarder.addRule(PortForwardingRule.defaultRule("dup", Protocol.TCP, firstHostPort, guestPort));
            assertEquals("PONG", readLine(firstHostPort), "first listener forwards");

            forwarder.addRule(PortForwardingRule.defaultRule("dup", Protocol.TCP, secondHostPort, guestPort));
            assertEquals(1, observer.replaced.get(), "onListenerReplaced must fire once");

            await(() -> !isConnectable(firstHostPort), "the previous listener must be closed");
            assertEquals("PONG", readLine(secondHostPort), "the replacement listener forwards");

            forwarder.removeRule(PortForwardingRule.defaultRule("dup", Protocol.TCP, secondHostPort, guestPort));
            await(() -> !isConnectable(secondHostPort), "removed listener must refuse new connections");
        } finally {
            forwarder.close();
        }
    }

    @Test
    public void closeEmitsOneSummaryPerRule() throws Exception {
        RecordingObserver observer = new RecordingObserver();
        SocketPortForwarder forwarder = new SocketPortForwarder(observer);
        forwarder.addRule(PortForwardingRule.defaultRule("summary", Protocol.TCP, freePort(), freePort()));
        forwarder.close();
        assertTrue(observer.summaries.get() >= 1, "close() must emit onSummary");
    }

    /** Counts the events the scenarios assert on instead of scraping log output. */
    private static final class RecordingObserver implements PortForwardingObserver {
        final AtomicInteger clientFailures = new AtomicInteger();
        final AtomicInteger replaced = new AtomicInteger();
        final AtomicInteger summaries = new AtomicInteger();
        final AtomicInteger suppressedReports = new AtomicInteger();

        @Override
        public void onClientFailure(String ruleId, int guestPort, IOException cause) {
            clientFailures.incrementAndGet();
        }

        @Override
        public void onListenerReplaced(String ruleId, int hostPort) {
            replaced.incrementAndGet();
        }

        @Override
        public void onSummary(String ruleId, long suppressedEvents, long failures) {
            summaries.incrementAndGet();
            if (suppressedEvents > 0) {
                suppressedReports.incrementAndGet();
            }
        }
    }

    /** Minimal guest service that answers one line per connection; closing it ends the thread. */
    private static ServerSocket startEchoServer(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket client = server.accept();
                        OutputStream out = client.getOutputStream()) {
                    client.getInputStream().read();
                    out.write("PONG\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException closing) {
                    return;
                }
            }
        }, "diagnostics-guest-server");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static String readLine(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            OutputStream out = socket.getOutputStream();
            out.write('x');
            out.flush();
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
        }
    }

    /** Returns the first byte of the forwarded stream, or -1 when the peer closed it. */
    private static int readOneByteOrEof(int port) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            return socket.getInputStream().read();
        }
    }

    private static boolean isConnectable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return probe.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError(message);
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + " but was " + actual);
        }
    }
}
