package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** QEMU adapter test through the strategy-neutral bridge wire behavior. */
public final class QemuBridgeAdapterTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        relaysAuthenticatedControlBytesToTheStrategyNeutralTerminalSeam();
        rejectsAnIncorrectToken();
        rejectsAnIncompatibleVersionRange();
        relaysAuthenticatedNotifyAndOpenUrlCommands();
    }

    private static void relaysAuthenticatedControlBytesToTheStrategyNeutralTerminalSeam()
            throws Exception {
        AtomicInteger columns = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        QemuBridgeAdapter adapter =
                new QemuBridgeAdapter(
                        new ByteArrayInputStream(
                                ("ORCHESTELLA/HELLO 1 1 "
                                                + TOKEN
                                                + "\nORCHESTELLA/PING\nORCHESTELLA/RESIZE 80 24\n")
                                        .getBytes(StandardCharsets.US_ASCII)),
                        outbound,
                        TOKEN,
                        (requestedColumns, requestedRows) -> {
                            columns.set(requestedColumns);
                            rows.set(requestedRows);
                            return true;
                        });

        int exitCode = adapter.serve();

        assertEquals(0, exitCode, "normal control session exit");
        assertEquals(
                "ORCHESTELLA/READY 1\nORCHESTELLA/PONG 1\nORCHESTELLA/OK resize\n",
                outbound.toString(StandardCharsets.US_ASCII),
                "observable bridge responses");
        assertEquals(80, columns.get(), "terminal columns");
        assertEquals(24, rows.get(), "terminal rows");
    }

    private static void rejectsAnIncorrectToken() throws Exception {
        AtomicInteger columns = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        String wrongToken = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        QemuBridgeAdapter adapter =
                new QemuBridgeAdapter(
                        new ByteArrayInputStream(
                                ("ORCHESTELLA/HELLO 1 1 "
                                                + wrongToken
                                                + "\nORCHESTELLA/PING\n")
                                        .getBytes(StandardCharsets.US_ASCII)),
                        outbound,
                        TOKEN,
                        (requestedColumns, requestedRows) -> {
                            columns.set(requestedColumns);
                            rows.set(requestedRows);
                            return true;
                        });

        int exitCode = adapter.serve();

        assertEquals(3, exitCode, "wrong-token session must exit unauthorized");
        assertEquals(
                "ORCHESTELLA/ERROR unauthorized\n",
                outbound.toString(StandardCharsets.US_ASCII),
                "wrong token must be rejected with exactly the unauthorized wire response");
        assertEquals(0, columns.get(), "terminal seam must not run for a rejected handshake");
        assertEquals(0, rows.get(), "terminal seam must not run for a rejected handshake");
    }

    private static void rejectsAnIncompatibleVersionRange() throws Exception {
        ByteArrayOutputStream outbound = new ByteArrayOutputStream();
        QemuBridgeAdapter adapter =
                new QemuBridgeAdapter(
                        new ByteArrayInputStream(
                                ("ORCHESTELLA/HELLO 2 3 " + TOKEN + "\nORCHESTELLA/PING\n")
                                        .getBytes(StandardCharsets.US_ASCII)),
                        outbound,
                        TOKEN,
                        (requestedColumns, requestedRows) -> true);

        int exitCode = adapter.serve();

        assertEquals(2, exitCode, "unsupported-version session must exit with the version rejection");
        assertEquals(
                "ORCHESTELLA/ERROR unsupported-version\n",
                outbound.toString(StandardCharsets.US_ASCII),
                "incompatible version range must be rejected with the unsupported-version wire response");
    }

    private static void relaysAuthenticatedNotifyAndOpenUrlCommands() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> titleRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> msgRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> urlRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        ByteArrayOutputStream outbound = new ByteArrayOutputStream();

        QemuBridgeAdapter adapter =
                new QemuBridgeAdapter(
                        new ByteArrayInputStream(
                                ("ORCHESTELLA/HELLO 1 1 "
                                                + TOKEN
                                                + "\nORCHESTELLA/NOTIFY Alert TaskDone\n"
                                                + "ORCHESTELLA/OPEN-URL https://orchestella.github.io\n")
                                        .getBytes(StandardCharsets.US_ASCII)),
                        outbound,
                        TOKEN,
                        (cols, rws) -> true,
                        action -> true,
                        (title, msg) -> {
                            titleRef.set(title);
                            msgRef.set(msg);
                            return true;
                        },
                        url -> {
                            urlRef.set(url);
                            return true;
                        });

        int exitCode = adapter.serve();

        assertEquals(0, exitCode, "session with notify and open-url should succeed");
        assertEquals(
                "ORCHESTELLA/READY 1\nORCHESTELLA/OK notify\nORCHESTELLA/OK open-url\n",
                outbound.toString(StandardCharsets.US_ASCII),
                "observable notify and open-url responses");
        assertEquals("Alert", titleRef.get(), "notified title");
        assertEquals("TaskDone", msgRef.get(), "notified message");
        assertEquals("https://orchestella.github.io", urlRef.get(), "opened url");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
