package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 4.8 Task 1: Comprehensive pure JVM unit tests for advanced editing in {@link InteractiveTerminalEngine}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>Cursor navigation and middle-of-line insertion</li>
 *   <li>Middle-of-line backspace deletion without flicker/corruption</li>
 *   <li>Middle-of-line delete key handling</li>
 *   <li>ANSI escape sequence processing (\e[D, \e[C, \e[A, \e[B)</li>
 *   <li>History navigation with automatic cursor positioning at end-of-line</li>
 *   <li>Signal handling (Ctrl+C, Ctrl+D, Ctrl+L preserving uncommitted line)</li>
 *   <li>Tab auto-completion stub: single candidate completion</li>
 *   <li>Tab auto-completion stub: multiple candidates callback</li>
 *   <li>Tab auto-completion fallback: raw 0x09 byte emission when no candidates match</li>
 * </ol>
 */
public final class InteractiveTerminalEngineAdvancedTest {

    private InteractiveTerminalEngine engine;
    private final List<byte[]> emittedBytes = new ArrayList<>();
    private final List<String> committedLines = new ArrayList<>();
    private final List<String> echoedLines = new ArrayList<>();
    private final AtomicBoolean clearInvoked = new AtomicBoolean(false);
    private final List<List<String>> completionCandidateEvents = new ArrayList<>();

    @Before
    public void setUp() {
        emittedBytes.clear();
        committedLines.clear();
        echoedLines.clear();
        clearInvoked.set(false);
        completionCandidateEvents.clear();

        InteractiveTerminalEngine.PromptInfo prompt =
                new InteractiveTerminalEngine.PromptInfo("root", "orchestella", "~", "❯", true);
        engine = new InteractiveTerminalEngine(prompt);

        engine.setByteSink(emittedBytes::add);
        engine.setLineCommitListener((p, cmd) -> committedLines.add(cmd));
        engine.setLineChangeListener((p, buffer, cursor) -> echoedLines.add(buffer));
        engine.setClearRequestListener(() -> clearInvoked.set(true));
        engine.setTabCompletionListener((buf, pos, candidates) -> completionCandidateEvents.add(candidates));
    }

    @Test
    public void testMiddleCursorInsertion() {
        // Buffer: "git commit"
        engine.handleTextInserted("git commit");
        assertEquals("git commit", engine.getBuffer());
        assertEquals(10, engine.getCursorPosition());

        // Move cursor back 6 places: between "git " and "commit"
        for (int i = 0; i < 6; i++) {
            engine.handleArrowLeft();
        }
        assertEquals(4, engine.getCursorPosition());

        // Insert "fast " at cursor position 4
        engine.handleTextInserted("fast ");
        assertEquals("git fast commit", engine.getBuffer());
        assertEquals(9, engine.getCursorPosition());
        assertEquals("git fast ▋commit", engine.getFormattedLineWithCursor());
    }

    @Test
    public void testMiddleCursorBackspace() {
        // Buffer: "systemctl start nginx"
        engine.handleTextInserted("systemctl start nginx");
        // Move cursor before " nginx" (position 15)
        for (int i = 0; i < 6; i++) {
            engine.handleArrowLeft();
        }
        assertEquals(15, engine.getCursorPosition());

        // Backspace removes 't' from "start" -> "star"
        engine.handleBackspace();
        assertEquals("systemctl star nginx", engine.getBuffer());
        assertEquals(14, engine.getCursorPosition());
    }

    @Test
    public void testMiddleCursorDelete() {
        // Buffer: "python app.py"
        engine.handleTextInserted("python app.py");
        // Move cursor to 'a' in "app.py" (position 7)
        for (int i = 0; i < 6; i++) {
            engine.handleArrowLeft();
        }
        assertEquals(7, engine.getCursorPosition());

        // Delete removes 'a' at cursor position
        engine.handleDelete();
        assertEquals("python pp.py", engine.getBuffer());
        assertEquals(7, engine.getCursorPosition());
    }

    @Test
    public void testAnsiEscapeSequencesForLeftRightCursor() {
        engine.handleTextInserted("alpine");
        assertEquals(6, engine.getCursorPosition());

        // Send \e[D (Arrow Left) via ANSI escape sequence
        engine.handleAnsiSequence("\u001b[D");
        assertEquals(5, engine.getCursorPosition());

        engine.handleAnsiSequence("\u001b[D");
        assertEquals(4, engine.getCursorPosition());

        // Send \e[C (Arrow Right)
        engine.handleAnsiSequence("\u001b[C");
        assertEquals(5, engine.getCursorPosition());
    }

    @Test
    public void testAnsiEscapeSequencesForUpDownHistoryWithCursorAtEnd() {
        // Commit 2 commands
        engine.handleTextInserted("apk update");
        engine.handleEnter();
        engine.handleTextInserted("apk add curl htop");
        engine.handleEnter();

        // Start typing a third command
        engine.handleTextInserted("ls");
        assertEquals(2, engine.getCursorPosition());

        // \e[A (Arrow Up) recalls previous command: "apk add curl htop"
        engine.handleAnsiSequence("\u001b[A");
        assertEquals("apk add curl htop", engine.getBuffer());
        // Cursor must be positioned at the end of the line
        assertEquals("apk add curl htop".length(), engine.getCursorPosition());

        // \e[A (Arrow Up) recalls "apk update"
        engine.handleAnsiSequence("\u001b[A");
        assertEquals("apk update", engine.getBuffer());
        assertEquals("apk update".length(), engine.getCursorPosition());

        // \e[B (Arrow Down) moves forward in history
        engine.handleAnsiSequence("\u001b[B");
        assertEquals("apk add curl htop", engine.getBuffer());
        assertEquals("apk add curl htop".length(), engine.getCursorPosition());

        // \e[B (Arrow Down) restores uncommitted input "ls"
        engine.handleAnsiSequence("\u001b[B");
        assertEquals("ls", engine.getBuffer());
        assertEquals(2, engine.getCursorPosition());
    }

    @Test
    public void testCtrlCInterruptionAndPromptReset() {
        engine.handleTextInserted("sleep 3600");
        assertEquals(10, engine.getCursorPosition());

        engine.handleCtrlC();

        // Emits byte 0x03 (SIGINT)
        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{0x03}, emittedBytes.get(0));

        // Commits interrupted line with ^C marker
        assertEquals(1, committedLines.size());
        assertEquals("sleep 3600^C", committedLines.get(0));

        // Buffer and cursor are reset to 0
        assertEquals("", engine.getBuffer());
        assertEquals(0, engine.getCursorPosition());
    }

    @Test
    public void testCtrlDEofHandling() {
        // Buffer non-empty: Ctrl+D must NOT emit EOF
        engine.handleTextInserted("cat");
        engine.handleCtrlD();
        assertEquals(0, emittedBytes.size());

        // Clear buffer: Ctrl+D emits 0x04 (EOF)
        engine.syncBuffer("", 0);
        engine.handleCtrlD();
        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{0x04}, emittedBytes.get(0));
    }

    @Test
    public void testCtrlLScreenClearPreservingLineBuffer() {
        engine.handleTextInserted("make build");
        engine.handleArrowLeft(); // cursor at 9
        assertEquals(9, engine.getCursorPosition());

        engine.handleCtrlL();

        // Emits form feed 0x0C
        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{0x0C}, emittedBytes.get(0));
        assertTrue("Clear screen listener must be triggered", clearInvoked.get());

        // Line buffer and cursor MUST be preserved intact
        assertEquals("make build", engine.getBuffer());
        assertEquals(9, engine.getCursorPosition());
    }

    @Test
    public void testTabCompletionSingleCandidate() {
        engine.setTabCompletionHandler((buf, pos) -> {
            if ("doc".equals(buf)) {
                return Collections.singletonList("docker");
            }
            return Collections.emptyList();
        });

        engine.handleTextInserted("doc");
        engine.handleTab();

        // Completed to "docker"
        assertEquals("docker", engine.getBuffer());
        assertEquals(6, engine.getCursorPosition());
        assertEquals(0, emittedBytes.size());
    }

    @Test
    public void testTabCompletionMultipleCandidates() {
        engine.setTabCompletionHandler((buf, pos) -> {
            if ("git c".equals(buf)) {
                return Arrays.asList("git checkout", "git commit", "git clone");
            }
            return Collections.emptyList();
        });

        engine.handleTextInserted("git c");
        engine.handleTab();

        // Buffer unchanged when multiple candidates exist
        assertEquals("git c", engine.getBuffer());
        assertEquals(1, completionCandidateEvents.size());
        assertEquals(Arrays.asList("git checkout", "git commit", "git clone"), completionCandidateEvents.get(0));
    }

    @Test
    public void testTabCompletionFallbackToRawTabByte() {
        // No completion handler registered -> raw \t emitted
        engine.handleTextInserted("unknown");
        engine.handleTab();

        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{'\t'}, emittedBytes.get(0));
    }
}
