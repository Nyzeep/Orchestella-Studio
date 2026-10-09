package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 4.9 Task 2: Pure JVM unit tests for {@link CommandCompletionEngine}
 * and its end-to-end integration with {@link InteractiveTerminalEngine}.
 */
public final class CommandCompletionEngineTest {

    private CommandCompletionEngine engine;

    @Before
    public void setUp() {
        engine = new CommandCompletionEngine();
    }

    @Test
    public void testUniqueCommandCompletion() {
        CommandCompletionEngine.Result resSys = engine.evaluate("sys", 3);
        assertTrue(resSys.isSingleMatch());
        assertEquals("systemctl", resSys.commonPrefixToken());
        assertEquals("systemctl", resSys.commonPrefixLine());
        assertEquals(9, resSys.commonPrefixCursorPosition());
        assertEquals(Collections.singletonList("systemctl"), resSys.completedLines());

        CommandCompletionEngine.Result resGre = engine.evaluate("gre", 3);
        assertTrue(resGre.isSingleMatch());
        assertEquals("grep", resGre.commonPrefixToken());
        assertEquals("grep", resGre.commonPrefixLine());
        assertEquals(4, resGre.commonPrefixCursorPosition());

        CommandCompletionEngine.Result resUna = engine.evaluate("una", 3);
        assertTrue(resUna.isSingleMatch());
        assertEquals("uname", resUna.commonPrefixToken());
        assertEquals("uname", resUna.commonPrefixLine());
    }

    @Test
    public void testMultipleCandidatesWithCommonPrefixExtension() {
        // "ch" matches "chmod", "chown" -> common prefix is "ch" (diverge at 'm' vs 'o')
        CommandCompletionEngine.Result resCh = engine.evaluate("ch", 2);
        assertFalse(resCh.isSingleMatch());
        assertEquals("ch", resCh.inputToken());
        assertEquals("ch", resCh.commonPrefixToken());
        assertFalse(resCh.hasCommonPrefixExtension());
        assertEquals(Arrays.asList("chmod", "chown"), resCh.matches());
        assertEquals("ch", resCh.commonPrefixLine());

        // Register custom commands with shared extended prefix: "sys-start" and "sys-status"
        engine.registerCommand("sys-start");
        engine.registerCommand("sys-status");
        CommandCompletionEngine.Result resSys = engine.evaluate("sys-s", 5);
        assertFalse(resSys.isSingleMatch());
        assertEquals("sys-s", resSys.inputToken());
        assertEquals("sys-sta", resSys.commonPrefixToken());
        assertTrue("Common prefix 'sys-sta' extends typed 'sys-s'", resSys.hasCommonPrefixExtension());
        assertEquals(Arrays.asList("sys-start", "sys-status"), resSys.matches());
        assertEquals("sys-sta", resSys.commonPrefixLine());
        assertEquals(7, resSys.commonPrefixCursorPosition());

        // "ap" matches "apk", "apt" -> common prefix is "ap" (no extension beyond "ap")
        CommandCompletionEngine.Result resAp = engine.evaluate("ap", 2);
        assertFalse(resAp.isSingleMatch());
        assertEquals("ap", resAp.inputToken());
        assertEquals("ap", resAp.commonPrefixToken());
        assertFalse(resAp.hasCommonPrefixExtension());
        assertEquals(Arrays.asList("apk", "apt"), resAp.matches());
    }

    @Test
    public void testNoMatchReturnsEmptyResult() {
        CommandCompletionEngine.Result resNone = engine.evaluate("xyzabc", 6);
        assertFalse(resNone.isSingleMatch());
        assertTrue(resNone.matches().isEmpty());
        assertTrue(resNone.completedLines().isEmpty());
        assertEquals("", resNone.commonPrefixLine());

        CommandCompletionEngine.Result resNull = engine.evaluate(null, 0);
        assertTrue(resNull.matches().isEmpty());

        CommandCompletionEngine.Result resEmpty = engine.evaluate("", 0);
        assertTrue(resEmpty.matches().isEmpty());
    }

    @Test
    public void testMidLineCursorPositionRecalculation() {
        // Buffer: "sys status" with cursor at 3 (immediately after "sys")
        String buffer = "sys status";
        int cursor = 3;

        CommandCompletionEngine.Result res = engine.evaluate(buffer, cursor);
        assertTrue(res.isSingleMatch());
        assertEquals("sys", res.inputToken());
        assertEquals("systemctl", res.commonPrefixToken());
        assertEquals("systemctl status", res.commonPrefixLine());
        assertEquals(9, res.commonPrefixCursorPosition());
        assertEquals(Collections.singletonList("systemctl status"), res.completedLines());
    }

    @Test
    public void testPipelineAwareCommandCompletion() {
        // Buffer: "cat log.txt | gr" with cursor at end (16)
        String line = "cat log.txt | gr";
        CommandCompletionEngine.Result res = engine.evaluate(line, line.length());
        assertTrue(res.isSingleMatch());
        assertEquals("gr", res.inputToken());
        assertEquals("grep", res.commonPrefixToken());
        assertEquals("cat log.txt | grep", res.commonPrefixLine());
        assertEquals("cat log.txt | grep".length(), res.commonPrefixCursorPosition());

        // Semicolon separator: "echo hello; mk" -> "echo hello; mkdir"
        String semiLine = "echo hello; mk";
        CommandCompletionEngine.Result resSemi = engine.evaluate(semiLine, semiLine.length());
        assertTrue(resSemi.isSingleMatch());
        assertEquals("echo hello; mkdir", resSemi.commonPrefixLine());

        // Logical AND separator: "cd /tmp && pw" -> "cd /tmp && pwd"
        String andLine = "cd /tmp && pw";
        CommandCompletionEngine.Result resAnd = engine.evaluate(andLine, andLine.length());
        assertTrue(resAnd.isSingleMatch());
        assertEquals("cd /tmp && pwd", resAnd.commonPrefixLine());
    }

    @Test
    public void testPathCompletion() {
        // "cd /et" -> "cd /etc"
        String buffer = "cd /et";
        CommandCompletionEngine.Result resEtc = engine.evaluate(buffer, buffer.length());
        assertTrue(resEtc.isSingleMatch());
        assertEquals("/et", resEtc.inputToken());
        assertEquals("/etc", resEtc.commonPrefixToken());
        assertEquals("cd /etc", resEtc.commonPrefixLine());

        // "ls -la /u" -> "ls -la /usr"
        String lsBuffer = "ls -la /u";
        CommandCompletionEngine.Result resUsr = engine.evaluate(lsBuffer, lsBuffer.length());
        assertTrue(resUsr.isSingleMatch());
        assertEquals("/usr", resUsr.commonPrefixToken());
        assertEquals("ls -la /usr", resUsr.commonPrefixLine());

        // "cat /va" -> "cat /var"
        String catBuffer = "cat /va";
        CommandCompletionEngine.Result resVar = engine.evaluate(catBuffer, catBuffer.length());
        assertTrue(resVar.isSingleMatch());
        assertEquals("cat /var", resVar.commonPrefixLine());
    }

    @Test
    public void testCustomCommandRegistration() {
        engine.registerCommand("orchestella-studio");
        engine.registerCommand("orchestella-cli");

        CommandCompletionEngine.Result res = engine.evaluate("orche", 5);
        assertFalse(res.isSingleMatch());
        assertEquals("orchestella-", res.commonPrefixToken());
        assertEquals(Arrays.asList("orchestella-cli", "orchestella-studio"), res.matches());

        CommandCompletionEngine.Result resCli = engine.evaluate("orchestella-c", 13);
        assertTrue(resCli.isSingleMatch());
        assertEquals("orchestella-cli", resCli.commonPrefixToken());
    }

    @Test
    public void testFormatCandidatesListing() {
        List<String> candidates = Arrays.asList("cat foo | chmod", "cat foo | chown");
        String formatted = CommandCompletionEngine.formatCandidatesListing(candidates);
        assertEquals("chmod  chown", formatted);

        List<String> plainCandidates = Arrays.asList("apk", "apt");
        String plainFormatted = CommandCompletionEngine.formatCandidatesListing(plainCandidates);
        assertEquals("apk  apt", plainFormatted);
    }

    @Test
    public void testInteractiveTerminalEngineFullIntegration() {
        InteractiveTerminalEngine.PromptInfo prompt =
                new InteractiveTerminalEngine.PromptInfo("root", "orchestella", "~", "❯", true);
        InteractiveTerminalEngine term = new InteractiveTerminalEngine(prompt);

        List<byte[]> emitted = new ArrayList<>();
        List<List<String>> candidateEvents = new ArrayList<>();
        term.setByteSink(emitted::add);
        term.setTabCompletionHandler(engine);
        term.setTabCompletionListener((buf, pos, cands) -> candidateEvents.add(cands));

        // 1. Single candidate completion: "sys" -> "systemctl"
        term.handleTextInserted("sys");
        term.handleTab();
        assertEquals("systemctl", term.getBuffer());
        assertEquals(9, term.getCursorPosition());
        assertTrue(candidateEvents.isEmpty());
        assertTrue(emitted.isEmpty());

        term.handleEnter();
        candidateEvents.clear();

        // 2. Multiple candidates without extended prefix: "ch" -> remains "ch", calls listener with candidates
        term.handleTextInserted("ch");
        term.handleTab();
        assertEquals("ch", term.getBuffer());
        assertEquals(2, term.getCursorPosition());
        assertEquals(1, candidateEvents.size());
        assertEquals(Arrays.asList("chmod", "chown"), candidateEvents.get(0));

        // Format candidate listing via helper
        String listing = term.renderCompletionListing(candidateEvents.get(0));
        assertTrue(listing.contains("chmod  chown"));
        assertTrue(listing.contains("[root@orchestella ~] ❯ ch"));

        term.handleEnter();
        candidateEvents.clear();

        // 2b. Multiple candidates with extended prefix: "sys-s" -> auto-advances to "sys-sta"
        engine.registerCommand("sys-start");
        engine.registerCommand("sys-status");
        term.handleTextInserted("sys-s");
        term.handleTab();
        assertEquals("sys-sta", term.getBuffer());
        assertEquals(7, term.getCursorPosition());
        assertEquals(1, candidateEvents.size());
        assertEquals(Arrays.asList("sys-start", "sys-status"), candidateEvents.get(0));

        term.handleEnter();
        candidateEvents.clear();

        // 3. Pipeline completion: "cat hosts | gr" -> "cat hosts | grep"
        term.handleTextInserted("cat hosts | gr");
        term.handleTab();
        assertEquals("cat hosts | grep", term.getBuffer());
        assertEquals("cat hosts | grep".length(), term.getCursorPosition());

        term.handleEnter();

        // 4. Unknown command fallback to raw 0x09 tab byte
        emitted.clear();
        term.handleTextInserted("unknown_cmd");
        term.handleTab();
        assertEquals(1, emitted.size());
        assertArrayEquals(new byte[]{'\t'}, emitted.get(0));
    }
}
