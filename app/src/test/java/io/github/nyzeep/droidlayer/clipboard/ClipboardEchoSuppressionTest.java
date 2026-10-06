package io.github.nyzeep.droidlayer.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for ClipboardBridgeManager verifying:
 * - Local clipboard copy dispatches to Bridge with correct SHA-256 hash
 * - Remote clipboard put applies to local clipboard without echo loop (Ping-Pong suppression)
 * - Repeated local copy with identical content is suppressed
 * - Window focus transitions register/unregister system clipboard listener
 * - 100% pure JVM testable using decoupled seams.
 */
public final class ClipboardEchoSuppressionTest {

    private FakeSystemClipboard systemClipboard;
    private RecordingRemoteSender remoteSender;
    private ClipboardBridgeManager manager;

    private static final class FakeSystemClipboard implements ClipboardBridgeManager.SystemClipboardSeam {
        String text;
        Runnable listener;
        int setTextCallCount = 0;
        int registerCount = 0;
        int unregisterCount = 0;

        @Override
        public void setText(String text) {
            this.text = text;
            setTextCallCount++;
            // Emulate system behavior: setting text triggers the listener
            if (listener != null) {
                listener.run();
            }
        }

        @Override
        public String getText() {
            return text;
        }

        @Override
        public void setListener(Runnable listener) {
            this.listener = listener;
            registerCount++;
        }

        @Override
        public void clearListener() {
            this.listener = null;
            unregisterCount++;
        }

        void simulateUserCopy(String newText) {
            this.text = newText;
            if (listener != null) {
                listener.run();
            }
        }
    }

    private static final class RecordingRemoteSender implements ClipboardBridgeManager.RemoteClipboardSender {
        final List<String> sentTexts = new ArrayList<>();

        @Override
        public boolean sendClipboardPut(String text) {
            sentTexts.add(text);
            return true;
        }
    }

    @Before
    public void setUp() {
        systemClipboard = new FakeSystemClipboard();
        remoteSender = new RecordingRemoteSender();
        manager = new ClipboardBridgeManager(systemClipboard, remoteSender);
        // Initially gained window focus
        manager.onWindowFocusChanged(true);
    }

    @Test
    public void testLocalCopyDispatchesToBridge() {
        systemClipboard.simulateUserCopy("git status --short\n");

        assertEquals(1, remoteSender.sentTexts.size());
        assertEquals("git status --short\n", remoteSender.sentTexts.get(0));
        assertNotNull(manager.getLastHostHash());
        assertEquals(manager.getLastHostHash(), manager.getLastSyncedHash());
    }

    @Test
    public void testRemotePutDoesNotEchoBackToBridge() {
        String remoteText = "echo 'Message from Linux guest'\n";
        boolean accepted = manager.onClipboardPut(remoteText);

        assertTrue("Remote clipboard update should be accepted", accepted);
        assertEquals("System clipboard must reflect remote content", remoteText, systemClipboard.getText());
        assertEquals("Remote sender must not have dispatched anything back (echo suppression)", 0, remoteSender.sentTexts.size());
        assertTrue("Suppression counter must record the prevented echo", manager.getSuppressionCount() >= 1);
        assertEquals(manager.getLastRemoteHash(), manager.getLastSyncedHash());
    }

    @Test
    public void testDuplicateLocalCopyIsSuppressed() {
        systemClipboard.simulateUserCopy("const x = 42;");
        assertEquals(1, remoteSender.sentTexts.size());

        // User copies identical text again
        systemClipboard.simulateUserCopy("const x = 42;");
        assertEquals("Duplicate local content must not be sent across Bridge", 1, remoteSender.sentTexts.size());
    }

    @Test
    public void testPingPongInterleavedSequence() {
        // 1. Host copies text A
        systemClipboard.simulateUserCopy("Host text A");
        assertEquals(1, remoteSender.sentTexts.size());
        assertEquals("Host text A", remoteSender.sentTexts.get(0));

        // 2. Guest pushes text B
        boolean appliedB = manager.onClipboardPut("Guest text B");
        assertTrue(appliedB);
        assertEquals("Guest text B", systemClipboard.getText());
        assertEquals("Echo back must not occur for Guest text B", 1, remoteSender.sentTexts.size());

        // 3. Host copies text C
        systemClipboard.simulateUserCopy("Host text C");
        assertEquals(2, remoteSender.sentTexts.size());
        assertEquals("Host text C", remoteSender.sentTexts.get(1));

        // 4. Guest pushes text D
        boolean appliedD = manager.onClipboardPut("Guest text D");
        assertTrue(appliedD);
        assertEquals("Guest text D", systemClipboard.getText());
        assertEquals("Echo back must not occur for Guest text D", 2, remoteSender.sentTexts.size());
    }

    @Test
    public void testWindowFocusTransitions() {
        assertTrue("Listener should be registered when window focused", manager.isListenerRegistered());

        // Window loses focus (e.g. app goes into background)
        manager.onWindowFocusChanged(false);
        assertFalse("Listener must be unregistered when focus lost", manager.isListenerRegistered());

        // Local changes in background do not trigger sync
        systemClipboard.simulateUserCopy("Background copied text");
        assertEquals("No sync dispatched while window unfocused", 0, remoteSender.sentTexts.size());

        // Window regains focus -> probes and syncs
        manager.onWindowFocusChanged(true);
        assertTrue("Listener restored on focus gain", manager.isListenerRegistered());
        assertEquals("Probe syncs the newly copied background text", 1, remoteSender.sentTexts.size());
        assertEquals("Background copied text", remoteSender.sentTexts.get(0));
    }

    @Test
    public void testDetachUnregistersListener() {
        manager.detach();
        assertFalse("Listener must be unregistered on detach", manager.isListenerRegistered());
    }
}
