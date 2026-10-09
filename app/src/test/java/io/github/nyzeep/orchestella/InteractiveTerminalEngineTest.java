package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM unit tests for {@link InteractiveTerminalEngine}.
 * Verifies real-time keystroke echo, cursor movement, history, and VT100 control sequences.
 */
public final class InteractiveTerminalEngineTest {

    private InteractiveTerminalEngine engine;
    private final List<byte[]> emittedBytes = new ArrayList<>();
    private final List<String> committedLines = new ArrayList<>();
    private final List<String> echoedLines = new ArrayList<>();
    private final AtomicBoolean clearInvoked = new AtomicBoolean(false);

    @Before
    public void setUp() {
        emittedBytes.clear();
        committedLines.clear();
        echoedLines.clear();
        clearInvoked.set(false);

        InteractiveTerminalEngine.PromptInfo prompt =
                new InteractiveTerminalEngine.PromptInfo("root", "orchestella", "~", "❯", true);
        engine = new InteractiveTerminalEngine(prompt);

        engine.setByteSink(emittedBytes::add);
        engine.setLineCommitListener((p, cmd) -> committedLines.add(cmd));
        engine.setLineChangeListener((p, buffer, cursor) -> echoedLines.add(buffer));
        engine.setClearRequestListener(() -> clearInvoked.set(true));
    }

    @Test
    public void testPromptFormatting() {
        assertEquals("root", engine.getPrompt().getUser());
        assertEquals("orchestella", engine.getPrompt().getHost());
        assertEquals("~", engine.getPrompt().getWorkingDirectory());
        assertEquals("❯", engine.getPrompt().getSymbol());
        assertTrue(engine.getPrompt().isRoot());
        assertEquals("root@orchestella:~#", engine.getPrompt().getContextIdentifier());
        assertEquals("[root@orchestella ~] ❯ ", engine.getPrompt().toPromptString());
    }

    @Test
    public void testKeystrokeRealTimeEcho() {
        // Type 'l' then 's'
        engine.handleKeyTyped('l');
        assertEquals("l", engine.getBuffer());
        assertEquals(1, engine.getCursorPosition());
        assertEquals("l▋", engine.getFormattedLineWithCursor());

        engine.handleKeyTyped('s');
        assertEquals("ls", engine.getBuffer());
        assertEquals(2, engine.getCursorPosition());
        assertEquals("ls▋", engine.getFormattedLineWithCursor());

        assertEquals(2, echoedLines.size());
        assertEquals("l", echoedLines.get(0));
        assertEquals("ls", echoedLines.get(1));
    }

    @Test
    public void testBackspaceAndBoundary() {
        engine.handleTextInserted("cat");
        assertEquals("cat", engine.getBuffer());
        assertEquals(3, engine.getCursorPosition());

        engine.handleBackspace();
        assertEquals("ca", engine.getBuffer());
        assertEquals(2, engine.getCursorPosition());
        assertEquals("ca▋", engine.getFormattedLineWithCursor());

        engine.handleBackspace();
        engine.handleBackspace();
        assertEquals("", engine.getBuffer());
        assertEquals(0, engine.getCursorPosition());

        // Underflow protection
        engine.handleBackspace();
        assertEquals("", engine.getBuffer());
        assertEquals(0, engine.getCursorPosition());
    }

    @Test
    public void testArrowNavigationAndMiddleInsertion() {
        engine.handleTextInserted("echo hi");
        assertEquals("echo hi", engine.getBuffer());
        assertEquals(7, engine.getCursorPosition());

        // Move cursor back 2 positions (before "hi")
        engine.handleArrowLeft();
        engine.handleArrowLeft();
        assertEquals(5, engine.getCursorPosition());
        assertEquals("echo ▋hi", engine.getFormattedLineWithCursor());

        // Insert "say "
        engine.handleTextInserted("say ");
        assertEquals("echo say hi", engine.getBuffer());
        assertEquals(9, engine.getCursorPosition());

        // Move right
        engine.handleArrowRight();
        assertEquals(10, engine.getCursorPosition());
        // Overflow protection
        engine.handleArrowRight();
        engine.handleArrowRight();
        assertEquals(11, engine.getCursorPosition());
    }

    @Test
    public void testHistoryNavigation() {
        // Execute cmd 1: "uname -a"
        engine.handleTextInserted("uname -a");
        engine.handleEnter();
        assertEquals(1, committedLines.size());
        assertEquals("uname -a", committedLines.get(0));
        assertArrayEquals("uname -a\r".getBytes(StandardCharsets.UTF_8), emittedBytes.get(0));
        assertEquals("", engine.getBuffer());

        // Execute cmd 2: "df -h"
        engine.handleTextInserted("df -h");
        engine.handleEnter();
        assertEquals(2, committedLines.size());
        assertEquals("df -h", committedLines.get(1));

        // Start typing a new uncommitted line
        engine.handleTextInserted("ls -l");

        // Arrow Up recalls "df -h"
        engine.handleArrowUp();
        assertEquals("df -h", engine.getBuffer());

        // Arrow Up recalls "uname -a"
        engine.handleArrowUp();
        assertEquals("uname -a", engine.getBuffer());

        // Arrow Down recalls "df -h"
        engine.handleArrowDown();
        assertEquals("df -h", engine.getBuffer());

        // Arrow Down restores the uncommitted "ls -l"
        engine.handleArrowDown();
        assertEquals("ls -l", engine.getBuffer());
    }

    @Test
    public void testTabSendsByte() {
        engine.handleTab();
        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{'\t'}, emittedBytes.get(0));
    }

    @Test
    public void testCtrlCWorkflow() {
        engine.handleTextInserted("sleep 100");
        engine.handleCtrlC();

        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{0x03}, emittedBytes.get(0));
        assertEquals(1, committedLines.size());
        assertEquals("sleep 100^C", committedLines.get(0));
        assertEquals("", engine.getBuffer());
        assertEquals(0, engine.getCursorPosition());
    }

    @Test
    public void testCtrlDWorkflow() {
        // Non-empty buffer: does not send EOF
        engine.handleTextInserted("test");
        engine.handleCtrlD();
        assertEquals(0, emittedBytes.size());

        // Empty buffer: sends EOF (0x04)
        engine.syncBuffer("", 0);
        engine.handleCtrlD();
        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{0x04}, emittedBytes.get(0));
    }

    @Test
    public void testCtrlLWorkflow() {
        engine.handleCtrlL();
        assertEquals(1, emittedBytes.size());
        assertArrayEquals(new byte[]{0x0C}, emittedBytes.get(0));
        assertTrue(clearInvoked.get());
    }

    @Test
    public void testDeleteKeystroke() {
        engine.handleTextInserted("abc");
        engine.handleArrowLeft(); // cursor at 2 ('c')
        engine.handleDelete();
        assertEquals("ab", engine.getBuffer());
        assertEquals(2, engine.getCursorPosition());

        // Delete at end: no-op
        engine.handleDelete();
        assertEquals("ab", engine.getBuffer());
    }

    @Test
    public void testLoadHistory() {
        List<String> commands = List.of("git pull", "cargo build", "cargo test");
        engine.loadHistory(commands);
        assertEquals(commands, engine.getHistory());

        // Test Up arrow recalls last loaded command
        engine.handleArrowUp();
        assertEquals("cargo test", engine.getBuffer());
        assertEquals("cargo test".length(), engine.getCursorPosition());
    }

    @Test
    public void testHomeAndEndNavigation() {
        engine.handleTextInserted("echo 'quick navigation'");
        assertEquals(23, engine.getCursorPosition());

        engine.handleHome();
        assertEquals(0, engine.getCursorPosition());
        assertEquals("echo 'quick navigation'", engine.getBuffer());

        engine.handleEnd();
        assertEquals(23, engine.getCursorPosition());
        assertEquals("echo 'quick navigation'", engine.getBuffer());
    }

    @Test
    public void testHomeAndEndAnsiSequences() {
        engine.handleTextInserted("command");
        assertEquals(7, engine.getCursorPosition());

        engine.handleAnsiSequence("\u001b[H"); // Home
        assertEquals(0, engine.getCursorPosition());

        engine.handleAnsiSequence("\u001b[F"); // End
        assertEquals(7, engine.getCursorPosition());

        engine.handleAnsiSequence("\\e[1~"); // VT100 Home
        assertEquals(0, engine.getCursorPosition());

        engine.handleAnsiSequence("\\e[4~"); // VT100 End
        assertEquals(7, engine.getCursorPosition());
    }

    @Test
    public void testClearScreenAndResetLine() {
        engine.handleTextInserted("uncommitted input");
        assertEquals("uncommitted input", engine.getBuffer());

        engine.handleClearScreenAndResetLine();
        assertEquals("", engine.getBuffer());
        assertEquals(0, engine.getCursorPosition());
        assertTrue(clearInvoked.get());
        assertTrue(emittedBytes.stream().anyMatch(b -> b.length == 1 && b[0] == 0x0C));
    }
}
