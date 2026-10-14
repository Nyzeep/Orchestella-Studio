package io.github.nyzeep.orchestella.runtime.contract.services;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns one externally launched service; health is an actual, non-redirecting local HTTP probe. */
public final class ScopedService implements AutoCloseable {
    public enum State { STOPPED, STARTING, HEALTHY, UNHEALTHY, FAILED, CLOSED }
    public record Snapshot(State state, URI origin, String failure) { }
    @FunctionalInterface public interface Launcher {
        Handle launch(ServiceCredential credential) throws IOException;
    }
    /** Adapter must own the process and its listener; liveness alone never establishes health. */
    public interface Handle extends AutoCloseable {
        boolean isAlive();
        @Override void close() throws IOException;
    }

    private final EndpointPolicy endpoints;
    private final Launcher launcher;
    private final ServiceCredential credential;
    private final long startupNanos;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "orchestella-service-health");
        thread.setDaemon(true);
        return thread;
    });
    private LocalTcpRelay lan;
    private Handle handle;
    private State state = State.STOPPED;
    private String failure;
    private long startupDeadline;

    public ScopedService(EndpointPolicy endpoints, Launcher launcher, ServiceCredential credential,
                         Duration startupTimeout) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.credential = Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(startupTimeout, "startupTimeout");
        if (startupTimeout.isNegative() || startupTimeout.isZero()
                || startupTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Startup timeout must be within one minute");
        }
        startupNanos = startupTimeout.toNanos();
        monitor.scheduleWithFixedDelay(() -> {
            synchronized (this) {
                if (state != State.CLOSED && lan != null && lan.binding() != null) {
                    try { probe(); } catch (RuntimeException error) { fail("service-monitor-failed"); }
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized Snapshot snapshot() { return new Snapshot(state, endpoints.origin(), failure); }

    public synchronized Snapshot start() throws IOException {
        requireOpen();
        credential.requireAvailable();
        if (handle != null) {
            if (state == State.FAILED) { throw new IOException("Failed service cleanup must be retried before start"); }
            return probe();
        }
        if (isListening()) {
            state = State.FAILED;
            failure = "port-conflict";
            throw new IOException("Selected service port is occupied");
        }
        state = State.STARTING;
        failure = null;
        startupDeadline = System.nanoTime() + startupNanos;
        try {
            handle = Objects.requireNonNull(launcher.launch(credential), "launcher handle");
        } catch (IOException | RuntimeException error) {
            state = State.FAILED;
            failure = "service-launch-failed";
            throw new IOException("Service launch failed");
        }
        return probe();
    }

    public synchronized Snapshot probe() {
        requireOpen();
        if (handle == null || state == State.FAILED) { return snapshot(); }
        if (!handle.isAlive()) { return fail("service-exited"); }
        HttpURLConnection connection = null;
        try {
            URI uri = endpoints.requireAllowed(endpoints.origin().resolve("healthz").toString());
            connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            if (!handle.isAlive()) { return fail("service-exited"); }
            state = code == 200 ? State.HEALTHY : State.UNHEALTHY;
            failure = code == 200 ? null : "http-health-rejected";
            if (state != State.HEALTHY) {
                try { disableLan(); } catch (IOException error) { return fail("lan-close-failed"); }
            }
        } catch (IOException error) {
            if (state != State.STARTING || System.nanoTime() - startupDeadline >= 0) {
                return fail("health-listener-unavailable");
            }
            failure = "awaiting-listener";
        } finally {
            if (connection != null) { connection.disconnect(); }
        }
        return snapshot();
    }

    public synchronized LocalTcpRelay.Binding enableLan(int port, LocalTcpRelay.LanLease lease) throws IOException {
        requireOpen();
        credential.requireAvailable();
        if (probe().state() != State.HEALTHY) {
            throw new IllegalStateException("Healthy authenticated service is required for LAN");
        }
        if (lan != null && lan.binding() != null) { throw new IllegalStateException("LAN is already enabled"); }
        disableLan();
        LocalTcpRelay candidate = new LocalTcpRelay(endpoints.origin().getPort());
        try {
            LocalTcpRelay.Binding result = candidate.startLan(port, lease);
            lan = candidate;
            return result;
        } catch (IOException | RuntimeException error) {
            candidate.close();
            throw error;
        }
    }

    public synchronized LocalTcpRelay.Binding lanBinding() { return lan == null ? null : lan.binding(); }

    public synchronized void disableLan() throws IOException {
        LocalTcpRelay old = lan;
        lan = null;
        if (old != null) { old.close(); }
    }

    public synchronized void stop() throws IOException {
        requireOpen();
        IOException lanFailure = null;
        try { disableLan(); } catch (IOException error) { lanFailure = error; }
        Handle old = handle;
        try {
            if (old != null) { old.close(); }
            if (old != null && isListening()) {
                state = State.FAILED;
                failure = "listener-remains-after-stop";
                throw new IOException("Service listener remains after stop");
            }
            handle = null;
            if (lanFailure != null) { throw lanFailure; }
            state = State.STOPPED;
            failure = null;
        } catch (IOException error) {
            state = State.FAILED;
            failure = "service-stop-failed";
            throw error;
        }
    }

    private Snapshot fail(String code) {
        state = State.FAILED;
        failure = code;
        try { disableLan(); } catch (IOException error) { failure = "lan-close-failed"; }
        Handle old = handle;
        if (old != null) {
            try { old.close(); handle = null; }
            catch (IOException error) { failure = "service-cleanup-failed"; }
        }
        return snapshot();
    }

    private boolean isListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", endpoints.origin().getPort()), 250);
            return true;
        } catch (IOException absent) { return false; }
    }

    private void requireOpen() {
        if (state == State.CLOSED) { throw new IllegalStateException("Service scope is closed"); }
    }

    @Override public synchronized void close() throws IOException {
        if (state == State.CLOSED) { return; }
        try { stop(); state = State.CLOSED; } finally { monitor.shutdownNow(); }
    }
}
