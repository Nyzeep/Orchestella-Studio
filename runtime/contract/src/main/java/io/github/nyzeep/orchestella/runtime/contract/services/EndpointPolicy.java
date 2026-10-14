package io.github.nyzeep.orchestella.runtime.contract.services;

import java.net.URI;
import java.net.URISyntaxException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

/** Navigation boundary for one explicitly selected loopback service; never resolves DNS. */
public final class EndpointPolicy {
    private final URI origin;
    private final String authority;

    private EndpointPolicy(String scheme, int port) {
        if ((!"http".equals(scheme) && !"https".equals(scheme)) || port < 1 || port > 65535) {
            throw new IllegalArgumentException("An HTTP(S) scheme and explicit valid port are required");
        }
        authority = "127.0.0.1:" + port;
        origin = URI.create(scheme + "://" + authority + "/");
    }

    public static EndpointPolicy loopback(String scheme, int port) {
        if (scheme == null) {
            throw new IllegalArgumentException("Scheme is required");
        }
        return new EndpointPolicy(scheme.toLowerCase(Locale.ROOT), port);
    }

    public URI origin() { return origin; }

    /** Queries and fragments are deliberately excluded, including encoded credential keys. */
    public URI requireAllowed(String address) { return validate(address, false); }

    /** Optional resource cache-busters only; workspace, redirect and credential queries remain denied. */
    public URI requireAllowedResource(String address) {
        URI uri = validate(address, true);
        if (uri.getRawQuery() != null) { validateResourceQuery(uri.getRawQuery()); }
        return uri;
    }

    /** Fixed code-server 4.141.0 navigation. Guest path values confer no host file capability. */
    public URI requireAllowedEditorNavigation(String address) {
        URI uri = validate(address, true);
        if (uri.getRawQuery() == null) { return uri; }
        validateEditorNavigationQuery(uri.getRawPath(), parseEditorQuery(uri.getRawQuery()));
        return uri;
    }

    /** Route-specific bundled webview/SW compatibility, never an arbitrary same-origin query allowlist. */
    public URI requireAllowedEditorResource(String address) {
        URI uri = validate(address, true);
        if (uri.getRawQuery() == null) { return uri; }
        String path = uri.getRawPath();
        String pre = "/out/vs/workbench/contrib/webview/browser/pre/";
        String pinned = "/stable-8b41915d25547e2edd553bab03ea4cbd621f98c5/static";
        String file = path.startsWith("/static" + pre) ? path.substring(("/static" + pre).length())
                : path.startsWith(pinned + pre) ? path.substring((pinned + pre).length()) : null;
        if (file != null) {
            validateWebviewQuery(file, parseEditorQuery(uri.getRawQuery()));
        } else if (Set.of("/", "/login", "/login/", "/logout", "/logout/").contains(path)) {
            validateEditorNavigationQuery(path, parseEditorQuery(uri.getRawQuery()));
        } else {
            validateResourceQuery(uri.getRawQuery());
        }
        return uri;
    }

    private URI validate(String address, boolean resource) {
        if (address == null || address.length() > 2048) {
            throw new IllegalArgumentException("Invalid service address");
        }
        final URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("Malformed service address");
        }
        if (uri.isOpaque() || !origin.getScheme().equals(uri.getScheme())
                || !authority.equals(uri.getRawAuthority())
                || uri.getRawUserInfo() != null || (!resource && uri.getRawQuery() != null)
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Address is outside the selected service origin");
        }
        validatePath(uri.getRawPath());
        return uri;
    }

    private static Map<String, String> parseEditorQuery(String raw) {
        if (raw.length() > 1024) { throw new IllegalArgumentException("Editor query too long"); }
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z][A-Za-z0-9-]{0,63}")
                    || query.containsKey(parts[0]) || query.size() >= 16) {
                throw new IllegalArgumentException("Malformed or repeated editor query");
            }
            query.put(parts[0], decodeQueryValue(parts[1]));
        }
        return query;
    }

    private static String decodeQueryValue(String raw) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '%') {
                decoded.append(raw.charAt(i) == '+' ? ' ' : raw.charAt(i));
                i++;
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < raw.length() && raw.charAt(i) == '%') {
                bytes.write(Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                i += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid editor query UTF-8"); }
        }
        String value = decoded.toString();
        if (value.indexOf('%') >= 0 || value.indexOf('\\') >= 0
                || value.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT || Character.getType(c) == Character.SURROGATE)) {
            throw new IllegalArgumentException("Unsafe editor query value");
        }
        return value;
    }

    private static void validateEditorNavigationQuery(String path, Map<String, String> query) {
        Set<String> allowed = "/".equals(path) ? Set.of("folder", "workspace", "ew")
                : Set.of("/login", "/login/", "/logout", "/logout/").contains(path)
                    ? Set.of("folder", "workspace", "ew", "to") : Set.of();
        if (!allowed.containsAll(query.keySet()) || (query.containsKey("folder") && query.containsKey("workspace"))) {
            throw new IllegalArgumentException("Editor navigation query outside its route");
        }
        for (Map.Entry<String, String> item : query.entrySet()) {
            String key = item.getKey();
            String value = item.getValue();
            if ("folder".equals(key) || "workspace".equals(key)) {
                validateGuestPath(value);
                if ("workspace".equals(key) && !value.endsWith(".code-workspace")) {
                    throw new IllegalArgumentException("Workspace query must reference a guest workspace file");
                }
            } else if ("ew".equals(key) ? !Set.of("true", "1").contains(value) : !"/".equals(value)) {
                throw new IllegalArgumentException("Editor redirect or workspace marker rejected");
            }
        }
    }

    private static void validateGuestPath(String path) {
        if (path.length() > 512 || !path.startsWith("/") || path.contains("//")
                || path.matches(".*[:?&#;=\\\\].*") || path.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Guest workspace path is not an absolute POSIX path");
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Guest workspace traversal rejected");
            }
        }
    }

    private void validateWebviewQuery(String file, Map<String, String> query) {
        final Set<String> allowed;
        final Set<String> required;
        switch (file) {
            case "index.html":
                allowed = Set.of("id", "parentId", "origin", "swVersion", "extensionId", "platform",
                        "vscode-resource-base-authority", "parentOrigin", "remoteAuthority", "purpose",
                        "disableServiceWorker", "vscode-coi");
                required = Set.of("id", "parentId", "origin", "swVersion", "extensionId", "platform",
                        "vscode-resource-base-authority", "parentOrigin");
                break;
            case "service-worker.js":
                allowed = Set.of("v", "vscode-resource-base-authority", "remoteAuthority", "platform");
                required = allowed;
                break;
            case "fake.html":
                allowed = Set.of("id");
                required = allowed;
                break;
            default: throw new IllegalArgumentException("Unknown bundled webview route");
        }
        if (!allowed.containsAll(query.keySet()) || !query.keySet().containsAll(required)) {
            throw new IllegalArgumentException("Bundled webview query fields rejected");
        }
        String uuid = "[a-fA-F0-9]{8}-(?:[a-fA-F0-9]{4}-){3}[a-fA-F0-9]{12}";
        for (Map.Entry<String, String> item : query.entrySet()) {
            String value = item.getValue();
            boolean accepted;
            switch (item.getKey()) {
                case "id": case "origin": accepted = value.matches(uuid); break;
                case "parentId": accepted = value.matches("[0-9]{1,9}"); break;
                case "swVersion": case "v": accepted = "6".equals(value); break;
                case "platform": accepted = "browser".equals(value); break;
                case "vscode-resource-base-authority": accepted = "vscode-resource.vscode-cdn.net".equals(value); break;
                case "parentOrigin": accepted = (origin.getScheme() + "://" + authority).equals(value); break;
                case "remoteAuthority": accepted = value.isEmpty() || authority.equals(value); break;
                case "extensionId": accepted = value.isEmpty() || value.matches("[A-Za-z0-9_-]{1,64}\\.[A-Za-z0-9_-]{1,64}"); break;
                case "purpose": accepted = value.matches("[A-Za-z][A-Za-z0-9]{0,47}"); break;
                case "disableServiceWorker": accepted = "true".equals(value); break;
                case "vscode-coi": accepted = value.matches("[1-3]"); break;
                default: accepted = false;
            }
            if (!accepted) { throw new IllegalArgumentException("Bundled webview query value rejected"); }
        }
    }

    private static void validateResourceQuery(String raw) {
        if (raw.length() > 256) { throw new IllegalArgumentException("Resource query too long"); }
        Set<String> keys = new HashSet<>();
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2 || !keys.add(parts[0]) || keys.size() > 3) {
                throw new IllegalArgumentException("Malformed or repeated resource query");
            }
            String key = parts[0];
            String value = parts[1];
            boolean accepted = "t".equals(key) ? value.matches("[0-9]{1,16}")
                    : ("v".equals(key) || "version".equals(key))
                        && (value.matches("[a-fA-F0-9]{1,64}") || value.matches("[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}"));
            if (!accepted) { throw new IllegalArgumentException("Resource query is outside the allowlist"); }
        }
    }

    private static void validatePath(String raw) {
        if (raw.contains("//") || raw.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Ambiguous service path");
        }
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) != '%') {
                decoded.append(raw.charAt(i++));
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (i < raw.length() && raw.charAt(i) == '%') {
                int value = Integer.parseInt(raw.substring(i + 1, i + 3), 16);
                if (value == '/' || value == '\\' || value == '%') {
                    throw new IllegalArgumentException("Encoded path delimiter");
                }
                bytes.write(value);
                i += 3;
            }
            try {
                decoded.append(StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())));
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("Invalid UTF-8 service path");
            }
        }
        String path = decoded.toString();
        if (path.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT)) {
            throw new IllegalArgumentException("Control character in service path");
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Service path traversal");
            }
        }
    }
}
