package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An exclusive, app-private lease for a derived gateway port and its local API socket.
 *
 * <p>The lock inode is kept at a fixed path. The separately persisted owner record fences a child
 * if its launching JVM dies, since the OS then releases the JVM's file lock independently of the
 * child. The record is written before spawn and upgraded with the peer's identity at readiness.
 */
public final class GatewaySlotLease {
    private static final int LOCK_ATTEMPTS = 4;
    private static final long RETRY_DELAY_MS = 40;
    private static final int MAX_OWNER_BYTES = 384;
    // On POSIX, closing any descriptor for the same inode may release this process's file locks.
    private static final Set<Path> LOCAL_LOCKS = ConcurrentHashMap.newKeySet();

    public enum ProcessState {
        RUNNING,
        EXITED,
        UNAVAILABLE
    }

    @FunctionalInterface
    public interface ProcessProbe {
        ProcessObservation inspect(int pid, long startTicks);
    }

    @FunctionalInterface
    public interface SocketProbe {
        /** Returns the file identity, null for an absent path, or an empty string for a non-socket. */
        String identity(Path socket) throws IOException;
    }

    public record ProcessObservation(ProcessState state, List<String> commandLine) {
        public ProcessObservation {
            Objects.requireNonNull(state);
            commandLine = List.copyOf(commandLine);
        }

        public ProcessObservation(ProcessState state) {
            this(state, List.of());
        }
    }

    private final Path ownerPath;
    private final Path apiSocket;
    private final SocketProbe socketProbe;
    private final Gate gate;
    private Owner owner;
    private boolean released;

    private GatewaySlotLease(Path ownerPath, Path apiSocket, SocketProbe socketProbe, Gate gate, Owner owner) {
        this.ownerPath = ownerPath;
        this.apiSocket = apiSocket;
        this.socketProbe = socketProbe;
        this.gate = gate;
        this.owner = owner;
    }

    public static GatewaySlotLease acquire(Path filesRoot, String instanceId, ProcessProbe processProbe,
            SocketProbe socketProbe) throws IOException {
        return acquire(filesRoot, instanceId, processProbe, socketProbe, 0, 0L);
    }

    public static GatewaySlotLease acquire(Path filesRoot, String instanceId, ProcessProbe processProbe,
            SocketProbe socketProbe, int launcherPid, long launcherStartTicks) throws IOException {
        if (!validInstanceId(instanceId)) throw new IOException("invalid gateway instance id");
        if (launcherPid < 0 || launcherStartTicks < 0 || (launcherPid == 0 && launcherStartTicks != 0)) {
            throw unavailable();
        }
        Path root = filesRoot.toRealPath();
        Path locks = root.resolve("net-slots");
        requireDirectory(locks);
        int port = GatewaySlotArbiter.derivedPort(instanceId);
        Path lockPath = locks.resolve(port + ".lock");
        Gate gate = acquireGate(lockPath);
        try {
            Path ownerPath = locks.resolve(port + ".owner");
            Path apiRoot = root.resolve("net-api");
            requireDirectory(apiRoot);
            Path api = apiSocket(apiRoot, instanceId);
            requireDirectory(api.getParent());
            Owner previous = readOwner(ownerPath);
            boolean bound = tcpListenerBound(port);
            if (previous != null) {
                if (GatewaySlotArbiter.derivedPort(previous.instanceId) != port) {
                    throw unavailable();
                }
                bound = recoverPreviousOwner(
                        apiRoot, ownerPath, port, instanceId, previous, processProbe, socketProbe, bound);
            }
            GatewaySlotVerdict verdict = GatewaySlotArbiter.verdict(instanceId,
                    new GatewaySlotEvidence(bound, attributes(api) != null, List.of(), true));
            if (verdict != GatewaySlotVerdict.FREE) throw rejected(verdict);
            Owner owner = new Owner(instanceId, UUID.randomUUID(), 0, 0, "", launcherPid, launcherStartTicks);
            writeOwner(ownerPath, owner);
            return new GatewaySlotLease(ownerPath, api, socketProbe, gate, owner);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                gate.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Path apiSocket() {
        return apiSocket;
    }

    /**
     * Records the process behind the private API socket. A zero start time means the OS could
     * verify the peer PID but does not expose its start time. Recovery then requires PID absence.
     */
    public synchronized void recordProcess(int pid, long startTicks, String identity) throws IOException {
        if (pid <= 0 || startTicks < 0 || owner.pid != 0 || !isValidSocketIdentity(identity)) {
            throw unavailable();
        }
        requireCurrentOwner();
        if (!identity.equals(socketProbe.identity(apiSocket))) throw unavailable();
        Owner running = new Owner(owner.instanceId, owner.generation, pid, startTicks, identity,
                owner.launcherPid, owner.launcherStartTicks);
        writeOwner(ownerPath, running);
        owner = running;
    }

    /** The caller has confirmed that its spawned process exited, or that it never spawned one. */
    public synchronized void releaseAfterExit() throws IOException {
        if (released) return;
        requireCurrentOwner();
        if (owner.socketIdentity.isEmpty()) {
            // acquire() verified the API path was absent under this exclusive lock. Any valid
            // socket created before recordProcess() belongs to the child whose exit is confirmed.
            String current = socketProbe.identity(apiSocket);
            if (current != null) {
                if (current.isEmpty()) {
                    throw new IOException("gateway-slot-socket-cleanup-failed");
                }
                Files.deleteIfExists(apiSocket);
                if (socketProbe.identity(apiSocket) != null) {
                    throw new IOException("gateway-slot-socket-cleanup-failed");
                }
            }
        } else {
            // If the path was replaced after authentication, leave the node for its owner.
            deleteOwnedSocket(apiSocket, socketProbe, owner);
        }
        Files.delete(ownerPath);
        released = true;
        gate.close();
    }

    private void requireCurrentOwner() throws IOException {
        if (released || !owner.equals(readOwner(ownerPath))) throw unavailable();
    }

    private static boolean recoverPreviousOwner(Path apiRoot, Path ownerPath, int port,
            String requestingInstance, Owner previous, ProcessProbe processProbe,
            SocketProbe socketProbe, boolean bound) throws IOException {
        Path api = apiSocket(apiRoot, previous.instanceId);
        requireDirectory(api.getParent());
        if (previous.pid == 0) {
            if (previous.launcherPid > 0 && !bound && attributes(api) == null) {
                ProcessObservation launcher = processProbe.inspect(
                        previous.launcherPid, previous.launcherStartTicks);
                if (launcher.state == ProcessState.EXITED) {
                    Files.delete(ownerPath);
                    return false;
                }
            }
            throw unavailable();
        }
        ProcessObservation observation = processProbe.inspect(previous.pid, previous.startTicks);
        if (observation.state == ProcessState.UNAVAILABLE) throw unavailable();
        if (observation.state == ProcessState.RUNNING) {
            GatewaySlotVerdict verdict = GatewaySlotArbiter.verdict(requestingInstance,
                    new GatewaySlotEvidence(bound, attributes(api) != null,
                            List.of(new ProcessRef(previous.pid, observation.commandLine)), true));
            if (verdict != GatewaySlotVerdict.FREE) throw rejected(verdict);
            // Ownership survives the gap before a registered process opens an endpoint.
            throw new IOException("gateway-slot-previous-owner-running");
        }
        boolean stillBound = bound;
        for (int attempt = 1; stillBound && attempt < LOCK_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("gateway-slot-interrupted", interrupted);
            }
            stillBound = tcpListenerBound(port);
        }
        if (stillBound || !deleteOwnedSocket(api, socketProbe, previous)) {
            throw rejected(GatewaySlotVerdict.HELD_BY_UNKNOWN);
        }
        Files.delete(ownerPath);
        return false;
    }

    private static boolean deleteOwnedSocket(Path api, SocketProbe socketProbe, Owner owner) throws IOException {
        String current = socketProbe.identity(api);
        if (current == null) return true;
        if (owner.socketIdentity.isEmpty() || !owner.socketIdentity.equals(current)) return false;
        Files.deleteIfExists(api);
        return true;
    }

    private static Path apiSocket(Path apiRoot, String instanceId) {
        return apiRoot.resolve(GatewaySlotArbiter.apiSocketDirectoryName(instanceId)).resolve("api.sock");
    }

    private static boolean validInstanceId(String instanceId) {
        return instanceId != null && instanceId.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static boolean isValidSocketIdentity(String identity) {
        return identity != null && identity.matches("[A-Za-z0-9:_-]{1,128}");
    }

    private static void requireDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        BasicFileAttributes attributes = attributes(path);
        if (attributes == null || !attributes.isDirectory()) throw unavailable();
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static Owner readOwner(Path path) throws IOException {
        BasicFileAttributes attributes = attributes(path);
        if (attributes == null) return null;
        if (!attributes.isRegularFile() || attributes.size() > MAX_OWNER_BYTES) throw unavailable();
        String data = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII);
        String[] fields = data.split("\n", -1);
        boolean v1 = fields.length == 7 && fields[0].equals("1") && fields[6].isEmpty();
        boolean v2 = fields.length == 9 && fields[0].equals("2") && fields[8].isEmpty();
        if ((!v1 && !v2) || !validInstanceId(fields[1])) {
            throw unavailable();
        }
        try {
            UUID generation = UUID.fromString(fields[2]);
            int pid = Integer.parseInt(fields[3]);
            long startTicks = Long.parseLong(fields[4]);
            if (pid < 0 || startTicks < 0 || (pid == 0 && (startTicks != 0 || !fields[5].isEmpty()))
                    || (pid > 0 && !isValidSocketIdentity(fields[5]))) {
                throw unavailable();
            }
            int launcherPid = v2 ? Integer.parseInt(fields[6]) : 0;
            long launcherStartTicks = v2 ? Long.parseLong(fields[7]) : 0L;
            if (launcherPid < 0 || launcherStartTicks < 0
                    || (launcherPid == 0 && launcherStartTicks != 0)) {
                throw unavailable();
            }
            return new Owner(fields[1], generation, pid, startTicks, fields[5], launcherPid, launcherStartTicks);
        } catch (IllegalArgumentException malformed) {
            throw unavailable();
        }
    }

    private static void writeOwner(Path ownerPath, Owner owner) throws IOException {
        Path temporary = Files.createTempFile(ownerPath.getParent(), "owner-", ".tmp");
        try {
            String data = "2\n" + owner.instanceId + "\n" + owner.generation + "\n"
                    + owner.pid + "\n" + owner.startTicks + "\n" + owner.socketIdentity + "\n"
                    + owner.launcherPid + "\n" + owner.launcherStartTicks + "\n";
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.US_ASCII.encode(data);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temporary, ownerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** A bind probe observes the listener without consuming gvproxy's single client connection. */
    public static boolean tcpListenerBound(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            // gvproxy also uses SO_REUSEADDR. A closed connection in TIME_WAIT isn't an owner.
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (BindException occupied) {
            return true;
        }
    }

    private static IOException unavailable() {
        return rejected(GatewaySlotVerdict.EVIDENCE_UNAVAILABLE);
    }

    private static IOException rejected(GatewaySlotVerdict verdict) {
        return new IOException("gateway-slot-" + verdict.name().toLowerCase(Locale.ROOT));
    }

    private static Gate acquireGate(Path path) throws IOException {
        for (int attempt = 1; attempt <= LOCK_ATTEMPTS; attempt++) {
            Gate gate = tryAcquireGate(path);
            if (gate != null) return gate;
            if (attempt < LOCK_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("gateway-slot-interrupted", interrupted);
                }
            }
        }
        throw new IOException("gateway-slot-owner-busy");
    }

    private static Gate tryAcquireGate(Path path) throws IOException {
        if (!LOCAL_LOCKS.add(path)) return null;
        FileChannel channel = null;
        Gate gate = null;
        try {
            channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock != null) gate = new Gate(path, channel, lock);
            return gate;
        } catch (OverlappingFileLockException occupied) {
            return null;
        } finally {
            if (gate == null) {
                try {
                    if (channel != null) channel.close();
                } finally {
                    LOCAL_LOCKS.remove(path);
                }
            }
        }
    }

    private record Owner(
            String instanceId,
            UUID generation,
            int pid,
            long startTicks,
            String socketIdentity,
            int launcherPid,
            long launcherStartTicks) {}

    private record Gate(Path path, FileChannel channel, FileLock lock) {
        void close() throws IOException {
            try {
                channel.close();
            } finally {
                LOCAL_LOCKS.remove(path);
            }
        }
    }
}
