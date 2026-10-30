package io.github.nyzeep.orchestella.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.GitHubUpdateChecker;
import org.junit.Test;

public final class UpdateCenterTest {

    private static final long NOW = 1_800_000_000_000L;

    private static final class MemoryStore implements UpdateStateStore {
        long checkedAt;
        String version;
        String summary;
        String page;
        String ignored;

        @Override public long lastCheckedAt() { return checkedAt; }
        @Override public void recordCheckAttempt(long nowMillis) { checkedAt = nowMillis; }

        @Override
        public void recordResult(long nowMillis, String version, String summary, String pageUrl) {
            checkedAt = nowMillis;
            this.version = version;
            this.summary = summary;
            this.page = pageUrl;
        }

        @Override public String cachedVersion() { return version; }
        @Override public String cachedSummary() { return summary == null ? "" : summary; }
        @Override public String cachedPageUrl() { return page == null ? "" : page; }
        @Override public String ignoredVersion() { return ignored; }
        @Override public void setIgnoredVersion(String version) { this.ignored = version; }
    }

    @Test
    public void summaryKeepsTheSubstanceAndDropsPageFurniture() {
        String body = "> **0.6.0 用户可以直接更新**：设置里点检查更新。\n\n"
                + "## 这次修的是什么\n\n"
                + "- **六种发行版下载失败**：`lxc-images` 只保留三天，清单里的路径全部 404。\n"
                + "- 见 [发布页](https://example.com/releases) 与 ![badge](https://example.com/b.svg)\n"
                + "| 文件 | 大小 |\n| --- | --- |\n";

        String summary = UpdateNotes.summarize(body);

        assertFalse("block quotes are meta, not changelog", summary.contains("0.6.0 用户可以直接更新"));
        assertFalse("headings are dropped", summary.contains("这次修的是什么"));
        assertTrue(summary.contains("六种发行版下载失败"));
        assertTrue("inline code markers are stripped", summary.contains("lxc-images") && !summary.contains("`"));
        assertTrue("links keep their text", summary.contains("发布页") && !summary.contains("https://"));
        assertFalse("tables are dropped", summary.contains("| ---"));
    }

    @Test
    public void summaryIsBoundedAndMarksTruncation() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            body.append("- 第 ").append(i).append(" 条修复说明，长度足够触发截断。\n");
        }
        String summary = UpdateNotes.summarize(body.toString());
        assertTrue("must respect the line budget", summary.split("\n").length <= UpdateNotes.DEFAULT_MAX_LINES);
        assertTrue("must respect the character budget", summary.length() <= UpdateNotes.DEFAULT_MAX_CHARS + 1);
        assertTrue("truncation is visible", summary.endsWith("…"));
    }

    @Test
    public void blankNotesYieldNoSummary() {
        assertEquals("", UpdateNotes.summarize(null));
        assertEquals("", UpdateNotes.summarize("   \n\n> only meta\n## heading\n"));
    }

    @Test
    public void nothingToShowBeforeAnyCheck() {
        assertNull(new UpdateCenter(new MemoryStore()).pending("0.6.1"));
    }

    @Test
    public void onlyAStrictlyNewerVersionIsOffered() {
        MemoryStore store = new MemoryStore();
        UpdateCenter center = new UpdateCenter(store);

        center.record(GitHubUpdateChecker.UpdateResult.updateAvailable(
                "v0.6.2", "- 修复镜像下载", "https://example.com/v0.6.2"), NOW);
        UpdateCenter.Available pending = center.pending("0.6.1");
        assertNotNull(pending);
        assertEquals("v0.6.2", pending.version());
        assertEquals("https://example.com/v0.6.2", pending.pageUrl());
        assertTrue(pending.summary().contains("修复镜像下载"));

        assertNull("the same version is never announced", center.pending("0.6.2"));
        assertNull("a downgrade is never announced", center.pending("0.7.0"));
    }

    @Test
    public void ignoringOneVersionHidesItUntilSomethingNewerArrives() {
        MemoryStore store = new MemoryStore();
        UpdateCenter center = new UpdateCenter(store);
        center.record(GitHubUpdateChecker.UpdateResult.updateAvailable("v0.6.2", "", null), NOW);

        center.ignore("v0.6.2");
        assertNull(center.pending("0.6.1"));

        center.record(GitHubUpdateChecker.UpdateResult.updateAvailable("v0.6.3", "", null), NOW + 1);
        assertNotNull("a newer release re-surfaces the badge", center.pending("0.6.1"));
        assertEquals("v0.6.3", center.pending("0.6.1").version());
    }

    @Test
    public void pageUrlFallsBackToThePinnedRepository() {
        MemoryStore store = new MemoryStore();
        UpdateCenter center = new UpdateCenter(store);
        center.record(GitHubUpdateChecker.UpdateResult.updateAvailable("v0.6.2", "", null), NOW);

        assertEquals("https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.6.2",
                center.pending("0.6.1").pageUrl());
    }

    @Test
    public void aFailedCheckKeepsTheLastKnownAnswerAndOnlyMovesTheThrottle() {
        MemoryStore store = new MemoryStore();
        UpdateCenter center = new UpdateCenter(store, 1000L);
        center.record(GitHubUpdateChecker.UpdateResult.updateAvailable("v0.6.2", "- 修复", null), NOW);

        center.record(GitHubUpdateChecker.UpdateResult.error("网络不可用"), NOW + 50);

        assertEquals("the badge survives a transient failure", "v0.6.2", center.pending("0.6.1").version());
        assertEquals("the attempt still counts for throttling", NOW + 50, store.lastCheckedAt());
    }

    @Test
    public void aCurrentAppClearsTheCachedUpdate() {
        MemoryStore store = new MemoryStore();
        UpdateCenter center = new UpdateCenter(store);
        center.record(GitHubUpdateChecker.UpdateResult.updateAvailable("v0.6.2", "", null), NOW);
        center.record(GitHubUpdateChecker.UpdateResult.upToDate("0.6.2"), NOW + 1);
        assertNull(center.pending("0.6.1"));
    }

    @Test
    public void disabledChecksPersistNothing() {
        MemoryStore store = new MemoryStore();
        new UpdateCenter(store).record(GitHubUpdateChecker.UpdateResult.disabled(), NOW);
        assertEquals("zero telemetry also means zero local writes", 0L, store.lastCheckedAt());
        assertNull(store.cachedVersion());
    }

    @Test
    public void automaticChecksAreThrottledButManualOnesAreNot() {
        MemoryStore store = new MemoryStore();
        UpdateCenter center = new UpdateCenter(store, 60_000L);

        assertTrue("never checked yet", center.shouldCheck(NOW));
        center.record(GitHubUpdateChecker.UpdateResult.upToDate("0.6.1"), NOW);
        assertFalse(center.shouldCheck(NOW + 59_999L));
        assertTrue(center.shouldCheck(NOW + 60_000L));
    }
}
