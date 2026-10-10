package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests verifying ANSI/VT100 escape sequence parsing, SGR attributes,
 * Dracula 16/256/TrueColor palette mapping, streaming state retention, and malformed sequence tolerance.
 */
public final class AnsiEscapeParserTest {

    private AnsiEscapeParser parser;

    @Before
    public void setUp() {
        parser = new AnsiEscapeParser();
    }

    @Test
    public void testPlainTextProducesSingleDefaultSpan() {
        List<TerminalSpan> spans = parser.parse("Hello, Orchestella!\n");
        assertEquals(1, spans.size());
        TerminalSpan span = spans.get(0);
        assertEquals("Hello, Orchestella!\n", span.getText());
        assertNull(span.getForegroundColor());
        assertNull(span.getBackgroundColor());
        assertFalse(span.isBold());
        assertFalse(span.isItalic());
        assertFalse(span.isUnderline());
        assertFalse(span.isInverse());
    }

    @Test
    public void testStandard16ColorMapping() {
        // Red foreground: \x1b[31mRed\x1b[0m
        List<TerminalSpan> spans = parser.parse("\u001b[31mRed\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("Red", spans.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), spans.get(0).getForegroundColor());

        // Reset clears color
        parser.reset();
        spans = parser.parse("\u001b[32mGreen\u001b[0mNormal");
        assertEquals(2, spans.size());
        assertEquals("Green", spans.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_GREEN), spans.get(0).getForegroundColor());
        assertEquals("Normal", spans.get(1).getText());
        assertNull(spans.get(1).getForegroundColor());
    }

    @Test
    public void testFullColorSpectrumCycle() {
        String input = "\u001b[31mRed \u001b[32mGreen \u001b[33mYellow \u001b[34mBlue \u001b[35mMagenta \u001b[36mCyan \u001b[0m\n";
        List<TerminalSpan> spans = parser.parse(input);
        assertEquals(7, spans.size());

        assertEquals("Red ", spans.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), spans.get(0).getForegroundColor());

        assertEquals("Green ", spans.get(1).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_GREEN), spans.get(1).getForegroundColor());

        assertEquals("Yellow ", spans.get(2).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_YELLOW), spans.get(2).getForegroundColor());

        assertEquals("Blue ", spans.get(3).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_BLUE), spans.get(3).getForegroundColor());

        assertEquals("Magenta ", spans.get(4).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_MAGENTA), spans.get(4).getForegroundColor());

        assertEquals("Cyan ", spans.get(5).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_CYAN), spans.get(5).getForegroundColor());

        assertEquals("\n", spans.get(6).getText());
        assertNull(spans.get(6).getForegroundColor());
    }

    @Test
    public void testBrightColors() {
        // Bright black (code 90), Bright red (code 91)
        List<TerminalSpan> spans = parser.parse("\u001b[90mGray\u001b[91mBrightRed\u001b[0m");
        assertEquals(2, spans.size());
        assertEquals("Gray", spans.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_BRIGHT_BLACK), spans.get(0).getForegroundColor());
        assertEquals("BrightRed", spans.get(1).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_BRIGHT_RED), spans.get(1).getForegroundColor());
    }

    @Test
    public void testBackgroundColors() {
        // Background green: \x1b[42m
        List<TerminalSpan> spans = parser.parse("\u001b[42mBgGreen\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("BgGreen", spans.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_GREEN), spans.get(0).getBackgroundColor());
    }

    @Test
    public void test256ColorExtendedPalette() {
        // Color 196: Pure Red (Cube index: r=5, g=0, b=0 -> 0xFFFF0000)
        List<TerminalSpan> spans = parser.parse("\u001b[38;5;196mRed256\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("Red256", spans.get(0).getText());
        assertEquals(Integer.valueOf(0xFFFF0000), spans.get(0).getForegroundColor());

        // Color 21: Pure Blue (Cube index: r=0, g=0, b=5 -> 0xFF0000FF)
        parser.reset();
        spans = parser.parse("\u001b[38;5;21mBlue256\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("Blue256", spans.get(0).getText());
        assertEquals(Integer.valueOf(0xFF0000FF), spans.get(0).getForegroundColor());

        // Grayscale ramp: 232 -> gray 8 (0xFF080808)
        parser.reset();
        spans = parser.parse("\u001b[38;5;232mDarkGray\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("DarkGray", spans.get(0).getText());
        assertEquals(Integer.valueOf(0xFF080808), spans.get(0).getForegroundColor());

        // Grayscale ramp: 255 -> gray 238 (0xFFEEEEEE)
        parser.reset();
        spans = parser.parse("\u001b[38;5;255mLightGray\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("LightGray", spans.get(0).getText());
        assertEquals(Integer.valueOf(0xFFEEEEEE), spans.get(0).getForegroundColor());

        // Background 256 color: \x1b[48;5;196m
        parser.reset();
        spans = parser.parse("\u001b[48;5;196mBg256\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(0xFFFF0000), spans.get(0).getBackgroundColor());
    }

    @Test
    public void testTrueColor24BitSemicolonAndColon() {
        // Semicolon syntax: \x1b[38;2;255;100;0m
        List<TerminalSpan> spans = parser.parse("\u001b[38;2;255;100;0mTrueColor Orange\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("TrueColor Orange", spans.get(0).getText());
        int expectedColor = 0xFF000000 | (255 << 16) | (100 << 8);
        assertEquals(Integer.valueOf(expectedColor), spans.get(0).getForegroundColor());

        // Colon syntax: \x1b[38:2:0:200:150m
        parser.reset();
        spans = parser.parse("\u001b[38:2:0:200:150mColonTrueColor\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals("ColonTrueColor", spans.get(0).getText());
        int colonExpected = 0xFF000000 | (200 << 8) | 150;
        assertEquals(Integer.valueOf(colonExpected), spans.get(0).getForegroundColor());

        // Background TrueColor: \x1b[48;2;40;42;54m
        parser.reset();
        spans = parser.parse("\u001b[48;2;40;42;54mBgTrueColor\u001b[0m");
        assertEquals(1, spans.size());
        int bgExpected = 0xFF000000 | (40 << 16) | (42 << 8) | 54;
        assertEquals(Integer.valueOf(bgExpected), spans.get(0).getBackgroundColor());
    }

    @Test
    public void testTextStylesBoldItalicUnderlineInverse() {
        // Bold: \x1b[1m
        List<TerminalSpan> spans = parser.parse("\u001b[1mBold\u001b[22mNormal");
        assertEquals(2, spans.size());
        assertTrue(spans.get(0).isBold());
        assertFalse(spans.get(1).isBold());

        // Italic: \x1b[3m
        parser.reset();
        spans = parser.parse("\u001b[3mItalic\u001b[23mNormal");
        assertEquals(2, spans.size());
        assertTrue(spans.get(0).isItalic());
        assertFalse(spans.get(1).isItalic());

        // Underline: \x1b[4m
        parser.reset();
        spans = parser.parse("\u001b[4mUnderline\u001b[24mNormal");
        assertEquals(2, spans.size());
        assertTrue(spans.get(0).isUnderline());
        assertFalse(spans.get(1).isUnderline());

        // Inverse: \x1b[7m
        parser.reset();
        spans = parser.parse("\u001b[7mInverse\u001b[27mNormal");
        assertEquals(2, spans.size());
        assertTrue(spans.get(0).isInverse());
        assertFalse(spans.get(1).isInverse());
    }

    @Test
    public void testCombinedAttributesInSingleSequence() {
        // Bold + Red + Green BG: \x1b[1;31;42m
        List<TerminalSpan> spans = parser.parse("\u001b[1;31;42mCombo\u001b[0m");
        assertEquals(1, spans.size());
        TerminalSpan span = spans.get(0);
        assertEquals("Combo", span.getText());
        assertTrue(span.isBold());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), span.getForegroundColor());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_GREEN), span.getBackgroundColor());
    }

    @Test
    public void testStreamingStateRetentionAcrossChunks() {
        List<TerminalSpan> chunk1 = parser.parse("\u001b[31mRedStart ");
        assertEquals(1, chunk1.size());
        assertEquals("RedStart ", chunk1.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), chunk1.get(0).getForegroundColor());

        // Next chunk continues in Red
        List<TerminalSpan> chunk2 = parser.parse("RedContinued\u001b[0m Reset");
        assertEquals(2, chunk2.size());
        assertEquals("RedContinued", chunk2.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), chunk2.get(0).getForegroundColor());
        assertEquals(" Reset", chunk2.get(1).getText());
        assertNull(chunk2.get(1).getForegroundColor());
    }

    @Test
    public void testMalformedAndUnsupportedEscapeSequencesStrippedSafely() {
        // Cursor movement \x1b[2J and \x1b[H and OSC title \x1b]0;Title\x07
        String input = "\u001b[2J\u001b[H\u001b]0;Term Title\u0007Prompt: \u001b[?25hReady";
        List<TerminalSpan> spans = parser.parse(input);
        assertNotNull(spans);
        StringBuilder fullText = new StringBuilder();
        for (TerminalSpan span : spans) {
            fullText.append(span.getText());
        }
        assertEquals("Prompt: Ready", fullText.toString());

        // Incomplete escape at chunk boundary
        parser.reset();
        List<TerminalSpan> partial1 = parser.parse("TextBefore\u001b[3");
        assertEquals(1, partial1.size());
        assertEquals("TextBefore", partial1.get(0).getText());

        List<TerminalSpan> partial2 = parser.parse("1mRedAfter");
        assertEquals(1, partial2.size());
        assertEquals("RedAfter", partial2.get(0).getText());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), partial2.get(0).getForegroundColor());
    }

    @Test
    public void testClearScreenSequencesParsedProperly() {
        // \033[2J clears screen
        List<TerminalSpan> spans = parser.parse("\u001b[2J");
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).isClearScreen());

        // \033[3J clears scrollback
        parser.reset();
        spans = parser.parse("\u001b[3J");
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).isClearScreen());

        // Typical Linux clear command: \033[H\033[2J\033[3J followed by shell prompt
        parser.reset();
        spans = parser.parse("\u001b[H\u001b[2J\u001b[3Jroot@orchestella:~# ");
        assertEquals(3, spans.size());
        assertTrue(spans.get(0).isClearScreen());
        assertTrue(spans.get(1).isClearScreen());
        assertEquals("root@orchestella:~# ", spans.get(2).getText());

        // RIS reset \033c
        parser.reset();
        spans = parser.parse("\u001bcPrompt");
        assertEquals(2, spans.size());
        assertTrue(spans.get(0).isClearScreen());
        assertEquals("Prompt", spans.get(1).getText());
    }
}
