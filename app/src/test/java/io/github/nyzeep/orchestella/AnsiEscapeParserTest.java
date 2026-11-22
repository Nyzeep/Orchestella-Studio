package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests verifying ANSI/VT100 escape sequence parsing, SGR attributes,
 * Dracula 16/256/TrueColor palette mapping, streaming state retention, and malformed sequence tolerance.
 *
 * <p>W2-01 equivalence suite: every new-parser result is compared field-for-field against
 * {@link LegacySplitBasedParser}, a frozen verbatim copy of the pre-refactor implementation,
 * across a fixed corpus, a seeded fuzz run, chunked delivery, and multi-sequence state carry.
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

    // ------------------------------------------------------------------
    // W2-01 equivalence suite (zero-regex SGR token scanning refactor)
    // ------------------------------------------------------------------

    private static final String[] CORPUS_STYLE_CODES = {
        "", ";", "::",
        "0", "1", "3", "4", "7", "21", "22", "23", "24", "27",
        "30", "31", "32", "33", "34", "35", "36", "37", "39",
        "40", "41", "42", "43", "44", "45", "46", "47", "49",
        "90", "91", "92", "93", "94", "95", "96", "97",
        "100", "101", "102", "103", "104", "105", "106", "107",
        "1;31", "0;1;4;7", "1;1;1;1;1", "31;41", "1;31;42;7",
        "91;101", "37;47", "97;107", "0;0;0", "9;9;9",
        "1;22;23;24;27", "2;3;4;5;6", "58", "28", "8", "5", "2"
    };

    private static final String[] CORPUS_EXTENDED_COLORS = {
        "38", "48",
        "38;5;196", "38;5;300", "38;5;x", "38;5", "38;5;", "48;5;21",
        "38;2;12;34;56", "38;2;12;34", "38;2;300;0;0", "48;2;255;0;128",
        "38:5:196", "38:5", "38:2::12:34:56", "38:2:1:12:34:56", "38:2:12:34:56",
        "38:2::", "38;2", "38;", "38:2",
        "48:5:9", "48:2:9:9:9", "38:5:300", "38:5:x",
        "38;2;12;34;56;1", "38;2;12;34;56;48;5;9", "0;38;5;200;48;5;100",
        "38;48;5;1", "48;38;5;1", "38;2;;1;2", "48:2:1:2:3:4",
        "38;\uff15;196", "38;\u0665;196", "\uff15", "\u0665", "\uff11",
        "38;5;1;38;5;2;38;5;3"
    };

    private static final String[] CORPUS_MALFORMED_EDGES = {
        "9999999999999", "2147483648", "2147483647", "-2147483648", "-2147483649",
        "+2147483648", "-5", "+5", "+", "-", "+0", "-0",
        "?25", "?", " 5", "5 ", "5x", "x", "-x", "+x", "+38",
        "1;;2", "38;;5;196", "3:4", "38;;", " 38; 5 ;196 ", "\t5",
        "07", "005", "000000000000005",
        ";1", "1;", ":1:", "1::2", ";38;5;1", "38;5;1;", "::1",
        "\t", " ", "  ", "\t\t", " \t ",
        "9x;1", "1;x;38;5;7", "38:5:x:196", "48;;5;;21", "38;;2;;12;;34;;56",
        "38;5;196\t", "38;5;196 ", ";::;::;::"
    };

    @Test
    public void testFrozenLegacyOracleSelfCheck() {
        // The oracle must reproduce the documented pre-refactor behaviors on its own,
        // so any equivalence failure below is attributable to the new implementation.
        List<TerminalSpan> spans = new LegacySplitBasedParser().parse("\u001b[38;5;196mRed256\u001b[0m");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(0xFFFF0000), spans.get(0).getForegroundColor());

        // '::' separator run collapses: the colorspace field is lost, tokens are
        // [38, 2, 12, 34, 56], so the legacy parser renders rgb(12, 34, 56).
        spans = new LegacySplitBasedParser().parse("\u001b[38:2::12:34:56mLost");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(0xFF000000 | (12 << 16) | (34 << 8) | 56), spans.get(0).getForegroundColor());

        // Leading separator produces a preserved empty token; "1" still applies bold.
        spans = new LegacySplitBasedParser().parse("\u001b[;1mbold");
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).isBold());

        // ';;' run collapses with NO empty middle token: tokens are [38, 5, 196],
        // so the 256-color value applies (empty token would have blocked the lookahead).
        spans = new LegacySplitBasedParser().parse("\u001b[38;;5;196mcollapsed");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(0xFFFF0000), spans.get(0).getForegroundColor());

        // Trailing separator stripped: tokens [38, 5, 1] -> ANSI red via get256(1)
        // (index < 16 maps into the ANSI 16 palette).
        spans = new LegacySplitBasedParser().parse("\u001b[38;5;1;mtrailing");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), spans.get(0).getForegroundColor());
    }

    @Test
    public void testEquivalenceCorpusStyleCodes() {
        for (String params : CORPUS_STYLE_CODES) {
            assertEquivalentParse("style corpus", "\u001b[" + params + "m TEXT");
        }
    }

    @Test
    public void testEquivalenceCorpusExtendedColors() {
        for (String params : CORPUS_EXTENDED_COLORS) {
            assertEquivalentParse("extended corpus", "\u001b[" + params + "m TEXT");
        }
    }

    @Test
    public void testEquivalenceCorpusMalformedEdges() {
        for (String params : CORPUS_MALFORMED_EDGES) {
            assertEquivalentParse("malformed corpus", "\u001b[" + params + "m TEXT");
        }
    }

    @Test
    public void testEquivalenceMultiSequenceStateCarry() {
        assertEquivalentSequence(
                "multi-sequence state carry",
                "\u001b[1;31m",
                "red text ",
                "\u001b[38:2::9:9:9m",
                "colon truecolor\n",
                "\u001b[38;5;196m",
                "256 red",
                "\u001b[0m",
                "plain again ",
                "\u001b[48;2;255;0;128m",
                "bg swap ",
                "\u001b[1;3;4;7m",
                "styles ",
                "\u001b[22;23;24;27;49m",
                "cleared ",
                "\u001b[m",
                "after empty reset",
                "\u001b[;38;5;9m",
                "leading empty token then 256 color\n",
                "\u001b[38;5;1;m",
                "trailing empty token",
                "\u001b[ 38 ; 5 ; 196 m",
                "spaced 256 color"
        );
    }

    @Test
    public void testMultiSequenceStateCarryExplicitExpectations() {
        // \u001b[1;31m then \u001b[38:2::9:9:9m then text: bold carried, '::' run
        // collapses so the colorspace field is lost and rgb(9,9,9) is applied.
        parser.parse("\u001b[1;31m");
        parser.parse("\u001b[38:2::9:9:9m");
        List<TerminalSpan> spans = parser.parse("final text");
        assertEquals(1, spans.size());
        TerminalSpan span = spans.get(0);
        assertEquals("final text", span.getText());
        assertEquals(Integer.valueOf(0xFF090909), span.getForegroundColor());
        assertTrue(span.isBold());
        assertFalse(span.isItalic());
        assertFalse(span.isUnderline());
        assertFalse(span.isInverse());
        assertNull(span.getBackgroundColor());

        // Whitespace around tokens is trimmed by the integer parse (legacy trim()).
        parser.reset();
        spans = parser.parse("\u001b[ 38 ; 5 ; 196 m spaced");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(0xFFFF0000), spans.get(0).getForegroundColor());

        // Unicode decimal digits parse via Character.digit (full-width '５' and
        // Arabic-Indic '٥' both mean 5), matching legacy Integer.parseInt(s.trim()).
        parser.reset();
        spans = parser.parse("\u001b[38;\uff15;196m fullwidth");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(0xFFFF0000), spans.get(0).getForegroundColor());
        parser.reset();
        spans = parser.parse("\u001b[38;\u0665;196m arabicindic");
        assertEquals(1, spans.size());
        assertEquals(Integer.valueOf(0xFFFF0000), spans.get(0).getForegroundColor());

        // Integer.MIN_VALUE parses (no overflow) but is not an SGR code: no style change.
        parser.reset();
        spans = parser.parse("\u001b[-2147483648m minint");
        assertEquals(1, spans.size());
        assertNull(spans.get(0).getForegroundColor());
        assertFalse(spans.get(0).isBold());

        // Overflow (+2147483648) falls back to the -1 default token: skipped entirely.
        parser.reset();
        spans = parser.parse("\u001b[2147483648m overflow");
        assertEquals(1, spans.size());
        assertNull(spans.get(0).getForegroundColor());
        assertFalse(spans.get(0).isBold());

        // Leading empty token from ';': "" falls to the -1 default and is skipped,
        // while the following "1" applies bold.
        parser.reset();
        spans = parser.parse("\u001b[;1m leading");
        assertEquals(1, spans.size());
        assertTrue(spans.get(0).isBold());
    }

    @Test
    public void testEquivalenceChunkedDelivery() {
        String[] inputs = {
                "\u001b[1;31mRed",
                "\u001b[38;5;196m256",
                "\u001b[38:2::12:34:56mcolon",
                "\u001b[ 38 ; 5 ; 196 m spaces",
                "\u001b[48;2;255;0;128mbg",
                "\u001b[;1;31;42;7mleading-empty"
        };
        for (String input : inputs) {
            for (int split = 1; split < input.length(); split++) {
                AnsiEscapeParser current = new AnsiEscapeParser();
                LegacySplitBasedParser legacy = new LegacySplitBasedParser();
                String label = "chunked [" + input + "] split@" + split;
                assertSpansEquivalent(label + " part1",
                        legacy.parse(input.substring(0, split)),
                        current.parse(input.substring(0, split)));
                assertSpansEquivalent(label + " part2",
                        legacy.parse(input.substring(split)),
                        current.parse(input.substring(split)));
            }
        }
    }

    @Test
    public void testSeededFuzzEquivalenceAgainstLegacyOracle() {
        final long seed = 20261122L;
        final int iterations = 3200;
        Random rng = new Random(seed);
        final String alphabet = "0123456789;: +-x?\t";
        for (int iter = 0; iter < iterations; iter++) {
            String params = randomFuzzParams(rng, alphabet);
            String label = "fuzz#" + iter + " params=[" + params + "]";
            AnsiEscapeParser current = new AnsiEscapeParser();
            LegacySplitBasedParser legacy = new LegacySplitBasedParser();

            // Warm both parsers with a pre-sequence so the fuzzed SGR lands mid-stream.
            String warm = "\u001b[1;31mwarm ";
            assertSpansEquivalent(label + " warm", legacy.parse(warm), current.parse(warm));

            // Fuzzed params delivered as a fresh SGR sequence with trailing text.
            String input = "\u001b[" + params + "m body ";
            assertSpansEquivalent(label, legacy.parse(input), current.parse(input));

            // Same params again on carried state (mid-stream embedding).
            assertSpansEquivalent(label + " echo", legacy.parse(input), current.parse(input));

            // Chunked delivery: split inside the sequence to exercise pendingEscape.
            String chunked = "\u001b[" + params + "m tail";
            int splitAt = 1 + rng.nextInt(chunked.length() - 1);
            assertSpansEquivalent(label + " chunkA@" + splitAt,
                    legacy.parse(chunked.substring(0, splitAt)),
                    current.parse(chunked.substring(0, splitAt)));
            assertSpansEquivalent(label + " chunkB@" + splitAt,
                    legacy.parse(chunked.substring(splitAt)),
                    current.parse(chunked.substring(splitAt)));
        }
    }

    @Test
    public void sgrParseSmokeCompletesWithoutHanging() {
        // 防挂死冒烟：只要求完成，不断言时序（避免 CI 抖动假红）。
        AnsiEscapeParser smoke = new AnsiEscapeParser();
        String[] paramsSet = {
                "1;31", "38;5;196", "0", "38:2::9:9:9", "48;2;255;0;128", "1;;2", "?25", " 5 "
        };
        long producedSpans = 0;
        for (int i = 0; i < 100_000; i++) {
            producedSpans += smoke.parse("\u001b[" + paramsSet[i % paramsSet.length] + "m tick ").size();
            if (i % 9 == 0) {
                smoke.reset();
            }
        }
        // No timing assertion: only guards against hangs/infinite loops and crashes.
        assertTrue(producedSpans > 0);
    }

    // ------------------------------------------------------------------
    // Equivalence helpers
    // ------------------------------------------------------------------

    private static void assertSpansEquivalent(String label, List<TerminalSpan> legacySpans,
                                              List<TerminalSpan> currentSpans) {
        assertEquals(label + ": span count", legacySpans.size(), currentSpans.size());
        for (int k = 0; k < legacySpans.size(); k++) {
            TerminalSpan expected = legacySpans.get(k);
            TerminalSpan actual = currentSpans.get(k);
            String at = label + " span#" + k;
            assertEquals(at + " text", expected.getText(), actual.getText());
            assertEquals(at + " fg", expected.getForegroundColor(), actual.getForegroundColor());
            assertEquals(at + " bg", expected.getBackgroundColor(), actual.getBackgroundColor());
            assertEquals(at + " bold", expected.isBold(), actual.isBold());
            assertEquals(at + " italic", expected.isItalic(), actual.isItalic());
            assertEquals(at + " underline", expected.isUnderline(), actual.isUnderline());
            assertEquals(at + " inverse", expected.isInverse(), actual.isInverse());
            assertEquals(at + " clearScreen", expected.isClearScreen(), actual.isClearScreen());
            // TerminalSpan.equals also covers the internal command flag, keeping a
            // second net in case new fields are ever added to the span.
            assertEquals(at + " full span", expected, actual);
        }
    }

    private static void assertEquivalentParse(String label, String input) {
        List<TerminalSpan> legacySpans = new LegacySplitBasedParser().parse(input);
        List<TerminalSpan> currentSpans = new AnsiEscapeParser().parse(input);
        assertSpansEquivalent(label + " input=[" + input + "]", legacySpans, currentSpans);
    }

    private static void assertEquivalentSequence(String label, String... inputs) {
        AnsiEscapeParser current = new AnsiEscapeParser();
        LegacySplitBasedParser legacy = new LegacySplitBasedParser();
        for (int k = 0; k < inputs.length; k++) {
            String at = label + " call#" + k + " input=[" + inputs[k] + "]";
            assertSpansEquivalent(at, legacy.parse(inputs[k]), current.parse(inputs[k]));
        }
    }

    private static String randomFuzzParams(Random rng, String alphabet) {
        StringBuilder sb = new StringBuilder();
        switch (rng.nextInt(5)) {
            case 0: {
                // Pure random alphabet soup, length 0..40.
                int len = rng.nextInt(41);
                for (int k = 0; k < len; k++) {
                    sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
                }
                break;
            }
            case 1: {
                // Structured 38/48;5;n and 38/48;2;r;g;b with occasional ';;' runs.
                sb.append(rng.nextBoolean() ? "38" : "48");
                if (rng.nextBoolean()) {
                    sb.append(semicolonRun(rng)).append('5').append(semicolonRun(rng)).append(randomIntToken(rng));
                } else {
                    sb.append(semicolonRun(rng)).append('2');
                    for (int k = 0; k < 3; k++) {
                        sb.append(semicolonRun(rng)).append(randomIntToken(rng));
                    }
                }
                break;
            }
            case 2: {
                // Colon variants, including colorspace-dropping '::' shapes.
                sb.append(rng.nextBoolean() ? "38" : "48");
                if (rng.nextBoolean()) {
                    sb.append(":5:").append(randomIntToken(rng));
                } else {
                    sb.append(":2");
                    for (int k = 0; k < rng.nextInt(5); k++) {
                        sb.append(':').append(randomIntToken(rng));
                    }
                }
                break;
            }
            case 3: {
                // Random tokens joined by mixed ';'/':' separator runs.
                int n = rng.nextInt(7);
                for (int k = 0; k < n; k++) {
                    if (k > 0) {
                        sb.append(mixedSeparatorRun(rng));
                    }
                    sb.append(randomIntToken(rng));
                }
                break;
            }
            default: {
                // Known SGR codes joined by a single ';' or ':'.
                String[] codes = {
                        "0", "1", "3", "4", "7", "21", "22", "23", "24", "27",
                        "30", "31", "32", "33", "37", "39", "40", "47", "49",
                        "90", "97", "100", "107", "38", "48", "5", "2",
                        "196", "255", "12", "34", "56", "-1", "+1", "x", "07"
                };
                int n = 1 + rng.nextInt(5);
                for (int k = 0; k < n; k++) {
                    if (k > 0) {
                        sb.append(rng.nextBoolean() ? ";" : ":");
                    }
                    sb.append(codes[rng.nextInt(codes.length)]);
                }
                break;
            }
        }
        if (rng.nextInt(6) == 0) {
            sb.insert(0, mixedSeparatorRun(rng));
        }
        if (rng.nextInt(6) == 0) {
            sb.append(mixedSeparatorRun(rng));
        }
        return sb.toString();
    }

    private static String semicolonRun(Random rng) {
        return rng.nextInt(4) == 0 ? ";;" : ";";
    }

    private static String mixedSeparatorRun(Random rng) {
        int n = 1 + rng.nextInt(3);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < n; k++) {
            sb.append(rng.nextBoolean() ? ';' : ':');
        }
        return sb.toString();
    }

    private static String randomIntToken(Random rng) {
        String[] tokens = {
                "", "0", "1", "2", "3", "4", "5", "7", "9", "12", "34", "56",
                "196", "255", "300", "07", "0009", "-1", "+1", "-5", "+5", "x", "?",
                " 9 ", "\t9", " 38 ", " 5 ", "2147483648", "-2147483648", "9999999999999"
        };
        return tokens[rng.nextInt(tokens.length)];
    }

    // ------------------------------------------------------------------
    // Frozen legacy oracle
    // ------------------------------------------------------------------

    /**
     * Frozen verbatim from the pre-W2-01 implementation (base commit b30e4053), kept as an
     * equivalence oracle for the zero-regex SGR token-scanning refactor: every new parser
     * result must match this class field-for-field on identical input streams.
     * 冻结自 W2-01 重构前的实现，作为等价性 oracle；禁止随后续重构同步修改。
     *
     * <p>Only the class name and constructor name were changed; the method bodies are a
     * byte-for-byte copy of the original {@code AnsiEscapeParser} (its regex-based
     * {@code split("[;:]+")} tokenization and {@code Integer.parseInt(s.trim())} are the
     * reference semantics under test).
     */
    public static final class LegacySplitBasedParser {

        private Integer currentFg = null;
        private Integer currentBg = null;
        private boolean bold = false;
        private boolean italic = false;
        private boolean underline = false;
        private boolean inverse = false;

        private String pendingEscape = "";

        public LegacySplitBasedParser() {
            reset();
        }

        /**
         * Resets parser style state and pending buffer to initial defaults.
         */
        public synchronized void reset() {
            currentFg = null;
            currentBg = null;
            bold = false;
            italic = false;
            underline = false;
            inverse = false;
            pendingEscape = "";
        }

        /**
         * Parses the given raw text and returns a list of styled {@link TerminalSpan}s.
         * State is retained across calls to support streaming terminal output.
         */
        public synchronized List<TerminalSpan> parse(String text) {
            List<TerminalSpan> spans = new ArrayList<>();
            if (text == null || text.isEmpty()) {
                return spans;
            }

            String input;
            if (!pendingEscape.isEmpty()) {
                input = pendingEscape + text;
                pendingEscape = "";
            } else {
                input = text;
            }

            StringBuilder textBuffer = new StringBuilder();
            int len = input.length();
            int i = 0;

            while (i < len) {
                char c = input.charAt(i);

                if (c == '\u001b') { // ESC (0x1B)
                    // Check if we have enough characters or if this escape is at the very end
                    if (i + 1 >= len) {
                        // Incomplete escape at chunk boundary
                        pendingEscape = input.substring(i);
                        break;
                    }

                    char next = input.charAt(i + 1);

                    if (next == '[') {
                        // CSI sequence: ESC [ ... <final_char>
                        int csiEnd = findCsiEnd(input, i + 2);
                        if (csiEnd == -1) {
                            // Incomplete CSI at chunk boundary
                            pendingEscape = input.substring(i);
                            break;
                        }

                        // Flush any pending text before style change
                        flushText(textBuffer, spans);

                        char finalChar = input.charAt(csiEnd);
                        String params = input.substring(i + 2, csiEnd);

                        if (finalChar == 'm') {
                            // SGR sequence
                            handleSgr(params);
                        } else if (finalChar == 'J') {
                            // Erase in Display (clear screen):
                            // 2J = clear entire screen, 3J = clear scrollback buffer
                            if (params.isEmpty() || params.contains("2") || params.contains("3")) {
                                spans.add(TerminalSpan.clearScreen());
                            }
                        }
                        // Non-SGR CSI commands (e.g., 'H', 'K', 'A', etc.) are stripped
                        i = csiEnd + 1;
                        continue;

                    } else if (next == ']') {
                        // OSC sequence: ESC ] ... (BEL or ESC \)
                        int oscEnd = findOscEnd(input, i + 2);
                        if (oscEnd == -1) {
                            // Incomplete OSC at chunk boundary
                            pendingEscape = input.substring(i);
                            break;
                        }
                        flushText(textBuffer, spans);
                        i = oscEnd;
                        continue;

                    } else if (next == '(' || next == ')') {
                        // Character set selection: ESC ( <char> or ESC ) <char>
                        if (i + 2 >= len) {
                            pendingEscape = input.substring(i);
                            break;
                        }
                        i += 3;
                        continue;

                    } else if (next == 'c') {
                        // Full Reset (RIS: \033c)
                        flushText(textBuffer, spans);
                        spans.add(TerminalSpan.clearScreen());
                        resetStyles();
                        i += 2;
                        continue;

                    } else {
                        // Other 2-char escape sequences: skip ESC and next char
                        i += 2;
                        continue;
                    }
                } else {
                    textBuffer.append(c);
                    i++;
                }
            }

            flushText(textBuffer, spans);
            return spans;
        }

        private void flushText(StringBuilder buffer, List<TerminalSpan> spans) {
            if (buffer.length() > 0) {
                spans.add(new TerminalSpan(
                        buffer.toString(),
                        currentFg,
                        currentBg,
                        bold,
                        italic,
                        underline,
                        inverse
                ));
                buffer.setLength(0);
            }
        }

        private static int findCsiEnd(String s, int start) {
            int len = s.length();
            for (int i = start; i < len; i++) {
                char c = s.charAt(i);
                // Final character of CSI is in range 0x40 - 0x7E (@ through ~)
                if (c >= 0x40 && c <= 0x7E) {
                    return i;
                }
            }
            return -1;
        }

        private static int findOscEnd(String s, int start) {
            int len = s.length();
            for (int i = start; i < len; i++) {
                char c = s.charAt(i);
                if (c == '\u0007') { // BEL
                    return i + 1;
                }
                if (c == '\u001b' && i + 1 < len && s.charAt(i + 1) == '\\') { // ST (ESC \)
                    return i + 2;
                }
            }
            return -1;
        }

        private void handleSgr(String params) {
            if (params.isEmpty()) {
                resetStyles();
                return;
            }

            String[] tokens = params.split("[;:]+");
            if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
                resetStyles();
                return;
            }

            for (int i = 0; i < tokens.length; i++) {
                int code = parseInteger(tokens[i], -1);
                if (code == -1) {
                    continue;
                }

                switch (code) {
                    case 0:
                        resetStyles();
                        break;
                    case 1:
                        bold = true;
                        break;
                    case 3:
                        italic = true;
                        break;
                    case 4:
                        underline = true;
                        break;
                    case 7:
                        inverse = true;
                        break;
                    case 21:
                    case 22:
                        bold = false;
                        break;
                    case 23:
                        italic = false;
                        break;
                    case 24:
                        underline = false;
                        break;
                    case 27:
                        inverse = false;
                        break;
                    case 30: case 31: case 32: case 33: case 34: case 35: case 36: case 37:
                        currentFg = DraculaPalette.getAnsi16(code - 30);
                        break;
                    case 38:
                        // Extended foreground: 38;5;n or 38;2;r;g;b
                        if (i + 1 < tokens.length) {
                            int type = parseInteger(tokens[i + 1], -1);
                            if (type == 5 && i + 2 < tokens.length) { // 256 color
                                int idx = parseInteger(tokens[i + 2], -1);
                                if (idx >= 0 && idx <= 255) {
                                    currentFg = DraculaPalette.get256(idx);
                                }
                                i += 2;
                            } else if (type == 2 && i + 4 < tokens.length) { // TrueColor
                                int r = parseInteger(tokens[i + 2], 0);
                                int g = parseInteger(tokens[i + 3], 0);
                                int b = parseInteger(tokens[i + 4], 0);
                                currentFg = DraculaPalette.getTrueColor(r, g, b);
                                i += 4;
                            }
                        }
                        break;
                    case 39:
                        currentFg = null; // reset to default foreground
                        break;
                    case 40: case 41: case 42: case 43: case 44: case 45: case 46: case 47:
                        currentBg = DraculaPalette.getAnsi16(code - 40);
                        break;
                    case 48:
                        // Extended background: 48;5;n or 48;2;r;g;b
                        if (i + 1 < tokens.length) {
                            int type = parseInteger(tokens[i + 1], -1);
                            if (type == 5 && i + 2 < tokens.length) { // 256 color
                                int idx = parseInteger(tokens[i + 2], -1);
                                if (idx >= 0 && idx <= 255) {
                                    currentBg = DraculaPalette.get256(idx);
                                }
                                i += 2;
                            } else if (type == 2 && i + 4 < tokens.length) { // TrueColor
                                int r = parseInteger(tokens[i + 2], 0);
                                int g = parseInteger(tokens[i + 3], 0);
                                int b = parseInteger(tokens[i + 4], 0);
                                currentBg = DraculaPalette.getTrueColor(r, g, b);
                                i += 4;
                            }
                        }
                        break;
                    case 49:
                        currentBg = null; // reset to default background
                        break;
                    case 90: case 91: case 92: case 93: case 94: case 95: case 96: case 97:
                        currentFg = DraculaPalette.getAnsi16(8 + (code - 90));
                        break;
                    case 100: case 101: case 102: case 103: case 104: case 105: case 106: case 107:
                        currentBg = DraculaPalette.getAnsi16(8 + (code - 100));
                        break;
                    default:
                        // Unknown or unsupported SGR code ignored
                        break;
                }
            }
        }

        private void resetStyles() {
            currentFg = null;
            currentBg = null;
            bold = false;
            italic = false;
            underline = false;
            inverse = false;
        }

        private static int parseInteger(String s, int defaultValue) {
            if (s == null || s.isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }
}
