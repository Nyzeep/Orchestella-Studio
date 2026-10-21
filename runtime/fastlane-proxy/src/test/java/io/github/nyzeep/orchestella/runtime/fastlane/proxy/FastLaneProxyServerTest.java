package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.After;
import org.junit.Test;

public final class FastLaneProxyServerTest {
    private FastLaneProxyServer proxy;

    @After
    public void tearDown() {
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
    }

    private static ProxyConfig config(int maxConnections, int idleTimeoutMs) throws Exception {
        return new ProxyConfig(
                InetAddress.getByName("127.0.0.1"),
                0,
                maxConnections,
                1000,
                idleTimeoutMs,
                16 * 1024,
                true);
    }

    @Test
    public void socks5Ipv4RoundTrip() throws Exception {
        try (EchoServer target = new EchoServer()) {
            proxy = new FastLaneProxyServer(config(4, 2000));
            proxy.start();
            final Socket client = socksConnect(proxy.port(), "127.0.0.1", target.port());
            client.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
            assertEquals("hello", readExactly(client, 5));
            client.close();
        }
    }

    @Test
    public void socks5DomainRoundTrip() throws Exception {
        try (EchoServer target = new EchoServer()) {
            proxy = new FastLaneProxyServer(config(4, 2000));
            proxy.start();
            final Socket client = socksConnect(proxy.port(), "localhost", target.port());
            client.getOutputStream().write("dns".getBytes(StandardCharsets.US_ASCII));
            assertEquals("dns", readExactly(client, 3));
            client.close();
        }
    }

    @Test
    public void httpConnectRoundTrip() throws Exception {
        try (EchoServer target = new EchoServer()) {
            proxy = new FastLaneProxyServer(config(4, 2000));
            proxy.start();
            final Socket client = httpConnect(proxy.port(), "localhost", target.port());
            client.getOutputStream().write("http".getBytes(StandardCharsets.US_ASCII));
            assertEquals("http", readExactly(client, 4));
            client.close();
        }
    }

    @Test
    public void dnsFailureUsesSocksHostUnreachable() throws Exception {
        proxy = new FastLaneProxyServer(config(4, 2000), failingConnector(ProxyFailure.PROXY_DNS_FAILED));
        proxy.start();
        final Socket client = socksConnectRaw(proxy.port(), "example.invalid", 80);
        assertEquals(0x04, readReplyCode(client));
        client.close();
        assertEquals(1, proxy.failureCount());
    }

    @Test
    public void timeoutUsesSocksTtlExpired() throws Exception {
        proxy = new FastLaneProxyServer(config(4, 2000), failingConnector(ProxyFailure.PROXY_TIMEOUT));
        proxy.start();
        final Socket client = socksConnectRaw(proxy.port(), "example.invalid", 80);
        assertEquals(0x06, readReplyCode(client));
        client.close();
    }

    @Test
    public void refusedTargetUsesSocksConnectionRefused() throws Exception {
        final int unusedPort;
        try (ServerSocket temporary = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = temporary.getLocalPort();
        }
        proxy = new FastLaneProxyServer(config(4, 2000));
        proxy.start();
        final Socket client = socksConnectRaw(proxy.port(), "127.0.0.1", unusedPort);
        assertEquals(0x05, readReplyCode(client));
        client.close();
    }

    @Test
    public void httpFailureIsGatewayError() throws Exception {
        proxy = new FastLaneProxyServer(config(4, 2000), failingConnector(ProxyFailure.PROXY_TIMEOUT));
        proxy.start();
        final Socket client = connect(proxy.port());
        client.getOutputStream().write(("CONNECT example.invalid:80 HTTP/1.1\r\n"
                + "Host: example.invalid\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        final String response = readHeaders(client);
        assertTrue(response.startsWith("HTTP/1.1 504"));
        client.close();
    }

    @Test
    public void rejectsConnectionsAboveLimit() throws Exception {
        try (EchoServer target = new EchoServer()) {
            proxy = new FastLaneProxyServer(config(1, 2000));
            proxy.start();
            final Socket first = socksConnect(proxy.port(), "127.0.0.1", target.port());
            waitFor(() -> proxy.activeConnections() == 1, 2000);
            final Socket second = connect(proxy.port());
            second.setSoTimeout(2000);
            assertEquals(-1, second.getInputStream().read());
            second.close();
            assertTrue(proxy.rejectedConnections() >= 1);
            first.close();
        }
    }

    @Test
    public void halfCloseStillDeliversTargetResponse() throws Exception {
        try (ResponseAfterEofServer target = new ResponseAfterEofServer()) {
            proxy = new FastLaneProxyServer(config(4, 2000));
            proxy.start();
            final Socket client = socksConnect(proxy.port(), "127.0.0.1", target.port());
            client.getOutputStream().write("request".getBytes(StandardCharsets.US_ASCII));
            client.shutdownOutput();
            assertEquals("done", readExactly(client, 4));
            assertEquals(-1, client.getInputStream().read());
            client.close();
        }
    }

    @Test
    public void idleTimeoutClosesTunnel() throws Exception {
        try (EchoServer target = new EchoServer()) {
            proxy = new FastLaneProxyServer(config(4, 300));
            proxy.start();
            final Socket client = socksConnect(proxy.port(), "127.0.0.1", target.port());
            waitFor(() -> proxy.activeConnections() == 1, 2000);
            Thread.sleep(900);
            assertTrue(proxy.activeConnections() == 0);
            client.setSoTimeout(1000);
            assertEquals(-1, client.getInputStream().read());
            client.close();
        }
    }

    @Test
    public void closeClosesActiveTunnels() throws Exception {
        try (EchoServer target = new EchoServer()) {
            proxy = new FastLaneProxyServer(config(4, 5000));
            proxy.start();
            final Socket client = socksConnect(proxy.port(), "127.0.0.1", target.port());
            proxy.close();
            client.setSoTimeout(2000);
            assertEquals(-1, client.getInputStream().read());
            client.close();
        }
    }

    @Test
    public void serveChannelCarriesGuestTunnel() throws Exception {
        try (EchoServer target = new EchoServer();
                ServerSocket channelServer = new ServerSocket(
                        0, 1, InetAddress.getByName("127.0.0.1"))) {
            proxy = new FastLaneProxyServer(config(4, 2000));
            proxy.start();
            final Socket hostSide = new Socket();
            hostSide.connect(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), channelServer.getLocalPort()), 1000);
            final Socket payloadSide = channelServer.accept();
            final FastLaneChannel channel = new SocketChannel(payloadSide);
            final Thread serverThread = new Thread(() -> proxy.serveChannel(channel));
            serverThread.setDaemon(true);
            serverThread.start();

            socksGreeting(hostSide);
            sendSocksRequest(hostSide, "127.0.0.1", target.port());
            assertEquals(0, readReplyCode(hostSide));
            hostSide.getOutputStream().write("channel".getBytes(StandardCharsets.US_ASCII));
            assertEquals("channel", readExactly(hostSide, 7));
            hostSide.close();
            serverThread.join(3000);
        }
    }

    @Test
    public void tlsThroughSocksTunnel() throws Exception {
        final Path keystore = Files.createTempFile("fastlane-tls-", ".p12");
        Files.deleteIfExists(keystore);
        final Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue("keytool is required for the TLS test", Files.isExecutable(keytool));
        final Process process = new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", "test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-dname", "CN=localhost",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-noprompt")
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assumeTrue("keytool failed", process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0);

        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = new FileInputStream(keystore.toFile())) {
            store.load(input, "changeit".toCharArray());
        }
        final KeyManagerFactory keyManagers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, "changeit".toCharArray());
        final SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, new SecureRandom());
        try (SSLServerSocket tlsServer = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread echo = new Thread(() -> {
                try (SSLSocket socket = (SSLSocket) tlsServer.accept()) {
                    final InputStream input = socket.getInputStream();
                    final OutputStream output = socket.getOutputStream();
                    final byte[] buffer = new byte[1024];
                    final int read = input.read(buffer);
                    if (read > 0) {
                        output.write(buffer, 0, read);
                        output.flush();
                    }
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            echo.setDaemon(true);
            echo.start();

            proxy = new FastLaneProxyServer(config(4, 5000));
            proxy.start();
            final Socket plain = socksConnect(proxy.port(), "127.0.0.1", tlsServer.getLocalPort());
            final SSLContext clientContext = SSLContext.getInstance("TLS");
            clientContext.init(null, new TrustManager[] {new TrustAll()}, new SecureRandom());
            final SSLSocketFactory factory = clientContext.getSocketFactory();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(
                    plain, "localhost", tlsServer.getLocalPort(), true)) {
                tls.startHandshake();
                tls.getOutputStream().write("secure".getBytes(StandardCharsets.US_ASCII));
                tls.getOutputStream().flush();
                assertEquals("secure", readExactly(tls, 6));
            }
            echo.join(3000);
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
        } finally {
            Files.deleteIfExists(keystore);
        }
    }

    private static TargetConnector failingConnector(ProxyFailure failure) {
        return (host, port, timeoutMs) -> {
            throw new ProxyException(failure, "test failure");
        };
    }

    private static Socket connect(int port) throws IOException {
        final Socket socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private static Socket socksConnect(int proxyPort, String host, int targetPort) throws Exception {
        final Socket socket = socksConnectRaw(proxyPort, host, targetPort);
        if (readReplyCode(socket) != 0) {
            throw new IOException("socks connect failed");
        }
        return socket;
    }

    private static Socket socksConnectRaw(int proxyPort, String host, int targetPort) throws Exception {
        final Socket socket = connect(proxyPort);
        socksGreeting(socket);
        sendSocksRequest(socket, host, targetPort);
        return socket;
    }

    private static void socksGreeting(Socket socket) throws IOException {
        socket.getOutputStream().write(new byte[] {5, 1, 0});
        socket.getOutputStream().flush();
        final byte[] reply = new byte[2];
        readFully(socket.getInputStream(), reply);
        assertEquals(5, reply[0]);
        assertEquals(0, reply[1]);
    }

    private static void sendSocksRequest(Socket socket, String host, int targetPort) throws IOException {
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(5);
        request.write(1);
        request.write(0);
        final InetAddress literal = parseLiteral(host);
        if (literal != null) {
            request.write(1);
            request.write(literal.getAddress());
        } else {
            final byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            request.write(3);
            request.write(name.length);
            request.write(name);
        }
        request.write((targetPort >> 8) & 0xFF);
        request.write(targetPort & 0xFF);
        socket.getOutputStream().write(request.toByteArray());
        socket.getOutputStream().flush();
    }

    private static InetAddress parseLiteral(String host) {
        try {
            if (host.chars().allMatch(character -> Character.isDigit(character) || character == '.')) {
                return InetAddress.getByName(host);
            }
        } catch (IOException ignored) {
            // Not a literal; fall through to a domain.
        }
        return null;
    }

    private static int readReplyCode(Socket socket) throws IOException {
        final byte[] reply = new byte[10];
        readFully(socket.getInputStream(), reply);
        assertEquals(5, reply[0]);
        return reply[1] & 0xFF;
    }

    private static Socket httpConnect(int proxyPort, String host, int targetPort) throws Exception {
        final Socket socket = connect(proxyPort);
        socket.getOutputStream().write(("CONNECT " + host + ":" + targetPort
                + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        final String response = readHeaders(socket);
        if (!response.startsWith("HTTP/1.1 200")) {
            throw new IOException("http connect failed");
        }
        return socket;
    }

    private static String readHeaders(Socket socket) throws IOException {
        final ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        for (;;) {
            final int value = socket.getInputStream().read();
            if (value < 0) {
                break;
            }
            header.write(value);
            if (matched == 0 && value == '\r') {
                matched = 1;
            } else if (matched == 1 && value == '\n') {
                matched = 2;
            } else if (matched == 2 && value == '\r') {
                matched = 3;
            } else if (matched == 3 && value == '\n') {
                matched = 4;
                break;
            } else {
                matched = value == '\r' ? 1 : 0;
            }
            if (header.size() > 16384) {
                throw new IOException("headers too large");
            }
        }
        return header.toString(StandardCharsets.US_ASCII);
    }

    private static void readFully(InputStream input, byte[] destination) throws IOException {
        int offset = 0;
        while (offset < destination.length) {
            final int read = input.read(destination, offset, destination.length - offset);
            if (read < 0) {
                throw new IOException("unexpected eof");
            }
            offset += read;
        }
    }

    private static String readExactly(Socket socket, int length) throws IOException {
        final byte[] data = new byte[length];
        readFully(socket.getInputStream(), data);
        return new String(data, StandardCharsets.US_ASCII);
    }

    private static String readExactly(SSLSocket socket, int length) throws IOException {
        final byte[] data = new byte[length];
        readFully(socket.getInputStream(), data);
        return new String(data, StandardCharsets.US_ASCII);
    }

    private static void waitFor(Check check, long timeoutMs) throws Exception {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (check.ok()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue("condition was not met", check.ok());
    }

    private interface Check {
        boolean ok();
    }

    private static final class EchoServer implements Closeable {
        private final ServerSocket server;
        private final Thread thread;

        EchoServer() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::run, "test-echo");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void run() {
            while (!server.isClosed()) {
                try {
                    final Socket client = server.accept();
                    final Thread worker = new Thread(() -> echo(client), "test-echo-conn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException failure) {
                    return;
                }
            }
        }

        private void echo(Socket client) {
            try (Socket socket = client) {
                final byte[] buffer = new byte[4096];
                for (;;) {
                    final int read = socket.getInputStream().read(buffer);
                    if (read < 0) {
                        return;
                    }
                    socket.getOutputStream().write(buffer, 0, read);
                    socket.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                // The test closes the peer.
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            thread.interrupt();
        }
    }

    private static final class ResponseAfterEofServer implements Closeable {
        private final ServerSocket server;
        private final Thread thread;

        ResponseAfterEofServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::run, "test-response");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void run() {
            try (Socket client = server.accept()) {
                final byte[] buffer = new byte[1024];
                while (client.getInputStream().read(buffer) >= 0) {
                    // Drain until the peer half-closes.
                }
                client.getOutputStream().write("done".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            } catch (IOException ignored) {
                // The test may close the proxy early.
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            thread.interrupt();
        }
    }

    private static final class SocketChannel implements FastLaneChannel {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;

        SocketChannel(Socket socket) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        }

        @Override
        public InputStream inputStream() {
            return input;
        }

        @Override
        public OutputStream outputStream() {
            return output;
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Test-only trust.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Test-only trust.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
