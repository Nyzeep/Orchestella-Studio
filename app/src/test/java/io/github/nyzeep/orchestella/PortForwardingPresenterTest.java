package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingStore;
import java.io.IOException;
import java.util.ArrayList;
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

        @Override
        public List<PortForwardingRule> loadRules(String instanceId) {
            return new ArrayList<>(data.getOrDefault(instanceId, new ArrayList<>()));
        }

        @Override
        public void saveRules(String instanceId, List<PortForwardingRule> rules) {
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
        assertEquals("Web service", view.displayedRules.get(0).getDescription());
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
        presenter.loadRules();

        assertTrue(view.displayedRules.get(0).isEnabled());

        // Toggle to disabled
        presenter.toggleRule(rule.id(), false);
        assertFalse(view.displayedRules.get(0).isEnabled());

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
}
