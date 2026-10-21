package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.net.InetAddress;
import org.junit.Test;

public final class ProxyConfigTest {
    @Test
    public void acceptsIpv4Loopback() throws Exception {
        final ProxyConfig config = new ProxyConfig(
                InetAddress.getByName("127.0.0.1"), 0, 4, 1000, 5000, 4096, false);
        assertEquals("127.0.0.1", config.bindAddress().getHostAddress());
        assertEquals(4, config.maxConnections());
    }

    @Test
    public void rejectsNonLoopback() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfig(
                InetAddress.getByName("192.0.2.1"), 0, 4, 1000, 5000, 4096, false));
    }

    @Test
    public void rejectsIpv6Loopback() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfig(
                InetAddress.getByName("::1"), 0, 4, 1000, 5000, 4096, false));
    }

    @Test
    public void rejectsInvalidLimits() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfig(
                InetAddress.getByName("127.0.0.1"), 0, 0, 1000, 5000, 4096, false));
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfig(
                InetAddress.getByName("127.0.0.1"), 0, 4, 0, 5000, 4096, false));
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfig(
                InetAddress.getByName("127.0.0.1"), 0, 4, 1000, 0, 4096, false));
        assertThrows(IllegalArgumentException.class, () -> new ProxyConfig(
                InetAddress.getByName("127.0.0.1"), 0, 4, 1000, 5000, 16, false));
    }
}
