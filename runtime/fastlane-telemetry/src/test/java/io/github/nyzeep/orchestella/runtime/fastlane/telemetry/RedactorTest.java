package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RedactorTest {
    @Test
    public void opaqueIdAcceptsBoundedIdentifiers() {
        assertEquals("i-abc123", Redactor.opaqueId("i-abc123"));
        assertEquals("0f8fad5b-d9cb-469f-a165-70867728950e",
                Redactor.opaqueId("0f8fad5b-d9cb-469f-a165-70867728950e"));
    }

    @Test
    public void opaqueIdRejectsPathsUrlsAddressesAndTokenShapes() {
        for (String value : new String[] {
                "/data/user/0/app", "a/b", "user@example.com", "https://example.com",
                "a b", "", "..", "a:b", "192.168.1.1",
                "AKIAIOSFODNN7EXAMPLE", "ghp_16C7e42F292c6912E7710c838347Ae178B4a",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0"}) {
            assertThrows("accepted: " + value,
                    IllegalArgumentException.class, () -> Redactor.opaqueId(value));
        }
    }

    @Test
    public void reasonCodeIsUpperCaseAndBounded() {
        assertEquals("PAYLOAD_VERIFY_FAILED", Redactor.reasonCode("PAYLOAD_VERIFY_FAILED"));
        assertThrows(IllegalArgumentException.class, () -> Redactor.reasonCode("payload-failed"));
        assertThrows(IllegalArgumentException.class, () -> Redactor.reasonCode("A-B"));
    }

    @Test
    public void redactsTokensPasswordsAndAuthorization() {
        String token = Redactor.redact("bridge token=abc123 called");
        assertFalse(token.contains("abc123"));
        assertTrue(token.contains("[redacted]"));

        String password = Redactor.redact("password=hunter2");
        assertFalse(password.contains("hunter2"));

        String bearer = Redactor.redact("Authorization: Bearer abc.def.ghi");
        assertFalse(bearer.contains("abc.def.ghi"));

        assertNoSecret("access_token=SECRETVALUE123", "SECRETVALUE123");
        assertNoSecret("token := SECRETVALUE123", "SECRETVALUE123");
        assertNoSecret("--password hunter2", "hunter2");
        assertNoSecret("Authorization: Basic dXNlcjpwYXNz", "dXNlcjpwYXNz");
        assertNoSecret("Bearer \"quotedtoken\"", "quotedtoken");
        assertNoSecret("ghp_16C7e42F292c6912E7710c838347Ae178B4a", "ghp_16C7e42");
        assertNoSecret("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0", "eyJhbGciOiJIUzI1NiJ9");
    }

    @Test
    public void redactsSchemeLessUserInfoAndPasswordsContainingSlashes() {
        assertNoSecret("alice:s3cr3t@localhost", "s3cr3t");
        assertNoSecret("https://alice:pa/ss@db.internal/x", "pa/ss");
        assertNoSecret("https://alice:s3cr3t@example.com/repo", "s3cr3t");
    }

    @Test
    public void redactsPathsWithSpacesAndCommaPrefix() {
        assertNoSecret("open /data/user/0/my app/secret.db", "/data/user/0/my app");
        assertNoSecret("path=,/data/user/0/app/secret", "/data/user/0/app");
    }

    @Test
    public void harmlessHashTextIsNotDiscarded() {
        assertTrue(Redactor.redact("issue #123").contains("issue #123"));
    }

    private static void assertNoSecret(String input, String secret) {
        String result = Redactor.redact(input);
        assertFalse("leaked in: " + result, result.contains(secret));
    }

    @Test
    public void redactsUrlCredentialsButKeepsTheHost() {
        String result = Redactor.redact("https://alice:s3cr3t@example.com/repo");
        assertFalse(result.contains("alice"));
        assertFalse(result.contains("s3cr3t"));
        assertTrue(result.contains("example.com"));
    }

    @Test
    public void redactsEmailAddresses() {
        String result = Redactor.redact("contact alice@example.com");
        assertEquals("contact [redacted-email]", result);
    }

    @Test
    public void redactsAbsolutePaths() {
        for (String value : new String[] {
                "open /data/user/0/com.example/files/secret",
                "home is /home/alice",
                "C:\\Users\\alice\\secret",
                "config ~/private/key"}) {
            String result = Redactor.redact(value);
            assertFalse(result, result.contains("/data/user"));
            assertFalse(result, result.contains("/home/alice"));
            assertFalse(result, result.contains("C:\\Users"));
            assertFalse(result, result.contains("~/private"));
        }
    }

    @Test
    public void redactsIpAddressesAndLongBlobs() {
        String ip = Redactor.redact("connected to 192.168.1.10:8080");
        assertFalse(ip.contains("192.168.1.10"));
        assertTrue(ip.contains("[redacted-ip]"));

        String blob = Redactor.redact("fingerprint " + "a".repeat(48));
        assertFalse(blob.contains("a".repeat(48)));
        assertTrue(blob.contains("[redacted-blob]"));
    }

    @Test
    public void replacesSourceAndKeystrokeText() {
        assertEquals("[redacted-content]",
                Redactor.redact("int main(void) { return 0; }"));
        assertEquals("[redacted-content]", Redactor.redact("rm -rf /\n"));
        assertEquals("[redacted-content]", Redactor.redact("#include <stdio.h>"));
        assertEquals("[redacted-content]", Redactor.redact("x".repeat(Redactor.MAX_TEXT_LENGTH + 1)));
    }

    @Test
    public void nullAndEmptyBecomeEmpty() {
        assertEquals("", Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }
}
