package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 4.8 Task 3: Pure JVM unit tests for {@link PortForwardingPresenter}.
 * Verifies rule loading, valid additions, conflict/range rejections, toggling, and deletions.
 */
public final class PortForwardingPresenterTest {

    private InMemoryStore store;
    private PortForwardingManager manager;
    private MockView view;
    private PortForwardingPresenter presenter;

    private static final class InMemoryStore implements PortForwardingStore {
        private final Map<String, List<PortForwardingRule>> data = new HashMap<>();
        boolean failSave;

        @Override
        public List<PortForwardingRule> loadRules(String instanceId) {
            return new ArrayList<>(data.getOrDefault(instanceId, new ArrayList<>()));
        }

        @Override
        public void saveRules(String instanceId, List<PortForwardingRule> rules) throws IOException {
            if (failSave) throw new IOException("synthetic persistence failure");
            data.put(instanceId, new ArrayList<>(rules));
        }

        @Override
        public void clearRules(String instanceId) {
            data.remove(instanceId);
        }
    }

    private static final class MockView implements PortForwardingPresenter.View {
        List<PortForwardingPresenter.RuleItemModel> displayedRules = new ArrayList<>();
        String lastValidationError;
        String lastSuccessMessage;
        String lastErrorMessage;
        boolean formCleared = false;

        @Override
        public void displayRules(List<PortForwardingPresenter.RuleItemModel> rules) {
            this.displayedRules = new ArrayList<>(rules);
        }

        @Override
        public void showValidationError(String message) {
            this.lastValidationError = message;
        }

        @Override
        public void clearInputForm() {
            this.formCleared = true;
        }

        @Override
        public void showSuccess(String message) {
            this.lastSuccessMessage = message;
        }

        @Override
        public void showError(String message) {
            this.lastErrorMessage = message;
        }
    }

    @Before
    public void setUp() throws IOException {
        store = new InMemoryStore();
        manager = new PortForwardingManager("inst-test", store, new PortConflictDetector());
        view = new MockView();
        presenter = new PortForwardingPresenter("inst-test", manager);
        presenter.attachView(view);
    }

    @Test
    public void testLoadRulesDisplaysInitialList() throws IOException {
        manager.addRule(PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80));
        manager.addRule(PortForwardingRule.defaultRule("r2", Protocol.TCP, 2222, 22));

        presenter.loadRules();

        assertEquals(2, view.displayedRules.size());
        assertEquals(8080, view.displayedRules.get(0).getHostPort());
        assertEquals(80, view.displayedRules.get(0).getGuestPort());
        assertEquals(2222, view.displayedRules.get(1).getHostPort());
        assertEquals(22, view.displayedRules.get(1).getGuestPort());
    }

    @Test
    public void testAddValidRuleAddsAndRefreshesView() {
        presenter.addRule("8080", "8080", Protocol.TCP, "Web service");

        assertEquals(1, view.displayedRules.size());
        assertEquals(8080, view.displayedRules.get(0).getHostPort());
        assertEquals(8080, view.displayedRules.get(0).getGuestPort());
        assertTrue(view.displayedRules.get(0).getDescription().contains("Web service"));
        assertFalse("saved configuration is not a live listener", view.displayedRules.get(0).isEnabled());
        assertTrue(view.formCleared);
        assertNotNull(view.lastSuccessMessage);
    }

    @Test
    public void testAddConflictingRuleSurfacesValidationError() throws IOException {
        manager.addRule(PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80));
        presenter.loadRules();

        // Try adding another TCP rule on same host port 8080
        presenter.addRule("8080", "8088", Protocol.TCP, "Duplicate web");

        assertNotNull(view.lastValidationError);
        assertTrue(view.lastValidationError.contains("8080"));
        // Rule list should still have only 1 rule
        assertEquals(1, view.displayedRules.size());
    }

    @Test
    public void testAddInvalidPortRangeSurfacesValidationError() {
        // Port < 1024
        presenter.addRule("80", "80", Protocol.TCP, "Privileged port");
        assertNotNull(view.lastValidationError);
        assertTrue(view.lastValidationError.contains("1024"));

        // Port > 65535
        presenter.addRule("70000", "80", Protocol.TCP, "Out of bounds");
        assertNotNull(view.lastValidationError);
    }

    @Test
    public void testToggleRuleUpdatesStatus() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80));
        FakeEngine engine = new FakeEngine();
        engine.addRule(rule);
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);
        presenter.loadRules();

        assertTrue(view.displayedRules.get(0).isEnabled());

        // Toggle to disabled
        presenter.toggleRule(rule.id(), false);
        assertFalse(view.displayedRules.get(0).isEnabled());
        assertTrue(manager.listRules().isEmpty());
        assertTrue("cold boot must not restore a disabled listener", new PortForwardingManager(
                "inst-test", store, new PortConflictDetector()).listRules().isEmpty());
        assertFalse(engine.isApplied(rule));

        // Toggle back to enabled
        presenter.toggleRule(rule.id(), true);
        assertTrue(view.displayedRules.get(0).isEnabled());
    }

    @Test
    public void testDeleteRuleRemovesAndRefreshesView() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("r1", Protocol.TCP, 8080, 80));
        presenter.loadRules();
        assertEquals(1, view.displayedRules.size());

        presenter.deleteRule(rule.id());
        assertEquals(0, view.displayedRules.size());
        assertNotNull(view.lastSuccessMessage);
    }

    @Test public void applyFailureCannotPersistOrClaimEnabled() {
        FakeEngine engine = new FakeEngine();
        engine.failAdd = true;
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);
        presenter.addRule("2222", "22", Protocol.TCP, "SSH");
        assertTrue(manager.listRules().isEmpty());
        assertTrue(store.loadRules("inst-test").isEmpty());
        assertNotNull(view.lastErrorMessage);
        assertEquals(null, view.lastSuccessMessage);
    }

    @Test public void saveFailureRollsBackAppliedListener() {
        FakeEngine engine = new FakeEngine();
        store.failSave = true;
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);
        presenter.addRule("2222", "22", Protocol.TCP, "SSH");
        assertTrue(engine.applied.isEmpty());
        assertTrue(manager.listRules().isEmpty());
        assertNotNull(view.lastErrorMessage);
    }

    @Test public void successfulLiveAdditionAppliesBeforePersistence() {
        FakeEngine engine = new FakeEngine();
        engine.requireEmptyStore = store;
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);
        presenter.addRule("2222", "22", Protocol.TCP, "SSH");
        assertEquals(1, manager.listRules().size());
        assertTrue(view.displayedRules.get(0).isEnabled());
        assertEquals("127.0.0.1", manager.listRules().get(0).bindAddress());
    }

    @Test public void deletePersistenceFailureRestoresOriginalListener() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("ssh", Protocol.TCP, 2222, 22));
        FakeEngine engine = new FakeEngine();
        engine.addRule(rule);
        store.failSave = true;
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);
        presenter.deleteRule(rule.id());
        assertTrue(engine.isApplied(rule));
        assertEquals(1, manager.listRules().size());
        assertEquals("the applied listener must be removed before persistence", 1, engine.removeCalls);
        assertEquals("persistence failure must restore the actual removed listener", 2, engine.addCalls);
        assertNotNull(view.lastErrorMessage);
    }

    @Test public void deleteUnsupportedPersistedUdpWhileGatewayRunsWithoutLiveMutation() throws IOException {
        assertUnappliedLegacyDeletion(PortForwardingRule.defaultRule("legacy-udp", Protocol.UDP, 4022, 22));
    }

    @Test public void deleteUnsupportedPersistedLanWhileGatewayRunsWithoutLiveMutation() throws IOException {
        assertUnappliedLegacyDeletion(PortForwardingRule.builder().id("legacy-lan")
                .protocol(Protocol.TCP).hostPort(4022).guestPort(22)
                .bindAddress("0.0.0.0").allowLanExposure(true).build());
    }

    private void assertUnappliedLegacyDeletion(PortForwardingRule rule) throws IOException {
        manager.addRule(rule);
        FakeEngine engine = new FakeEngine();
        assertFalse("unsupported legacy rules are not applied", engine.isApplied(rule));
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);
        presenter.deleteRule(rule.id());
        assertTrue(manager.listRules().isEmpty());
        assertTrue("deletion must persist across reconstruction", new PortForwardingManager(
                "inst-test", store, new PortConflictDetector()).listRules().isEmpty());
        assertEquals("unapplied rule must not call live removal", 0, engine.removeCalls);
        assertEquals("unapplied rule must not call live apply or rollback", 0, engine.addCalls);
        assertEquals(null, view.lastErrorMessage);
        assertNotNull(view.lastSuccessMessage);
    }

    @Test public void unappliedDeletePersistenceFailureMustNotCreateListenerDuringRollback() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("pending-ssh", Protocol.TCP, 2222, 22));
        FakeEngine engine = new FakeEngine();
        store.failSave = true;
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);
        presenter.deleteRule(rule.id());
        assertEquals(1, manager.listRules().size());
        assertEquals(1, store.loadRules("inst-test").size());
        assertFalse(engine.isApplied(rule));
        assertEquals(0, engine.removeCalls);
        assertEquals("no actual live change means no live rollback", 0, engine.addCalls);
        assertNotNull(view.lastErrorMessage);
        assertEquals(null, view.lastSuccessMessage);
    }

    @Test public void missingEngineAndUdpCannotPretendToToggleLive() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("ssh", Protocol.TCP, 2222, 22));
        presenter.toggleRule(rule.id(), true);
        assertFalse(view.displayedRules.get(0).isEnabled());
        assertNotNull(view.lastErrorMessage);
        presenter.addRule("4022", "22", Protocol.UDP, "unsupported");
        assertEquals(1, manager.listRules().size());
        assertNotNull(view.lastValidationError);
    }

    @Test public void activeSessionProjectionControlsOperationsAndRefreshesAfterReplacement()
            throws IOException {
        FakeEngine engine = new FakeEngine();
        FakeEngine unsupportedEngine = new FakeEngine();
        ExecutionSession capableSession = new ForwardingSession(engine, true);
        ExecutionSession unsupportedSession = new ForwardingSession(unsupportedEngine, false);
        presenter = PortForwardingPresenter.forActiveSession("inst-test", manager);
        presenter.attachView(view);
        try {
            ExecutionSessionRegistry.register("inst-test", unsupportedSession);
            presenter.addRule("4040", "80", Protocol.TCP, "Web");
            assertFalse(view.displayedRules.get(0).isEnabled());
            assertEquals(0, unsupportedEngine.addCalls);

            ExecutionSessionRegistry.register("inst-test", capableSession);
            presenter.toggleRule(view.displayedRules.get(0).getId(), true);
            assertTrue(view.displayedRules.get(0).isEnabled());
            assertEquals(1, engine.addCalls);
            assertEquals(0, unsupportedEngine.addCalls);
        } finally {
            ExecutionSessionRegistry.unregister("inst-test");
        }
    }

    @Test public void hotEditWithBusyEndpointKeepsOriginalListenerAndConfiguration() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("web", Protocol.TCP, 4040, 80));
        FakeEngine engine = new FakeEngine();
        engine.addRule(rule);
        engine.busyPort = 4041;
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);

        presenter.updateRule(rule.id(), "4041", "8080", "Edited");

        assertEquals(rule, store.loadRules("inst-test").get(0));
        assertTrue(engine.isApplied(rule));
        assertEquals(0, engine.removeCalls);
        assertTrue(view.displayedRules.get(0).isEnabled());
        assertNotNull(view.lastErrorMessage);
    }

    @Test public void hotEditPersistFailureRestoresTheOriginalListener() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("web", Protocol.TCP, 4040, 80));
        FakeEngine engine = new FakeEngine();
        engine.addRule(rule);
        store.failSave = true;
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);

        presenter.updateRule(rule.id(), "4041", "8080", "Edited");

        assertEquals(rule, store.loadRules("inst-test").get(0));
        assertEquals(List.of(rule), new ArrayList<>(engine.applied.values()));
        assertNotNull(view.lastErrorMessage);
    }

    @Test public void addRemoveOnlyGatewayKeepsItsBindWhenSamePortRetargetIsRequested()
            throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("web", Protocol.TCP, 4040, 80));
        FakeEngine engine = new FakeEngine();
        engine.addRule(rule);
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);

        presenter.updateRule(rule.id(), "4040", "8080", "Edited");

        assertEquals(List.of(rule), manager.listRules());
        assertTrue(engine.isApplied(rule));
        assertEquals(0, engine.removeCalls);
        assertNotNull(view.lastValidationError);
    }

    @Test public void successfulHotEditPersistsReplacementUnderTheSameRuleId() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("web", Protocol.TCP, 4040, 80));
        FakeEngine engine = new FakeEngine();
        engine.addRule(rule);
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);

        presenter.updateRule(rule.id(), "4041", "8080", "Edited");

        PortForwardingRule changed = store.loadRules("inst-test").get(0);
        assertEquals(rule.id(), changed.id());
        assertEquals(rule.createdAtMillis(), changed.createdAtMillis());
        assertEquals(4041, changed.hostPort());
        assertEquals(8080, changed.guestPort());
        assertTrue(engine.isApplied(changed));
        assertFalse(engine.isApplied(rule));
        assertTrue(view.displayedRules.get(0).isEnabled());
    }

    @Test public void reservedHostPortsAndRuntimeGuestPortAreHandledHonestly() throws IOException {
        PortForwardingRule relayRule = manager.addRule(
                PortForwardingRule.defaultRule("relay-conflict", Protocol.TCP, 8085, 65353));
        FakeEngine umlLikeEngine = new FakeEngine();
        umlLikeEngine.reservedGuestPort = 65353;
        presenter = new PortForwardingPresenter("inst-test", manager, umlLikeEngine);
        presenter.attachView(view);

        presenter.loadRules();
        PortForwardingPresenter.RuleItemModel model = view.displayedRules.get(0);
        assertTrue("reserved runtime guest port must not misreport TCP 127.0.0.1 error",
                model.getDescription().contains("Guest 端口"));
        assertTrue("user must be able to edit the reserved guest port without deleting", model.canEdit());
        assertFalse(model.canToggle());

        // Adding 3632/3633 or runtime-reserved guest port fails cleanly via validation error.
        presenter.addRule("3632", "22", Protocol.TCP, "Compiler");
        assertNotNull(view.lastValidationError);
        assertTrue(view.lastValidationError.contains("3632"));

        presenter.addRule("8086", "65353", Protocol.TCP, "Relay");
        assertNotNull(view.lastValidationError);
        assertTrue(view.lastValidationError.contains("Guest port"));

        // Editing the saved 65353 rule to 8080 succeeds on UML-like engine.
        presenter.updateRule(relayRule.id(), "8085", "8080", "Fixed");
        assertTrue(umlLikeEngine.isApplied(manager.getRule(relayRule.id()).orElseThrow()));
    }

    @Test public void descriptionOnlyEditAndConcurrentDeleteCloseFormCleanly() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("web", Protocol.TCP, 4040, 80));
        FakeEngine engine = new FakeEngine();
        engine.addRule(rule);
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);

        presenter.updateRule(rule.id(), "4040", "80", "Updated label");
        assertEquals("Updated label", manager.getRule(rule.id()).orElseThrow().description());
        assertTrue(engine.isApplied(manager.getRule(rule.id()).orElseThrow()));

        // Concurrent deletion while editing must clear the edit dialog state and surface error.
        view.formCleared = false;
        presenter.updateRule("missing-id", "4040", "80", "Stale");
        assertTrue(view.formCleared);
        assertNotNull(view.lastErrorMessage);

        PortForwardingManager raceManager = new PortForwardingManager(
                "inst-race", store, new PortConflictDetector());
        PortForwardingRule raced = raceManager.addRule(
                PortForwardingRule.defaultRule("raced", Protocol.TCP, 5050, 80));
        PortForwardingPresenter racePresenter = new PortForwardingPresenter("inst-race", raceManager, engine);
        MockView raceView = new MockView();
        racePresenter.attachView(raceView);
        raceManager.removeRule(raced.id());
        racePresenter.updateRule(raced.id(), "5050", "80", "Gone");
        assertTrue(raceView.formCleared);
        assertNotNull(raceView.lastErrorMessage);
    }

    @Test public void toggleOnPersistedRuleAppliesThroughManager() throws IOException {
        PortForwardingRule rule = manager.addRule(PortForwardingRule.defaultRule("ssh", Protocol.TCP, 2222, 22));
        FakeEngine engine = new FakeEngine();
        presenter = new PortForwardingPresenter("inst-test", manager, engine);
        presenter.attachView(view);

        presenter.toggleRule(rule.id(), true);
        assertTrue(engine.isApplied(rule));
        assertTrue(view.displayedRules.get(0).isEnabled());
    }

    private record ForwardingSession(FakeEngine engine, boolean capable) implements RuntimeOwnedSession {
        @Override public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            SessionCapabilities descriptor = SessionCapabilities.builder(
                            FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL)
                    .portForwarding(capable)
                    .build();
            return FullLinuxContract.CapabilityMatrix.from(descriptor,
                    EnumSet.of(FullLinuxContract.Capability.PORT_FORWARDING));
        }

        @Override public PortForwardingPresenter.LiveEngine portForwardingEngine() { return engine; }
        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return true; }
        @Override public int waitFor() { return 0; }
        @Override public OutputStream terminalInput() { return OutputStream.nullOutputStream(); }
        @Override public InputStream terminalOutput() { return InputStream.nullInputStream(); }
        @Override public OutputStream controlInput() { return OutputStream.nullOutputStream(); }
        @Override public InputStream controlOutput() { return InputStream.nullInputStream(); }
        @Override public void close() {}
    }

    private static final class FakeEngine implements PortForwardingPresenter.LiveEngine {
        final Map<Integer, PortForwardingRule> applied = new HashMap<>();
        boolean failAdd;
        int busyPort;
        int reservedGuestPort;
        int addCalls;
        int removeCalls;
        InMemoryStore requireEmptyStore;
        @Override public boolean supportsRule(PortForwardingRule rule) {
            return io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine.Live
                    .supportsIpv4LoopbackTcp(rule)
                    && rule.guestPort() != reservedGuestPort;
        }
        @Override public void addRule(PortForwardingRule rule) throws IOException {
            addCalls++;
            if (requireEmptyStore != null) assertTrue(requireEmptyStore.loadRules("inst-test").isEmpty());
            if (failAdd || rule.hostPort() == busyPort) throw new IOException("synthetic apply failure");
            applied.put(rule.hostPort(), rule);
        }
        @Override public void removeRule(PortForwardingRule rule) {
            removeCalls++;
            applied.remove(rule.hostPort());
        }
        @Override public boolean isRunning() { return true; }
        @Override public boolean isApplied(PortForwardingRule rule) {
            PortForwardingRule active = applied.get(rule.hostPort());
            return active != null && active.hasSameListener(rule) && active.guestPort() == rule.guestPort();
        }
        @Override public void refresh() { }
    }
}
