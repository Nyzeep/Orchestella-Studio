package io.github.nyzeep.orchestella;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlNetworkProfile;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter;
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore;
import io.github.nyzeep.orchestella.runtime.network.LoopbackEndpointRegistry;
import io.github.nyzeep.orchestella.runtime.network.SocketPortForwarder;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Per-session passt owner and authenticated transport for loopback TCP rules. */
final class UmlPasstNetwork implements PortForwardingEngine.Live {
    enum Status {
        WAITING_FOR_SHELL, STARTING, OFFLINE, NO_IPV4_DNS, READY,
        MISSING_HELPER, TRANSPORT_UNAVAILABLE, HELPER_EXITED, GUEST_SETUP_UNAVAILABLE, STOPPED
    }

    @FunctionalInterface
    interface ExecutableSupplier {
        String resolve() throws IOException;
    }

    interface PasstProcess extends Closeable {
        int pid();
        void pump(int guestFd);
        void stopPump();
        boolean reap(boolean force);
        @Override void close();
    }

    @FunctionalInterface
    interface PasstLauncher {
        PasstProcess spawn(String[] argv, Path pidFile) throws IOException;
    }

    interface RouteMonitor {
        HostRoute snapshot();
        AutoCloseable observe(Runnable onChange);
    }

    record HostRoute(Object networkKey, String dns) {
        Status status() {
            if (networkKey == null) return Status.OFFLINE;
            return dns == null ? Status.NO_IPV4_DNS : Status.READY;
        }
    }

    private static final String TAG = "OrchestellaUmlNetwork";
    private static final String PASST_LIBRARY = "libuml_passt.so";
    private static final String LOOPBACK = PortForwardingRule.LOOPBACK_ADDRESS;
    private static final String UNREACHABLE_RESOLVER = "192.0.2.53";
    private static final int START_ATTEMPTS = 3;
    private static final long START_TIMEOUT_MS = 8_000L;
    private static final long HEALTHY_INTERVAL_MS = 30_000L;
    private static final int AUTH_TIMEOUT_MS = 2_000;
    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final long FORCE_AFTER_MS = 250L;
    private static final long CLOSE_TIMEOUT_MS = 1_500L;
    private static final long READINESS_JOIN_TIMEOUT_MS = 500L;
    private static final int DEFAULT_CONNECTION_LIMIT = 12;
    private static final int TCP_IDLE_TIMEOUT_MS = 60_000;
    private static final Set<Integer> RESERVED_RELAY_PORTS = ConcurrentHashMap.newKeySet();

    private static final class Attempt {
        final int pid;
        final int relayPort;
        final PasstProcess process;
        final HostRoute route;
        boolean cancelled;
        boolean intentionalCancel;
        boolean ready;

        Attempt(int pid, int relayPort, PasstProcess process, HostRoute route) {
            this.pid = pid;
            this.relayPort = relayPort;
            this.process = process;
            this.route = route;
        }
    }

    private final Object lock = new Object();
    private final Path runtimeDir;
    private final FilePortForwardingStore store;
    private final String instanceId;
    private final String generation;
    private final String token;
    private final ExecutableSupplier executableSupplier;
    private final int guestFd;
    private final Closeable guestSocketCloser;
    private final PasstLauncher passtLauncher;
    private final RouteMonitor routeMonitor;
    private final LoopbackEndpointRegistry endpointRegistry;
    private final Map<String, LoopbackEndpointRegistry.EndpointLease> ownedEndpoints =
            new LinkedHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final SocketPortForwarder forwarding = new SocketPortForwarder(
            this::connectGuest, DEFAULT_CONNECTION_LIMIT, TCP_IDLE_TIMEOUT_MS);
    private volatile Status status = Status.WAITING_FOR_SHELL;
    private String executable;
    private HostRoute route;
    private int attemptCount;
    private int relayLeaseSeq;
    private Thread worker;
    private Attempt attempt;
    private boolean closed;
    private boolean listenersStarted;

    UmlPasstNetwork(Context context, Path runtimeDir, String instanceId, String token,
            ParcelFileDescriptor guestSocket) {
        this(context, runtimeDir, instanceId, "uml-net", token, guestSocket,
                LoopbackEndpointRegistry.defaultRegistry());
    }

    UmlPasstNetwork(Context context, Path runtimeDir, String instanceId, String generation,
            String token, ParcelFileDescriptor guestSocket,
            LoopbackEndpointRegistry endpointRegistry) {
        this(
                runtimeDir,
                context.getFilesDir().toPath().resolve("network"),
                instanceId,
                generation,
                token,
                () -> NativeExecutableStore.ensure(context, PASST_LIBRARY),
                guestSocket != null ? guestSocket.getFd() : -1,
                guestSocket != null ? guestSocket : () -> {},
                androidPasstLauncher(),
                new AndroidRouteMonitor(context.getSystemService(ConnectivityManager.class)),
                endpointRegistry);
    }

    UmlPasstNetwork(
            Path runtimeDir,
            Path networkStoreRoot,
            String instanceId,
            String token,
            ExecutableSupplier executableSupplier,
            int guestFd,
            Closeable guestSocketCloser,
            PasstLauncher passtLauncher,
            RouteMonitor routeMonitor) {
        this(runtimeDir, networkStoreRoot, instanceId, "uml-net", token, executableSupplier,
                guestFd, guestSocketCloser, passtLauncher, routeMonitor,
                LoopbackEndpointRegistry.defaultRegistry());
    }

    UmlPasstNetwork(
            Path runtimeDir,
            Path networkStoreRoot,
            String instanceId,
            String generation,
            String token,
            ExecutableSupplier executableSupplier,
            int guestFd,
            Closeable guestSocketCloser,
            PasstLauncher passtLauncher,
            RouteMonitor routeMonitor,
            LoopbackEndpointRegistry endpointRegistry) {
        this.runtimeDir = Objects.requireNonNull(runtimeDir, "runtimeDir");
        this.store = new FilePortForwardingStore(Objects.requireNonNull(networkStoreRoot, "networkStoreRoot"));
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.generation = (generation == null || generation.isBlank()) ? "uml-net" : generation;
        this.token = Objects.requireNonNull(token, "token");
        this.executableSupplier = Objects.requireNonNull(executableSupplier, "executableSupplier");
        this.guestFd = guestFd;
        this.guestSocketCloser = Objects.requireNonNull(guestSocketCloser, "guestSocketCloser");
        this.passtLauncher = Objects.requireNonNull(passtLauncher, "passtLauncher");
        this.routeMonitor = Objects.requireNonNull(routeMonitor, "routeMonitor");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
    }

    void restoreBeforePublish() {
        synchronized (lock) {
            if (guestFd < 0) {
                setStatus(Status.TRANSPORT_UNAVAILABLE);
                return;
            }
            try {
                executable = executableSupplier.resolve();
            } catch (IOException | RuntimeException unavailable) {
                setStatus(Status.MISSING_HELPER);
                return;
            }
            restoreRules();
            listenersStarted = true;
        }
    }

    void start() {
        synchronized (lock) {
            if (closed || worker != null || executable == null) return;
            worker = new Thread(this::run, "uml-passt-" + instanceId);
            worker.setDaemon(true);
            worker.start();
        }
    }

    Status status() {
        return status;
    }

    private void run() {
        AutoCloseable observerHandle = null;
        try {
            try {
                observerHandle = routeMonitor.observe(this::refreshRoute);
            } catch (RuntimeException unavailable) {
                // A launch-time snapshot can still be used on a restricted host.
            }
            refreshRoute();
            HostRoute attemptedRoute = null;
            while (true) {
                final HostRoute next;
                synchronized (lock) {
                    while (!closed && attemptCount >= START_ATTEMPTS && Objects.equals(attemptedRoute, route)) {
                        lock.wait();
                    }
                    if (closed) return;
                    next = route;
                    if (!Objects.equals(next, attemptedRoute)) attemptCount = 0;
                    attemptedRoute = next;
                    attemptCount++;
                    setStatus(Status.STARTING);
                }
                long startedAt = monotonicMillis();
                boolean reachedGuest = runAttempt(executable, next);
                synchronized (lock) {
                    if (reachedGuest && monotonicMillis() - startedAt >= HEALTHY_INTERVAL_MS) {
                        attemptCount = 0;
                    }
                    if (!closed && Objects.equals(next, route) && attemptCount < START_ATTEMPTS) {
                        // A route callback or refresh() wakes this wait immediately. Crashes have a bounded budget.
                        lock.wait(250L << attemptCount);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (observerHandle != null) {
                try {
                    observerHandle.close();
                } catch (Exception ignored) {
                    // The observer was already unregistered.
                }
            }
            synchronized (lock) {
                if (closed) finishClose();
            }
        }
    }

    private void refreshRoute() {
        // Serialize snapshots and callbacks so a late notification cannot publish an older network.
        synchronized (lock) {
            if (closed) return;
            HostRoute latest = routeMonitor.snapshot();
            if (!Objects.equals(latest, route)) {
                route = latest;
                cancelAttemptLocked(true);
                lock.notifyAll();
            }
        }
    }

    private boolean runAttempt(String executable, HostRoute next) {
        Path pidFile = null;
        Integer reservedPort = null;
        LoopbackEndpointRegistry.EndpointLease relayLease = null;
        Attempt candidate = null;
        Thread readiness = null;
        try {
            relayLease = acquireRelayPortLease();
            int port = relayLease.hostPort();
            reservedPort = port;
            pidFile = Files.createTempFile(runtimeDir, "passt-", ".pid");
            PasstProcess process = passtLauncher.spawn(argv(executable, pidFile, port, next.dns()), pidFile);
            candidate = new Attempt(process.pid(), port, process, next);
            final Path readinessFile = pidFile;
            final Attempt active = candidate;
            synchronized (lock) {
                attempt = active;
                if (closed || !next.equals(route)) cancelAttemptLocked(true);
            }
            if (!active.cancelled) {
                readiness = new Thread(
                        () -> confirmReadiness(active, readinessFile),
                        "uml-passt-readiness-" + instanceId);
                readiness.setDaemon(true);
                readiness.start();
                process.pump(guestFd);
            }
        } catch (IOException | RuntimeException unavailable) {
            synchronized (lock) {
                if (!closed && Objects.equals(next, route) && status != Status.GUEST_SETUP_UNAVAILABLE) {
                    setStatus(Status.HELPER_EXITED);
                }
            }
        } finally {
            if (candidate != null) {
                synchronized (lock) {
                    candidate.cancelled = true;
                    candidate.process.stopPump();
                    candidate.process.close();
                }
                if (readiness != null) {
                    try {
                        readiness.join(READINESS_JOIN_TIMEOUT_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                reap(candidate.process);
                synchronized (lock) {
                    if (attempt == candidate) attempt = null;
                    if (!closed && !candidate.intentionalCancel && Objects.equals(next, route)
                            && status != Status.GUEST_SETUP_UNAVAILABLE) {
                        setStatus(Status.HELPER_EXITED);
                    }
                }
            }
            if (relayLease != null) {
                endpointRegistry.release(relayLease);
            }
            if (reservedPort != null) {
                RESERVED_RELAY_PORTS.remove(reservedPort);
            }
            if (pidFile != null) {
                try {
                    Files.deleteIfExists(pidFile);
                } catch (IOException ignored) {
                    // This file contains no address or credentials and cannot be reused by another launch.
                }
            }
        }
        return candidate != null && candidate.ready;
    }

    private LoopbackEndpointRegistry.EndpointLease acquireRelayPortLease() throws IOException {
        final int ordinal;
        synchronized (lock) {
            ordinal = ++relayLeaseSeq;
        }
        String relayRuleId = "passt-relay-" + ordinal;
        for (int attempt = 0; attempt < 16; attempt++) {
            int port;
            try (ServerSocket pickPort = new ServerSocket()) {
                pickPort.bind(new InetSocketAddress(LOOPBACK, 0));
                port = pickPort.getLocalPort();
            }
            if (!PortForwardingRule.isReservedHostPort(port)
                    && (port < GatewaySlotArbiter.PORT_BASE
                            || port >= GatewaySlotArbiter.PORT_BASE + GatewaySlotArbiter.PORT_SLOTS)
                    && RESERVED_RELAY_PORTS.add(port)) {
                try {
                    return endpointRegistry.acquire(
                            LOOPBACK, port, instanceId, generation, relayRuleId);
                } catch (IOException ownedElsewhere) {
                    RESERVED_RELAY_PORTS.remove(port);
                }
            }
        }
        throw new IOException("no unreserved local relay port available");
    }

    static String[] argv(String executable, Path pidFile, int relayPort, String resolver) {
        List<String> args = new ArrayList<>(List.of(
                executable, "-f", "-q", "-F", "3", "-4",
                "-a", UmlNetworkProfile.GUEST_ADDRESS + "/" + UmlNetworkProfile.PREFIX_LENGTH,
                "-g", UmlNetworkProfile.GUEST_GATEWAY, "--no-map-gw", "--no-icmp", "--no-dhcp",
                "--dns-forward", UmlNetworkProfile.GUEST_DNS,
                "--dns-host", resolver != null ? resolver : UNREACHABLE_RESOLVER,
                "-t", LOOPBACK + "/" + relayPort + ":" + UmlNetworkProfile.GUEST_RELAY_PORT,
                "-u", "none", "-P", pidFile.toAbsolutePath().toString()));
        return args.toArray(String[]::new);
    }

    private void confirmReadiness(Attempt candidate, Path pidFile) {
        long deadline = monotonicMillis() + START_TIMEOUT_MS;
        boolean sawHelper = false;
        while (monotonicMillis() < deadline) {
            synchronized (lock) {
                if (candidate.cancelled) return;
            }
            try {
                String owner = new String(Files.readAllBytes(pidFile), StandardCharsets.US_ASCII).trim();
                if (String.valueOf(candidate.pid).equals(owner)) {
                    sawHelper = true;
                    try (Socket probe = authenticate(candidate.relayPort, null)) {
                        synchronized (lock) {
                            if (candidate.cancelled) return;
                            candidate.ready = true;
                            setStatus(candidate.route.status());
                        }
                        return;
                    }
                }
            } catch (IOException ignored) {
                // The guest link may still be coming up. Probe again within the startup budget.
            }
            if (!sleepMillis(100L)) return;
        }
        synchronized (lock) {
            if (candidate.cancelled) return;
            setStatus(sawHelper ? Status.GUEST_SETUP_UNAVAILABLE : Status.HELPER_EXITED);
            cancelAttemptLocked(true);
        }
    }

    private void restoreRules() {
        try {
            for (PortForwardingRule rule : store.loadRules(instanceId)) {
                if (!supportsRule(rule)) continue;
                try {
                    applyRuleWithLeaseLocked(rule, null);
                } catch (IOException refused) {
                    Log.w(TAG, "forwarding=RESTORE_BIND_REFUSED endpoint=USER_LOOPBACK");
                }
            }
        } catch (IOException | RuntimeException refused) {
            Log.w(TAG, "forwarding=RESTORE_CONFIG_UNAVAILABLE endpoint=USER_LOOPBACK");
        }
    }

    private Socket connectGuest(PortForwardingRule rule) throws IOException {
        final Attempt active;
        synchronized (lock) {
            if (closed || attempt == null || !attempt.ready || attempt.cancelled) {
                throw new IOException("UML loopback forwarding relay unavailable");
            }
            active = attempt;
        }
        Socket connection = authenticate(active.relayPort, rule.guestPort());
        synchronized (lock) {
            if (attempt == active && !active.cancelled && !closed) return connection;
        }
        closeQuietly(connection);
        throw new IOException("UML loopback forwarding relay replaced");
    }

    private Socket authenticate(int port, Integer guestPort) throws IOException {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String challenge = StorageContract.bytesToHex(nonce);
        Socket socket = new Socket();
        boolean authenticated = false;
        try {
            socket.connect(new InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS);
            long authDeadline = monotonicMillis() + AUTH_TIMEOUT_MS;
            var output = socket.getOutputStream();
            output.write(("H1 " + challenge + "\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            String[] response = readLine(socket, authDeadline).split(" ", -1);
            if (response.length != 3 || !"G1".equals(response[0])
                    || !response[2].matches("[0-9a-f]{64}")
                    || !equalProof(response[1], digest("G1:" + challenge + ":" + token))) {
                throw new IOException("guest forwarding relay did not authenticate");
            }
            if (guestPort != null) {
                String proof = digest("F1:" + response[2] + ":" + guestPort + ":" + token);
                output.write(("F1 " + guestPort + " " + proof + "\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                if (!"OK".equals(readLine(socket, authDeadline))) {
                    throw new IOException("guest forwarding relay declined");
                }
            }
            authenticated = true;
            return socket;
        } finally {
            if (!authenticated) closeQuietly(socket);
        }
    }

    private static String readLine(Socket socket, long deadline) throws IOException {
        InputStream input = socket.getInputStream();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 135; i++) {
            long remaining = deadline - monotonicMillis();
            if (remaining <= 0) throw new SocketTimeoutException("guest relay authentication timeout");
            socket.setSoTimeout((int) remaining);
            int value = input.read();
            if (value == '\n') return line.toString();
            if (value < 32 || value > 126) break;
            line.append((char) value);
        }
        throw new IOException("invalid guest forwarding reply");
    }

    private static boolean equalProof(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String digest(String message) {
        return StorageContract.sha256Hex(message.getBytes(StandardCharsets.US_ASCII));
    }

    private void cancelAttemptLocked(boolean intentional) {
        if (attempt != null) {
            if (intentional) attempt.intentionalCancel = true;
            if (!attempt.cancelled) {
                attempt.cancelled = true;
                attempt.process.stopPump();
            }
        }
    }

    private static void reap(PasstProcess process) {
        long forceAt = monotonicMillis() + FORCE_AFTER_MS;
        while (!process.reap(monotonicMillis() >= forceAt)) {
            if (!sleepMillis(40L)) return;
        }
    }

    void closeAfterGuestExit() {
        final Thread running;
        synchronized (lock) {
            closed = true;
            cancelAttemptLocked(true);
            lock.notifyAll();
            running = worker;
        }
        if (running != null) {
            try {
                running.join(CLOSE_TIMEOUT_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (running.isAlive()) {
                // Existing supervisor retains the session/root reservation until this worker reaps.
                throw new IllegalStateException("UML passt exit unconfirmed");
            }
        }
        synchronized (lock) {
            finishClose();
        }
    }

    private void finishClose() {
        forwarding.close();
        for (LoopbackEndpointRegistry.EndpointLease lease : ownedEndpoints.values()) {
            endpointRegistry.release(lease);
        }
        ownedEndpoints.clear();
        closeQuietly(guestSocketCloser);
        setStatus(Status.STOPPED);
    }

    private void setStatus(Status next) {
        Status previous = status;
        status = next;
        if (previous != next) Log.i(TAG, "status=" + next.name() + " endpoint=UML_IPV4");
    }

    @Override
    public boolean supportsRule(PortForwardingRule rule) {
        return rule.protocol() == Protocol.TCP && LOOPBACK.equals(rule.bindAddress())
                && !PortForwardingRule.isReservedHostPort(rule.hostPort())
                && rule.guestPort() != UmlNetworkProfile.GUEST_RELAY_PORT;
    }

    @Override
    public boolean isRunning() {
        synchronized (lock) {
            return !closed && listenersStarted;
        }
    }

    private void requireListeners() throws IOException {
        if (!isRunning()) throw new IOException("UML loopback listeners unavailable");
    }

    @Override
    public void addRule(PortForwardingRule rule) throws IOException {
        synchronized (lock) {
            requireListeners();
            if (!supportsRule(rule)) throw new IOException("unsupported UML loopback forwarding rule");
            applyRuleWithLeaseLocked(rule, null);
        }
    }

    @Override
    public void replaceRule(PortForwardingRule previous, PortForwardingRule replacement) throws IOException {
        synchronized (lock) {
            requireListeners();
            if (!supportsRule(replacement)) throw new IOException("unsupported UML loopback forwarding rule");
            if (!previous.id().equals(replacement.id())) {
                throw new IOException("the previous forwarding listener is not active");
            }
            applyRuleWithLeaseLocked(replacement, previous);
        }
    }

    private void applyRuleWithLeaseLocked(PortForwardingRule target, PortForwardingRule previous)
            throws IOException {
        LoopbackEndpointRegistry.EndpointLease existing = ownedEndpoints.get(target.id());
        LoopbackEndpointRegistry.EndpointLease acquired = endpointRegistry.acquire(
                target.bindAddress(), target.hostPort(), instanceId, generation, target.id());
        try {
            if (previous != null) {
                forwarding.replaceRule(previous, target);
            } else {
                forwarding.addRule(target);
            }
        } catch (IOException | RuntimeException failure) {
            if (acquired != existing) {
                endpointRegistry.release(acquired);
            }
            throw failure;
        }
        ownedEndpoints.put(target.id(), acquired);
        if (existing != null && existing != acquired) {
            endpointRegistry.release(existing);
        }
    }

    @Override
    public void removeRule(PortForwardingRule rule) throws IOException {
        synchronized (lock) {
            forwarding.removeRule(rule);
            LoopbackEndpointRegistry.EndpointLease lease = ownedEndpoints.get(rule.id());
            if (lease != null
                    && lease.hostPort() == rule.hostPort()
                    && Objects.equals(lease.bindAddress(), rule.bindAddress())) {
                ownedEndpoints.remove(rule.id());
                endpointRegistry.release(lease);
            }
        }
    }

    @Override
    public void refresh() throws IOException {
        synchronized (lock) {
            requireListeners();
            if (attemptCount >= START_ATTEMPTS) {
                attemptCount = 0;
                lock.notifyAll();
            }
        }
        refreshRoute();
    }

    @Override
    public boolean isApplied(PortForwardingRule rule) {
        return isRunning() && forwarding.isApplied(rule);
    }

    private static long monotonicMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private static boolean sleepMillis(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // The owned descriptor is already unusable.
        }
    }

    private static PasstLauncher androidPasstLauncher() {
        return (argv, pidFile) -> {
            int[] handles = nativeSpawnPasst(argv);
            int pid = handles[0];
            ParcelFileDescriptor stream = ParcelFileDescriptor.adoptFd(handles[1]);
            return new PasstProcess() {
                @Override public int pid() { return pid; }
                @Override public void pump(int guestFd) { nativePumpPasst(guestFd, stream.getFd()); }
                @Override public void stopPump() { nativeStopPump(stream.getFd()); }
                @Override public boolean reap(boolean force) { return nativeReapPasst(pid, force); }
                @Override public void close() { closeQuietly(stream); }
            };
        };
    }

    private static final class AndroidRouteMonitor implements RouteMonitor {
        private final ConnectivityManager connectivity;

        AndroidRouteMonitor(ConnectivityManager connectivity) {
            this.connectivity = connectivity;
        }

        @Override
        public HostRoute snapshot() {
            Network active = null;
            String dns = null;
            if (connectivity != null) {
                try {
                    active = connectivity.getActiveNetwork();
                    LinkProperties properties = active == null ? null : connectivity.getLinkProperties(active);
                    if (properties != null) {
                        dns = properties.getDnsServers().stream()
                                .filter(a -> a instanceof Inet4Address && !a.isLoopbackAddress()
                                        && !a.isAnyLocalAddress() && !a.isMulticastAddress())
                                .map(a -> a.getHostAddress()).findFirst().orElse(null);
                    }
                } catch (RuntimeException unavailable) {
                    // Continue with the available snapshot and report missing resolver separately.
                }
            }
            return new HostRoute(active, dns);
        }

        @Override
        public AutoCloseable observe(Runnable onChange) {
            if (connectivity == null) return () -> {};
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { onChange.run(); }
                @Override public void onLost(Network network) { onChange.run(); }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    onChange.run();
                }
            };
            connectivity.registerDefaultNetworkCallback(callback);
            return () -> connectivity.unregisterNetworkCallback(callback);
        }
    }

    private static native int[] nativeSpawnPasst(String[] argv) throws IOException;
    private static native void nativePumpPasst(int guestFd, int streamFd);
    private static native void nativeStopPump(int streamFd);
    private static native boolean nativeReapPasst(int pid, boolean force);
}
