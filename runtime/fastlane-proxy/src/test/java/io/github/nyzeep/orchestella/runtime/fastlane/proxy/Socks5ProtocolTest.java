package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class Socks5ProtocolTest {
    private static byte[] bytes(int... values) {
        final byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }

    @Test
    public void connectsIpv4() throws Exception {
        final byte[] request = bytes(
                5, 1, 0,
                5, 1, 0, 1, 127, 0, 0, 1, 0x1F, 0x90);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ProxyTarget target = Socks5Protocol.readRequest(
                new ByteArrayInputStream(request), output);
        assertEquals("127.0.0.1", target.host());
        assertEquals(8080, target.port());
        assertEquals(2, output.size());
        assertEquals(5, output.toByteArray()[0]);
        assertEquals(0, output.toByteArray()[1]);
    }

    @Test
    public void connectsDomain() throws Exception {
        final byte[] domain = "example.com".getBytes(StandardCharsets.US_ASCII);
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(bytes(5, 1, 0, 5, 1, 0, 3, domain.length));
        request.write(domain);
        request.write(bytes(0x01, 0xBB));
        final ProxyTarget target = Socks5Protocol.readRequest(
                new ByteArrayInputStream(request.toByteArray()), new ByteArrayOutputStream());
        assertEquals("example.com", target.host());
        assertEquals(443, target.port());
    }

    @Test
    public void rejectsIpv6WithReply() throws Exception {
        final byte[] request = bytes(5, 1, 0, 5, 1, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final ProxyException failure = assertThrows(ProxyException.class,
                () -> Socks5Protocol.readRequest(new ByteArrayInputStream(request), output));
        assertEquals(ProxyFailure.PROXY_PROTOCOL_FAILED, failure.failure());
        final byte[] reply = output.toByteArray();
        assertEquals(5, reply[reply.length - 10]);
        assertEquals(0x08, reply[reply.length - 9]);
    }

    @Test
    public void rejectsBindWithReply() throws Exception {
        final byte[] request = bytes(5, 1, 0, 5, 2, 0, 1, 127, 0, 0, 1, 0, 80);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(ProxyException.class,
                () -> Socks5Protocol.readRequest(new ByteArrayInputStream(request), output));
        final byte[] reply = output.toByteArray();
        assertEquals(0x07, reply[reply.length - 9]);
    }

    @Test
    public void rejectsMissingNoAuthMethod() throws Exception {
        final byte[] request = bytes(5, 1, 2);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(ProxyException.class,
                () -> Socks5Protocol.readRequest(new ByteArrayInputStream(request), output));
        assertEquals(2, output.size());
        assertEquals((byte) 0xFF, output.toByteArray()[1]);
    }

    @Test
    public void rejectsTruncatedRequest() {
        assertThrows(ProxyException.class, () -> Socks5Protocol.readRequest(
                new ByteArrayInputStream(bytes(5, 1, 0, 5)), new ByteArrayOutputStream()));
    }

    @Test
    public void writesFailureReply() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Socks5Protocol.writeFailure(output, ProxyFailure.PROXY_TIMEOUT);
        final byte[] reply = output.toByteArray();
        assertEquals(10, reply.length);
        assertEquals(5, reply[0]);
        assertEquals(0x06, reply[1]);
        assertEquals(0, reply[2]);
        assertEquals(1, reply[3]);
    }
}
