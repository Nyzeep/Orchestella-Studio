package io.github.nyzeep.orchestella.runtime.instance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class GatewaySlotLeaseTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 15000)
    public void anotherProcessCanAcquireOnlyAfterTheRecordedOwnerExits() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instance = availableInstance();
        Path api = apiSocket(root, instance);
        Process owner = startChild(root, instance, "hold");
        Process gateway = null;
        try (BufferedReader output = new BufferedReader(new InputStreamReader(owner.getInputStream()))) {
            assertEquals("LEASED", output.readLine());
            gateway = startChild(root, instance, "listen");
            try (BufferedReader gatewayOutput =
                         new BufferedReader(new InputStreamReader(gateway.getInputStream()))) {
                assertEquals("BOUND", gatewayOutput.readLine());
            }
            owner.getOutputStream().write((gateway.pid() + "\n").getBytes(StandardCharsets.US_ASCII));
            owner.getOutputStream().flush();
            assertEquals("READY", output.readLine());
            assertTrue(GatewaySlotLease.tcpListenerBound(GatewaySlotArbiter.derivedPort(instance)));
            assertThrows(IOException.class, () -> acquire(root, instance));
            assertEquals("owner", Files.readString(api));

            owner.destroyForcibly();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
            // The app process's OS lock is gone while its independently owned listener lives.
            assertTrue(gateway.isAlive());
            assertThrows(IOException.class, () -> acquire(root, instance));
            assertEquals("owner", Files.readString(api));
        } finally {
            owner.destroyForcibly();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
            if (gateway != null) {
                gateway.destroyForcibly();
                assertTrue(gateway.waitFor(5, TimeUnit.SECONDS));
            }
        }

        Files.writeString(api, "unknown");
        assertThrows(IOException.class, () -> acquire(root, instance));
        assertEquals("unknown", Files.readString(api));
        Files.writeString(api, "owner");

        GatewaySlotLease replacement = acquire(root, instance);
        try (ServerSocket listener = bind(instance)) {
            assertFalse("only the exited owner's socket can be cleaned up", Files.exists(api));
            assertTrue(listener.isBound());
        } finally {
            replacement.releaseAfterExit();
        }
    }

    @Test(timeout = 15000)
    public void rejectedLocalAttemptAndStaleCallbackCannotReleaseAnotherGeneration() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instance = availableInstance();
        GatewaySlotLease lease = acquire(root, instance);
        try {
            assertThrows(IOException.class, () -> acquire(root, instance));
            Process contender = startChild(root, instance, "contend");
            try (BufferedReader output = new BufferedReader(new InputStreamReader(contender.getInputStream()))) {
                assertEquals("DENIED", output.readLine());
                assertTrue(contender.waitFor(5, TimeUnit.SECONDS));
            } finally {
                contender.destroyForcibly();
            }
        } finally {
            lease.releaseAfterExit();
        }

        GatewaySlotLease replacement = acquire(root, instance);
        try {
            Files.writeString(replacement.apiSocket(), "replacement");
            replacement.recordProcess((int) ProcessHandle.current().pid(), 1, "replacement");
            lease.releaseAfterExit();
            assertEquals("replacement", Files.readString(replacement.apiSocket()));
            assertThrows(IOException.class, () -> acquire(root, instance));
        } finally {
            replacement.releaseAfterExit();
        }
    }

    @Test(timeout = 15000)
    public void pendingOwnerAndUnknownSocketArePreservedWithoutExitEvidence() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instance = availableInstance();
        Path api = apiSocket(root, instance);
        Files.createDirectories(api.getParent());
        Files.writeString(api, "unknown");
        assertThrows(IOException.class, () -> acquire(root, instance));
        assertEquals("unknown", Files.readString(api));
        Files.delete(api);

        Process owner = startChild(root, instance, "pending");
        try (BufferedReader output = new BufferedReader(new InputStreamReader(owner.getInputStream()))) {
            assertEquals("READY", output.readLine());
        } finally {
            owner.destroyForcibly();
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
        }
        assertThrows(IOException.class, () -> acquire(root, instance));
        assertEquals("owner", Files.readString(api));
    }

    @Test(timeout = 15000)
    public void preRecordStartFailureCleansCreatedSocketAndAllowsReacquire() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instance = availableInstance();
        GatewaySlotLease lease = acquire(root, instance);
        Files.writeString(lease.apiSocket(), "pre-record-socket");
        lease.releaseAfterExit();

        assertFalse("pre-record socket owned by this exclusive lease must be cleaned up on confirmed exit",
                Files.exists(lease.apiSocket()));
        GatewaySlotLease reacquired = acquire(root, instance);
        try {
            assertFalse(Files.exists(reacquired.apiSocket()));
        } finally {
            reacquired.releaseAfterExit();
        }
    }

    @Test(timeout = 15000)
    public void pendingOwnerIsReclaimedOnlyWhenLauncherExitedWithoutResources() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instance = availableInstance();
        Path netSlots = root.resolve("net-slots");
        int port = GatewaySlotArbiter.derivedPort(instance);

        Process crashedLauncher = startChild(root, instance, "crash-before-socket");
        try (BufferedReader output = new BufferedReader(new InputStreamReader(crashedLauncher.getInputStream()))) {
            assertEquals("PENDING_CRASH", output.readLine());
            assertTrue(crashedLauncher.waitFor(5, TimeUnit.SECONDS));
        } finally {
            crashedLauncher.destroyForcibly();
        }

        // With dead launcher and no socket/port, recovery succeeds.
        GatewaySlotLease recovered = acquireWithLiveProbe(root, instance);
        try {
            assertTrue(Files.exists(netSlots.resolve(port + ".owner")));
        } finally {
            recovered.releaseAfterExit();
        }

        // If launcher is dead, pending record must STILL stay preserved when api.sock exists.
        Process crashedWithSocket = startChild(root, instance, "crash-with-socket");
        try (BufferedReader output = new BufferedReader(new InputStreamReader(crashedWithSocket.getInputStream()))) {
            assertEquals("PENDING_SOCKET_CRASH", output.readLine());
            assertTrue(crashedWithSocket.waitFor(5, TimeUnit.SECONDS));
        } finally {
            crashedWithSocket.destroyForcibly();
        }
        assertThrows(IOException.class, () -> acquireWithLiveProbe(root, instance));
        assertEquals("pending-child", Files.readString(apiSocket(root, instance)));
        Files.delete(apiSocket(root, instance));
        Files.deleteIfExists(netSlots.resolve(port + ".owner"));

        // If launcher is still running, pending record must NOT be stolen.
        Process liveLauncher = startChild(root, instance, "hold-pending");
        try (BufferedReader output = new BufferedReader(new InputStreamReader(liveLauncher.getInputStream()))) {
            assertEquals("PENDING_HELD", output.readLine());
            assertThrows(IOException.class, () -> acquireWithLiveProbe(root, instance));
        } finally {
            liveLauncher.destroyForcibly();
            assertTrue(liveLauncher.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private static GatewaySlotLease acquireWithLiveProbe(Path root, String instance) throws IOException {
        return GatewaySlotLease.acquire(root, instance, (pid, startTicks) -> {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            return new GatewaySlotLease.ProcessObservation(alive
                    ? GatewaySlotLease.ProcessState.RUNNING : GatewaySlotLease.ProcessState.EXITED);
        }, socket -> Files.exists(socket) ? Files.readString(socket) : null,
                (int) ProcessHandle.current().pid(), 1L);
    }

    @Test(timeout = 15000)
    public void diskLevelStaleGenerationCannotRecordOrReleaseReplacement() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instance = availableInstance();
        GatewaySlotLease stale = acquire(root, instance);
        Path ownerPath = root.resolve("net-slots").resolve(GatewaySlotArbiter.derivedPort(instance) + ".owner");
        String originalOwnerText = Files.readString(ownerPath);
        // Simulate an external replacement having superseded the .owner record on disk while stale still believes it is active.
        String replacedOwnerText = originalOwnerText.replaceFirst(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                "00000000-0000-4000-8000-000000000099");
        Files.writeString(ownerPath, replacedOwnerText);
        Files.writeString(stale.apiSocket(), "replacement-socket");

        assertThrows(IOException.class, () -> stale.recordProcess(1234, 1L, "replacement-socket"));
        assertThrows(IOException.class, stale::releaseAfterExit);
        assertEquals("replacement-socket", Files.readString(stale.apiSocket()));
        assertEquals(replacedOwnerText, Files.readString(ownerPath));
    }

    @Test(timeout = 15000)
    public void crossInstanceDerivedPortCollisionRespectsRunningAndCleansExitedOwner() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instanceA = availableInstance();
        String instanceB = collidingInstance(instanceA);
        assertEquals(GatewaySlotArbiter.derivedPort(instanceA), GatewaySlotArbiter.derivedPort(instanceB));

        GatewaySlotLease leaseA = acquire(root, instanceA);
        Files.writeString(leaseA.apiSocket(), "owner-a");
        leaseA.recordProcess((int) ProcessHandle.current().pid(), 1L, "owner-a");

        // While owner A is running, instance B is rejected and A's socket is untouched.
        assertThrows(IOException.class, () -> GatewaySlotLease.acquire(
                root, instanceB,
                (pid, ticks) -> new GatewaySlotLease.ProcessObservation(
                        GatewaySlotLease.ProcessState.RUNNING,
                        java.util.List.of(GatewaySlotArbiter.configFileName(instanceA))),
                socket -> Files.exists(socket) ? Files.readString(socket) : null));
        assertEquals("owner-a", Files.readString(leaseA.apiSocket()));

        // Close A's gate cleanly only via disk simulation? Better: run A in a child process and kill it!
        leaseA.releaseAfterExit();

        Process ownerChild = startChild(root, instanceA, "hold-no-release");
        try (BufferedReader output = new BufferedReader(new InputStreamReader(ownerChild.getInputStream()))) {
            assertEquals("LEASED", output.readLine());
            ownerChild.getOutputStream().write((ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.US_ASCII));
            ownerChild.getOutputStream().flush();
            assertEquals("READY", output.readLine());
            assertThrows(IOException.class, () -> GatewaySlotLease.acquire(
                    root, instanceB,
                    (pid, ticks) -> new GatewaySlotLease.ProcessObservation(
                            GatewaySlotLease.ProcessState.RUNNING,
                            java.util.List.of(GatewaySlotArbiter.configFileName(instanceA))),
                    socket -> Files.exists(socket) ? Files.readString(socket) : null));
        } finally {
            ownerChild.destroyForcibly();
            assertTrue(ownerChild.waitFor(5, TimeUnit.SECONDS));
        }

        // Now A's recorded process exited; B must clean A's socket and succeed.
        GatewaySlotLease leaseB = GatewaySlotLease.acquire(
                root, instanceB,
                (pid, ticks) -> new GatewaySlotLease.ProcessObservation(GatewaySlotLease.ProcessState.EXITED),
                socket -> Files.exists(socket) ? Files.readString(socket) : null);
        try {
            assertFalse("exited colliding instance's socket must be cleaned up",
                    Files.exists(apiSocket(root, instanceA)));
        } finally {
            leaseB.releaseAfterExit();
        }
    }

    @Test(timeout = 15000)
    public void boundedRetrySucceedsWhenLockReleasesWithinWindowAndInterruptPropagates() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        String instance = availableInstance();
        GatewaySlotLease holder = acquire(root, instance);
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(45L);
                holder.releaseAfterExit();
            } catch (Exception ignored) {
            }
        });
        releaser.start();
        GatewaySlotLease waiter = acquire(root, instance);
        try {
            releaser.join(5000);
        } finally {
            waiter.releaseAfterExit();
        }

        GatewaySlotLease busy = acquire(root, instance);
        try {
            Thread.currentThread().interrupt();
            IOException interrupted = assertThrows(IOException.class, () -> acquire(root, instance));
            assertTrue(interrupted.getMessage().contains("interrupted"));
            assertTrue(Thread.interrupted());
        } finally {
            busy.releaseAfterExit();
        }
    }

    private static String collidingInstance(String base) {
        int target = GatewaySlotArbiter.derivedPort(base);
        for (int i = 0; i < 10_000; i++) {
            String candidate = base + "-col-" + i;
            if (!candidate.equals(base) && GatewaySlotArbiter.derivedPort(candidate) == target) {
                return candidate;
            }
        }
        throw new AssertionError("failed to find colliding instance id");
    }

    private static GatewaySlotLease acquire(Path root, String instance) throws IOException {
        return GatewaySlotLease.acquire(root, instance, (pid, startTicks) -> {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            return new GatewaySlotLease.ProcessObservation(alive
                    ? GatewaySlotLease.ProcessState.RUNNING : GatewaySlotLease.ProcessState.EXITED);
        }, socket -> Files.exists(socket) ? Files.readString(socket) : null);
    }

    private static Path apiSocket(Path root, String instance) {
        return root.resolve("net-api").resolve(GatewaySlotArbiter.apiSocketDirectoryName(instance))
                .resolve("api.sock");
    }

    private static String availableInstance() throws IOException {
        for (int attempt = 0; attempt < 40; attempt++) {
            String instance = "slot-test-" + Long.toUnsignedString(System.nanoTime());
            try (ServerSocket listener = bind(instance)) {
                assertTrue(listener.isBound());
                return instance;
            } catch (BindException inUse) {
                // Another host test or service can already hold a derived port.
            }
        }
        throw new IOException("could not find an idle test slot");
    }

    private static ServerSocket bind(String instance) throws IOException {
        ServerSocket listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress("127.0.0.1", GatewaySlotArbiter.derivedPort(instance)));
            return listener;
        } catch (IOException failure) {
            listener.close();
            throw failure;
        }
    }

    private static Process startChild(Path root, String instance, String action) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = classDirectory(Child.class) + File.pathSeparator + classDirectory(GatewaySlotLease.class);
        return new ProcessBuilder(java, "-cp", classpath, Child.class.getName(), root.toString(), instance, action)
                .redirectErrorStream(true).start();
    }

    private static Path classDirectory(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            String instance = args[1];
            if (args[2].equals("contend")) {
                try {
                    acquire(root, instance).releaseAfterExit();
                    System.out.println("ACQUIRED");
                } catch (IOException fenced) {
                    System.out.println("DENIED");
                }
                return;
            }
            if (args[2].equals("listen")) {
                try (ServerSocket listener = bind(instance)) {
                    if (!listener.isBound()) throw new IOException("fixture listener unavailable");
                    Files.writeString(apiSocket(root, instance), "owner");
                    System.out.println("BOUND");
                    System.in.read();
                }
                return;
            }
            GatewaySlotLease lease = GatewaySlotLease.acquire(
                    root, instance,
                    (pid, ticks) -> new GatewaySlotLease.ProcessObservation(GatewaySlotLease.ProcessState.EXITED),
                    socket -> Files.exists(socket) ? Files.readString(socket) : null,
                    (int) ProcessHandle.current().pid(), 1L);
            if (args[2].equals("crash-before-socket")) {
                System.out.println("PENDING_CRASH");
                System.out.flush();
                Runtime.getRuntime().halt(0);
                return;
            }
            if (args[2].equals("crash-with-socket")) {
                Files.writeString(lease.apiSocket(), "pending-child");
                System.out.println("PENDING_SOCKET_CRASH");
                System.out.flush();
                Runtime.getRuntime().halt(0);
                return;
            }
            if (args[2].equals("hold-pending")) {
                System.out.println("PENDING_HELD");
                System.out.flush();
                System.in.read();
                return;
            }
            if (args[2].equals("hold") || args[2].equals("hold-no-release")) {
                try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                    System.out.println("LEASED");
                    String socketMarker = args[2].equals("hold-no-release") ? "owner-a" : "owner";
                    if (args[2].equals("hold-no-release")) {
                        Files.writeString(lease.apiSocket(), socketMarker);
                    }
                    lease.recordProcess(Integer.parseInt(input.readLine()), 1, socketMarker);
                    System.out.println("READY");
                    if (args[2].equals("hold-no-release")) {
                        Runtime.getRuntime().halt(0);
                    }
                    input.read();
                } finally {
                    if (!args[2].equals("hold-no-release")) {
                        lease.releaseAfterExit();
                    }
                }
                return;
            }
            try (ServerSocket listener = bind(instance)) {
                if (!listener.isBound()) throw new IOException("fixture listener unavailable");
                Files.write(lease.apiSocket(), "owner".getBytes(StandardCharsets.UTF_8));
                System.out.println("READY");
                System.in.read();
            } finally {
                lease.releaseAfterExit();
            }
        }
    }
}
