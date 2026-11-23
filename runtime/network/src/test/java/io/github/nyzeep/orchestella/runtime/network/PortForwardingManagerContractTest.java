package io.github.nyzeep.orchestella.runtime.network;

import io.github.nyzeep.orchestella.runtime.contract.PortConflictException;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Contract tests for port forwarding manager, conflict detector, persistent store,
 * dynamic engine reconciliation, and live socket traffic (ADR-0008, ADR-0010, Ticket 29 AC-1 to AC-5).
 *
 * <p>The scenario set is strategy-neutral: {@link #main(String[])} runs it with
 * the QEMU-profile {@link SocketPortForwarder}, and other execution strategies
 * run the SAME scenarios by supplying their own live engine through
 * {@link #runAllScenarios(Path, LiveEngineFactory)} (Ticket 36 AC-3 — the AVF
 * profile re-runs this exact suite over its vsock forwarding engine).
 */
public final class PortForwardingManagerContractTest {

    /**
     * Supplies one started live engine for the traffic scenario, bound to the
     * given guest service port (the port the guest-side target listens on).
     */
    @FunctionalInterface
    public interface LiveEngineFactory {
        PortForwardingEngine create(int guestServicePort) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path tempDir = args.length > 0 ? Paths.get(args[0]) : Files.createTempDirectory("pf-test");
        runAllScenarios(tempDir, guestServicePort -> new SocketPortForwarder());
    }

    /** Runs every scenario against the engine produced by {@code liveEngineFactory}. */
    public static void runAllScenarios(Path tempDir, LiveEngineFactory liveEngineFactory)
            throws Exception {
        cleanDirectory(tempDir);
        Files.createDirectories(tempDir);

        try {
            addsAndListsRulesWithDefaultLoopback(tempDir);
            detectsConflictOnDuplicateHostPortSameProtocol(tempDir);
            detectsConflictOnLanExposureOverlappingLoopback(tempDir);
            persistsRulesAcrossStoreAndManagerReconstruction(tempDir);
            reconcilesWithRunningEngineOnAdditionAndRemoval(tempDir);
            attachingRunningEngineReplaysExistingRules(tempDir);
            compilerEndpointsStayReservedWhenLoadingOldRules(tempDir);
            openPagesShareRuleChanges(tempDir);
            updatesRulesAtomicallyRollsBackSaveFailuresAndSerializesApplyRule(tempDir);
            removesRuleAndUpdatesStore(tempDir);
            verifiesLiveTrafficForwardingAndImmediateInvalidationOnRemoval(
                    tempDir, liveEngineFactory);
            System.out.println("PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK");
        } finally {
            cleanDirectory(tempDir);
        }
    }

    private static void verifiesLiveTrafficForwardingAndImmediateInvalidationOnRemoval(
            Path dir, LiveEngineFactory liveEngineFactory) throws Exception {
        // Start a mock guest server
        ServerSocket guestServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        int guestPort = guestServer.getLocalPort();

        Thread serverThread = new Thread(() -> {
            try {
                while (!guestServer.isClosed()) {
                    Socket client = guestServer.accept();
                    try (client; OutputStream out = client.getOutputStream()) {
                        out.write("HELLO FROM GUEST\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            } catch (IOException ignored) {}
        }, "mock-guest-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Pick an available host port
        int hostPort;
        try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            hostPort = probe.getLocalPort();
        }

        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortConflictDetector detector = new PortConflictDetector();
        PortForwardingManager manager = new PortForwardingManager("inst-live", store, detector);

        PortForwardingEngine engine = liveEngineFactory.create(guestPort);
        manager.attachEngine(engine);

        PortForwardingRule rule = PortForwardingRule.defaultRule("live-rule", Protocol.TCP, hostPort, guestPort);
        manager.addRule(rule);

        // AC-1: Connect to hostPort, traffic must reach guest server and receive payload
        try (Socket clientSocket = new Socket("127.0.0.1", hostPort)) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            assertEquals("HELLO FROM GUEST", line, "AC-1: host port forward reaches guest server");
        }

        // AC-2: Remove rule hot without stopping
        boolean removed = manager.removeRule("live-rule");
        assertTrue(removed, "rule removed successfully");

        // Allow OS brief moment to close listener
        Thread.sleep(100);

        // Immediate connection attempt to hostPort must fail (Connection refused)
        assertThrows(
                IOException.class,
                () -> {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress("127.0.0.1", hostPort), 500);
                    }
                },
                "AC-2: connection after hot-removal must immediately fail without VM restart");

        if (engine instanceof AutoCloseable closeable) {
            closeable.close();
        }
        guestServer.close();
    }

    private static void addsAndListsRulesWithDefaultLoopback(Path dir) throws IOException {
        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortConflictDetector detector = new PortConflictDetector();
        PortForwardingManager manager = new PortForwardingManager("inst-1", store, detector);

        PortForwardingRule rule1 = PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80);
        manager.addRule(rule1);

        List<PortForwardingRule> rules = manager.listRules();
        assertEquals(1, rules.size(), "rules size");
        assertEquals("r1", rules.get(0).id(), "rule id");
        assertEquals(PortForwardingRule.LOOPBACK_ADDRESS, rules.get(0).bindAddress(), "bindAddress");
        assertFalse(rules.get(0).isLanExposed(), "isLanExposed");

        Optional<PortForwardingRule> fetched = manager.getRule("r1");
        assertTrue(fetched.isPresent(), "fetched rule present");
        assertEquals(8080, fetched.get().hostPort(), "fetched hostPort");
    }

    private static void detectsConflictOnDuplicateHostPortSameProtocol(Path dir) throws IOException {
        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortConflictDetector detector = new PortConflictDetector();
        PortForwardingManager manager = new PortForwardingManager("inst-conflict", store, detector);

        PortForwardingRule r1 = PortForwardingRule.defaultRule("r1", Protocol.TCP, 9090, 80);
        manager.addRule(r1);

        // Conflict: Same protocol (TCP) and same host port (9090)
        PortForwardingRule duplicate = PortForwardingRule.defaultRule("r2", Protocol.TCP, 9090, 8080);
        PortConflictException ex = assertThrows(
                PortConflictException.class,
                () -> manager.addRule(duplicate),
                "duplicate host port must throw PortConflictException (AC-5)");

        assertEquals(Protocol.TCP, ex.protocol(), "exception protocol");
        assertEquals(9090, ex.hostPort(), "exception hostPort");
        assertEquals("r1", ex.conflictingRuleId(), "conflictingRuleId");

        // Allowed: Different protocol (UDP) on same host port (9090)
        PortForwardingRule udpRule = PortForwardingRule.defaultRule("r-udp", Protocol.UDP, 9090, 90);
        manager.addRule(udpRule);
        assertEquals(2, manager.listRules().size(), "both TCP and UDP on same port allowed");
    }

    private static void detectsConflictOnLanExposureOverlappingLoopback(Path dir) throws IOException {
        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortConflictDetector detector = new PortConflictDetector();
        PortForwardingManager manager = new PortForwardingManager("inst-lan-conflict", store, detector);

        PortForwardingRule loopback = PortForwardingRule.defaultRule("r-loopback", Protocol.TCP, 7070, 80);
        manager.addRule(loopback);

        // Adding 0.0.0.0 on same host port must conflict with existing 127.0.0.1
        PortForwardingRule lanRule = PortForwardingRule.builder()
                .id("r-lan")
                .protocol(Protocol.TCP)
                .hostPort(7070)
                .guestPort(80)
                .bindAddress(PortForwardingRule.LAN_EXPOSED_ADDRESS)
                .allowLanExposure(true)
                .build();

        assertThrows(
                PortConflictException.class,
                () -> manager.addRule(lanRule),
                "LAN exposed 0.0.0.0 rule must conflict with existing loopback rule on same port (AC-5)");
    }

    private static void persistsRulesAcrossStoreAndManagerReconstruction(Path dir) throws IOException {
        PortForwardingStore store1 = new FilePortForwardingStore(dir);
        PortConflictDetector detector1 = new PortConflictDetector();
        PortForwardingManager manager1 = new PortForwardingManager("inst-persist", store1, detector1);

        manager1.addRule(PortForwardingRule.defaultRule("r1", Protocol.TCP, 3000, 3000));
        manager1.addRule(PortForwardingRule.builder()
                .id("r2")
                .protocol(Protocol.UDP)
                .hostPort(4000)
                .guestPort(4000)
                .bindAddress(PortForwardingRule.LAN_EXPOSED_ADDRESS)
                .allowLanExposure(true)
                .description("Telemetry")
                .build());

        assertEquals(2, manager1.listRules().size(), "manager1 rules count");

        // Simulate application restart: create brand new store and manager pointing to same directory
        PortForwardingStore store2 = new FilePortForwardingStore(dir);
        PortConflictDetector detector2 = new PortConflictDetector();
        PortForwardingManager manager2 = new PortForwardingManager("inst-persist", store2, detector2);

        List<PortForwardingRule> restored = manager2.listRules();
        assertEquals(2, restored.size(), "restored rules count across restart (AC-3)");

        PortForwardingRule restoredR1 = manager2.getRule("r1").orElseThrow();
        assertEquals(Protocol.TCP, restoredR1.protocol(), "r1 protocol");
        assertEquals(3000, restoredR1.hostPort(), "r1 hostPort");
        assertEquals(PortForwardingRule.LOOPBACK_ADDRESS, restoredR1.bindAddress(), "r1 bindAddress");

        PortForwardingRule restoredR2 = manager2.getRule("r2").orElseThrow();
        assertEquals(Protocol.UDP, restoredR2.protocol(), "r2 protocol");
        assertEquals(4000, restoredR2.hostPort(), "r2 hostPort");
        assertEquals(PortForwardingRule.LAN_EXPOSED_ADDRESS, restoredR2.bindAddress(), "r2 bindAddress");
        assertTrue(restoredR2.isLanExposed(), "r2 isLanExposed");
        assertEquals("Telemetry", restoredR2.description(), "r2 description");
    }

    private static void reconcilesWithRunningEngineOnAdditionAndRemoval(Path dir) throws IOException {
        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortConflictDetector detector = new PortConflictDetector();
        PortForwardingManager manager = new PortForwardingManager("inst-reconcile", store, detector);

        MockEngine engine = new MockEngine();
        manager.attachEngine(engine);

        PortForwardingRule rule = PortForwardingRule.defaultRule("dyn-1", Protocol.TCP, 5000, 80);
        manager.addRule(rule);

        assertEquals(1, engine.addedRules.size(), "engine received dynamic addition");
        assertEquals("dyn-1", engine.addedRules.get(0).id(), "added rule id");

        // Remove rule: engine must receive removeRule immediately (hot-reload, AC-2)
        boolean removed = manager.removeRule("dyn-1");
        assertTrue(removed, "removeRule return value");
        assertEquals(1, engine.removedRules.size(), "engine received dynamic removal");
        assertEquals("dyn-1", engine.removedRules.get(0).id(), "removed rule id");
        assertEquals(0, manager.listRules().size(), "manager rules empty after removal");
    }

    private static void attachingRunningEngineReplaysExistingRules(Path dir) throws IOException {
        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortConflictDetector detector = new PortConflictDetector();
        PortForwardingManager manager = new PortForwardingManager("inst-replay", store, detector);

        manager.addRule(PortForwardingRule.defaultRule("r-pre-1", Protocol.TCP, 6001, 80));
        manager.addRule(PortForwardingRule.defaultRule("r-pre-2", Protocol.TCP, 6002, 80));

        // Now attach a newly booted engine: it must receive both pre-configured rules
        MockEngine engine = new MockEngine();
        manager.attachEngine(engine);

        assertEquals(2, engine.addedRules.size(), "engine replayed rules on attach");
    }

    private static void removesRuleAndUpdatesStore(Path dir) throws IOException {
        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortConflictDetector detector = new PortConflictDetector();
        PortForwardingManager manager = new PortForwardingManager("inst-remove", store, detector);

        manager.addRule(PortForwardingRule.defaultRule("to-del", Protocol.TCP, 7777, 80));
        assertEquals(1, manager.listRules().size(), "before removal");

        assertTrue(manager.removeRule("to-del"), "successful remove");
        assertFalse(manager.removeRule("non-existent"), "remove non-existent returns false");

        // Verify store on disk has 0 rules
        PortForwardingStore store2 = new FilePortForwardingStore(dir);
        assertEquals(0, store2.loadRules("inst-remove").size(), "store has 0 rules after removal");
    }

    private static void compilerEndpointsStayReservedWhenLoadingOldRules(Path dir) throws IOException {
        String instance = "inst-compiler-endpoints";
        PortForwardingStore store = new FilePortForwardingStore(dir);
        PortForwardingRule legacy = PortForwardingRule.defaultRule("legacy", Protocol.TCP, 3632, 80);
        store.saveRules(instance, List.of(legacy));
        PortForwardingManager manager = new PortForwardingManager(instance, store, new PortConflictDetector());
        MockEngine engine = new MockEngine();

        manager.attachEngine(engine);
        assertTrue(engine.addedRules.isEmpty(), "restart skips the reserved host endpoint");
        assertEquals(List.of(legacy), manager.listRules(), "old configuration remains available for removal");

        PortForwardingRule compileListener = PortForwardingRule.defaultRule(
                "new", Protocol.UDP, 3633, 8080);
        assertThrows(IOException.class, () -> manager.addRule(compileListener),
                "adding a reserved host endpoint is rejected");
        assertTrue(manager.removeRule(legacy.id()), "the user can delete the old rule");
        assertTrue(engine.removedRules.isEmpty(), "deleting the old rule cannot touch the compiler listener");
    }

    private static void openPagesShareRuleChanges(Path dir) throws IOException {
        String instance = "inst-shared-page";
        PortForwardingManager first = PortForwardingManager.forInstance(instance, dir);
        PortForwardingManager second = PortForwardingManager.forInstance(instance, dir);
        PortForwardingRule web = PortForwardingRule.defaultRule("web", Protocol.TCP, 8000, 80);
        PortForwardingRule ssh = PortForwardingRule.defaultRule("ssh", Protocol.TCP, 2222, 22);

        first.addRule(web);
        second.addRule(ssh);

        assertEquals(List.of(web, ssh), new FilePortForwardingStore(dir).loadRules(instance),
                "one page cannot overwrite an edit from another page");
    }

    private static void updatesRulesAtomicallyRollsBackSaveFailuresAndSerializesApplyRule(Path dir)
            throws IOException {
        Path storeDir = dir.resolve("update-contract");
        Files.createDirectories(storeDir);
        FailingSaveStore store = new FailingSaveStore(new FilePortForwardingStore(storeDir));
        PortForwardingManager manager = new PortForwardingManager(
                "inst-update", store, new PortConflictDetector());
        TrackingLiveEngine engine = new TrackingLiveEngine();

        PortForwardingRule first = PortForwardingRule.defaultRule("r1", Protocol.TCP, 8101, 80);
        PortForwardingRule other = PortForwardingRule.defaultRule("r2", Protocol.TCP, 8102, 81);
        manager.addRule(first, engine);
        manager.addRule(other, engine);

        // Update colliding with another rule's host port fails and leaves original applied.
        PortForwardingRule conflict = PortForwardingRule.builder(first).hostPort(8102).build();
        assertThrows(PortConflictException.class, () -> manager.updateRule(conflict, engine),
                "conflicting update must reject");
        assertTrue(engine.isApplied(first), "conflict preserves original rule");

        // Update onto reserved compiler port fails.
        PortForwardingRule reserved = PortForwardingRule.builder(first)
                .hostPort(PortForwardingRule.COMPILE_WORKER_HOST_PORT).build();
        assertThrows(IOException.class, () -> manager.updateRule(reserved, engine),
                "reserved host port update must reject");
        assertTrue(engine.isApplied(first), "reserved rejection preserves original rule");

        // Save failure rolls back live engine replacement.
        store.failNextSave = true;
        PortForwardingRule moved = PortForwardingRule.builder(first).hostPort(8103).guestPort(8080).build();
        assertThrows(IOException.class, () -> manager.updateRule(moved, engine),
                "store failure must fail update");
        assertTrue(engine.isApplied(first), "save failure must roll back live replacement");
        assertFalse(engine.isApplied(moved), "rolled-back replacement must not remain applied");
        assertEquals(first, manager.getRule("r1").orElseThrow(), "manager retains original rule");

        // Successful update and applyRule on an unapplied saved rule.
        assertTrue(manager.updateRule(moved, engine), "valid update returns true");
        assertTrue(engine.isApplied(moved), "moved rule is live");
        engine.removeRule(moved);
        assertFalse(engine.isApplied(moved), "manually stopped before applyRule");
        manager.applyRule("r1", engine);
        assertTrue(engine.isApplied(moved), "applyRule re-activates saved rule under lock");
    }

    private static final class FailingSaveStore implements PortForwardingStore {
        private final PortForwardingStore delegate;
        boolean failNextSave;

        FailingSaveStore(PortForwardingStore delegate) {
            this.delegate = delegate;
        }

        @Override public List<PortForwardingRule> loadRules(String instanceId) throws IOException {
            return delegate.loadRules(instanceId);
        }

        @Override public void saveRules(String instanceId, List<PortForwardingRule> rules) throws IOException {
            if (failNextSave) {
                failNextSave = false;
                throw new IOException("simulated save failure");
            }
            delegate.saveRules(instanceId, rules);
        }

        @Override public void clearRules(String instanceId) throws IOException {
            delegate.clearRules(instanceId);
        }
    }

    private static final class TrackingLiveEngine implements PortForwardingEngine.Live {
        private final java.util.Map<String, PortForwardingRule> applied = new java.util.HashMap<>();

        @Override public void addRule(PortForwardingRule rule) throws IOException {
            applied.put(rule.id(), rule);
        }

        @Override public void removeRule(PortForwardingRule rule) throws IOException {
            applied.remove(rule.id(), rule);
        }

        @Override public boolean isRunning() { return true; }
        @Override public void refresh() {}
        @Override public boolean isApplied(PortForwardingRule rule) {
            return rule.equals(applied.get(rule.id()));
        }
    }

    private static final class MockEngine implements PortForwardingEngine {
        final List<PortForwardingRule> addedRules = new ArrayList<>();
        final List<PortForwardingRule> removedRules = new ArrayList<>();
        boolean running = true;

        @Override
        public void addRule(PortForwardingRule rule) {
            addedRules.add(rule);
        }

        @Override
        public void removeRule(PortForwardingRule rule) {
            removedRules.add(rule);
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private static void cleanDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        if (condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
