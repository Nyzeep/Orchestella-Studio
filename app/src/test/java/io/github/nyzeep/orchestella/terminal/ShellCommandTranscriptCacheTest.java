package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * W2-01: displaySnapshot 版本化缓存、(block, columns) 折行缓存、O(1) 淘汰行数
 * 与命令块行区间元数据。全部经 {@link TerminalScreenModel} 公开面驱动，纯 JVM 零设备。
 */
public final class ShellCommandTranscriptCacheTest {
    private static String marker(String value) { return "\u001b]133;" + value + "\u0007"; }
    private static String commandMarker(String command) {
        return marker("C;cmd=" + java.util.Base64.getEncoder().encodeToString(
                command.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private static void prompt(TerminalScreenModel model) {
        model.append(marker("A;orchestella=1") + "root@guest:~\r\n❯ " + marker("B"));
    }
    private static void runCommand(TerminalScreenModel model, String command, String output) {
        prompt(model);
        model.append(command + "\r\n" + commandMarker(command) + output + "\r\n" + marker("D;0"));
    }
    /** Strips the trailing blank rendered by the pane's cursor cell on the active snapshot line. */
    private static String withoutTrailingCursorCell(String text) {
        return text.replaceFirst(" +$", "");
    }

    @Test public void repeatedDisplaySnapshotsReuseTheCachedInstanceWithoutRebuilding() {
        TerminalScreenModel model = new TerminalScreenModel(40, 8);
        runCommand(model, "echo first", "first");
        TerminalScreenModel.Snapshot first = model.displaySnapshot();
        assertSame(first, model.displaySnapshot());
        model.append("more output");
        TerminalScreenModel.Snapshot rebuilt = model.displaySnapshot();
        assertNotSame(first, rebuilt);
        // 处女 pane 的快照含一个光标空格单元格（" "），归档文本之后跟 "\n\n "；
        // 追加输出后该空格单元被实际内容替换。行尾空格 = pane 光标所在列渲染出的空单元格。
        assertEquals(withoutTrailingCursorCell(first.text()) + "more output ", rebuilt.text());
    }

    @Test public void resizedModelRecomputesWrapsCursorAndRangesLikeANativeModel() {
        // 两个模型都在 40 列完成归档（echo 相同、存储文本一致）；一个先铺设 40 列缓存
        // 再 resize，一个直接 resize 后首算——缓存失效重折路径必须产出逐字节一致的快照。
        String command = "render --field long-value-that-must-wrap-across-several-display-lines";
        TerminalScreenModel cachedThenResized = new TerminalScreenModel(40, 10);
        runCommand(cachedThenResized, command, "ok");
        TerminalScreenModel.Snapshot before = cachedThenResized.displaySnapshot();
        cachedThenResized.resize(18, 10);
        TerminalScreenModel.Snapshot after = cachedThenResized.displaySnapshot();

        TerminalScreenModel neverWrapped = new TerminalScreenModel(40, 10);
        runCommand(neverWrapped, command, "ok");
        neverWrapped.resize(18, 10);
        TerminalScreenModel.Snapshot expected = neverWrapped.displaySnapshot();

        assertNotSame(before, after);
        assertEquals(expected.text(), after.text());
        assertEquals(expected.cursorOffset, after.cursorOffset);
        assertEquals(expected.evictedHistoryRows, after.evictedHistoryRows);
        assertEquals(expected.commandLineRanges(), after.commandLineRanges());
        assertFalse(after.commandLineRanges().isEmpty());
        // 折行确实按新 18 列口径重算（权威命令块带 "❯ " 前缀：2 列 + 16 个命令字符；
        // 前方还有 40 列网格折行的未剥离回显块）
        assertTrue(Arrays.asList(after.text().split("\n", -1)).contains("❯ render --field l"));
    }

    @Test public void evictedRowAccountingMatchesWrappedBlockRowsExactly() {
        TerminalScreenModel model = new TerminalScreenModel(40, 8);
        for (int i = 0; i < 10; i++) runCommand(model, "echo " + i, "out " + i);
        model.displaySnapshot();
        for (int i = 10; i < 34; i++) runCommand(model, "echo " + i, "out " + i);
        // 每个被淘汰块 "root@guest:~\n❯ echo N\n\nout N" 折行后 3 个换行，加块间隔 2 行
        assertEquals(10L, model.displaySnapshot().evictedHistoryRows);
        runCommand(model, "echo final", "out final");
        assertEquals(15L, model.displaySnapshot().evictedHistoryRows);
        TerminalScreenModel.Snapshot snapshot = model.displaySnapshot();
        String[] lines = snapshot.text().split("\n", -1);
        for (TerminalScreenModel.Snapshot.CommandRange range : snapshot.commandLineRanges()) {
            assertTrue(range.startLine() >= 0 && range.endLineExclusive() <= lines.length);
            assertTrue(range.startLine() < range.endLineExclusive());
        }
        assertTrue(lines[1].contains("❯ echo"));
    }

    @Test public void commandRangesTrackDisplayLinesOfArchivedCommandHeads() {
        TerminalScreenModel model = new TerminalScreenModel(40, 12);
        runCommand(model, "echo alpha", "alpha-out");
        runCommand(model, "printf %s long-command-payload-wrapping-past-the-terminal-width", "tail");
        TerminalScreenModel.Snapshot snapshot = model.displaySnapshot();
        String[] lines = snapshot.text().split("\n", -1);
        List<TerminalScreenModel.Snapshot.CommandRange> ranges = snapshot.commandLineRanges();
        assertEquals(2, ranges.size());
        // 块 1：两行提示头（banner + ❯ echo alpha），无折行
        assertEquals(0, ranges.get(0).startLine());
        assertEquals(2, ranges.get(0).endLineExclusive());
        assertTrue(lines[0].contains("root@guest:~"));
        assertTrue(lines[1].contains("❯ echo alpha"));
        // 第二条命令的回显在 40 列 pane 网格折行、无法按权威载荷剥离（W1-01 语义：
        // 未匹配回显保留为普通块），占 2 行；块 1(4 行) + 空行 + 回显块(2 行) + 空行
        // 之后，权威命令块从 line 8 开始
        assertTrue(lines[5].contains("printf %s long-command-payload-wrapping-"));
        assertTrue(lines[6].contains("past-the-terminal-width"));
        // 块 2：banner + 两行折行命令 + 块内空行 + 输出；区间只覆盖头 3 行
        assertEquals(8, ranges.get(1).startLine());
        assertEquals(11, ranges.get(1).endLineExclusive());
        assertTrue(lines[8].contains("root@guest:~"));
        String head = String.join("\n", Arrays.copyOfRange(lines, 8, 11));
        assertTrue(head.contains("printf %s long-command"));
        assertTrue(head.contains("terminal-width"));
        assertFalse(snapshot.text().contains("▌"));
        assertFalse(lines[11].contains("printf"));
        assertEquals("tail", withoutTrailingCursorCell(lines[12]));
    }

    @Test public void activeOutputPhaseExposesCommandHeadRangeBeforeOutputArrives() {
        TerminalScreenModel model = new TerminalScreenModel(40, 12);
        prompt(model);
        model.append("echo live\r\n" + commandMarker("echo live"));
        TerminalScreenModel.Snapshot pending = model.displaySnapshot();
        assertEquals(1, pending.commandLineRanges().size());
        assertEquals(0, pending.commandLineRanges().get(0).startLine());
        assertEquals(2, pending.commandLineRanges().get(0).endLineExclusive());
        String[] lines = pending.text().split("\n", -1);
        assertTrue(lines[1].contains("❯ echo live"));
        model.append("live\r\n");
        TerminalScreenModel.Snapshot streaming = model.displaySnapshot();
        assertEquals(1, streaming.commandLineRanges().size());
        assertEquals(2, streaming.commandLineRanges().get(0).endLineExclusive());
        // pending 快照尾部带处女 pane 的光标空格单元（" \n" 前）；流式输出后该单元
        // 被 "live" 行替换，光标行再渲染出一个空格。
        assertEquals(withoutTrailingCursorCell(pending.text()) + "live\n ", streaming.text());
    }
}
