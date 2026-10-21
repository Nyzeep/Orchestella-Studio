package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP CONNECT parsing with bounded headers. Only CONNECT is accepted; the proxy
 * never forwards an absolute URL, request body, or header to the target.
 */
public final class HttpConnectProtocol {
    private static final int MAX_LINE_BYTES = 8192;
    private static final int MAX_HEADER_BYTES = 16384;

    private HttpConnectProtocol() {}

    public static ProxyTarget readRequest(InputStream input) throws IOException, ProxyException {
        final String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) {
            throw protocol("empty request");
        }
        final String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3) {
            throw protocol("malformed request line");
        }
        if (!"CONNECT".equals(parts[0])) {
            throw new ProxyException(ProxyFailure.PROXY_PROTOCOL_FAILED, "only CONNECT is supported");
        }
        if (!"HTTP/1.1".equals(parts[2]) && !"HTTP/1.0".equals(parts[2])) {
            throw protocol("unsupported HTTP version");
        }
        final ProxyTarget target = parseAuthority(parts[1]);

        int headerBytes = 0;
        for (;;) {
            final String line = readLine(input);
            if (line == null) {
                throw protocol("truncated headers");
            }
            if (line.isEmpty()) {
                break;
            }
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw protocol("headers are too large");
            }
        }
        return target;
    }

    public static void writeEstablished(OutputStream output) throws IOException {
        output.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    public static void writeFailure(OutputStream output, ProxyFailure failure) throws IOException {
        final String response = "HTTP/1.1 " + failure.httpStatus() + " " + failure.httpReason()
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static ProxyTarget parseAuthority(String authority) throws ProxyException {
        final int colon = authority.lastIndexOf(':');
        if (colon <= 0 || colon == authority.length() - 1) {
            throw protocol("authority must be host:port");
        }
        final String host = authority.substring(0, colon);
        final String portText = authority.substring(colon + 1);
        if (portText.isEmpty() || portText.length() > 5) {
            throw protocol("invalid port");
        }
        for (int index = 0; index < portText.length(); index++) {
            final char character = portText.charAt(index);
            if (character < '0' || character > '9') {
                throw protocol("invalid port");
            }
        }
        final int port = Integer.parseInt(portText);
        try {
            return new ProxyTarget(host, port);
        } catch (IllegalArgumentException failure) {
            throw protocol("invalid target");
        }
    }

    private static String readLine(InputStream input) throws IOException, ProxyException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (;;) {
            final int value = input.read();
            if (value < 0) {
                if (line.size() == 0) {
                    return null;
                }
                throw protocol("truncated header line");
            }
            if (value == '\n') {
                final byte[] bytes = line.toByteArray();
                final int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                        ? bytes.length - 1
                        : bytes.length;
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            if (line.size() >= MAX_LINE_BYTES) {
                throw protocol("header line is too long");
            }
            line.write(value);
        }
    }

    private static ProxyException protocol(String message) {
        return new ProxyException(ProxyFailure.PROXY_PROTOCOL_FAILED, message);
    }
}
