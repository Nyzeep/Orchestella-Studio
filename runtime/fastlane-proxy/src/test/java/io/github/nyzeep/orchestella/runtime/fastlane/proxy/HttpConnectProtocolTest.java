package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class HttpConnectProtocolTest {
    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void parsesConnectWithHeaders() throws Exception {
        final ProxyTarget target = HttpConnectProtocol.readRequest(input(
                "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n"
                        + "Proxy-Connection: keep-alive\r\n\r\n"));
        assertEquals("example.com", target.host());
        assertEquals(443, target.port());
    }

    @Test
    public void parsesIpv4() throws Exception {
        final ProxyTarget target = HttpConnectProtocol.readRequest(
                input("CONNECT 127.0.0.1:8080 HTTP/1.0\r\n\r\n"));
        assertEquals("127.0.0.1", target.host());
        assertEquals(8080, target.port());
    }

    @Test
    public void rejectsAbsoluteUrl() {
        assertThrows(ProxyException.class, () -> HttpConnectProtocol.readRequest(
                input("GET http://example.com/ HTTP/1.1\r\n\r\n")));
    }

    @Test
    public void rejectsMissingPort() {
        assertThrows(ProxyException.class, () -> HttpConnectProtocol.readRequest(
                input("CONNECT example.com HTTP/1.1\r\n\r\n")));
    }

    @Test
    public void rejectsIpv6Literal() {
        assertThrows(ProxyException.class, () -> HttpConnectProtocol.readRequest(
                input("CONNECT [::1]:443 HTTP/1.1\r\n\r\n")));
    }

    @Test
    public void rejectsOversizedLine() {
        final String huge = "CONNECT " + "a".repeat(9000) + ":443 HTTP/1.1\r\n\r\n";
        assertThrows(ProxyException.class,
                () -> HttpConnectProtocol.readRequest(input(huge)));
    }

    @Test
    public void writesEstablished() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        HttpConnectProtocol.writeEstablished(output);
        assertEquals("HTTP/1.1 200 Connection Established\r\n\r\n",
                output.toString(StandardCharsets.US_ASCII));
    }

    @Test
    public void writesFailureStatus() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        HttpConnectProtocol.writeFailure(output, ProxyFailure.PROXY_TIMEOUT);
        final String response = output.toString(StandardCharsets.US_ASCII);
        assertTrue(response.startsWith("HTTP/1.1 504 Gateway Timeout\r\n"));
        assertTrue(response.contains("Content-Length: 0"));
    }
}
