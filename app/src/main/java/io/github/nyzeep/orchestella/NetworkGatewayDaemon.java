package io.github.nyzeep.orchestella;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Credentials;
import android.net.LinkProperties;
import android.net.Network;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter;
import io.github.nyzeep.orchestella.runtime.instance.GatewaySlotLease;
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the packaged gvproxy gateway and its private forwarding API for one QEMU instance. */
public final class NetworkGatewayDaemon {
    /** Startup failed after spawning a process whose exit could not be verified. */
    static final class UnconfirmedStartCleanup extends IOException {
        UnconfirmedStartCleanup(Throwable cause) {
            super("gateway-start-cleanup-unconfirmed", cause);
        }
    }

    public static final class Running implements RuntimeGateway {
        public final String unixSocketPath;
        public final int tcpPort;
        public final Process process;
        private final String instanceId;
        private final File apiSocket;
        private final ForwardingEngine forwarding;
        private final GatewaySlotLease lease;
        private final Object stopLock = new Object();
        private boolean stopConfirmed;

        Running(String configPath, int tcpPort, Process process, String instanceId,
                File apiSocket, ForwardingEngine forwarding) {
            this(configPath, tcpPort, process, instanceId, apiSocket, forwarding, null);
        }

        Running(String configPath, int tcpPort, Process process, String instanceId,
                File apiSocket, ForwardingEngine forwarding, GatewaySlotLease lease) {
            this.unixSocketPath = configPath;
            this.tcpPort = tcpPort;
            this.process = process;
            this.instanceId = instanceId;
            this.apiSocket = apiSocket;
            this.forwarding = forwarding;
            this.lease = lease;
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public PortForwardingEngine.Live forwardingEngine() {
            return isAlive() ? forwarding : null;
        }

        /** Default-grace stop used by callers that do not tune the shutdown window. */
        public boolean stop() {
            return stop(DEFAULT_STOP_GRACE_MS);
        }

        /**
         * Idempotent bounded stop. The lease stays held until the process has exited and
         * its generation's socket can be released.
         */
        @Override
        public boolean stop(long graceMs) {
            synchronized (stopLock) {
                if (stopConfirmed) return true;
                if (!terminateProcess(process, graceMs, FORCE_WAIT_MS)) return false;
                try {
                    if (lease != null) {
                        lease.releaseAfterExit();
                    } else if (LIVE.get(instanceId) == this) {
                        Files.deleteIfExists(apiSocket.toPath());
                    }
                } catch (IOException failure) {
                    Log.w(TAG, "gateway-slot-release-failed endpoint=local-gateway");
                    return false;
                }
                LIVE.remove(instanceId, this);
                stopConfirmed = true;
                return true;
            }
        }
    }

    public static final String GUEST_IP = "192.168.127.15";
    public static final String GUEST_GATEWAY = "192.168.127.1";
    public static final String GUEST_NETMASK = "255.255.255.0";
    /** Fallback only when the active network has no readable, usable IPv4 resolver. */
    public static final String GUEST_DNS = "223.5.5.5";
    private static final String TAG = "OrchestellaGvproxy";
    private static final long SOCKET_WAIT_MILLIS = 8000L;
    private static final int API_TIMEOUT_MS = 1500;
    private static final int MAX_RESPONSE_BYTES = 32 * 1024;
    static final Map<String, Running> LIVE = new ConcurrentHashMap<>();
    private static final Object START_LOCK = new Object();
    private static final long DEFAULT_STOP_GRACE_MS = 2000L;
    private static final long FORCE_WAIT_MS = 2000L;

    private NetworkGatewayDaemon() {}

    /**
     * Snapshots the current network's resolver at launch, including a VPN's DNS when it is
     * the active network. This reads existing network state; it never binds a network,
     * requests one, resolves a name, or changes the gateway's host routing.
     */
    public static String guestDnsFor(Context context) {
        String selected = GUEST_DNS;
        try {
            Object service = context != null ? context.getSystemService(Context.CONNECTIVITY_SERVICE) : null;
            if (service instanceof ConnectivityManager) {
                ConnectivityManager manager = (ConnectivityManager) service;
                Network active = manager.getActiveNetwork();
                LinkProperties properties = active != null ? manager.getLinkProperties(active) : null;
                if (properties != null) {
                    List<String> candidates = new ArrayList<>();
                    for (java.net.InetAddress server : properties.getDnsServers()) {
                        if (server != null) candidates.add(server.getHostAddress());
                    }
                    selected = chooseGuestDns(candidates);
                }
            }
        } catch (RuntimeException unavailable) {
            // Missing permission, unavailable service, or a network transition: keep the fallback.
        }
        Log.i(TAG, "guest-dns source=" + (GUEST_DNS.equals(selected) ? "fallback" : "active-network"));
        return selected;
    }

    /** Pure literal selection: ordered active-network candidates, with no name-service I/O. */
    public static String chooseGuestDns(List<String> candidates) {
        if (candidates != null) {
            for (String candidate : candidates) {
                if (isUsableDnsLiteral(candidate)) return candidate;
            }
        }
        return GUEST_DNS;
    }

    private static boolean isUsableDnsLiteral(String address) {
        if (address == null || address.length() > 15) return false;
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) return false;
        int first = -1;
        for (int i = 0; i < octets.length; i++) {
            if (!octets[i].matches("0|[1-9][0-9]{0,2}")) return false;
            int value = Integer.parseInt(octets[i]);
            if (value > 255) return false;
            if (i == 0) first = value;
        }
        // Exclude unspecified/this-network, loopback, multicast and reserved/broadcast space.
        return first > 0 && first != 127 && first < 224;
    }

    public static PortForwardingEngine.Live forwardingEngine(String instanceId) {
        Running running = LIVE.get(instanceId);
        return running != null ? running.forwardingEngine() : null;
    }

    /** Loads only explicit user rules; an empty store never creates an SSH listener. */
    public static Running start(Context context, String instanceId, File runtimeDirectory) throws IOException {
        synchronized (START_LOCK) {
            return startLocked(context, instanceId, runtimeDirectory);
        }
    }

    private static Running startLocked(Context context, String instanceId, File runtimeDirectory) throws IOException {
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IOException("invalid gateway instance id");
        }
        Path privateRoot = context.getFilesDir().getCanonicalFile().toPath();
        Path runtimePath = runtimeDirectory.getCanonicalFile().toPath();
        if (!runtimePath.startsWith(privateRoot) || runtimePath.equals(privateRoot)) {
            throw new IOException("gateway runtime must be app-private");
        }
        Files.createDirectories(runtimePath);
        int port = listenPortFor(instanceId);
        for (Running existing : LIVE.values()) {
            if (existing.tcpPort == port && !existing.isAlive()) existing.stop(0);
        }
        Path netSlots = privateRoot.resolve("net-slots");
        Files.createDirectories(netSlots);
        chmod(netSlots, 0700);
        Path netApi = privateRoot.resolve("net-api");
        Files.createDirectories(netApi);
        chmod(netApi, 0700);
        int launcherPid = android.os.Process.myPid();
        long launcherStartTicks = processStartTicks(launcherPid);
        GatewaySlotLease lease = GatewaySlotLease.acquire(privateRoot, instanceId,
                NetworkGatewayDaemon::inspectProcess, NetworkGatewayDaemon::apiSocketIdentity,
                launcherPid, launcherStartTicks);
        Process process = null;
        Running running = null;
        File apiSocket = lease.apiSocket().toFile();
        try {
            // Keep the pathname below sockaddr_un's limit even with a full UUID runtime directory.
            Path api = lease.apiSocket();
            if (api.toString().getBytes(StandardCharsets.UTF_8).length > 100) {
                throw new IOException("gateway API socket path is too long");
            }
            Path apiDirectory = api.getParent();
            chmod(apiDirectory.getParent(), 0700);
            chmod(apiDirectory, 0700);
            Path config = runtimePath.resolve(GatewaySlotArbiter.configFileName(instanceId));
            List<PortForwardingRule> stored =
                    new FilePortForwardingStore(privateRoot.resolve("network")).loadRules(instanceId);
            Files.write(config, configuration(port, api.toString(), stored).getBytes(StandardCharsets.UTF_8));
            chmod(config, 0600);
            List<String> command = List.of(NativeExecutableStore.ensure(context, "libgvproxy.so"),
                    "-config", config.toString());
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process gateway = process;
            GatewayPeer peer = new GatewayPeer(gateway, command, lease);
            ForwardingEngine engine = new ForwardingEngine(
                    (method, endpoint, body) -> unixRequest(apiSocket, peer, method, endpoint, body),
                    gateway::isAlive);
            running = new Running(config.toString(), port, process, instanceId, apiSocket, engine, lease);
            drainGatewayOutput(process);
            awaitReady(running, stored);
            LIVE.put(instanceId, running);
            return running;
        } catch (IOException | RuntimeException | Error failure) {
            if (running != null) {
                if (!running.stop(0)) {
                    LIVE.put(instanceId, running);
                    throw new UnconfirmedStartCleanup(failure);
                }
            } else {
                if (!terminateProcess(process, 0, FORCE_WAIT_MS)) {
                    if (process != null) {
                        LIVE.put(instanceId, new Running(
                                runtimePath.resolve(GatewaySlotArbiter.configFileName(instanceId)).toString(),
                                port, process, instanceId, apiSocket, null, lease));
                    }
                    throw new UnconfirmedStartCleanup(failure);
                }
                try {
                    lease.releaseAfterExit();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                    throw new UnconfirmedStartCleanup(failure);
                }
            }
            throw failure;
        }
    }

    private static void chmod(Path path, int mode) throws IOException {
        try {
            Os.chmod(path.toString(), mode);
        } catch (ErrnoException failure) {
            throw new IOException("cannot secure gateway files", failure);
        }
    }

    private static String apiSocketIdentity(Path api) throws IOException {
        try {
            StructStat attributes = Os.lstat(api.toString());
            if (!OsConstants.S_ISSOCK(attributes.st_mode)) return "";
            return Long.toUnsignedString(attributes.st_dev) + ':' + Long.toUnsignedString(attributes.st_ino);
        } catch (ErrnoException failure) {
            if (failure.errno == OsConstants.ENOENT) return null;
            throw new IOException("cannot verify gateway API socket", failure);
        }
    }

    private static void drainGatewayOutput(Process process) {
        Thread drainer = new Thread(() -> {
            try (InputStream output = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                while (output.read(buffer) != -1) {
                    // Native output can contain paths and network addresses.
                }
            } catch (IOException ignored) {
                // The stream closes during teardown.
            }
        }, "gvproxy-output");
        drainer.setDaemon(true);
        drainer.start();
    }

    private static void awaitReady(Running running, List<PortForwardingRule> stored) throws IOException {
        String stage = "services-socket";
        int probes = 0;
        IOException lastProbeFailure = null;
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + SOCKET_WAIT_MILLIS;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!running.isAlive()) throw new IOException("gvproxy exited during startup");
                if (running.apiSocket.exists()) {
                    try {
                        stage = "services-http";
                        probes++;
                        ForwardingEngine engine = running.forwarding;
                        engine.refresh(); // GET /services/forwarder/all only; never consume the QEMU single-client listener.
                        stage = "qemu-listener";
                        if (GatewaySlotLease.tcpListenerBound(running.tcpPort) && running.isAlive()) {
                            stage = "configured-forwards";
                            for (PortForwardingRule rule : stored) {
                                if (isSupported(rule) && !engine.isApplied(rule)) {
                                    throw new IOException("configured forward was not installed");
                                }
                            }
                            Log.i(TAG, "gateway-ready api=200 forwards=" + engine.appliedCount() + " probes=" + probes);
                            return;
                        }
                    } catch (ApiTransportFailure transientFailure) {
                        lastProbeFailure = transientFailure;
                        Log.w(TAG, "gateway-api-not-ready stage=" + transientFailure.stage + " attempt=" + probes);
                    }
                }
                try { Thread.sleep(120); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("gateway startup interrupted", interrupted);
                }
            }
            throw new IOException("gateway API did not become ready at " + stage, lastProbeFailure);
        } catch (IOException | RuntimeException | Error failure) {
            Log.w(TAG, "gateway-start-failed stage=" + stage + " probes=" + probes
                    + " reason=" + diagnosticReason(failure));
            throw failure;
        }
    }

    public static void stop(Running running) {
        if (running != null) running.stop(DEFAULT_STOP_GRACE_MS);
    }

    /** Bounded destroy/force sequence shared by running handles and start-failure cleanup. */
    static boolean terminateProcess(Process process, long graceMs, long forceWaitMs) {
        if (process == null) return true;
        if (!process.isAlive()) return true;
        process.destroy();
        if (waitQuietly(process, graceMs)) return true;
        process.destroyForcibly();
        return waitQuietly(process, forceWaitMs);
    }

    private static boolean waitQuietly(Process process, long timeoutMs) {
        try {
            return process.waitFor(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean isSupported(PortForwardingRule rule) {
        return PortForwardingEngine.Live.supportsIpv4LoopbackTcp(rule);
    }

    /** gvproxy v0.9.0: top-level services, stack.forwards map of host endpoint to netstack guest endpoint. */
    static String configuration(int port, String apiPath, List<PortForwardingRule> rules) throws IOException {
        if (apiPath == null || !apiPath.startsWith("/") || apiPath.contains("\n") || apiPath.contains("\r")) {
            throw new IOException("invalid gateway API socket path");
        }
        StringBuilder yaml = new StringBuilder("log-level: info\nservices: \"unix://")
                .append(apiPath.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n")
                .append("interfaces:\n  qemu: \"tcp://127.0.0.1:").append(port).append("\"\n")
                .append("stack:\n  mtu: 1500\n  subnet: 192.168.127.0/24\n  gatewayIP: 192.168.127.1\n")
                .append("  deviceIP: ").append(GUEST_IP).append("\n  hostIP: 192.168.127.254\n")
                .append("  gatewayMacAddress: 5a:94:ef:e4:0c:dd\n  forwards:");
        List<PortForwardingRule> supported = new ArrayList<>();
        for (PortForwardingRule rule : rules) if (isSupported(rule)) supported.add(rule);
        if (supported.isEmpty()) return yaml.append(" {}\n").toString();
        if (supported.size() > 128) throw new IOException("too many forwarding rules");
        Map<Integer, Boolean> seen = new HashMap<>();
        yaml.append('\n');
        for (PortForwardingRule rule : supported) {
            if (seen.put(rule.hostPort(), true) != null || rule.hostPort() == port) {
                throw new IOException("forwarding port conflict");
            }
            yaml.append("    \"127.0.0.1:").append(rule.hostPort()).append("\": \"")
                    .append(GUEST_IP).append(':').append(rule.guestPort()).append("\"\n");
        }
        return yaml.toString();
    }

    interface Api { String request(String method, String endpoint, String body) throws IOException; }

    static final class ForwardingEngine implements PortForwardingEngine.Live {
        private final Api api;
        private final java.util.function.BooleanSupplier alive;
        private final Map<String, String> confirmed = new HashMap<>();
        ForwardingEngine(Api api, java.util.function.BooleanSupplier alive) { this.api = api; this.alive = alive; }
        @Override public boolean isRunning() { return alive.getAsBoolean(); }
        synchronized int appliedCount() { return confirmed.size(); }
        @Override public synchronized boolean isApplied(PortForwardingRule rule) {
            return isSupported(rule) && remote(rule).equals(confirmed.get(local(rule)));
        }
        @Override public synchronized void refresh() throws IOException {
            confirmed.clear();
            if (!isRunning()) throw new IOException("gateway is not running");
            IOException lastFailure = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    confirmed.putAll(parseForwards(api.request("GET", "/services/forwarder/all", "")));
                    return;
                } catch (ApiTransportFailure transientFailure) {
                    lastFailure = transientFailure;
                    if (attempt == 0 && isRunning()) {
                        try {
                            Thread.sleep(60L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw transientFailure;
                        }
                    }
                }
            }
            throw lastFailure;
        }
        @Override public synchronized void addRule(PortForwardingRule rule) throws IOException {
            requireSupported(rule);
            refresh();
            if (isApplied(rule)) return;
            if (confirmed.containsKey(local(rule))) throw new IOException("gateway forward conflicts");
            boolean accepted = false;
            try {
                api.request("POST", "/services/forwarder/expose", payload(rule));
                accepted = true;
                refresh();
                if (!isApplied(rule)) throw new IOException("gateway forward was not confirmed");
            } catch (IOException failure) {
                try {
                    if (!accepted) refresh();
                    if (accepted || isApplied(rule)) api.request("POST", "/services/forwarder/unexpose", payload(rule));
                    refresh();
                } catch (IOException rollback) { failure.addSuppressed(rollback); confirmed.clear(); }
                throw failure;
            }
        }
        @Override public synchronized void removeRule(PortForwardingRule rule) throws IOException {
            requireSupported(rule);
            refresh();
            if (!isApplied(rule)) return;
            try {
                api.request("POST", "/services/forwarder/unexpose", payload(rule));
                refresh();
                if (isApplied(rule)) throw new IOException("gateway removal was not confirmed");
            } catch (IOException failure) {
                try { api.request("POST", "/services/forwarder/expose", payload(rule)); refresh(); }
                catch (IOException rollback) { failure.addSuppressed(rollback); confirmed.clear(); }
                throw failure;
            }
        }
        private static void requireSupported(PortForwardingRule rule) throws IOException {
            if (!isSupported(rule)) throw new IOException("only explicit TCP loopback forwards are supported");
        }
        private static String local(PortForwardingRule rule) { return "127.0.0.1:" + rule.hostPort(); }
        private static String remote(PortForwardingRule rule) { return GUEST_IP + ":" + rule.guestPort(); }
        private static String payload(PortForwardingRule rule) {
            return "{\"local\":\"" + local(rule) + "\",\"remote\":\"" + remote(rule) + "\",\"protocol\":\"tcp\"}";
        }
    }

    static Map<String, String> parseForwards(String json) throws IOException {
        if (json == null || json.length() > MAX_RESPONSE_BYTES) throw new IOException("invalid forwarding response");
        String text = json.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) throw new IOException("invalid forwarding response");
        Map<String, String> result = new HashMap<>();
        String inside = text.substring(1, text.length() - 1).trim();
        if (inside.isEmpty()) return result;
        Pattern record = Pattern.compile("\\{\\s*\"local\"\\s*:\\s*\"(127\\.0\\.0\\.1:[0-9]{1,5})\"\\s*,\\s*\"remote\"\\s*:\\s*\"(192\\.168\\.127\\.15:[0-9]{1,5})\"\\s*,\\s*\"protocol\"\\s*:\\s*\"tcp\"\\s*\\}");
        Matcher matcher = record.matcher(inside);
        int end = 0;
        while (matcher.find()) {
            String between = inside.substring(end, matcher.start()).trim();
            if (!(end == 0 ? between.isEmpty() : between.equals(","))) throw new IOException("unknown forwarding record");
            int hostPort = Integer.parseInt(matcher.group(1).substring(matcher.group(1).lastIndexOf(':') + 1));
            int guestPort = Integer.parseInt(matcher.group(2).substring(matcher.group(2).lastIndexOf(':') + 1));
            if (hostPort < 1 || hostPort > 65535 || guestPort < 1 || guestPort > 65535) {
                throw new IOException("invalid forwarding response port");
            }
            if (result.put(matcher.group(1), matcher.group(2)) != null || result.size() > 128) {
                throw new IOException("duplicate forwarding record");
            }
            end = matcher.end();
        }
        if (end == 0 || !inside.substring(end).trim().isEmpty()) throw new IOException("unknown forwarding record");
        return result;
    }

    private static String unixRequest(File socketPath, GatewayPeer peer, String method, String endpoint,
            String body) throws IOException {
        if (!(method.equals("GET") && endpoint.equals("/services/forwarder/all")) &&
                !(method.equals("POST") && (endpoint.equals("/services/forwarder/expose") || endpoint.equals("/services/forwarder/unexpose")))) {
            throw new IOException("unknown gateway API request");
        }
        byte[] data = body.getBytes(StandardCharsets.US_ASCII);
        if (data.length > 512) throw new IOException("gateway request too large");
        try (LocalSocket socket = new LocalSocket()) {
            // connect() creates the Android LocalSocket fd. Setting SO_TIMEOUT before that
            // calls setOption on an uncreated socket and aborts launch before QEMU starts.
            String identity = apiSocketIdentity(socketPath.toPath());
            if (identity != null && identity.isEmpty()) throw new IOException("gateway API path is not a socket");
            try {
                if (identity == null) throw new IOException("gateway API socket is absent");
                socket.connect(new LocalSocketAddress(socketPath.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException failure) {
                throw new ApiTransportFailure("connect", failure);
            }
            peer.verify(socket, identity);
            try {
                socket.setSoTimeout(API_TIMEOUT_MS); // Set receive/send timeouts only after fd creation.
            } catch (IOException failure) {
                throw new IOException("gvproxy-api-timeout-options-failed", failure);
            }
            String headers = method + " " + endpoint + " HTTP/1.0\r\nHost: localhost\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + data.length
                    + "\r\nConnection: close\r\n\r\n";
            byte[] headerBytes = headers.getBytes(StandardCharsets.US_ASCII);
            byte[] request = new byte[headerBytes.length + data.length];
            System.arraycopy(headerBytes, 0, request, 0, headerBytes.length);
            System.arraycopy(data, 0, request, headerBytes.length, data.length);
            try {
                // LocalSocket writes directly to its descriptor. Go can close after replying to
                // a GET before a second empty write or flush gets to the socket.
                socket.getOutputStream().write(request);
            } catch (IOException failure) {
                throw new ApiTransportFailure("write", failure);
            }
            return readResponse(socket.getInputStream());
        }
    }

    /** Authenticates the API peer and fences operations issued by a handle from a prior start. */
    private static final class GatewayPeer {
        private final Process process;
        private final List<String> command;
        private final GatewaySlotLease lease;
        private int pid;
        private long startTicks;
        private String socketIdentity;

        GatewayPeer(Process process, List<String> command, GatewaySlotLease lease) {
            this.process = process;
            this.command = command;
            this.lease = lease;
        }

        synchronized void verify(LocalSocket socket, String identity) throws IOException {
            Credentials credentials = socket.getPeerCredentials();
            int peerPid = credentials.getPid();
            if (peerPid <= 0 || credentials.getUid() != android.os.Process.myUid() || !process.isAlive()) {
                throw new IOException("gateway API peer could not be verified");
            }
            if (!identity.equals(apiSocketIdentity(lease.apiSocket()))) {
                throw new IOException("gateway API socket changed");
            }
            long peerStart = processStartTicks(peerPid);
            if (pid != 0) {
                if (pid != peerPid || !identity.equals(socketIdentity) || (startTicks != 0 && peerStart != startTicks)) {
                    throw new IOException("gateway API peer changed");
                }
                return;
            }
            List<String> actualCommand = processCommandLine(peerPid);
            // The virgin app-private path and peer UID cover devices that restrict /proc.
            if (!actualCommand.isEmpty() && !actualCommand.equals(command)) {
                throw new IOException("gateway API peer identity differs");
            }
            lease.recordProcess(peerPid, peerStart, identity);
            pid = peerPid;
            startTicks = peerStart;
            socketIdentity = identity;
        }
    }

    private static GatewaySlotLease.ProcessObservation inspectProcess(int pid, long startTicks) {
        return inspectProcess(
                pid,
                startTicks,
                () -> processStartTicks(pid),
                () -> {
                    try {
                        // Signal zero queries existence and permission without delivering a signal.
                        Os.kill(pid, 0);
                        return GatewaySlotLease.ProcessState.RUNNING;
                    } catch (ErrnoException error) {
                        return error.errno == OsConstants.ESRCH
                                ? GatewaySlotLease.ProcessState.EXITED
                                : GatewaySlotLease.ProcessState.UNAVAILABLE;
                    }
                },
                () -> processCommandLine(pid));
    }

    static GatewaySlotLease.ProcessObservation inspectProcess(
            int pid,
            long startTicks,
            LongSupplier actualStartTicks,
            Supplier<GatewaySlotLease.ProcessState> killProbe,
            Supplier<List<String>> cmdlineSupplier) {
        long actualStart = actualStartTicks.getAsLong();
        if (startTicks != 0 && actualStart != 0 && startTicks != actualStart) {
            return new GatewaySlotLease.ProcessObservation(GatewaySlotLease.ProcessState.EXITED);
        }
        GatewaySlotLease.ProcessState signalState = killProbe.get();
        if (signalState != GatewaySlotLease.ProcessState.RUNNING) {
            return new GatewaySlotLease.ProcessObservation(signalState);
        }
        if (startTicks != 0 && actualStart == 0) {
            return new GatewaySlotLease.ProcessObservation(GatewaySlotLease.ProcessState.UNAVAILABLE);
        }
        return new GatewaySlotLease.ProcessObservation(GatewaySlotLease.ProcessState.RUNNING,
                cmdlineSupplier.get());
    }

    private static long processStartTicks(int pid) {
        return parseStartTicksFromStat(readProcessField(pid, "stat"));
    }

    static long parseStartTicksFromStat(String stat) {
        if (stat == null) return 0;
        // comm is parenthesized and can itself contain spaces and parentheses. After it,
        // the state is field 3, so the kernel's field 22 (starttime) is at index 19.
        int commandEnd = stat.lastIndexOf(')');
        if (commandEnd < 0 || commandEnd + 2 >= stat.length()) return 0;
        String[] fields = stat.substring(commandEnd + 2).split(" ", 21);
        if (fields.length <= 19) return 0;
        try {
            return Math.max(0, Long.parseLong(fields[19]));
        } catch (NumberFormatException malformed) {
            return 0;
        }
    }

    private static List<String> processCommandLine(int pid) {
        String command = readProcessField(pid, "cmdline");
        return command == null || command.isEmpty() ? List.of() : List.of(command.split("\0"));
    }

    private static String readProcessField(int pid, String field) {
        try (InputStream input = Files.newInputStream(Paths.get("/proc", Integer.toString(pid), field))) {
            byte[] data = new byte[4096];
            int length = 0;
            while (length < data.length) {
                int count = input.read(data, length, data.length - length);
                if (count < 0) return new String(data, 0, length, StandardCharsets.UTF_8);
                length += count;
            }
            return null;
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
    }

    private static final class ApiTransportFailure extends IOException {
        private static final long serialVersionUID = 1L;
        final String stage;
        ApiTransportFailure(String stage, IOException cause) {
            super("gvproxy-api-" + stage + "-failed", cause);
            this.stage = stage;
        }
    }

    static IOException transportFailureForTest(String stage) {
        return new ApiTransportFailure(stage, new IOException("synthetic transport failure"));
    }

    static String diagnosticReason(Throwable failure) {
        String message = failure.getMessage();
        // These are local parser/stage diagnostics, never response bodies or guest data.
        if (message != null && message.matches("(?:gvproxy-api-|gateway |gateway-slot-|invalid gateway |configured forward |unknown forwarding |duplicate forwarding |invalid forwarding )[A-Za-z0-9 ._-]{0,100}")) {
            return message;
        }
        return failure.getClass().getSimpleName();
    }

    /** Parses exactly one bounded HTTP message without waiting for EOF after a framed body. */
    static String readResponse(InputStream in) throws IOException {
        HttpInput input = new HttpInput(in);
        String status = input.line();
        if (!status.matches("HTTP/1\\.[01] [0-9]{3}(?: [\\x20-\\x7e]*)?")) {
            throw new IOException("invalid gateway HTTP status");
        }
        int statusCode = Integer.parseInt(status.substring(9, 12));
        if (statusCode != 200) throw new IOException("gateway HTTP status " + statusCode);
        long length = -1;
        String transferEncoding = null;
        int headers = 0;
        int headerBytes = status.length();
        while (true) {
            String line = input.line();
            if (line.isEmpty()) break;
            if (++headers > 64 || (headerBytes += line.length()) > 8192) {
                throw new IOException("gateway HTTP headers too large");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IOException("invalid gateway HTTP header");
            String name = line.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                if (length >= 0 || !value.matches("[0-9]{1,8}")) {
                    throw new IOException("invalid gateway HTTP content length");
                }
                length = Long.parseLong(value);
                if (length > MAX_RESPONSE_BYTES) throw new IOException("gateway response too large");
            } else if (name.equals("transfer-encoding")) {
                if (transferEncoding != null) throw new IOException("invalid gateway HTTP transfer encoding");
                transferEncoding = value.toLowerCase(java.util.Locale.ROOT);
            }
        }
        if (transferEncoding != null && (length >= 0 || !transferEncoding.equals("chunked"))) {
            throw new IOException("invalid gateway HTTP transfer encoding");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (transferEncoding != null) {
            while (true) {
                String chunk = input.line();
                int extension = chunk.indexOf(';');
                String sizeText = extension >= 0 ? chunk.substring(0, extension) : chunk;
                if (!sizeText.matches("[0-9a-fA-F]{1,8}")) throw new IOException("invalid gateway HTTP chunk");
                long size = Long.parseLong(sizeText, 16);
                if (size > MAX_RESPONSE_BYTES - body.size()) throw new IOException("gateway response too large");
                if (size == 0) {
                    int trailers = 0;
                    while (!input.line().isEmpty()) {
                        if (++trailers > 32) throw new IOException("gateway HTTP trailers too large");
                    }
                    break;
                }
                input.copyExact(body, (int) size);
                if (!input.line().isEmpty()) throw new IOException("invalid gateway HTTP chunk ending");
            }
        } else if (length >= 0) {
            input.copyExact(body, (int) length);
        } else {
            // HTTP/1.0 close-delimited bodies are valid, but retain socket and total deadlines.
            int value;
            while ((value = input.read()) != -1) body.write(value);
        }
        return new String(body.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static final class HttpInput {
        private final InputStream in;
        private final long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(API_TIMEOUT_MS);
        private int consumed;
        HttpInput(InputStream in) { this.in = new java.io.BufferedInputStream(in, 1024); }
        int read() throws IOException {
            if (System.nanoTime() >= deadline) throw new IOException("gateway response deadline exceeded");
            int value;
            try { value = in.read(); }
            catch (IOException failure) { throw new ApiTransportFailure("read", failure); }
            if (value != -1 && ++consumed > MAX_RESPONSE_BYTES) throw new IOException("gateway response too large");
            return value;
        }
        String line() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (line.size() <= 4096) {
                int value = read();
                if (value == -1) throw new IOException("gateway HTTP response truncated");
                if (value == '\r') {
                    if (read() != '\n') throw new IOException("invalid gateway HTTP line ending");
                    return new String(line.toByteArray(), StandardCharsets.US_ASCII);
                }
                if (value < 32 || value > 126) throw new IOException("invalid gateway HTTP line");
                line.write(value);
            }
            throw new IOException("gateway HTTP line too large");
        }
        void copyExact(ByteArrayOutputStream body, int size) throws IOException {
            for (int i = 0; i < size; i++) {
                int value = read();
                if (value == -1) throw new IOException("gateway HTTP body truncated");
                body.write(value);
            }
        }
    }

    static int listenPortFor(String instanceId) {
        return GatewaySlotArbiter.derivedPort(instanceId);
    }
}
