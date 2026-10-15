package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReleaseSelectorTest {
    @Test
    public void buildMetadataDeterminesChannelWithoutAssumingAllZeroMajorsArePreview() {
        assertEquals(ReleaseSelector.Channel.STABLE, ReleaseSelector.channelForBuild("0.4.3", false));
        assertEquals(ReleaseSelector.Channel.STABLE, ReleaseSelector.channelForBuild("1.0.0", false));
        assertEquals(ReleaseSelector.Channel.PREVIEW, ReleaseSelector.channelForBuild("0.4.3", true));
        assertEquals(ReleaseSelector.Channel.PREVIEW, ReleaseSelector.channelForBuild("1.0.0", true));
        assertEquals(ReleaseSelector.Channel.PREVIEW, ReleaseSelector.channelForBuild("0.4.3-rc.1", false));
        assertEquals(ReleaseSelector.Channel.STABLE, ReleaseSelector.channelForBuild(null, false));
    }

    @Test
    public void highestVersionWinsRegardlessOfResponseOrderAndPublicationDates() {
        String low = release("v0.9.0", false, false, "Low");
        String high = release("v0.10.0", false, false, "High");
        String middle = release("v0.9.9", false, false, "Middle");
        assertEquals("v0.10.0", select("[" + low + "," + high + "," + middle + "]", false).tag());
        assertEquals("v0.10.0", select("[" + middle + "," + low + "," + high + "]", false).tag());
    }

    @Test
    public void stableFiltersBothGitHubPrereleaseFlagAndSemVerPrereleaseSuffix() {
        String stable = release("v0.5.0", false, false, "Stable");
        String preview = release("v0.6.0", false, true, "Preview");
        String mislabeled = release("v0.7.0-rc.1", false, false, "RC");
        String draft = release("v9.0.0", true, false, "Draft");
        String releases = "[" + draft + "," + preview + "," + mislabeled + "," + stable + "]";
        assertEquals("v0.5.0", select(releases, false).tag());
        assertEquals("v0.7.0-rc.1", select(releases, true).tag());
    }

    @Test
    public void releasesMustHaveExplicitBooleanFlags() {
        String good = release("v0.5.0", false, false, "Stable");
        for (String invalid : new String[] {good.replace("\"draft\":false,", ""),
                good.replace("\"prerelease\":false,", ""), good.replace("\"draft\":false", "\"draft\":\"false\""),
                good.replace("\"prerelease\":false", "\"prerelease\":null")}) {
            assertNull(select("[" + invalid + "]", true));
        }
    }

    @Test
    public void badTagDoesNotHideOtherValidReleases() {
        for (String tag : new String[] {"", "v", "vv1.0.0", "v0.05.0", "nightly", "v1.0.0-01"}) {
            String releases = "[" + release(tag, false, false, "Bad") + ","
                    + release("v0.5.0", false, false, "Good") + "]";
            assertEquals("v0.5.0", select(releases, true).tag());
        }
    }

    @Test
    public void noDowngradeOrSamePrecedenceRebuildIsOffered() {
        for (String tag : new String[] {"v0.4.2", "v0.4.3", "v0.4.3-rc.99", "v0.4.3+rebuild.2"}) {
            assertNull(select("[" + release(tag, false, true, "Already installed or older") + "]", true));
        }
        assertNull(select("[]", true));
    }

    @Test
    public void rejectsReleaseSourcesAndPagesOutsidePinnedRepository() {
        String good = release("v0.5.0", false, false, "Good");
        String api = ReleaseSelector.RELEASES_API + "/42";
        String page = ReleaseSelector.RELEASES_PAGE + "/tag/v0.5.0";
        for (String source : new String[] {"http://api.github.com/repos/Nyzeep/Orchestella-Studio/releases/42",
                "https://api.github.com/repos/attacker/Orchestella-Studio/releases/42", api + "?token=synthetic",
                api + "/assets", api + "#fragment", api.replace("/42", "/0"),
                "https://api.github.com.evil.invalid/repos/Nyzeep/Orchestella-Studio/releases/42"}) {
            assertNull(select("[" + good.replace(api, source) + "]", true));
        }
        for (String destination : new String[] {"https://example.invalid/download.apk",
                page.replace("Orchestella-Studio", "other"), page + "?token=synthetic",
                page + "#fragment", "intent://download", "file:///data/private/.env",
                "https://github.com@evil.invalid/Nyzeep/Orchestella-Studio/releases/tag/v0.5.0"}) {
            assertNull(select("[" + good.replace(page, destination) + "]", true));
        }
        assertNull(select("[" + good.replace("\"url\":\"" + api + "\",", "") + "]", true));
        assertNull(select("[" + good.replace("\"html_url\":\"" + page + "\",", "") + "]", true));
    }

    @Test
    public void nestedAssetUrlsAreNeverUsedAsReleaseOrDownloadDestinations() {
        String asset = "{\"url\":\"https://example.invalid/upload\",\"tag_name\":\"v99.0.0\","
                + "\"html_url\":\"https://example.invalid/secret\","
                + "\"browser_download_url\":\"https://example.invalid/arbitrary.apk?token=synthetic\"}";
        String good = release("v0.5.0", false, true, "Notes");
        String json = "[" + good.replace("{", "{\"assets\":[" + asset + "],") + "]";
        ReleaseSelector.Release selected = select(json, true);
        assertNotNull(selected);
        assertEquals("v0.5.0", selected.tag());
        assertEquals(ReleaseSelector.RELEASES_PAGE + "/tag/v0.5.0", selected.pageUrl());
        assertFalse(selected.pageUrl().contains("example.invalid"));
        String noOwnSource = good.replace("\"url\":\"" + ReleaseSelector.RELEASES_API + "/42\",", "");
        assertNull(select("[" + noOwnSource.replace("{", "{\"assets\":[" + asset + "],") + "]", true));
    }

    @Test
    public void correctlyDecodesEscapedNotesInsteadOfMatchingNestedOrQuotedFields() {
        String notes = "Line 1\\n\\u4e2d\\u6587 \\\"tag_name\\\": \\\"v99.0.0\\\" \\\\path";
        ReleaseSelector.Release selected = select("[" + release("v0.5.0", false, false, notes) + "]", false);
        assertEquals("Line 1\n中文 \"tag_name\": \"v99.0.0\" \\path", selected.notes());
        assertEquals("v0.5.0", selected.tag());
        assertEquals("", select("[" + release("v0.5.0", false, false, "Notes")
                .replace("\"body\":\"Notes\"", "\"body\":null") + "]", false).notes());
    }

    @Test
    public void malformedJsonFailsClosedWithoutIncludingResponseContent() {
        String good = release("v0.5.0", false, false, "Notes");
        for (String invalid : new String[] {"", "null", "{}", "[", "[" + good, "[" + good + ",]",
                "[" + good + "]synthetic-secret", "[" + good.replace("\"draft\":false", "\"draft\":false,\"draft\":true") + "]",
                "[" + good.replace("Notes", "bad\\q") + "]", "[" + good.replace("Notes", "unescaped\nnewline") + "]"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> select(invalid, true));
            assertFalse(error.getMessage().contains("synthetic-secret"));
        }
    }

    @Test
    public void boundsJsonDepthCountAndResponseSize() {
        assertThrows(IllegalArgumentException.class, () -> select("[".repeat(34) + "]".repeat(34), true));
        assertThrows(IllegalArgumentException.class, () -> select(" ".repeat(ReleaseSelector.MAX_RESPONSE_BYTES + 1), true));
        assertThrows(IllegalArgumentException.class, () -> select("[" + "null,".repeat(100) + "null]", true));
        assertTrue(select("[]", true) == null);
    }

    private static ReleaseSelector.Release select(String json, boolean preview) {
        return ReleaseSelector.select(json, "0.4.3", preview ? ReleaseSelector.Channel.PREVIEW : ReleaseSelector.Channel.STABLE);
    }

    private static String release(String tag, boolean draft, boolean prerelease, String escapedNotes) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease + ","
                + "\"url\":\"" + ReleaseSelector.RELEASES_API + "/42\","
                + "\"html_url\":\"" + ReleaseSelector.RELEASES_PAGE + "/tag/" + tag + "\","
                + "\"body\":\"" + escapedNotes + "\"}";
    }
}
