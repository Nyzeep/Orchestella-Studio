package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class UmlBridgeAndPtyContractTest {
    private static final String TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String WRONG_TOKEN =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void negotiatesBridgeHandshakeReauthResizeAndPowerAndRejectsBadAuthOrVersion()
            throws Exception {
        // 1. Happy path: HELLO/READY, PING/PONG, RESIZE, POWER
        PipedOutputStream hostToGuestOut = new PipedOutputStream();
        PipedInputStream guestIn = new PipedInputStream(hostToGuestOut, 4096);
        PipedOutputStream guestToHostOut = new PipedOutputStream();
        PipedInputStream hostIn = new PipedInputStream(guestToHostOut, 4096);

        AtomicInteger resizedCols = new AtomicInteger(-1);
        AtomicInteger resizedRows = new AtomicInteger(-1);
        AtomicReference<BridgeControl.PowerAction> powerAction = new AtomicReference<>();

        UmlBridgeAdapter server = new UmlBridgeAdapter(
                guestIn,
                guestToHostOut,
                TOKEN,
                (cols, rows) -> {
                    resizedCols.set(cols);
                    resizedRows.set(rows);
                    return true;
                },
                action -> {
                    powerAction.set(action);
                    return true;
                });

        Thread serverThread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException ignored) {
            }
        }, "uml-bridge-server-test");
        serverThread.setDaemon(true);
        serverThread.start();

        BridgeClient client = new BridgeClient(hostIn, hostToGuestOut);
        client.startResponsePump(req -> "ORCHESTELLA/ERROR unsupported-command\n", 1500L, 1500L, 100L);
        assertTrue(client.handshake(TOKEN).accepted());
        assertTrue(client.ping().accepted());
        assertTrue(client.resize(110, 42).accepted());
        assertEquals(110, resizedCols.get());
        assertEquals(42, resizedRows.get());
        assertTrue(client.power(BridgeControl.PowerAction.POWEROFF).accepted());
        assertEquals(BridgeControl.PowerAction.POWEROFF, powerAction.get());

        hostToGuestOut.close();
        serverThread.join(1000L);

        // 2. Unauthorized token rejected
        PipedOutputStream h2gBad = new PipedOutputStream();
        PipedInputStream gInBad = new PipedInputStream(h2gBad, 4096);
        PipedOutputStream g2hBad = new PipedOutputStream();
        PipedInputStream hInBad = new PipedInputStream(g2hBad, 4096);
        UmlBridgeAdapter badServer = new UmlBridgeAdapter(
                gInBad, g2hBad, TOKEN, (c, r) -> true, a -> true);
        CountDownLatch badDone = new CountDownLatch(1);
        AtomicInteger badCode = new AtomicInteger(-1);
        new Thread(() -> {
            try {
                badCode.set(badServer.serve());
            } catch (IOException ignored) {
            } finally {
                badDone.countDown();
            }
        }).start();

        BridgeClient badClient = new BridgeClient(hInBad, h2gBad);
        badClient.startResponsePump(req -> "ORCHESTELLA/ERROR unsupported-command\n", 1000L, 1000L, 100L);
        BridgeClient.Result badResult = badClient.handshake(WRONG_TOKEN);
        assertFalse(badResult.accepted());
        assertEquals("unauthorized", badResult.responseKind());
        assertFalse(badClient.isReady());
        assertTrue(badDone.await(1, TimeUnit.SECONDS));
        assertEquals(3, badCode.get());

        // 3. Unsupported version rejected directly on protocol wire
        PipedOutputStream h2gVer = new PipedOutputStream();
        PipedInputStream gInVer = new PipedInputStream(h2gVer, 4096);
        PipedOutputStream g2hVer = new PipedOutputStream();
        PipedInputStream hInVer = new PipedInputStream(g2hVer, 4096);
        UmlBridgeAdapter verServer = new UmlBridgeAdapter(
                gInVer, g2hVer, TOKEN, (c, r) -> true, a -> true);
        AtomicInteger verCode = new AtomicInteger(-1);
        CountDownLatch verDone = new CountDownLatch(1);
        new Thread(() -> {
            try {
                verCode.set(verServer.serve());
            } catch (IOException ignored) {
            } finally {
                verDone.countDown();
            }
        }).start();

        h2gVer.write(("ORCHESTELLA/HELLO 99 100 " + TOKEN + "\n").getBytes("US-ASCII"));
        h2gVer.flush();
        byte[] reply = new byte[128];
        int n = hInVer.read(reply);
        String line = new String(reply, 0, n, "US-ASCII");
        assertEquals("ORCHESTELLA/ERROR unsupported-version\n", line);
        assertTrue(verDone.await(1, TimeUnit.SECONDS));
        assertEquals(2, verCode.get());
    }

    @Test
    public void duplexPtyStreamsForwardCtrlCReportEofAndPreserveExactExitCode() throws Exception {
        PipedOutputStream hostWritesToGuest = new PipedOutputStream();
        PipedInputStream guestReads = new PipedInputStream(hostWritesToGuest, 1024);
        PipedOutputStream guestWritesToHost = new PipedOutputStream();
        PipedInputStream hostReads = new PipedInputStream(guestWritesToHost, 1024);
        AtomicInteger cols = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        CountDownLatch exitLatch = new CountDownLatch(1);

        UmlProcessHandle handle = new UmlProcessHandle() {
            @Override public long pid() { return 888L; }
            @Override public boolean isAlive() { return exitLatch.getCount() > 0; }
            @Override public int waitFor() throws InterruptedException {
                exitLatch.await();
                return 130;
            }
            @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                return exitLatch.await(timeout, unit);
            }
            @Override public void destroy() { exitLatch.countDown(); }
            @Override public void destroyForcibly() { exitLatch.countDown(); }
            @Override public java.io.OutputStream terminalInput() { return hostWritesToGuest; }
            @Override public java.io.InputStream terminalOutput() { return hostReads; }
            @Override public java.io.OutputStream controlInput() { return hostWritesToGuest; }
            @Override public java.io.InputStream controlOutput() { return hostReads; }
            @Override public boolean resizeTerminal(int c, int r) {
                cols.set(c);
                rows.set(r);
                return true;
            }
            @Override public void closeChannels() {}
        };

        assertTrue(handle.resizeTerminal(90, 30));
        assertEquals(90, cols.get());
        assertEquals(30, rows.get());

        // Forward Ctrl-C (0x03) over terminalInput
        handle.terminalInput().write(new byte[] {0x03});
        handle.terminalInput().flush();
        assertEquals(0x03, guestReads.read());

        // Guest echoes ^C, closes output (EOF), and exits with 130
        guestWritesToHost.write("^C\n".getBytes("US-ASCII"));
        guestWritesToHost.close();
        byte[] buf = handle.terminalOutput().readAllBytes();
        assertArrayEquals("^C\n".getBytes("US-ASCII"), buf);
        assertEquals(-1, handle.terminalOutput().read());

        // EOF happened before process exit; process is still alive until exitLatch
        assertTrue(handle.isAlive());
        exitLatch.countDown();
        assertEquals(130, handle.waitFor());
    }
}
