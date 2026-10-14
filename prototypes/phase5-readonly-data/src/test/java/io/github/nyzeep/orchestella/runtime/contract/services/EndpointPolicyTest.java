package io.github.nyzeep.orchestella.runtime.contract.services;

import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.*;

public final class EndpointPolicyTest {
    @Test public void browserPathAmbiguityAndSecretCarriersFailClosed() {
        EndpointPolicy policy = EndpointPolicy.loopback("http", 8080);
        for (String path : new String[] {"/../outside", "/./login", "/a/%2e%2e/outside",
                "/a%2fb", "/a%5Cb", "/%252e%252e/outside", "/%00", "/%0d%0aHost",
                "/%c0%af", "/%ed%a0%80", "//external", "/#password=synthetic",
                "/?folder=Downloads", "/?%74oken=synthetic"}) {
            assertThrows("reject ambiguous path or credential carrier", IllegalArgumentException.class,
                    () -> policy.requireAllowed("http://127.0.0.1:8080" + path));
        }
        assertEquals("/assets/editor.js", policy.requireAllowed("http://127.0.0.1:8080/assets/editor.js").getPath());
        assertEquals("/文件", policy.requireAllowed("http://127.0.0.1:8080/%E6%96%87%E4%BB%B6").getPath());
    }

    @Test public void resourceQueriesHaveSeparateBoundedNonCredentialVocabulary() {
        EndpointPolicy policy = EndpointPolicy.loopback("http", 8080);
        assertEquals("v=abcdef1234567&version=4.141.0&t=1720000000000",
                policy.requireAllowedResource("http://127.0.0.1:8080/assets/editor.js?v=abcdef1234567&version=4.141.0&t=1720000000000").getRawQuery());
        for (String query : new String[] {"token=synthetic", "%74oken=synthetic", "auth=abc", "cookie=abc",
                "folder=/home/developer", "v=abcdefg", "v=123&v=456", "t=1;password=abc",
                "version=%34.141.0", "t=1&unknown=2", "v="}) {
            assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedResource("http://127.0.0.1:8080/assets/editor.js?" + query));
        }
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedResource("http://127.0.0.1:8081/assets/editor.js?v=123"));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowed("http://127.0.0.1:8080/assets/editor.js?v=123"));
    }

    @Test public void fixedEditorWorkspaceRedirectsKeepGuestPathsAndNoCredentialQueries() {
        EndpointPolicy policy = EndpointPolicy.loopback("http", 8080);
        for (String route : new String[] {"/?folder=/home/developer", "/login?folder=%2Fhome%2Fdeveloper",
                "/?workspace=%2Fhome%2Fdeveloper%2FMy+Project%2Ftest.code-workspace", "/?ew=true", "/logout?to=%2F"}) {
            assertNotNull(policy.requireAllowedEditorNavigation("http://127.0.0.1:8080" + route));
        }
        for (String route : new String[] {"/?folder=file%3A%2F%2F%2Fprivate", "/?folder=%2Fhome%2F..%2Fetc",
                "/?folder=%252Fhome", "/?folder=%2Fhome%00", "/?folder=C%3A%5Cprivate",
                "/?folder=/home&token=synthetic", "/login?%70assword=synthetic", "/logout?to=https%3A%2F%2Fevil.test",
                "/assets/script.js?folder=/home", "/?folder=/home&workspace=/home/test.code-workspace",
                "/?workspace=/home/ordinary.txt", "/?auth=synthetic"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedEditorNavigation("http://127.0.0.1:8080" + route));
        }
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedEditorNavigation("http://127.0.0.1:8081/?folder=/home"));
    }

    @Test public void fixedBundleWebviewAndServiceWorkerQueriesHaveExactRoutesAndAuthorities() {
        EndpointPolicy policy = EndpointPolicy.loopback("http", 8080);
        String pre = "/stable-8b41915d25547e2edd553bab03ea4cbd621f98c5/static/out/vs/workbench/contrib/webview/browser/pre/";
        String uuid = "12345678-1234-4234-8234-123456789abc";
        String query = "id=" + uuid + "&parentId=1&origin=" + uuid + "&swVersion=6&extensionId=vscode.markdown-language-features"
                + "&platform=browser&vscode-resource-base-authority=vscode-resource.vscode-cdn.net"
                + "&parentOrigin=http%3A%2F%2F127.0.0.1%3A8080&remoteAuthority=127.0.0.1%3A8080&vscode-coi=3";
        assertNotNull(policy.requireAllowedEditorResource("http://127.0.0.1:8080" + pre + "index.html?" + query));
        String worker = "v=6&vscode-resource-base-authority=vscode-resource.vscode-cdn.net&remoteAuthority=127.0.0.1:8080&platform=browser";
        assertNotNull(policy.requireAllowedEditorResource("http://127.0.0.1:8080" + pre + "service-worker.js?" + worker));
        assertNotNull(policy.requireAllowedEditorResource("http://127.0.0.1:8080/static/out/vs/workbench/contrib/webview/browser/pre/fake.html?id=" + uuid));
        for (String bad : new String[] {query + "&password=synthetic", query + "&%74oken=synthetic",
                query.replace("%3A8080&remoteAuthority", "%3A8081&remoteAuthority"),
                query.replace("remoteAuthority=127.0.0.1%3A8080", "remoteAuthority=evil.test%3A8080"),
                query.replace("swVersion=6", "swVersion=7"), query.replace("platform=browser", "platform=electron"),
                query.replace("id=" + uuid, "id=%2531"), query + "&id=" + uuid}) {
            assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedEditorResource("http://127.0.0.1:8080" + pre + "index.html?" + bad));
        }
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedEditorResource("http://127.0.0.1:8080/other/index.html?" + query));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedEditorResource("http://127.0.0.1:8081" + pre + "index.html?" + query));
        assertThrows(IllegalArgumentException.class, () -> policy.requireAllowedEditorResource("http://127.0.0.1:8080" + pre + "service-worker.js?" + worker.replace("vscode-resource.vscode-cdn.net", "evil.test")));
    }

    @Test public void navigationStaysOnTheSelectedExplicitOrigin() {
        EndpointPolicy policy = EndpointPolicy.loopback("http", 8080);
        assertEquals(URI.create("http://127.0.0.1:8080/"), policy.origin());
        assertEquals(URI.create("http://127.0.0.1:8080/login"),
                policy.requireAllowed("http://127.0.0.1:8080/login"));
        for (String url : new String[] {
                "http://127.0.0.1:8081/", "https://127.0.0.1:8080/",
                "http://127.0.0.1/", "http://localhost:8080/",
                "http://[::1]:8080/", "http://127.1:8080/",
                "http://2130706433:8080/", "http://%31%32%37.0.0.1:8080/",
                "http://127.0.0.1.:8080/", "http://127.0.0.1:08080/",
                "http://user:password@127.0.0.1:8080/",
                "http://127.0.0.1:8080/?token=synthetic",
                "file:///Downloads/file.txt", "content://provider/document/1",
                "javascript:alert(1)", "http://example.com:8080/",
                "http://ｅxample.com:8080/", "http://xn--example:8080/"}) {
            assertThrows("reject unsafe origin", IllegalArgumentException.class,
                    () -> policy.requireAllowed(url));
        }
    }
}
