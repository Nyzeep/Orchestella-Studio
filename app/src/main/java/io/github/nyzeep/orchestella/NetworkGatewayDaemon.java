package io.github.nyzeep.orchestella;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import android.util.Log;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns the packaged gvproxy gateway and its private forwarding API for one QEMU instance. */
public final class NetworkGatewayDaemon {
    public static final class Running implements RuntimeGateway {
        public final String unixSocketPath;
        public final int tcpPort;
        public final Process process;
        private final String instanceId;
        private final File apiSocket;
        private final ForwardingEngine forwarding;
        private final Object stopLock = new Object();
        private boolean stopStarted;
        private boolean stopConfirmed;

        Running(String configPath, int tcpPort, Process process, String instanceId,
                File apiSocket, ForwardingEngine forwarding) {
            this.unixSocketPath = configPath;
            this.tcpPort = tcpPort;
            this.process = process;
            this.instanceId = instanceId;
            this.apiSocket = apiSocket;
            this.forwarding = forwarding;
        }

        @Override
        public boolean isAlive() {
            return process.isAlive();
        }

        /** Default-grace stop used by callers that do not tune the shutdown window. */
        public boolean stop() {
            return stop(DEFAULT_STOP_GRACE_MS);
        }

        /**
         * Idempotent bounded stop. The LIVE entry and API socket are only removed
         * after the process has actually exited; an unconfirmed stop keeps both so
         * the next start fails instead of reusing a port owned by an old gateway.
         */
        @Override
        public boolean stop(long graceMs) {
            synchronized (stopLock) {
                if (stopConfirmed) return true;
                // A previous UNCONFIRMED attempt is retryable. If the process has
                // exited in the meantime, finish the LIVE/socket cleanup now.
                if (stopStarted && !process.isAlive()) {
                    stopConfirmed = true;
                    cleanupLiveEntry();
                    return true;
                }
                stopStarted = true;
                stopConfirmed = terminateProcess(process, graceMs, FORCE_WAIT_MS);
                if (stopConfirmed) cleanupLiveEntry();
                return stopConfirmed;
            }
        }

        /** Removes only this owner's LIVE entry and socket; never a replacement's. */
        private void cleanupLiveEntry() {
            if (LIVE.remove(instanceId, this)) {
                try { Files.deleteIfExists(apiSocket.toPath()); } catch (IOException ignored) { }
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
        Log.i(TAG, "guest-dns server=" + selected);
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

    public static PortForwardingPresenter.LiveEngine forwardingEngine(String instanceId) {
        Running running = LIVE.get(instanceId);
        return running != null && running.isAlive() ? running.forwarding : null;
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
        // Payload builds keep native libraries inside the APK (no legacy
        // extraction), so the gateway binary may need to be materialized
        // from the APK before ProcessBuilder can exec it.
        String gvproxyPath = NativeExecutableStore.ensure(context, "libgvproxy.so");
        Path privateRoot = context.getFilesDir().getCanonicalFile().toPath();
        Path runtimePath = runtimeDirectory.getCanonicalFile().toPath();
        if (!runtimePath.startsWith(privateRoot) || runtimePath.equals(privateRoot)) {
            throw new IOException("gateway runtime must be app-private");
        }
        // Keep the pathname below sockaddr_un's limit even with a full UUID runtime directory.
        Path apiRoot = privateRoot.resolve("net-api");
        Path apiDirectory = apiRoot.resolve(Integer.toUnsignedString(instanceId.hashCode(), 16));
        if (Files.isSymbolicLink(apiRoot) || Files.isSymbolicLink(apiDirectory)) {
            throw new IOException("invalid gateway API directory");
        }
        Files.createDirectories(apiDirectory);
        File apiSocket = apiDirectory.resolve("api.sock").toFile();
        if (apiSocket.getAbsolutePath().getBytes(StandardCharsets.UTF_8).length > 100) {
            throw new IOException("gateway API socket path is too long");
        }
        int port = listenPortFor(instanceId);
        ensureGatewaySlotAvailable(instanceId);
        if (portAssignedToAnotherInstance(instanceId, port)) {
            throw new IOException("gateway port is shared by another instance");
        }
        if (portBound(port)) {
            throw new IOException("gateway port is already in use; old gateway may still be running");
        }
        try { Os.chmod(apiRoot.toString(), 0700); Os.chmod(apiDirectory.toString(), 0700); }
        catch (Exception failure) { throw new IOException("cannot secure gateway API directory", failure); }
        Files.deleteIfExists(apiSocket.toPath());
        File configFile = new File(runtimeDirectory, "net-" + instanceId + ".yaml");
        List<PortForwardingRule> stored = new FilePortForwardingStore(privateRoot.resolve("network")).loadRules(instanceId);
        Files.write(configFile.toPath(), configuration(port, apiSocket.getAbsolutePath(), stored).getBytes(StandardCharsets.UTF_8));
        try { Os.chmod(configFile.getAbsolutePath(), 0600); }
        catch (Exception failure) { throw new IOException("cannot secure gateway config", failure); }

        Process process = new ProcessBuilder(gvproxyPath, "-config", configFile.getAbsolutePath())
                .redirectErrorStream(true).start();
        Thread logger = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) Log.d(TAG, line);
            } catch (IOException ignored) { }
        }, "gvproxy-log-" + instanceId);
        logger.setDaemon(true);
        logger.start();
        ForwardingEngine engine = new ForwardingEngine(
                (method, endpoint, body) -> unixRequest(apiSocket, method, endpoint, body), process::isAlive);
        String stage = "services-socket";
        int probes = 0;
        IOException lastProbeFailure = null;
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + SOCKET_WAIT_MILLIS;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!process.isAlive()) throw new IOException("gvproxy exited during startup");
                if (apiSocket.exists()) {
                    try {
                        stage = "services-permissions";
                        Os.chmod(apiSocket.getAbsolutePath(), 0600);
                        stage = "services-http";
                        probes++;
                        engine.refresh(); // GET /services/forwarder/all only; never consume the QEMU single-client listener.
                        stage = "qemu-listener";
                        if (portBound(port)) {
                            stage = "configured-forwards";
                            for (PortForwardingRule rule : stored) {
                                if (isSupported(rule) && !engine.isApplied(rule)) {
                                    throw new IOException("configured forward was not installed");
                                }
                            }
                            Running running = new Running(configFile.getAbsolutePath(), port,
                                    process, instanceId, apiSocket, engine);
                            LIVE.put(instanceId, running);
                            Log.i(TAG, "gateway-ready api=200 forwards=" + engine.appliedCount() + " probes=" + probes);
                            return running;
                        }
                    } catch (ApiTransportFailure transientFailure) {
                        lastProbeFailure = transientFailure;
                        Log.w(TAG, "gateway-api-not-ready stage=" + transientFailure.stage + " attempt=" + probes);
                    } catch (IOException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IOException("cannot secure gateway API socket", failure);
                    }
                }
                try { Thread.sleep(120); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("gateway startup interrupted", interrupted);
                }
            }
            throw new IOException("gateway API did not become ready at " + stage, lastProbeFailure);
        } catch (IOException failure) {
            Log.w(TAG, "gateway-start-failed stage=" + stage + " probes=" + probes
                    + " reason=" + diagnosticReason(failure));
            terminateProcess(process, 0, FORCE_WAIT_MS);
            try { Files.deleteIfExists(apiSocket.toPath()); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public static void stop(Running running) {
        if (running != null) running.stop(DEFAULT_STOP_GRACE_MS);
    }

    /** Fails fast when an instance already owns a live gateway; cleans a dead entry first. */
    static void ensureGatewaySlotAvailable(String instanceId) throws IOException {
        Running existing = LIVE.get(instanceId);
        if (existing == null) return;
        if (existing.isAlive()) throw new IOException("gateway is already running for this instance");
        existing.stop(0);
        if (existing.isAlive()) throw new IOException("previous gateway did not stop");
    }

    static boolean portAssignedToAnotherInstance(String instanceId, int port) {
        for (Map.Entry<String, Running> entry : LIVE.entrySet()) {
            if (entry.getKey().equals(instanceId)) continue;
            Running other = entry.getValue();
            if (!other.isAlive()) {
                // A dead entry must not reserve a port for an instance that is gone.
                other.stop(0);
                continue;
            }
            if (other.tcpPort == port) return true;
        }
        return false;
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
        return rule.protocol() == Protocol.TCP && PortForwardingRule.LOOPBACK_ADDRESS.equals(rule.bindAddress());
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

    static final class ForwardingEngine implements PortForwardingPresenter.LiveEngine {
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
            confirmed.putAll(parseForwards(api.request("GET", "/services/forwarder/all", "")));
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

    private static String unixRequest(File socketPath, String method, String endpoint, String body) throws IOException {
        if (!(method.equals("GET") && endpoint.equals("/services/forwarder/all")) &&
                !(method.equals("POST") && (endpoint.equals("/services/forwarder/expose") || endpoint.equals("/services/forwarder/unexpose")))) {
            throw new IOException("unknown gateway API request");
        }
        byte[] data = body.getBytes(StandardCharsets.US_ASCII);
        if (data.length > 512) throw new IOException("gateway request too large");
        try (LocalSocket socket = new LocalSocket()) {
            // connect() creates the Android LocalSocket fd. Setting SO_TIMEOUT before that
            // calls setOption on an uncreated socket and aborts launch before QEMU starts.
            try {
                socket.connect(new LocalSocketAddress(socketPath.getAbsolutePath(), LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (IOException failure) {
                throw new ApiTransportFailure("connect", failure);
            }
            try {
                socket.setSoTimeout(API_TIMEOUT_MS); // Set receive/send timeouts only after fd creation.
            } catch (IOException failure) {
                throw new IOException("gvproxy-api-timeout-options-failed", failure);
            }
            String headers = method + " " + endpoint + " HTTP/1.0\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
            try {
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(data);
                socket.getOutputStream().flush();
            } catch (IOException failure) {
                throw new ApiTransportFailure("write", failure);
            }
            return readResponse(socket.getInputStream());
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

    private static String diagnosticReason(IOException failure) {
        String message = failure.getMessage();
        // These are local parser/stage diagnostics, never response bodies or guest data.
        if (message != null && message.matches("(?:gvproxy-api-|gateway |invalid gateway |configured forward |unknown forwarding |duplicate forwarding |invalid forwarding )[A-Za-z0-9 ._-]{0,100}")) {
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

    private static boolean portBound(int port) {
        try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
            ss.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (IOException bound) { return true; }
    }

    static int listenPortFor(String instanceId) {
        return 24100 + Math.floorMod(instanceId != null ? instanceId.hashCode() : 0, 800);
    }
}
