package io.github.nyzeep.droidlayer.runtime.network;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Host-side Virtio-Serial Bridge Proxy Gateway (ADR-0008, ADR-0010, Phase 4.5).
 *
 * <p>Serves as high-performance proxy relay between Android Host network stack and
 * Linux Guest operating system across the dedicated {@code orchestella.proxy} virtio-serial port:
 * <ul>
 *   <li>Demultiplexes concurrent client streams using 9-byte framed protocol across the single serial channel;</li>
 *   <li>Intercepts standard {@code CONNECT host:port HTTP/1.1} tunnels for HTTPS;</li>
 *   <li>Intercepts direct absolute-URI {@code GET/POST http://host...} requests for HTTP (e.g. Debian APT);</li>
 *   <li>Connects to public endpoints using standard host TCP sockets and relays full-duplex traffic;</li>
 *   <li>Guarantees channel isolation, resource cleanup, and thread-safe serial writes.</li>
 * </ul>
 */
public final class BridgeProxyGateway implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(BridgeProxyGateway.class.getName());

    public static final byte CMD_DATA = 0x01;
    public static final byte CMD_CLOSE = 0x02;
    public static final byte CMD_RESET = 0x03;

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int BUFFER_SIZE = 16 * 1024;

    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final DataInputStream inData;
    private final DataOutputStream outData;
    private final Object outLock = new Object();

    private final Map<Integer, ChannelState> activeChannels = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orchestella-proxy-gateway");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listenerThread;

    public interface SocketFactory {
        Socket createSocket() throws IOException;
    }

    private final SocketFactory socketFactory;

    public BridgeProxyGateway(InputStream in, OutputStream out) {
        this(in, out, Socket::new);
    }

    public BridgeProxyGateway(InputStream in, OutputStream out, SocketFactory socketFactory) {
        this.rawIn = Objects.requireNonNull(in, "in");
        this.rawOut = Objects.requireNonNull(out, "out");
        this.inData = new DataInputStream(in);
        this.outData = new DataOutputStream(out);
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
    }

    /**
     * Starts listening for multiplexed serial frames on a background daemon thread.
     */
    public synchronized void startAsync() {
        if (running.compareAndSet(false, true)) {
            listenerThread = new Thread(this::listenLoop, "bridge-proxy-listener");
            listenerThread.setDaemon(true);
            listenerThread.start();
        }
    }

    /**
     * Main frame decoding loop reading from virtio-serial port.
     */
    private void listenLoop() {
        try {
            while (running.get()) {
                int channelId = inData.readInt();
                byte command = inData.readByte();
                int length = inData.readInt();
                if (length < 0 || length > 16 * 1024 * 1024) {
                    LOGGER.log(Level.WARNING, "Invalid frame length: " + length + " on ch=" + channelId);
                    break;
                }
                byte[] payload = new byte[length];
                if (length > 0) {
                    inData.readFully(payload);
                }

                handleIncomingFrame(channelId, command, payload);
            }
        } catch (EOFException | java.net.SocketException ignored) {
            // Normal teardown
        } catch (IOException error) {
            if (running.get()) {
                LOGGER.log(Level.WARNING, "BridgeProxyGateway listen error: " + error.getMessage(), error);
            }
        } finally {
            closeQuietly();
        }
    }

    private void handleIncomingFrame(int channelId, byte command, byte[] payload) {
        if (command == CMD_CLOSE || command == CMD_RESET) {
            closeChannel(channelId);
            return;
        }

        if (command == CMD_DATA) {
            ChannelState channel = activeChannels.get(channelId);
            if (channel == null) {
                // New channel connection initiated by Guest
                executor.execute(() -> initializeNewChannel(channelId, payload));
            } else {
                // Forward data to existing target socket
                channel.writeToTarget(payload);
            }
        }
    }

    private void initializeNewChannel(int channelId, byte[] initialPayload) {
        String headerStr = new String(initialPayload, StandardCharsets.US_ASCII);
        if (headerStr.startsWith("CONNECT ")) {
            handleConnectRequest(channelId, headerStr, initialPayload);
        } else if (headerStr.startsWith("GET ") || headerStr.startsWith("POST ")
                || headerStr.startsWith("HEAD ") || headerStr.startsWith("PUT ")
                || headerStr.startsWith("DELETE ") || headerStr.startsWith("OPTIONS ")) {
            handlePlainHttpRequest(channelId, headerStr, initialPayload);
        } else {
            LOGGER.log(Level.WARNING, "Unrecognized initial request on ch=" + channelId + ": " + headerStr);
            send502AndReset(channelId, "502 Bad Gateway\r\n\r\nUnsupported request method");
        }
    }

    private void handleConnectRequest(int channelId, String headerStr, byte[] initialPayload) {
        int firstSpace = headerStr.indexOf(' ');
        int secondSpace = headerStr.indexOf(' ', firstSpace + 1);
        if (firstSpace < 0 || secondSpace < 0) {
            send502AndReset(channelId, "502 Bad Gateway\r\n\r\nMalformed CONNECT request");
            return;
        }
        String hostPort = headerStr.substring(firstSpace + 1, secondSpace).trim();
        String host;
        int port = 443;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                port = 443;
            }
        } else {
            host = hostPort;
        }

        try {
            Socket targetSocket = socketFactory.createSocket();
            targetSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            targetSocket.setTcpNoDelay(true);

            ChannelState state = new ChannelState(channelId, targetSocket);
            activeChannels.put(channelId, state);

            // Acknowledge CONNECT with 200 Connection Established
            byte[] okResponse = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: Orchestella-Bridge/1.0\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII);
            sendFrame(channelId, CMD_DATA, okResponse);

            // Spawn target reader thread
            executor.execute(() -> streamTargetToGuest(state));
        } catch (IOException e) {
            LOGGER.log(Level.INFO, "Failed to connect to target " + host + ":" + port + ": " + e.getMessage());
            send502AndReset(channelId, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 17\r\n\r\n502 Bad Gateway\n");
        }
    }

    private void handlePlainHttpRequest(int channelId, String headerStr, byte[] initialPayload) {
        // Extract host from Host: header or absolute URI
        String host = null;
        int port = 80;

        String[] lines = headerStr.split("\r\n");
        for (String line : lines) {
            if (line.regionMatches(true, 0, "Host:", 0, 5)) {
                String val = line.substring(5).trim();
                int colon = val.lastIndexOf(':');
                if (colon > 0) {
                    host = val.substring(0, colon);
                    try {
                        port = Integer.parseInt(val.substring(colon + 1));
                    } catch (NumberFormatException e) {
                        port = 80;
                    }
                } else {
                    host = val;
                }
                break;
            }
        }

        if (host == null) {
            // Check absolute URI in request line (e.g. GET http://deb.debian.org/...)
            int firstSpace = headerStr.indexOf(' ');
            int secondSpace = headerStr.indexOf(' ', firstSpace + 1);
            if (firstSpace > 0 && secondSpace > 0) {
                String uri = headerStr.substring(firstSpace + 1, secondSpace);
                if (uri.startsWith("http://")) {
                    String noProto = uri.substring(7);
                    int slash = noProto.indexOf('/');
                    String hp = slash > 0 ? noProto.substring(0, slash) : noProto;
                    int colon = hp.lastIndexOf(':');
                    if (colon > 0) {
                        host = hp.substring(0, colon);
                        try {
                            port = Integer.parseInt(hp.substring(colon + 1));
                        } catch (NumberFormatException ignored) {}
                    } else {
                        host = hp;
                    }
                }
            }
        }

        if (host == null) {
            send502AndReset(channelId, "502 Bad Gateway\r\n\r\nMissing Host header");
            return;
        }

        try {
            Socket targetSocket = socketFactory.createSocket();
            targetSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            targetSocket.setTcpNoDelay(true);

            ChannelState state = new ChannelState(channelId, targetSocket);
            activeChannels.put(channelId, state);

            // Forward the initial HTTP request to target (rewriting absolute URI to origin-form per RFC 7230 §5.3.2)
            byte[] payloadToSend = initialPayload;
            int firstLineEnd = -1;
            for (int i = 0; i < initialPayload.length - 1; i++) {
                if (initialPayload[i] == '\r' && initialPayload[i + 1] == '\n') {
                    firstLineEnd = i;
                    break;
                }
            }
            if (firstLineEnd > 0) {
                String firstLine = new String(initialPayload, 0, firstLineEnd, StandardCharsets.US_ASCII);
                int sp1 = firstLine.indexOf(' ');
                int sp2 = firstLine.lastIndexOf(' ');
                if (sp1 > 0 && sp2 > sp1) {
                    String method = firstLine.substring(0, sp1);
                    String targetUri = firstLine.substring(sp1 + 1, sp2);
                    String version = firstLine.substring(sp2 + 1);
                    if (targetUri.startsWith("http://")) {
                        int slashIdx = targetUri.indexOf('/', 7);
                        String originPath = (slashIdx >= 0) ? targetUri.substring(slashIdx) : "/";
                        String newFirstLine = method + " " + originPath + " " + version;
                        byte[] newFirstLineBytes = newFirstLine.getBytes(StandardCharsets.US_ASCII);
                        int remainingLen = initialPayload.length - firstLineEnd;
                        byte[] combined = new byte[newFirstLineBytes.length + remainingLen];
                        System.arraycopy(newFirstLineBytes, 0, combined, 0, newFirstLineBytes.length);
                        System.arraycopy(initialPayload, firstLineEnd, combined, newFirstLineBytes.length, remainingLen);
                        payloadToSend = combined;
                    }
                }
            }

            state.writeToTarget(payloadToSend);

            // Spawn target reader thread
            executor.execute(() -> streamTargetToGuest(state));
        } catch (IOException e) {
            LOGGER.log(Level.INFO, "Failed to connect to HTTP host " + host + ":" + port + ": " + e.getMessage());
            send502AndReset(channelId, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 17\r\n\r\n502 Bad Gateway\n");
        }
    }

    private void streamTargetToGuest(ChannelState state) {
        byte[] buf = new byte[BUFFER_SIZE];
        try (InputStream targetIn = state.socket.getInputStream()) {
            int read;
            while (running.get() && (read = targetIn.read(buf)) != -1) {
                byte[] chunk = new byte[read];
                System.arraycopy(buf, 0, chunk, 0, read);
                sendFrame(state.channelId, CMD_DATA, chunk);
            }
        } catch (IOException ignored) {
        } finally {
            try {
                sendFrame(state.channelId, CMD_CLOSE, new byte[0]);
            } catch (IOException ignored) {}
            closeChannel(state.channelId);
        }
    }

    private void send502AndReset(int channelId, String errorBody) {
        try {
            byte[] payload = errorBody.getBytes(StandardCharsets.US_ASCII);
            sendFrame(channelId, CMD_DATA, payload);
            sendFrame(channelId, CMD_RESET, new byte[0]);
        } catch (IOException ignored) {}
        closeChannel(channelId);
    }

    /**
     * Sends a framed packet across the virtio-serial output stream in a synchronized block.
     */
    public void sendFrame(int channelId, byte command, byte[] payload) throws IOException {
        synchronized (outLock) {
            outData.writeInt(channelId);
            outData.writeByte(command);
            int len = payload != null ? payload.length : 0;
            outData.writeInt(len);
            if (len > 0) {
                outData.write(payload);
            }
            outData.flush();
        }
    }

    private void closeChannel(int channelId) {
        ChannelState state = activeChannels.remove(channelId);
        if (state != null) {
            state.close();
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        running.set(false);
        for (ChannelState state : activeChannels.values()) {
            state.close();
        }
        activeChannels.clear();
        try {
            rawIn.close();
        } catch (IOException ignored) {}
        try {
            rawOut.close();
        } catch (IOException ignored) {}
        executor.shutdownNow();
    }

    private static final class ChannelState {
        final int channelId;
        final Socket socket;
        final OutputStream targetOut;

        ChannelState(int channelId, Socket socket) throws IOException {
            this.channelId = channelId;
            this.socket = socket;
            this.targetOut = socket.getOutputStream();
        }

        synchronized void writeToTarget(byte[] data) {
            try {
                targetOut.write(data);
                targetOut.flush();
            } catch (IOException ignored) {}
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
