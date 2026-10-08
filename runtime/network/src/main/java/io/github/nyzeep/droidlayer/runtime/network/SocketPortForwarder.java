package io.github.nyzeep.droidlayer.runtime.network;

import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JVM-level live TCP socket port forwarder implementing {@link PortForwardingEngine}.
 *
 * <p>Serves dynamic port forwarding rules by opening host-side {@link ServerSocket} listeners
 * and relaying bidirectional TCP byte streams to target guest endpoints.
 * Hot-removal immediately unbinds and closes the host port (Ticket 29 AC-1, AC-2).
 */
public final class SocketPortForwarder implements PortForwardingEngine, Closeable {
    private final Map<String, ActiveListener> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orchestella-port-forwarder");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    private static final class ActiveListener {
        final ServerSocket serverSocket;
        final PortForwardingRule rule;

        ActiveListener(ServerSocket serverSocket, PortForwardingRule rule) {
            this.serverSocket = serverSocket;
            this.rule = rule;
        }

        void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
    }

    public SocketPortForwarder() {}

    @Override
    public void addRule(PortForwardingRule rule) throws IOException {
        Objects.requireNonNull(rule, "rule");
        if (!running) {
            throw new IOException("SocketPortForwarder is closed");
        }

        InetAddress bindAddr = InetAddress.getByName(rule.bindAddress());
        ServerSocket serverSocket = new ServerSocket(rule.hostPort(), 50, bindAddr);

        ActiveListener listener = new ActiveListener(serverSocket, rule);
        listeners.put(rule.id(), listener);

        executor.submit(() -> acceptLoop(listener));
    }

    @Override
    public void removeRule(PortForwardingRule rule) {
        Objects.requireNonNull(rule, "rule");
        ActiveListener listener = listeners.remove(rule.id());
        if (listener != null) {
            listener.close();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        running = false;
        for (ActiveListener listener : listeners.values()) {
            listener.close();
        }
        listeners.clear();
        executor.shutdownNow();
    }

    private void acceptLoop(ActiveListener listener) {
        while (running && !listener.serverSocket.isClosed()) {
            try {
                Socket clientSocket = listener.serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket, listener.rule));
            } catch (SocketException e) {
                // Server socket closed on rule removal
                break;
            } catch (IOException e) {
                if (listener.serverSocket.isClosed()) {
                    break;
                }
            }
        }
    }

    private void handleClient(Socket clientSocket, PortForwardingRule rule) {
        try (clientSocket) {
            try (Socket targetSocket = new Socket("127.0.0.1", rule.guestPort())) {
                Thread t1 = new Thread(() -> relay(clientSocket, targetSocket), "forward-c2s");
                Thread t2 = new Thread(() -> relay(targetSocket, clientSocket), "forward-s2c");
                t1.setDaemon(true);
                t2.setDaemon(true);
                t1.start();
                t2.start();
                t1.join();
                t2.join();
            }
        } catch (Exception ignored) {
        }
    }

    private static void relay(Socket source, Socket dest) {
        try {
            InputStream in = source.getInputStream();
            OutputStream out = dest.getOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }
}
