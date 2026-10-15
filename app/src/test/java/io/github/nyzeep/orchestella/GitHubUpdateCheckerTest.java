package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class GitHubUpdateCheckerTest {
    @Test
    public void disabledUpdateCheckMakesZeroNetworkCalls() {
        SettingsManager settings = settings(false);
        AtomicInteger calls = new AtomicInteger();
        GitHubUpdateChecker checker = new GitHubUpdateChecker(
                GitHubUpdateChecker.DEFAULT_RELEASES_API_URL, settings, true, url -> {
                    calls.incrementAndGet();
                    throw new IOException("must never open");
                });

        assertEquals(GitHubUpdateChecker.Status.DISABLED, checker.checkForUpdate("0.4.2").status());
        assertEquals(0, calls.get());
    }

    @Test
    public void explicitForcePreservesManualCheckWhenSettingIsOff() throws Exception {
        FakeConnection response = new FakeConnection("[" + release("v0.4.4", false, true) + "]");
        AtomicInteger calls = new AtomicInteger();
        GitHubUpdateChecker checker = new GitHubUpdateChecker(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL,
                settings(false), true, url -> { calls.incrementAndGet(); return response; });

        assertEquals(GitHubUpdateChecker.Status.UPDATE_AVAILABLE, checker.checkForUpdate("0.4.3", true).status());
        assertEquals(1, calls.get());
    }

    @Test
    public void versionComparisonLogic() {
        assertTrue(GitHubUpdateChecker.isNewerVersion("0.2.0", "0.1.0"));
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.0.0", "0.9.9"));
        assertTrue(GitHubUpdateChecker.isNewerVersion("0.1.0", "0.1.0-dev"));
        assertFalse(GitHubUpdateChecker.isNewerVersion("0.1.0", "0.1.0"));
        assertFalse(GitHubUpdateChecker.isNewerVersion("0.4.1", "0.4.3-dev"));
        assertFalse(GitHubUpdateChecker.isNewerVersion("", "0.1.0"));
        assertFalse(GitHubUpdateChecker.isNewerVersion(null, "0.1.0"));
        assertFalse(GitHubUpdateChecker.isNewerVersion("0.4.4", null));
        assertFalse(GitHubUpdateChecker.isNewerVersion("0.4.4+new", "0.4.4+old"));
    }

    @Test
    public void reportsUpdateAvailableWhenNewerVersionExists() throws Exception {
        try (ServerSocket server = serve(200, "[" + release("v0.2.0", false, false) + "]")) {
            GitHubUpdateChecker.UpdateResult result = localChecker(server, false).checkForUpdate("0.1.0");
            assertEquals(GitHubUpdateChecker.Status.UPDATE_AVAILABLE, result.status());
            assertEquals("v0.2.0", result.latestVersion());
            assertEquals("Release notes", result.releaseNotes());
            assertEquals(ReleaseSelector.RELEASES_PAGE + "/tag/v0.2.0", result.releaseUrl());
        }
    }

    @Test
    public void reportsUpToDateWhenVersionMatches() throws Exception {
        try (ServerSocket server = serve(200, "[" + release("v0.1.0", false, false) + "]")) {
            GitHubUpdateChecker.UpdateResult result = localChecker(server, false).checkForUpdate("0.1.0");
            assertEquals(GitHubUpdateChecker.Status.UP_TO_DATE, result.status());
            assertEquals("0.1.0", result.latestVersion());
        }
    }

    @Test
    public void olderReleaseDoesNotOfferADowngradeThroughCheckForUpdate() throws Exception {
        try (ServerSocket server = serve(200, "[" + release("v0.4.1", false, false) + "]")) {
            GitHubUpdateChecker.UpdateResult result = localChecker(server, true).checkForUpdate("0.4.2");
            assertEquals("an older GitHub release must never be offered as an update",
                    GitHubUpdateChecker.Status.UP_TO_DATE, result.status());
            assertEquals("0.4.2", result.latestVersion());
        }
    }

    @Test
    public void selectsNewestEligiblePreviewAcrossUnsortedReleaseList() throws Exception {
        String json = "[" + release("v9.0.0", true, false) + ","
                + release("v0.4.1", false, false) + "," + release("v0.4.4", false, true) + "]";
        try (ServerSocket server = serve(200, json)) {
            GitHubUpdateChecker.UpdateResult result = localChecker(server, true).checkForUpdate("0.4.2");
            assertEquals(GitHubUpdateChecker.Status.UPDATE_AVAILABLE, result.status());
            assertEquals("v0.4.4", result.latestVersion());
        }
    }

    @Test
    public void stableBuildCannotSilentlySubscribeToPreviews() throws Exception {
        FakeConnection response = new FakeConnection("[" + release("v0.5.0", false, true) + ","
                + release("v0.6.0-rc.1", false, false) + "," + release("v0.4.3", false, false) + "]");
        assertEquals(GitHubUpdateChecker.Status.UP_TO_DATE, checker(response, false).checkForUpdate("0.4.3").status());
    }

    @Test
    public void previewBuildCannotDowngradeToOlderOrSameCorePrerelease() throws Exception {
        FakeConnection response = new FakeConnection("[" + release("v0.4.2", false, true) + ","
                + release("v0.4.3-rc.9", false, true) + "," + release("v0.4.3+rebuilt", false, true) + "]");
        assertEquals(GitHubUpdateChecker.Status.UP_TO_DATE, checker(response, true).checkForUpdate("0.4.3").status());
    }

    @Test
    public void explicitlyInstalledPrereleaseCanSeeNewerPreviewWithoutBuildFlag() throws Exception {
        FakeConnection response = new FakeConnection("[" + release("v0.4.3-rc.2", false, true) + "]");
        assertEquals(GitHubUpdateChecker.Status.UPDATE_AVAILABLE,
                checker(response, false).checkForUpdate("0.4.3-rc.1").status());
    }

    @Test
    public void invalidInstalledVersionStopsBeforeNetwork() {
        for (String version : new String[] {null, "", "broken", "0.4", "0.04.3", "0.4.3\r\nX-Injected: yes"}) {
            GitHubUpdateChecker checker = new GitHubUpdateChecker(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL,
                    settings(true), true, url -> { throw new AssertionError("invalid version opened a connection"); });
            assertEquals(GitHubUpdateChecker.Status.ERROR, checker.checkForUpdate(version).status());
        }
    }

    @Test
    public void unapprovedApiUrlsStopBeforeOpeningAConnection() {
        String base = ReleaseSelector.RELEASES_API;
        for (String endpoint : new String[] {"https://example.invalid/releases", base.replace("https:", "http:"),
                base.replace("Orchestella-Studio", "other-repository"), base + "/latest",
                base + "?per_page=100&access_token=synthetic-sentinel", "https://api.github.com.evil.invalid/releases"}) {
            GitHubUpdateChecker checker = new GitHubUpdateChecker(endpoint, settings(true), true,
                    url -> { throw new AssertionError("unapproved endpoint contacted"); });
            assertEquals(GitHubUpdateChecker.Status.ERROR, checker.checkForUpdate("0.4.3").status());
        }
    }

    @Test
    public void usesOnlyPinnedGetAndBoundedTimeoutsWithoutRedirects() throws Exception {
        FakeConnection response = new FakeConnection("[]");
        checker(response, false).checkForUpdate("0.4.3");
        assertEquals("GET", response.getRequestMethod());
        assertEquals("application/vnd.github+json", response.getRequestProperty("Accept"));
        assertEquals("Orchestella-Studio-Android/0.4.3", response.getRequestProperty("User-Agent"));
        assertEquals(2, response.getRequestProperties().size());
        assertEquals(10_000, response.getConnectTimeout());
        assertEquals(10_000, response.getReadTimeout());
        assertFalse(response.getInstanceFollowRedirects());
        assertFalse(response.getDoOutput());
        assertTrue(response.disconnected);
    }

    @Test
    public void rejectsRedirectWithoutFollowingServerLocation() throws Exception {
        FakeConnection response = new FakeConnection("[]");
        response.code = 302;
        response.location = "https://example.invalid/upload?token=synthetic-sentinel";
        assertEquals(GitHubUpdateChecker.Status.ERROR, checker(response, true).checkForUpdate("0.4.3").status());
        assertFalse(response.getInstanceFollowRedirects());
        assertEquals(0, response.bodyReads);
        assertTrue(response.disconnected);
    }

    @Test
    public void reportsErrorOnServerHttpError() throws Exception {
        try (ServerSocket server = serve(500, "synthetic-response-sentinel")) {
            GitHubUpdateChecker.UpdateResult result = localChecker(server, true).checkForUpdate("0.4.3");
            assertEquals(GitHubUpdateChecker.Status.ERROR, result.status());
            assertNotNull(result.errorMessage());
            assertFalse(result.errorMessage().contains("synthetic-response-sentinel"));
        }
    }

    @Test
    public void malformedResponseDoesNotLeakContentOrCreateAnUpdate() throws Exception {
        for (String json : new String[] {"", "{\"tag_name\":\"v9.0.0\"}", "[\"synthetic-secret", "null"}) {
            GitHubUpdateChecker.UpdateResult result = checker(new FakeConnection(json), true).checkForUpdate("0.4.3");
            assertEquals(GitHubUpdateChecker.Status.ERROR, result.status());
            assertFalse(result.errorMessage().contains("synthetic-secret"));
        }
    }

    @Test
    public void emptyOrBadTagsNeverOfferAnUpdate() throws Exception {
        FakeConnection response = new FakeConnection("[" + release("", false, false) + ","
                + release("broken", false, false) + "," + release("v0.04.4", false, false) + "]");
        GitHubUpdateChecker.UpdateResult result = checker(response, true).checkForUpdate("0.4.3");
        assertEquals(GitHubUpdateChecker.Status.UP_TO_DATE, result.status());
        assertEquals("0.4.3", result.latestVersion());
    }

    @Test
    public void incompletePaginatedListReturnsErrorWithoutFollowingLink() throws Exception {
        FakeConnection response = new FakeConnection("[]");
        response.link = "<https://example.invalid/unapproved>; rel=\"next\"";
        assertEquals(GitHubUpdateChecker.Status.ERROR, checker(response, true).checkForUpdate("0.4.3").status());
        assertEquals(0, response.bodyReads);
    }

    @Test
    public void boundsDeclaredAndStreamedResponseSize() throws Exception {
        FakeConnection declared = new FakeConnection("[]");
        declared.length = ReleaseSelector.MAX_RESPONSE_BYTES + 1L;
        assertEquals(GitHubUpdateChecker.Status.ERROR, checker(declared, true).checkForUpdate("0.4.3").status());
        assertEquals(0, declared.bodyReads);
        FakeConnection streamed = new FakeConnection(" ".repeat(ReleaseSelector.MAX_RESPONSE_BYTES + 1));
        assertEquals(GitHubUpdateChecker.Status.ERROR, checker(streamed, true).checkForUpdate("0.4.3").status());
        assertTrue(streamed.disconnected);
    }

    @Test
    public void failedTransportIsNotRetriedOrLeaked() {
        AtomicInteger calls = new AtomicInteger();
        GitHubUpdateChecker checker = new GitHubUpdateChecker(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL,
                settings(true), true, url -> {
                    calls.incrementAndGet();
                    throw new IOException("synthetic-secret-at-internal.invalid");
                });
        GitHubUpdateChecker.UpdateResult result = checker.checkForUpdate("0.4.3");
        assertEquals(GitHubUpdateChecker.Status.ERROR, result.status());
        assertFalse(result.errorMessage().contains("synthetic-secret"));
        assertEquals(1, calls.get());
    }

    @Test
    public void uncheckedTransportErrorsCannotLeakServerContent() {
        GitHubUpdateChecker checker = new GitHubUpdateChecker(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL,
                settings(true), true, url -> {
                    throw new IllegalArgumentException("synthetic-secret-from-transport");
                });
        GitHubUpdateChecker.UpdateResult result = checker.checkForUpdate("0.4.3");
        assertEquals(GitHubUpdateChecker.Status.ERROR, result.status());
        assertFalse(result.errorMessage().contains("synthetic-secret"));
    }

    private static SettingsManager settings(boolean enabled) {
        SettingsManager settings = new SettingsManager(new FakeSharedPreferences());
        settings.setUpdateCheckEnabled(enabled);
        return settings;
    }

    private static GitHubUpdateChecker checker(FakeConnection response, boolean preview) {
        return new GitHubUpdateChecker(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL, settings(true), preview, url -> {
            assertEquals(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL, url.toExternalForm());
            return response;
        });
    }

    private static GitHubUpdateChecker localChecker(ServerSocket server, boolean preview) {
        return new GitHubUpdateChecker(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL, settings(true), preview,
                url -> (HttpURLConnection) new URL("http://127.0.0.1:" + server.getLocalPort() + "/releases").openConnection());
    }

    private static String release(String tag, boolean draft, boolean prerelease) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease
                + ",\"url\":\"" + ReleaseSelector.RELEASES_API + "/1\",\"body\":\"Release notes\","
                + "\"html_url\":\"" + ReleaseSelector.RELEASES_PAGE + "/tag/" + tag + "\"}";
    }

    private static ServerSocket serve(int code, String body) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread thread = new Thread(() -> serveHttp(server, code, body));
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static void serveHttp(ServerSocket server, int statusCode, String bodyText) {
        try (Socket client = server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Read only this synthetic local HTTP request, never a production response.
            }
            byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
            String status = statusCode == 200 ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 500 Internal Error\r\n";
            out.write((status + "Content-Type: application/json; charset=utf-8\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
            // The client assertions fail if the synthetic server did not respond.
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final byte[] body;
        int code = 200;
        long length = -1;
        String link;
        String location;
        int bodyReads;
        boolean disconnected;

        FakeConnection(String body) throws Exception {
            super(new URL(GitHubUpdateChecker.DEFAULT_RELEASES_API_URL));
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override public int getResponseCode() { return code; }
        @Override public long getContentLengthLong() { return length; }
        @Override public String getHeaderField(String name) {
            return "Link".equals(name) ? link : "Location".equals(name) ? location : null;
        }
        @Override public InputStream getInputStream() { bodyReads++; return new ByteArrayInputStream(body); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
}
