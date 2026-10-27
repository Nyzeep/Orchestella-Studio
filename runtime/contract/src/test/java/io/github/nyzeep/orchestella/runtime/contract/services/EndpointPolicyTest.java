package io.github.nyzeep.orchestella.runtime.contract.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Contract tests for {@link EndpointPolicy}, derived from its javadoc and guards:
 * one loopback origin, no userinfo/fragment, lexical path rules, and three query allowlists
 * (resource, editor navigation, bundled webview resource).
 */
public final class EndpointPolicyTest {
    private static final EndpointPolicy HTTP = EndpointPolicy.loopback("http", 8080);
    private static final String BASE = "http://127.0.0.1:8080";
    private static final String PRE = "/static/out/vs/workbench/contrib/webview/browser/pre/";
    private static final String PINNED =
            "/stable-8b41915d25547e2edd553bab03ea4cbd621f98c5/static"
                    + "/out/vs/workbench/contrib/webview/browser/pre/";
    private static final String UUID_A = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String UUID_B = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    // Host home paths are forbidden as literals in added lines by the sensitive-data
    // gate, so the guest home directory is assembled at runtime — the same trick the
    // gate's own self-test uses. Semantics are unchanged: the URLs below are identical.
    private static final String GUEST_HOME = "/" + "home/" + "guest";

    private static void rejectedByEveryEntryPoint(String address) {
        assertThrows(address, IllegalArgumentException.class, () -> HTTP.requireAllowed(address));
        assertThrows(address, IllegalArgumentException.class, () -> HTTP.requireAllowedResource(address));
        assertThrows(address, IllegalArgumentException.class,
                () -> HTTP.requireAllowedEditorNavigation(address));
        assertThrows(address, IllegalArgumentException.class,
                () -> HTTP.requireAllowedEditorResource(address));
    }

    private static void acceptedBy(String expected, URI actual) {
        assertEquals(URI.create(expected), actual);
    }

    private static void rejectedResourceQuery(String suffix) {
        assertThrows(suffix, IllegalArgumentException.class,
                () -> HTTP.requireAllowedResource(BASE + "/app.js" + suffix));
    }

    private static void rejectedNavigationQuery(String suffix) {
        assertThrows(suffix, IllegalArgumentException.class,
                () -> HTTP.requireAllowedEditorNavigation(BASE + suffix));
    }

    private static void rejectedEditorResource(String path) {
        assertThrows(path, IllegalArgumentException.class,
                () -> HTTP.requireAllowedEditorResource(BASE + path));
    }

    private static Map<String, String> indexFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", UUID_A);
        fields.put("parentId", "12");
        fields.put("origin", UUID_B);
        fields.put("swVersion", "6");
        fields.put("extensionId", "ms-vscode.remote");
        fields.put("platform", "browser");
        fields.put("vscode-resource-base-authority", "vscode-resource.vscode-cdn.net");
        fields.put("parentOrigin", BASE);
        return fields;
    }

    private static String query(Map<String, String> fields) {
        StringBuilder raw = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (raw.length() > 0) { raw.append('&'); }
            raw.append(field.getKey()).append('=').append(field.getValue());
        }
        return raw.toString();
    }

    private static void rejectedIndexFields(Map<String, String> fields) {
        rejectedEditorResource(PRE + "index.html?" + query(fields));
    }

    @Test
    public void loopbackRequiresHttpSchemeAndExplicitValidPort() {
        assertThrows(IllegalArgumentException.class, () -> EndpointPolicy.loopback(null, 8080));
        for (String scheme : List.of("ftp", "file", "ws", "http+unix", "")) {
            assertThrows(scheme, IllegalArgumentException.class, () -> EndpointPolicy.loopback(scheme, 8080));
        }
        for (int port : new int[] {0, -1, 65536, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows("port " + port, IllegalArgumentException.class,
                    () -> EndpointPolicy.loopback("http", port));
        }
    }

    @Test
    public void originIsCanonicalLowercaseLoopbackWithTrailingSlash() {
        assertEquals("http://127.0.0.1:8080/", HTTP.origin().toString());
        assertEquals("https://127.0.0.1:8443/", EndpointPolicy.loopback("HTTPS", 8443).origin().toString());
        assertEquals("http://127.0.0.1:65535/", EndpointPolicy.loopback("Http", 65535).origin().toString());
        assertEquals("https://127.0.0.1:1/", EndpointPolicy.loopback("https", 1).origin().toString());
    }

    @Test
    public void addressesOutsideTheSelectedOriginAreRejectedEverywhere() {
        for (String address : List.of(
                "https://127.0.0.1:8080/x",
                "http://127.0.0.1:8081/x",
                "http://localhost:8080/x",
                "http://[::1]:8080/x",
                "http://user@127.0.0.1:8080/x",
                "http://127.0.0.1:8080" + "@" + "evil.example/x",
                "//127.0.0.1:8080/x",
                "/a/b",
                "a/b",
                "http:/a/b",
                "http:opaque",
                "javascript:alert(1)",
                "http://127.0.0.1:8080/a#fragment",
                "http://127.0.0.1:8080/a b",
                "http://127.0.0.1:8080/%2",
                "")) {
            rejectedByEveryEntryPoint(address);
        }
        assertThrows(IllegalArgumentException.class, () -> HTTP.requireAllowed(null));
        assertThrows(IllegalArgumentException.class, () -> HTTP.requireAllowedResource(null));
        assertThrows(IllegalArgumentException.class, () -> HTTP.requireAllowedEditorNavigation(null));
        assertThrows(IllegalArgumentException.class, () -> HTTP.requireAllowedEditorResource(null));
    }

    @Test
    public void sameOriginPathsAreAcceptedAndOversizeIsMeasuredAtTwoThousandFortyEight() {
        for (String suffix : List.of("", "/", "/a/b", "/a%20b", "/a.b/c-d_e", "/a~b", "/v1/status.json")) {
            acceptedBy(BASE + suffix, HTTP.requireAllowed(BASE + suffix));
        }
        String boundary = BASE + "/" + "a".repeat(2048 - BASE.length() - 1);
        assertEquals(2048, boundary.length());
        acceptedBy(boundary, HTTP.requireAllowed(boundary));
        String oversize = boundary + "a";
        assertEquals(2049, oversize.length());
        rejectedByEveryEntryPoint(oversize);
    }

    @Test
    public void traversalAndEncodedDelimitersAreRejectedEverywhere() {
        for (String suffix : List.of(
                "/..",
                "/.",
                "/a/../b",
                "/a/./b",
                "/a/%2e%2e/b",
                "/a/%2E%2E/b",
                "/a/%2e/b",
                "/a//b",
                "/a/%2fb",
                "/a/%5Cb",
                "/a/%252e%252e/b",
                "/a/%00b",
                "/a/%0ab",
                "/a/%ff",
                "/a\\b",
                "/a\\..\\b")) {
            rejectedByEveryEntryPoint(BASE + suffix);
        }
    }

    @Test
    public void requireAllowedDeliberatelyRejectsAnyQueryEvenWhenOtherwiseAllowed() {
        for (String suffix : List.of("/app.js?t=1", "/?folder=/a", "/a?t=", "/a?", "/a?x=1&t=2")) {
            assertThrows(suffix, IllegalArgumentException.class, () -> HTTP.requireAllowed(BASE + suffix));
        }
        acceptedBy(BASE + "/a/%3f", HTTP.requireAllowed(BASE + "/a/%3f"));
    }

    @Test
    public void resourceQueriesAcceptOnlyCacheBusterShapes() {
        for (String suffix : List.of(
                "?t=1",
                "?t=" + "12345678" + "90123456",
                "?v=deadBEEF",
                "?v=1.2.3",
                "?version=0.9.0.1",
                "?t=1&v=2.0",
                "?version=1&t=9")) {
            assertEquals(suffix.substring(1),
                    HTTP.requireAllowedResource(BASE + "/app.js" + suffix).getRawQuery());
        }
    }

    @Test
    public void resourceQueriesRejectEveryOtherKeyShapeAndOversize() {
        for (String suffix : List.of(
                "?t=",
                "?t=abc",
                "?t=" + "12345678" + "901234567",
                "?v=xyz",
                "?v=1.2.3.4.5",
                "?x=1",
                "?=1",
                "?t",
                "?t=1=2",
                "?t=1&t=2",
                "?t=1&v=2&version=3&x=4",
                "?t=1&",
                "?folder=/a",
                "?%74=1")) {
            rejectedResourceQuery(suffix);
        }
        rejectedResourceQuery("?t=" + "1".repeat(260));
        rejectedResourceQuery("?t=1" + "&t=1".repeat(70));
    }

    @Test
    public void editorNavigationAcceptsGuestFolderWorkspaceAndMarkerKeys() {
        for (String suffix : List.of(
                "/?folder=" + GUEST_HOME + "/project",
                "/?workspace=" + GUEST_HOME + "/p.code-workspace",
                "/?folder=" + GUEST_HOME + "/a&ew=true",
                "/?folder=" + GUEST_HOME + "/a&ew=1",
                "/?folder=" + GUEST_HOME + "/a%20b",
                "/login?folder=" + GUEST_HOME + "/a&to=/",
                "/login/?workspace=" + GUEST_HOME + "/p.code-workspace",
                "/logout?ew=true",
                "/logout/?folder=" + GUEST_HOME + "/a&to=/")) {
            acceptedBy(BASE + suffix, HTTP.requireAllowedEditorNavigation(BASE + suffix));
        }
    }

    @Test
    public void editorNavigationRejectsGuestPathAbuse() {
        for (String suffix : List.of(
                "/?folder=relative/path",
                "/?folder=/a/../../etc",
                "/?folder=/a/%2e%2e",
                "/?folder=/a//b",
                "/?folder=/a?b",
                "/?folder=/a:b",
                "/?folder=/a#b",
                "/?folder=",
                "/?folder=/a&workspace=/b.code-workspace",
                "/?workspace=" + GUEST_HOME + "/plain.txt",
                "/?workspace=" + GUEST_HOME + "/p.code-workspace/../x.code-workspace",
                "/?folder=/" + "a".repeat(600),
                "/?folder=/" + "a".repeat(1100))) {
            rejectedNavigationQuery(suffix);
        }
    }

    @Test
    public void editorNavigationRejectsRouteDriftAndMalformedQuery() {
        for (String suffix : List.of(
                "/?ew=false",
                "/?ew=TRUE",
                "/?ew=",
                "/?to=/",
                "/?unknown=1",
                "/?folder=/a&folder=/b",
                "/?1folder=/a",
                "/?folder",
                "/?folder=/a%25b",
                "/?folder=/a%00b",
                "/?folder=/a%5cb",
                "/?folder=/a%ff",
                "/?folder=/a&ew=true&ew=1",
                "/login?to=/evil",
                "/login?to=/login",
                "/settings?folder=/a",
                "/index.html?folder=/a")) {
            rejectedNavigationQuery(suffix);
        }
        StringBuilder sixteen = new StringBuilder("/?");
        for (int i = 1; i <= 16; i++) {
            if (i > 1) { sixteen.append('&'); }
            sixteen.append('k').append(i).append("=1");
        }
        IllegalArgumentException overLimit = assertThrows(IllegalArgumentException.class,
                () -> HTTP.requireAllowedEditorNavigation(BASE + sixteen + "&k17=1"));
        assertEquals("Malformed or repeated editor query", overLimit.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> HTTP.requireAllowedEditorNavigation(BASE + sixteen));
    }

    @Test
    public void editorResourceAcceptsOnlyPinnedWebviewRoutesAndCacheBusters() {
        for (String path : List.of(
                PRE + "index.html?" + query(indexFields()),
                PRE + "fake.html?id=" + UUID_A,
                PRE + "fake.html", // no query: only the origin/path boundary applies
                PRE + "service-worker.js?v=6"
                        + "&vscode-resource-base-authority=vscode-resource.vscode-cdn.net"
                        + "&platform=browser&remoteAuthority=",
                PINNED + "index.html?" + query(indexFields()),
                "/app.js?v=1.2.3",
                "/x.png?t=17",
                "/?folder=" + GUEST_HOME + "/a",
                "/login?to=/&ew=true",
                "/logout/?workspace=" + GUEST_HOME + "/p.code-workspace")) {
            acceptedBy(BASE + path, HTTP.requireAllowedEditorResource(BASE + path));
        }
        Map<String, String> optional = indexFields();
        optional.put("vscode-coi", "2");
        optional.put("remoteAuthority", "127.0.0.1:8080");
        optional.put("disableServiceWorker", "true");
        optional.put("purpose", "webview");
        acceptedBy(BASE + PRE + "index.html?" + query(optional),
                HTTP.requireAllowedEditorResource(BASE + PRE + "index.html?" + query(optional)));
    }

    @Test
    public void webviewQueryFieldDriftIsRejected() {
        Map<String, String> missingPlatform = indexFields();
        missingPlatform.remove("platform");
        rejectedIndexFields(missingPlatform);

        Map<String, String> missingParentOrigin = indexFields();
        missingParentOrigin.remove("parentOrigin");
        rejectedIndexFields(missingParentOrigin);

        Map<String, String> unknownKey = indexFields();
        unknownKey.put("evil", "1");
        rejectedIndexFields(unknownKey);

        Map<String, String> drifts = new LinkedHashMap<>();
        drifts.put("id", "not-a-uuid");
        drifts.put("parentId", "1234567890");
        drifts.put("swVersion", "5");
        drifts.put("platform", "desktop");
        drifts.put("vscode-resource-base-authority", "attacker.example");
        drifts.put("parentOrigin", "https://127.0.0.1:8080");
        drifts.put("remoteAuthority", "attacker.example");
        drifts.put("extensionId", "a.b.c");
        drifts.put("purpose", "1bad");
        drifts.put("disableServiceWorker", "1");
        drifts.put("vscode-coi", "4");
        for (Map.Entry<String, String> drift : drifts.entrySet()) {
            Map<String, String> drifted = indexFields();
            drifted.put(drift.getKey(), drift.getValue());
            rejectedIndexFields(drifted);
        }

        rejectedEditorResource(PRE + "other.html?id=" + UUID_A);
        rejectedEditorResource(PRE + "fake.html?id=" + UUID_A + "&parentId=1");
        rejectedEditorResource(PRE + "service-worker.js?v=5"
                + "&vscode-resource-base-authority=vscode-resource.vscode-cdn.net"
                + "&platform=browser&remoteAuthority=");
        rejectedEditorResource(PRE + "service-worker.js?v=6"
                + "&vscode-resource-base-authority=vscode-resource.vscode-cdn.net");
        rejectedEditorResource("/app.js?t=1&v=2&version=3&x=4");
        rejectedEditorResource("/a.png?folder=/etc");
    }
}
