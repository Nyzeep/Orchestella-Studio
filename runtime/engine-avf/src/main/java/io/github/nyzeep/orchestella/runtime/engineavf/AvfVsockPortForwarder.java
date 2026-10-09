package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
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
 * The AVF profile's live port-forwarding engine (Ticket 36 AC-2): host traffic
 * accepted on a rule's host port is tunneled over vsock to the in-guest
 * forward proxy, which relays to the guest-local service on
 * {@code 127.0.0.1:<guestPort>}. This is the AVF counterpart of the QEMU
 * SLIRP {@code hostfwd} path and delivers the SAME hot-update contract:
 * {@link #addRule} binds and starts serving without any VM restart and
 * {@link #removeRule} unbinds the host port immediately, so new connections
 * are refused at once (ADR-0008; hot-update semantics identical to Ticket 29).
 *
 * <p>Guest-bound channels are obtained through {@link VsockChannelFactory} —
 * production wires {@link AvfVirtualMachineEngine.AvfVmSession#openChannel},
 * JVM tests inject the fake vsock network. Connection-shape semantics mirror
 * {@code SocketPortForwarder} exactly so the two strategies stay
 * contract-equivalent.
 */
public final class AvfVsockPortForwarder implements PortForwardingEngine, AutoCloseable {

    /** Opens one guest-bound vsock channel for a forwarded guest service port. */
    @FunctionalInterface
    public interface VsockChannelFactory {
        AvfVirtualMachineApi.AvfVsockChannel connect(int guestServicePort) throws IOException;
    }

    private final VsockChannelFactory channelFactory;
    private final Map<String, ActiveListener> listeners = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "droidlayer-avf-port-forwarder");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public AvfVsockPortForwarder(VsockChannelFactory channelFactory) {
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
    }

    @Override
    public void addRule(PortForwardingRule rule) throws IOException {
        Objects.requireNonNull(rule, "rule");
        if (!running) {
            throw new IOException("AvfVsockPortForwarder is closed");
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
            AvfVirtualMachineApi.AvfVsockChannel channel = channelFactory.connect(rule.guestPort());
            try (channel) {
                Thread hostToGuest = relay(clientSocket.getInputStream(), channel.outputStream());
                Thread guestToHost = relay(channel.inputStream(), clientSocket.getOutputStream());
                hostToGuest.join();
                guestToHost.join();
            }
        } catch (Exception ignored) {
            // vsock connect failure or stream end: the forwarded flow dies locally,
            // exactly like an unreachable guest target on the QEMU path
        }
    }

    private static Thread relay(InputStream in, OutputStream out) {
        Thread thread = new Thread(() -> {
            byte[] buf = new byte[4096];
            int n;
            try {
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // relay direction ended
            }
        }, "droidlayer-avf-forward-relay");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

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
}
