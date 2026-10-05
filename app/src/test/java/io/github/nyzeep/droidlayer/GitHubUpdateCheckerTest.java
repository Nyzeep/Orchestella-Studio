package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class GitHubUpdateCheckerTest {

    @Test
    public void disabledUpdateCheckMakesZeroNetworkCalls() {
        SettingsManager settings = new SettingsManager(new FakeSharedPreferences());
        settings.setUpdateCheckEnabled(false);

        // Point to an invalid port that would throw ConnectException if contacted
        GitHubUpdateChecker checker = new GitHubUpdateChecker("http://127.0.0.1:1/invalid", settings);
        GitHubUpdateChecker.UpdateResult result = checker.checkForUpdate("0.1.0");

        assertEquals("status must be DISABLED", GitHubUpdateChecker.Status.DISABLED, result.status());
        assertNotNull("error/reason message must be present", result.errorMessage());
    }

    @Test
    public void versionComparisonLogic() {
        assertTrue(GitHubUpdateChecker.isNewerVersion("0.2.0", "0.1.0"));
        assertTrue(GitHubUpdateChecker.isNewerVersion("1.0.0", "0.9.9"));
        assertTrue(GitHubUpdateChecker.isNewerVersion("0.1.0", "0.1.0-dev"));
        assertFalse(GitHubUpdateChecker.isNewerVersion("0.1.0", "0.1.0"));
        assertFalse(GitHubUpdateChecker.isNewerVersion("", "0.1.0"));
    }

    @Test
    public void reportsUpdateAvailableWhenNewerVersionExists() throws Exception {
        String json = "{\n"
                + "  \"tag_name\": \"v0.2.0\",\n"
                + "  \"name\": \"v0.2.0 Release\",\n"
                + "  \"body\": \"Ticket 35 hardening features\",\n"
                + "  \"html_url\": \"https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.2.0\"\n"
                + "}";

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread thread = new Thread(() -> serveHttp(server, 200, json));
            thread.setDaemon(true);
            thread.start();

            SettingsManager settings = new SettingsManager(new FakeSharedPreferences());
            settings.setUpdateCheckEnabled(true);

            GitHubUpdateChecker checker =
                    new GitHubUpdateChecker("http://127.0.0.1:" + port + "/release", settings);
            GitHubUpdateChecker.UpdateResult result = checker.checkForUpdate("0.1.0");

            assertEquals(GitHubUpdateChecker.Status.UPDATE_AVAILABLE, result.status());
            assertEquals("v0.2.0", result.latestVersion());
            assertEquals("Ticket 35 hardening features", result.releaseNotes());
            assertEquals("https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.2.0", result.releaseUrl());
        }
    }

    @Test
    public void reportsUpToDateWhenVersionMatches() throws Exception {
        String json = "{\n"
                + "  \"tag_name\": \"v0.1.0\",\n"
                + "  \"body\": \"Release notes\",\n"
                + "  \"html_url\": \"https://github.com/Nyzeep/Orchestella-Studio/releases/tag/v0.1.0\"\n"
                + "}";

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread thread = new Thread(() -> serveHttp(server, 200, json));
            thread.setDaemon(true);
            thread.start();

            SettingsManager settings = new SettingsManager(new FakeSharedPreferences());
            settings.setUpdateCheckEnabled(true);

            GitHubUpdateChecker checker =
                    new GitHubUpdateChecker("http://127.0.0.1:" + port + "/release", settings);
            GitHubUpdateChecker.UpdateResult result = checker.checkForUpdate("0.1.0");

            assertEquals(GitHubUpdateChecker.Status.UP_TO_DATE, result.status());
        }
    }

    @Test
    public void reportsErrorOnServerHttpError() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread thread = new Thread(() -> serveHttp(server, 500, "Internal Server Error"));
            thread.setDaemon(true);
            thread.start();

            SettingsManager settings = new SettingsManager(new FakeSharedPreferences());
            settings.setUpdateCheckEnabled(true);

            GitHubUpdateChecker checker =
                    new GitHubUpdateChecker("http://127.0.0.1:" + port + "/release", settings);
            GitHubUpdateChecker.UpdateResult result = checker.checkForUpdate("0.1.0");

            assertEquals(GitHubUpdateChecker.Status.ERROR, result.status());
            assertNotNull(result.errorMessage());
        }
    }

    private static void serveHttp(ServerSocket server, int statusCode, String bodyText) {
        try (Socket client = server.accept();
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = client.getOutputStream()) {
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // read request headers
            }
            byte[] bodyBytes = bodyText.getBytes(StandardCharsets.UTF_8);
            String statusLine = statusCode == 200 ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 500 Internal Error\r\n";
            String headers = statusLine
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
