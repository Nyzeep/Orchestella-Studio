package io.github.nyzeep.droidlayer.runtime.network;

import io.github.nyzeep.droidlayer.runtime.contract.PortConflictException;
import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.droidlayer.runtime.contract.PortForwardingRule;
import io.github.nyzeep.droidlayer.runtime.contract.Protocol;
import java.io.IOException;
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
 * and dynamic engine reconciliation (ADR-0008, ADR-0010, Ticket 29 AC-3, AC-4, AC-5).
 */
public final class PortForwardingManagerContractTest {
    public static void main(String[] args) throws Exception {
        Path tempDir = args.length > 0 ? Paths.get(args[0]) : Files.createTempDirectory("pf-test");
        cleanDirectory(tempDir);
        Files.createDirectories(tempDir);

        try {
            addsAndListsRulesWithDefaultLoopback(tempDir);
            detectsConflictOnDuplicateHostPortSameProtocol(tempDir);
            detectsConflictOnLanExposureOverlappingLoopback(tempDir);
            persistsRulesAcrossStoreAndManagerReconstruction(tempDir);
            reconcilesWithRunningEngineOnAdditionAndRemoval(tempDir);
            attachingRunningEngineReplaysExistingRules(tempDir);
            removesRuleAndUpdatesStore(tempDir);
            System.out.println("PORT_FORWARDING_MANAGER_CONTRACT_TEST_OK");
        } finally {
            cleanDirectory(tempDir);
        }
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
