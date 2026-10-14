package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.*;
import io.github.nyzeep.orchestella.DraculaPalette;
import io.github.nyzeep.orchestella.TerminalSpan;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class TerminalScreenModelTest {
    private static String line(TerminalScreenModel model, int row) {
        return model.snapshot().text().split("\n", -1)[row].replaceFirst(" +$", "");
    }

    @Test public void carriageReturnOverwritesWithoutErasingTail() {
        TerminalScreenModel model = new TerminalScreenModel(20, 4);
        model.append("abcdef\rXY");
        assertEquals("XYcdef", line(model, 0));
        assertEquals(2, model.cursorColumn());
        model.append("\u001b[K");
        assertEquals("XY", line(model, 0));
    }

    @Test public void splitCrLfAndEscapeSequencesAreIdenticalToWholeStream() {
        String stream = "one\r\ntwo\u001b[1;2H\u001b[31mX\u001b[0m\r\n你好😀";
        TerminalScreenModel whole = new TerminalScreenModel(20, 5);
        TerminalScreenModel split = new TerminalScreenModel(20, 5);
        whole.append(stream);
        for (int i = 0; i < stream.length(); i++) split.append(stream.substring(i, i + 1));
        assertEquals(whole.snapshot().text(), split.snapshot().text());
        assertFalse(split.snapshot().text().contains("\ufffd"));
    }

    @Test public void backspaceMovesCursorAndLeavesUntouchedCharacters() {
        TerminalScreenModel model = new TerminalScreenModel(20, 4);
        model.append("abc\bX");
        assertEquals("abX", line(model, 0));
        model.append("\b \b");
        assertEquals("ab", line(model, 0));
        assertEquals(2, model.cursorColumn());
    }

    @Test public void topRedrawOverwritesRowsRatherThanAppendingEveryFrame() {
        TerminalScreenModel model = new TerminalScreenModel(30, 6);
        model.append("\u001b[?25l\u001b[Htop - 12:00\r\nTasks: 10\r\nCPU: 30%\r\nPID USER");
        model.append("\u001b[Htop - 12:01\u001b[K\r\nTasks: 2\u001b[K\r\nCPU: 4%\u001b[K");
        assertEquals("top - 12:01", line(model, 0));
        assertEquals("Tasks: 2", line(model, 1));
        assertEquals("CPU: 4%", line(model, 2));
        assertEquals("PID USER", line(model, 3));
        assertEquals(0, model.snapshot().historyRows);
        assertFalse(model.isCursorVisible());
    }

    @Test public void vimAlternateScreenRestoresShellAndSavedCursor() {
        TerminalScreenModel model = new TerminalScreenModel(30, 6);
        model.append("guest$ vim\u001b[?1049h\u001b[?1h\u001b[2J\u001b[Hhello\r\nworld\u001b[6;1H-- INSERT --");
        assertTrue(model.isAlternateScreen());
        assertTrue(model.isApplicationCursorKeys());
        assertEquals("hello", line(model, 0));
        assertEquals("-- INSERT --", line(model, 5));
        model.append("\u001b[2;1Hchanged\u001b[K\u001b[?1049l\u001b[?1l");
        assertFalse(model.isAlternateScreen());
        assertFalse(model.isApplicationCursorKeys());
        assertEquals("guest$ vim", line(model, 0));
        assertEquals(10, model.cursorColumn());
        assertFalse(model.snapshot().text().contains("changed"));
    }

    @Test public void scrollingRegionProtectsHeaderAndFooter() {
        TerminalScreenModel model = new TerminalScreenModel(20, 5);
        model.append("\u001b[?1049hHEADER\u001b[2;1Hone\u001b[3;1Htwo\u001b[4;1Hthree\u001b[5;1HFOOTER");
        model.append("\u001b[2;4r\u001b[4;1H\nlast");
        assertEquals("HEADER", line(model, 0));
        assertEquals("two", line(model, 1));
        assertEquals("three", line(model, 2));
        assertEquals("last", line(model, 3));
        assertEquals("FOOTER", line(model, 4));
        assertEquals(0, model.snapshot().historyRows);
    }

    @Test public void lineInsertAndDeleteInsideRegionKeepGridSize() {
        TerminalScreenModel model = new TerminalScreenModel(10, 4);
        model.append("\u001b[?1049hA\r\nB\r\nC\r\nD\u001b[2;1H\u001b[LX");
        assertEquals("X", line(model, 1));
        assertEquals("B", line(model, 2));
        assertEquals("C", line(model, 3));
        model.append("\u001b[2;1H\u001b[M");
        assertEquals("B", line(model, 1));
        assertEquals("C", line(model, 2));
        assertEquals("", line(model, 3));
    }

    @Test public void characterInsertionDeletionAndErasePreserveTail() {
        TerminalScreenModel model = new TerminalScreenModel(12, 4);
        model.append("abcdef\u001b[1;3H\u001b[2@XY");
        assertEquals("abXYcdef", line(model, 0));
        model.append("\u001b[1;3H\u001b[2P");
        assertEquals("abcdef", line(model, 0));
        model.append("\u001b[2X");
        assertEquals("ab  ef", line(model, 0));
    }

    @Test public void displayEraseKeepsCursorAndScrollbackUnlessModeThree() {
        TerminalScreenModel model = new TerminalScreenModel(10, 3);
        model.append("a\r\nb\r\nc\r\nd");
        assertTrue(model.snapshot().historyRows > 0);
        model.append("\u001b[2J");
        assertEquals(2, model.cursorRow());
        assertEquals(1, model.cursorColumn());
        assertTrue(model.snapshot().historyRows > 0);
        model.append("\u001b[3J");
        assertEquals(0, model.snapshot().historyRows);
    }

    @Test public void displayEraseZeroClearsOnlyAfterCursor() {
        TerminalScreenModel model = new TerminalScreenModel(10, 4);
        model.append("first\r\nsecond\r\nthird\u001b[2;4H\u001b[J");
        assertEquals("first", line(model, 0));
        assertEquals("sec", line(model, 1));
        assertFalse(model.snapshot().text().contains("third"));
    }

    @Test public void resizeShrinksPrimaryAroundCursorAndRestoresAlternateOnExit() {
        TerminalScreenModel model = new TerminalScreenModel(10, 5);
        model.append("a\r\nb\r\nc\r\nd\r\ne");
        model.resize(8, 3);
        assertEquals(3, model.rows());
        assertEquals(8, model.columns());
        assertEquals(2, model.cursorRow());
        assertEquals(2, model.snapshot().historyRows);
        model.append("\u001b[?1049h\u001b[3;8HX");
        model.resize(6, 2);
        assertEquals(1, model.cursorRow());
        assertEquals(5, model.cursorColumn());
        model.append("\u001b[?1049l");
        assertFalse(model.isAlternateScreen());
        assertTrue(model.snapshot().text().contains("e"));
    }

    @Test public void cursorClampAndOriginModeAreBounded() {
        TerminalScreenModel model = new TerminalScreenModel(10, 6);
        model.append("\u001b[2;5r\u001b[?6h\u001b[1;1H");
        assertEquals(1, model.cursorRow());
        model.append("\u001b[999B\u001b[999C");
        assertEquals(4, model.cursorRow());
        assertEquals(9, model.cursorColumn());
        model.append("\u001b[?6l\u001b[999;999H");
        assertEquals(5, model.cursorRow());
    }

    @Test public void wideAndCombiningCharactersOccupyCellsWithoutBrokenSurrogates() {
        TerminalScreenModel model = new TerminalScreenModel(12, 4);
        model.append("你😀e\u0301");
        assertEquals(5, model.cursorColumn());
        assertTrue(line(model, 0).startsWith("你😀e\u0301"));
        model.append("\u001b[1;2HX");
        assertFalse(model.snapshot().text().contains("你"));
        assertFalse(model.snapshot().text().contains("\ufffd"));
    }

    @Test public void autowrapHappensOnlyWhenNextPrintableArrives() {
        TerminalScreenModel model = new TerminalScreenModel(4, 3);
        model.append("abcd");
        assertEquals(0, model.cursorRow());
        model.append("e");
        assertEquals(1, model.cursorRow());
        assertEquals("abcd", line(model, 0));
        assertEquals("e", line(model, 1));
    }

    @Test public void bracketedPasteAndCursorModesSurviveSplitCommands() {
        TerminalScreenModel model = new TerminalScreenModel(10, 3);
        model.append("\u001b[?20"); model.append("04h\u001b[?25l");
        assertTrue(model.isBracketedPaste());
        assertFalse(model.isCursorVisible());
        model.append("\u001b[?2004l\u001b[?25h");
        assertFalse(model.isBracketedPaste());
        assertTrue(model.isCursorVisible());
    }

    @Test public void cursorQueriesReturnActualPositionWithoutDisplayingProtocol() {
        TerminalScreenModel model = new TerminalScreenModel(20, 6);
        List<String> responses = new ArrayList<>();
        model.setResponseSink(responses::add);
        model.append("\u001b[3;4H\u001b[6n\u001b[5n\u001b[c");
        assertEquals(java.util.Arrays.asList("\u001b[3;4R", "\u001b[0n", "\u001b[?1;2c"), responses);
    }

    @Test public void oscAndDcsAreDiscardedIncludingSplitTerminators() {
        TerminalScreenModel model = new TerminalScreenModel(10, 3);
        model.append("before\u001b]52;c;secret\u001b");
        model.append("\\after\u001bPpayload\u001b\\");
        assertFalse(model.snapshot().text().contains("secret"));
        assertFalse(model.snapshot().text().contains("payload"));
    }

    @Test public void sgrPreservesForegroundBackgroundAndStyles() {
        TerminalScreenModel model = new TerminalScreenModel(10, 3);
        model.append("\u001b[1;4;31;44mX\u001b[0mY");
        TerminalSpan span = model.snapshot().spans.get(0);
        assertTrue(span.isBold()); assertTrue(span.isUnderline());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), span.getForegroundColor());
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_BLUE), span.getBackgroundColor());
    }

    @Test public void restoringCursorRestoresStyleForSubsequentSgrChanges() {
        TerminalScreenModel model = new TerminalScreenModel(20, 4);
        model.append("\u001b[31mred\u001b7\u001b[34mblue\u001b8\u001b[1mX");
        TerminalSpan last = model.snapshot().spans.stream()
                .filter(span -> span.getText().contains("X")).findFirst().get();
        assertEquals(Integer.valueOf(DraculaPalette.COLOR_RED), last.getForegroundColor());
        assertTrue(last.isBold());
    }

    @Test public void fullHistoryTracksEvictionsToPreserveAnUnchangedReadingAnchor() {
        TerminalScreenModel model = new TerminalScreenModel(20, 4);
        for (int i = 0; i < 2100; i++) model.append("row-" + i + "\r\n");
        TerminalScreenModel.Snapshot before = model.snapshot();
        assertEquals(2000, before.historyRows);
        String anchoredLine = before.text().split("\n")[500];
        for (int i = 2100; i < 2200; i++) model.append("row-" + i + "\r\n");
        TerminalScreenModel.Snapshot after = model.snapshot();
        assertEquals(2000, after.historyRows);
        assertEquals(100L, after.evictedHistoryRows - before.evictedHistoryRows);
        assertEquals(anchoredLine, after.text().split("\n")[400]);
    }

    private static String marker(String value) { return "\u001b]133;" + value + "\u0007"; }
    private static String commandMarker(String command) {
        return marker("C;cmd=" + java.util.Base64.getEncoder().encodeToString(command.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void prompt(TerminalScreenModel model) {
        model.append(marker("A;orchestella=1") + "root@guest:~\r\n❯ " + marker("B"));
    }

    @Test public void committedCommandsSurviveGuestEraseAndHaveDistinctBlankSeparatedOutput() {
        TerminalScreenModel model = new TerminalScreenModel(50, 6);
        prompt(model);
        model.append("echo first\r\n" + commandMarker("echo first") + "first\r\n" + marker("D;0"));
        prompt(model);
        model.append("echo second\r\n" + commandMarker("echo second") + "\u001b[2J\u001b[Hsecond\r\n" + marker("D;0"));
        prompt(model);
        String rendered = model.displaySnapshot().text();
        assertTrue(rendered.contains("❯ echo first\n\nfirst"));
        assertTrue(rendered.contains("❯ echo second\n\nsecond"));
        assertTrue(rendered.indexOf("echo first") < rendered.indexOf("echo second"));
        assertTrue(model.displaySnapshot().spans.stream().anyMatch(span -> span.getText().equals("echo first") && span.isBold() && span.isInverse()));
        assertFalse(model.snapshot().text().contains("echo first"));
    }

    @Test public void commandHighlightHasAccessibleContrastOnLightAndDarkThemes() {
        try {
            for (int foreground : new int[]{0xfff8f8f2, 0xff59483e, 0xff777777, 0xff000000, 0xffffffff}) {
                DraculaPalette.setPaletteOverride(new int[16], foreground, 0xffffffff);
                TerminalScreenModel model = new TerminalScreenModel(40, 6);
                prompt(model);
                model.append("echo readable\r\n" + commandMarker("echo readable"));
                TerminalSpan command = model.displaySnapshot().spans.stream()
                        .filter(span -> span.isInverse() && span.getText().equals("echo readable")).findFirst().get();
                int ink = command.getEffectiveForegroundColor();
                assertTrue(ink == 0xff000000 || ink == 0xffffffff);
                double backgroundLuminance = luminance(command.getEffectiveBackgroundColor());
                double inkLuminance = luminance(ink);
                double contrast = (Math.max(backgroundLuminance, inkLuminance) + .05)
                        / (Math.min(backgroundLuminance, inkLuminance) + .05);
                assertTrue("Command contrast=" + contrast, contrast >= 4.5);
            }
        } finally { DraculaPalette.setPaletteOverride(null, 0, 0); }
    }

    private static double luminance(int color) {
        double[] channels = {((color >> 16) & 255) / 255., ((color >> 8) & 255) / 255., (color & 255) / 255.};
        for (int i = 0; i < channels.length; i++) channels[i] = channels[i] <= .04045
                ? channels[i] / 12.92 : Math.pow((channels[i] + .055) / 1.055, 2.4);
        return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
    }

    @Test public void guestResetStartsANewVisibleCommandEpoch() {
        TerminalScreenModel model = new TerminalScreenModel(40, 6);
        prompt(model);
        model.append("echo keep\r\n" + commandMarker("echo keep") + "keep\r\n" + marker("D;0"));
        prompt(model);
        model.append("reset\r\n" + commandMarker("reset") + "\u001bc" + marker("D;0"));
        prompt(model);
        assertFalse(model.displaySnapshot().text().contains("echo keep"));
        assertFalse(model.displaySnapshot().text().contains("reset"));
        assertTrue(model.displaySnapshot().text().contains("❯ "));
    }

    @Test public void markerOptInIsRequiredAndOutputPromptGlyphsNeverBecomeCommands() {
        TerminalScreenModel model = new TerminalScreenModel(50, 6);
        model.append("output > ❯\r\n" + commandMarker("fake"));
        assertEquals(model.snapshot().text(), model.displaySnapshot().text());
        prompt(model);
        model.append("echo symbols\r\n" + commandMarker("echo symbols") + "output > ❯\r\n" + marker("D;0"));
        assertTrue(model.displaySnapshot().text().contains("output > ❯"));
        assertFalse(model.displaySnapshot().spans.stream().anyMatch(span -> span.isInverse() && span.getText().contains("output >")));
    }

    @Test public void ignoredCommandsAndHiddenPasswordReadsAreNeverArchivedFromHostInput() {
        TerminalScreenModel model = new TerminalScreenModel(50, 6);
        prompt(model);
        model.append("private text\r\n" + marker("C") + "safe result\r\n" + marker("D;0"));
        prompt(model);
        model.append("read -s password\r\n" + commandMarker("read -s password") + "Password: \r\nsafe\r\n" + marker("D;0"));
        prompt(model);
        String rendered = model.displaySnapshot().text();
        assertFalse(rendered.contains("private text"));
        assertTrue(rendered.contains("safe result"));
        assertTrue(rendered.contains("read -s password"));
        assertFalse(rendered.contains("PASSWORD_NOT_ECHOED"));
    }

    @Test public void emptyCommandAndCtrlCDoNotReusePreviousSubmission() {
        TerminalScreenModel model = new TerminalScreenModel(40, 6);
        prompt(model);
        model.append("echo once\r\n" + commandMarker("echo once") + "once\r\n" + marker("D;0"));
        prompt(model);
        model.append("\r\n" + marker("D;0"));
        prompt(model);
        model.append("unsubmitted^C\r\n" + marker("D;130"));
        prompt(model);
        String rendered = model.displaySnapshot().text();
        assertEquals(1, rendered.split("echo once", -1).length - 1);
        assertFalse(rendered.contains("unsubmitted"));
    }

    @Test public void continuationPromptAndMultilineCommandComeFromOneAuthoritativePayload() {
        TerminalScreenModel model = new TerminalScreenModel(60, 6);
        prompt(model);
        String command = "printf '%s\\n' 'line1\nline2'";
        model.append("printf '%s\\n' 'line1\r\n" + marker("B;continuation=1") + "> "
                + marker("B;continuation=2") + "line2'\r\n" + commandMarker(command) + "line1\r\nline2\r\n" + marker("D;0"));
        String rendered = model.displaySnapshot().text();
        assertTrue(rendered.contains(command));
        assertEquals(1, rendered.split("printf", -1).length - 1);
        assertTrue(rendered.contains("line1\nline2"));
        assertTrue(model.displaySnapshot().spans.stream().anyMatch(span -> span.isInverse() && span.getText().equals(command)));
    }

    @Test public void backgroundOutputWhileEditingIsSeparateFromAuthoritativeCommand() {
        TerminalScreenModel model = new TerminalScreenModel(50, 6);
        prompt(model);
        model.append("echo queued\r\n[background job done]\r\n" + commandMarker("echo queued") + "queued\r\n" + marker("D;0"));
        String rendered = model.displaySnapshot().text();
        assertTrue(rendered.contains("[background job done]"));
        assertEquals(1, rendered.split("echo queued", -1).length - 1);
        assertFalse(model.displaySnapshot().spans.stream().anyMatch(span -> span.isInverse() && span.getText().contains("background")));
    }

    @Test public void fullScreenGridAndCursorQueriesIgnoreCommandBlockSeparators() {
        TerminalScreenModel model = new TerminalScreenModel(30, 5);
        prompt(model);
        model.append("vim file\r\n" + commandMarker("vim file") + "\u001b[?1049h\u001b[2J\u001b[Heditor\u001b[5;1Hstatus");
        assertEquals(model.snapshot().text(), model.displaySnapshot().text());
        assertEquals(5, model.displaySnapshot().text().split("\n", -1).length);
        model.append("\u001b[?1049l" + marker("D;0"));
        prompt(model);
        assertTrue(model.displaySnapshot().text().contains("vim file"));
        assertFalse(model.displaySnapshot().text().contains("editor"));
        assertFalse(model.displaySnapshot().text().contains("status"));
    }

    @Test public void unknownOscAndFullScreenExitDoNotSwallowNormalCommandOutput() {
        TerminalScreenModel model = new TerminalScreenModel(40, 6);
        prompt(model);
        model.append("tool\r\n" + commandMarker("tool") + "before\r\n\u001b]0;title\u0007after-title\r\n"
                + "\u001b]52;c;ignored\u001b\\after-osc\r\n"
                + "\u001b[?1049h\u001b[Heditor\u001b[?1049lafter-editor\r\n" + marker("D;0"));
        String rendered = model.displaySnapshot().text();
        assertTrue(rendered.contains("before"));
        assertTrue(rendered.contains("after-title"));
        assertTrue(rendered.contains("after-osc"));
        assertTrue(rendered.contains("after-editor"));
        assertFalse(rendered.contains("titleafter"));
        assertFalse(rendered.contains("ignored"));
    }

    @Test public void malformedAndControlBearingPayloadsHaveNoCommandDisplayEffect() {
        TerminalScreenModel model = new TerminalScreenModel(40, 6);
        prompt(model);
        model.append("raw\r\n" + marker("C;cmd=not-base64!") + "output\r\n" + marker("D;0"));
        prompt(model);
        model.append("raw\r\n" + commandMarker("evil\u001b]52;c;secret") + "safe\r\n" + marker("D;0"));
        prompt(model);
        assertFalse(model.displaySnapshot().text().contains("evil"));
        assertFalse(model.displaySnapshot().text().contains("raw"));
        assertFalse(model.displaySnapshot().text().contains("secret"));
    }

    @Test public void oversizedMarkerIsDiscardedAndCommittedBlocksRemainBounded() {
        TerminalScreenModel model = new TerminalScreenModel(40, 6);
        prompt(model);
        model.append(marker("C;cmd=" + "A".repeat(16000)) + "ignored\r\n" + marker("D;0"));
        for (int i = 0; i < 50; i++) {
            prompt(model);
            String command = "echo marker_" + i;
            model.append(command + "\r\n" + commandMarker(command) + "result_" + i + "\r\n" + marker("D;0"));
        }
        prompt(model);
        String rendered = model.displaySnapshot().text();
        assertFalse(rendered.contains("echo marker_0\n"));
        assertTrue(rendered.contains("echo marker_49"));
        assertTrue(rendered.length() < 64_200);
        assertTrue(model.displaySnapshot().evictedHistoryRows > 0);
    }

    @Test public void splitOscMarkersAndUtf8CommandPayloadRenderIdentically() {
        String stream = marker("A;orchestella=1") + "❯ " + marker("B") + "echo 你好😀\r\n" + commandMarker("echo 你好😀") + "你好😀\r\n" + marker("D;0");
        TerminalScreenModel whole = new TerminalScreenModel(40, 5);
        TerminalScreenModel split = new TerminalScreenModel(40, 5);
        whole.append(stream);
        for (int i = 0; i < stream.length(); i++) split.append(stream.substring(i, i + 1));
        assertEquals(whole.displaySnapshot().text(), split.displaySnapshot().text());
        assertFalse(split.displaySnapshot().text().contains("\ufffd"));
    }

    private static String highlightedCommand(TerminalScreenModel.Snapshot snapshot) {
        StringBuilder result = new StringBuilder();
        for (TerminalSpan span : snapshot.spans)
            if (span.isInverse() && span.isBold()) result.append(span.getText());
        return result.toString();
    }

    private static void assertDisplayLinesFit(TerminalScreenModel.Snapshot snapshot, int columns) {
        for (String row : snapshot.text().split("\n", -1)) {
            int width = 0;
            for (int offset = 0; offset < row.length();) {
                int codePoint = row.codePointAt(offset);
                int type = Character.getType(codePoint);
                if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK && type != Character.FORMAT)
                    width += codePoint >= 0x1f300 || (codePoint >= 0x4e00 && codePoint <= 0x9fff) ? 2 : 1;
                offset += Character.charCount(codePoint);
            }
            assertTrue("Display row uses " + width + " cells for " + columns + " columns: " + row, width <= columns);
        }
    }

    @Test public void screenshotLongCommandWrapsAt41ColumnsIncludingPromptAndKeepsEveryCharacter() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        model.append(marker("A;orchestella=1") + "root@orchestella:~\r\n❯ " + marker("B"));
        String command = "echo FIRST_COMMAND; printf 'first output\n'";
        model.append(commandMarker(command) + "FIRST_COMMAND\r\nfirst output\r\n" + marker("D;0"));
        TerminalScreenModel.Snapshot display = model.displaySnapshot();
        assertEquals(command.substring(0, 39) + "\n" + command.substring(39), highlightedCommand(display));
        assertDisplayLinesFit(display, 41);
        assertTrue(display.text().contains("FIRST_COMMAND\nfirst output"));
    }

    @Test public void completedUnbrokenCommandRewrapsOnResizeWithoutModifyingItsStoredText() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        String command = "echo " + "abcdefghijklmnopqrstuvwxyz".repeat(4);
        model.append(commandMarker(command) + marker("D;0"));
        assertDisplayLinesFit(model.displaySnapshot(), 41);
        assertEquals(command, highlightedCommand(model.displaySnapshot()).replace("\n", ""));
        model.resize(19, 16);
        assertDisplayLinesFit(model.displaySnapshot(), 19);
        assertEquals(command, highlightedCommand(model.displaySnapshot()).replace("\n", ""));
        model.resize(160, 16);
        assertEquals(command, highlightedCommand(model.displaySnapshot()));
    }

    @Test public void commandWrappingCountsWideCodePointsAndKeepsCombiningMarksWithTheirBase() {
        TerminalScreenModel model = new TerminalScreenModel(7, 10);
        model.append(marker("A;orchestella=1") + "❯ " + marker("B"));
        String command = "abc你e\u0301😀界";
        model.append(commandMarker(command));
        TerminalScreenModel.Snapshot display = model.displaySnapshot();
        assertEquals("abc你\ne\u0301😀界", highlightedCommand(display));
        assertDisplayLinesFit(display, 7);
        assertFalse(display.text().contains("\ufffd"));
        assertFalse(display.text().contains("e\n\u0301"));
        for (String row : highlightedCommand(display).split("\n", -1)) {
            assertFalse(Character.isHighSurrogate(row.charAt(row.length() - 1)));
            assertFalse(Character.isLowSurrogate(row.charAt(0)));
        }
    }

    @Test public void wrappedCommandPrefixMapsCursorToTheSameOutputCharacter() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("echo " + "x".repeat(90)) + "result");
        TerminalScreenModel.Snapshot display = model.displaySnapshot();
        assertEquals(display.text().lastIndexOf("result") + 6, display.cursorOffset);
        model.resize(17, 16);
        display = model.displaySnapshot();
        assertEquals(display.text().lastIndexOf("result") + 6, display.cursorOffset);
        assertDisplayLinesFit(display, 17);
    }

    @Test public void commandWrapProjectionNeverChangesAlternateScreenDimensionsOrCursorReports() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("nano " + "filename".repeat(10)) + "\u001b[?1049h\u001b[16;41H!");
        assertEquals(model.snapshot().text(), model.displaySnapshot().text());
        assertEquals(model.snapshot().cursorOffset, model.displaySnapshot().cursorOffset);
        assertEquals(16, model.displaySnapshot().text().split("\n", -1).length);
        model.append("\u001b[?1049l" + marker("D;0"));
        assertEquals("nano " + "filename".repeat(10), highlightedCommand(model.displaySnapshot()).replace("\n", ""));
        assertDisplayLinesFit(model.displaySnapshot(), 41);
    }

    @Test public void shellClearDisplayAndScrollbackBeginsANewVisibleCommandEpoch() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("echo old") + "old output\r\n" + marker("D;0"));
        prompt(model);
        model.append(commandMarker("clear") + "\u001b[H\u001b[2J\u001b[3J" + marker("D;0"));
        prompt(model);
        assertFalse(model.displaySnapshot().text().contains("echo old"));
        assertFalse(model.displaySnapshot().text().contains("old output"));
        assertFalse(model.displaySnapshot().text().contains("clear"));
        assertTrue(model.displaySnapshot().text().contains("root@guest"));
        model.append(commandMarker("echo new😀") + "new😀\r\n" + marker("D;0"));
        assertTrue(model.displaySnapshot().text().contains("echo new😀"));
    }

    @Test public void readlineHomeEraseClearsVisibleBlocksWhileKeepingGuestPromptRedraw() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("echo old") + "old\r\n" + marker("D;0"));
        prompt(model);
        model.append("\u001b[H\u001b[2J");
        prompt(model);
        assertFalse(model.displaySnapshot().text().contains("echo old"));
        assertTrue(model.displaySnapshot().text().contains("❯ "));
    }

    @Test public void alternateScreenEraseDoesNotClearPrimarySemanticCommandHistory() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("echo keep") + "keep\r\n" + marker("D;0"));
        prompt(model);
        model.append(commandMarker("top") + "\u001b[?1049h\u001b[H\u001b[2J\u001b[3Jtop-grid");
        assertEquals(model.snapshot().text(), model.displaySnapshot().text());
        model.append("\u001b[?1049l" + marker("D;0"));
        prompt(model);
        assertTrue(model.displaySnapshot().text().contains("echo keep"));
        assertFalse(model.displaySnapshot().text().contains("top-grid"));
    }

    @Test public void realVt220ClearHomeDefaultEraseStartsANewVisibleEpoch() {
        for (String erase : new String[]{"\u001b[J", "\u001b[0J"}) {
            TerminalScreenModel model = new TerminalScreenModel(41, 16);
            prompt(model);
            model.append(commandMarker("echo old😀") + "old output\r\n" + marker("D;0"));
            prompt(model);
            long epoch = model.displayEpoch();
            // Exact device trace shape: OSC133 C, then HOME/default ED, then output.
            model.append(commandMarker("clear; printf AFTER_REAL_CLEAR")
                    + "\u001b[H" + erase + "AFTER_REAL_CLEAR" + marker("D;0"));
            prompt(model);
            assertEquals(epoch + 1, model.displayEpoch());
            assertFalse(model.displaySnapshot().text().contains("echo old"));
            assertFalse(model.displaySnapshot().text().contains("old output"));
            assertFalse(model.displaySnapshot().text().contains("clear; printf"));
            assertTrue(model.displaySnapshot().text().contains("AFTER_REAL_CLEAR"));
            assertTrue(model.displaySnapshot().text().contains("root@guest"));
            assertFalse(model.displaySnapshot().text().contains("\ufffd"));
        }
    }

    @Test public void readlineHomeDefaultEraseClearsBlocksDuringInputPrompt() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("echo old") + "old\r\n" + marker("D;0"));
        prompt(model);
        long epoch = model.displayEpoch();
        model.append("\u001b[H\u001b[J");
        prompt(model);
        assertEquals(epoch + 1, model.displayEpoch());
        assertFalse(model.displaySnapshot().text().contains("echo old"));
        assertTrue(model.displaySnapshot().text().contains("❯ "));
    }

    @Test public void partialDefaultEraseAndOutputWholeDisplayRedrawDoNotClearCommittedBlocks() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("echo keep") + "keep\r\n" + marker("D;0"));
        prompt(model);
        long epoch = model.displayEpoch();
        model.append(commandMarker("printf clear-word") + "clear > ❯\r\n\u001b[2;2H\u001b[Jafter-partial");
        assertEquals(epoch, model.displayEpoch());
        assertTrue(model.displaySnapshot().text().contains("echo keep"));
        model.append("\u001b[H\u001b[2Jredraw");
        assertEquals(epoch, model.displayEpoch());
        assertTrue(model.displaySnapshot().text().contains("echo keep"));
    }

    @Test public void alternateHomeDefaultEraseKeepsPrimaryHistoryAndEpoch() {
        TerminalScreenModel model = new TerminalScreenModel(41, 16);
        prompt(model);
        model.append(commandMarker("echo keep") + "keep\r\n" + marker("D;0"));
        prompt(model);
        model.append(commandMarker("nano") + "\u001b[?1049h\u001b[Heditor");
        long epoch = model.displayEpoch();
        model.append("\u001b[H\u001b[Jnew-grid");
        assertEquals(epoch, model.displayEpoch());
        assertEquals(model.snapshot().text(), model.displaySnapshot().text());
        model.append("\u001b[?1049l" + marker("D;0"));
        prompt(model);
        assertTrue(model.displaySnapshot().text().contains("echo keep"));
        assertFalse(model.displaySnapshot().text().contains("new-grid"));
    }

    @Test public void scrollbackIsBoundedAndAlternateDoesNotLeakIntoIt() {
        TerminalScreenModel model = new TerminalScreenModel(40, 4);
        for (int i = 0; i < 5000; i++) model.append("line " + i + "\r\n");
        assertTrue(model.snapshot().historyRows <= 2000);
        String shell = model.snapshot().text();
        model.append("\u001b[?1049h");
        for (int i = 0; i < 20; i++) model.append("editor\r\n");
        assertEquals(0, model.snapshot().historyRows);
        model.append("\u001b[?1049l");
        assertEquals(shell, model.snapshot().text());
    }
}
