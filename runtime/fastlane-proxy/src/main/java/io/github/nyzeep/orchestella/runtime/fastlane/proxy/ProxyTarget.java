package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.util.Locale;
import java.util.Objects;

/** A validated destination; never a URL, path, or credential. */
public final class ProxyTarget {
    private final String host;
    private final int port;

    public ProxyTarget(String host, int port) {
        Objects.requireNonNull(host, "host");
        if (host.isEmpty() || host.length() > 255) {
            throw new IllegalArgumentException("host length is invalid");
        }
        if (host.indexOf('[') >= 0 || host.indexOf(']') >= 0) {
            throw new IllegalArgumentException("IPv6 literals are not supported");
        }
        for (int index = 0; index < host.length(); index++) {
            final char character = host.charAt(index);
            if (character <= 0x20 || character == 0x7F || character == '/' || character == '@'
                    || character == '\\' || character == ':') {
                throw new IllegalArgumentException("host contains an invalid character");
            }
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be in [1, 65535]");
        }
        this.host = host.toLowerCase(Locale.ROOT);
        this.port = port;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    @Override
    public String toString() {
        // Deliberately excludes the target: this value is safe for diagnostics.
        return "ProxyTarget";
    }
}
