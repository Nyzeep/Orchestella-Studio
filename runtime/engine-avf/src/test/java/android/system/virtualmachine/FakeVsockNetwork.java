package android.system.virtualmachine;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Socket-backed stand-in for the vsock namespace shared between the fake VM
 * and the simulated guest. Each registered vsock port is backed by a real
 * loopback server socket, so host and guest sides get honest byte-stream
 * semantics (independent directions, close/EOF, half-open writes) without
 * pipe-buffer deadlocks.
 */
public final class FakeVsockNetwork {
    private final Map<Integer, Listener> listeners = new ConcurrentHashMap<>();
    private final ExecutorService handlerPool =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "simulated-avf-guest");
                thread.setDaemon(true);
                return thread;
            });

    /** Registers a guest-side server on the vsock port; daemon acceptor loop. */
    public void listen(int vsockPort, Consumer<Socket> guestHandler) throws IOException {
        Listener listener = new Listener(vsockPort, guestHandler);
        listeners.put(vsockPort, listener);
        listener.start();
    }

    /** Host-side connect; fails like a real vsock connect to an absent server. */
    public VsockConnection connect(int vsockPort) throws IOException {
        Listener listener = listeners.get(vsockPort);
        if (listener == null) {
            throw new IOException("no vsock server on port " + vsockPort);
        }
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.localPort());
        if (!listener.awaitAccepted()) {
            socket.close();
            throw new IOException("vsock server did not accept the connection");
        }
        return new VsockConnection(socket);
    }

    /** Closes all guest-side server sockets and their accepted connections. */
    public void closeGuestSide() {
        for (Listener listener : listeners.values()) {
            listener.close();
        }
    }

    private final class Listener implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final Consumer<Socket> guestHandler;
        private final Thread acceptor;
        private final java.util.Set<Socket> accepted =
                java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private final Semaphore acceptedSignal = new Semaphore(0);

        Listener(int vsockPort, Consumer<Socket> guestHandler) throws IOException {
            this.serverSocket =
                    new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            this.guestHandler = guestHandler;
            this.acceptor = new Thread(
                    () -> {
                        while (!serverSocket.isClosed()) {
                            try {
                                Socket socket = serverSocket.accept();
                                accepted.add(socket);
                                acceptedSignal.release();
                                handlerPool.submit(
                                        () -> {
                                            try {
                                                guestHandler.accept(socket);
                                            } catch (RuntimeException ignored) {
                                                // simulated guest handler failure stays local
                                            }
                                        });
                            } catch (IOException closed) {
                                return;
                            }
                        }
                    },
                    "vsock-listener-" + vsockPort);
            this.acceptor.setDaemon(true);
        }

        void start() {
            acceptor.start();
        }

        int localPort() {
            return serverSocket.getLocalPort();
        }

        boolean awaitAccepted() throws IOException {
            try {
                return acceptedSignal.tryAcquire(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for vsock accept", interrupted);
            }
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // closing twice is fine for a test fixture
            }
            // A dying VM drops its accepted connections: the host observes EOF.
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // already closed
                    }
                }
            }
        }
    }
}
