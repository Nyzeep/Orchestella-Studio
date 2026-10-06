package io.github.nyzeep.droidlayer.runtime.network.download;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight, zero-dependency Mock HTTP Server for testing HTTP Range resumption,
 * connection dropping, server error codes, and header inspection.
 */
public final class MockHttpServer implements Closeable {

    public enum Mode {
        STANDARD,          // Supports 200 and 206 Partial Content
        NO_RANGE_SUPPORT,  // Always returns 200 OK even if Range header requested
        RETURN_416,        // Returns 416 Range Not Satisfiable
        RETURN_404,        // Returns 404 Not Found
        RETURN_500,        // Returns 500 Internal Server Error
        DROP_AFTER_BYTES   // Sends specified bytes then forcibly drops connection
    }

    private final ServerSocket serverSocket;
    private final Thread listenerThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final List<Map<String, String>> receivedHeaders = Collections.synchronizedList(new ArrayList<>());

    private volatile byte[] payload;
    private volatile Mode mode = Mode.STANDARD;
    private volatile int dropAfterBytes = 0;

    public MockHttpServer(byte[] payload) throws IOException {
        this.payload = payload != null ? payload.clone() : new byte[0];
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.listenerThread = new Thread(this::acceptLoop, "MockHttpServer-" + serverSocket.getLocalPort());
        this.listenerThread.setDaemon(true);
        this.listenerThread.start();
    }

    public void setPayload(byte[] payload) {
        this.payload = payload != null ? payload.clone() : new byte[0];
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setDropAfterBytes(int bytes) {
        this.mode = Mode.DROP_AFTER_BYTES;
        this.dropAfterBytes = bytes;
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public String url() {
        return "http://127.0.0.1:" + getPort() + "/asset.bin";
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    public List<Map<String, String>> getReceivedHeaders() {
        return new ArrayList<>(receivedHeaders);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                handleClient(client);
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = client.getOutputStream()) {

            requestCount.incrementAndGet();
            String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
                }
            }
            receivedHeaders.add(headers);

            Mode currentMode = this.mode;
            byte[] currentPayload = this.payload;

            switch (currentMode) {
                case RETURN_404 -> {
                    byte[] body = "Not Found".getBytes(StandardCharsets.UTF_8);
                    String header = "HTTP/1.1 404 Not Found\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                }
                case RETURN_500 -> {
                    byte[] body = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
                    String header = "HTTP/1.1 500 Internal Server Error\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                }
                case RETURN_416 -> {
                    String header = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                            + "Content-Range: bytes */" + currentPayload.length + "\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                case NO_RANGE_SUPPORT -> {
                    // Always respond with 200 OK
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + currentPayload.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.write(currentPayload);
                    out.flush();
                }
                case DROP_AFTER_BYTES -> {
                    // Send 200 OK header, send dropAfterBytes, then abruptly close socket
                    String header = "HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + currentPayload.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    int bytesToSend = Math.min(dropAfterBytes, currentPayload.length);
                    out.write(currentPayload, 0, bytesToSend);
                    out.flush();
                    // Hard close
                    client.close();
                }
                case STANDARD -> {
                    String rangeHeader = headers.get("range");
                    if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                        long offset = parseRangeStart(rangeHeader);
                        if (offset >= currentPayload.length) {
                            String header = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                                    + "Content-Range: bytes */" + currentPayload.length + "\r\n"
                                    + "Content-Length: 0\r\n"
                                    + "Connection: close\r\n\r\n";
                            out.write(header.getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            return;
                        }

                        int start = (int) offset;
                        int end = currentPayload.length - 1;
                        int length = currentPayload.length - start;

                        String header = "HTTP/1.1 206 Partial Content\r\n"
                                + "Content-Range: bytes " + start + "-" + end + "/" + currentPayload.length + "\r\n"
                                + "Content-Length: " + length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        out.write(header.getBytes(StandardCharsets.US_ASCII));
                        out.write(currentPayload, start, length);
                        out.flush();
                    } else {
                        String header = "HTTP/1.1 200 OK\r\n"
                                + "Content-Length: " + currentPayload.length + "\r\n"
                                + "Connection: close\r\n\r\n";
                        out.write(header.getBytes(StandardCharsets.US_ASCII));
                        out.write(currentPayload);
                        out.flush();
                    }
                }
            }
        } catch (IOException ignored) {
            // Expected during abrupt drops
        }
    }

    private static long parseRangeStart(String rangeHeader) {
        try {
            String value = rangeHeader.substring("bytes=".length()).trim();
            int dash = value.indexOf('-');
            if (dash > 0) {
                return Long.parseLong(value.substring(0, dash));
            } else if (dash == 0) {
                return 0L;
            } else {
                return Long.parseLong(value);
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {}
    }
}
