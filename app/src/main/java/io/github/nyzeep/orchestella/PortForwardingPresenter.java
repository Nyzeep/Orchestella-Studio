package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Presenter governing instance port forwarding visualization, validation, toggling, and deletion.
 *
 * <p>Conforms to Phase 4.8 Task 3 and deep-module design:
 * <ul>
 *   <li>Decoupled from Android View hierarchy: 100% testable in pure JVM;</li>
 *   <li>Maintains local active toggle states and delegates persistence to {@link PortForwardingManager};</li>
 *   <li>Coordinates real-time validation via {@link PortForwardingRuleValidator}.</li>
 * </ul>
 */
public final class PortForwardingPresenter {

    public interface View {
        void displayRules(List<RuleItemModel> rules);
        void showValidationError(String message);
        void clearInputForm();
        void showSuccess(String message);
        void showError(String message);
    }

    public static final class RuleItemModel {
        private final String id;
        private final Protocol protocol;
        private final int hostPort;
        private final int guestPort;
        private final String description;
        private final boolean enabled;

        public RuleItemModel(
                String id,
                Protocol protocol,
                int hostPort,
                int guestPort,
                String description,
                boolean enabled) {
            this.id = id;
            this.protocol = protocol;
            this.hostPort = hostPort;
            this.guestPort = guestPort;
            this.description = description != null ? description : "";
            this.enabled = enabled;
        }

        public String getId() {
            return id;
        }

        public Protocol getProtocol() {
            return protocol;
        }

        public int getHostPort() {
            return hostPort;
        }

        public int getGuestPort() {
            return guestPort;
        }

        public String getDescription() {
            return description;
        }

        public boolean isEnabled() {
            return enabled;
        }
    }

    private final String instanceId;
    private final PortForwardingManager manager;
    private View view;
    private final Map<String, Boolean> enabledStates = new HashMap<>();

    public PortForwardingPresenter(String instanceId, PortForwardingManager manager) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void attachView(View view) {
        this.view = view;
    }

    public void detachView() {
        this.view = null;
    }

    public void loadRules() {
        List<PortForwardingRule> rules = manager.listRules();
        List<RuleItemModel> items = new ArrayList<>();
        for (PortForwardingRule rule : rules) {
            boolean enabled = enabledStates.getOrDefault(rule.id(), true);
            items.add(new RuleItemModel(
                    rule.id(),
                    rule.protocol(),
                    rule.hostPort(),
                    rule.guestPort(),
                    rule.description(),
                    enabled));
        }
        if (view != null) {
            view.displayRules(items);
        }
    }

    public void addRule(String hostPortText, String guestPortText, Protocol protocol, String description) {
        List<PortForwardingRule> currentRules = manager.listRules();
        PortForwardingRuleValidator.Result validation =
                PortForwardingRuleValidator.validateRule(hostPortText, guestPortText, protocol, currentRules);

        if (!validation.isValid()) {
            if (view != null) {
                view.showValidationError(validation.getErrorMessage());
            }
            return;
        }

        int hostPort = Integer.parseInt(hostPortText.trim());
        int guestPort = Integer.parseInt(guestPortText.trim());

        PortForwardingRule rule = PortForwardingRule.builder()
                .hostPort(hostPort)
                .guestPort(guestPort)
                .protocol(protocol != null ? protocol : Protocol.TCP)
                .description(description != null ? description : "")
                .build();

        try {
            manager.addRule(rule);
            enabledStates.put(rule.id(), true);
            if (view != null) {
                view.clearInputForm();
                view.showSuccess("Rule " + rule.hostPort() + " ➔ " + rule.guestPort() + " added successfully.");
            }
            loadRules();
        } catch (Exception e) {
            if (view != null) {
                view.showError("Failed to add rule: " + e.getMessage());
            }
        }
    }

    public void toggleRule(String ruleId, boolean enabled) {
        enabledStates.put(ruleId, enabled);
        loadRules();
    }

    public void deleteRule(String ruleId) {
        try {
            boolean removed = manager.removeRule(ruleId);
            enabledStates.remove(ruleId);
            if (view != null) {
                if (removed) {
                    view.showSuccess("Rule deleted.");
                } else {
                    view.showError("Rule not found.");
                }
            }
            loadRules();
        } catch (IOException e) {
            if (view != null) {
                view.showError("Failed to delete rule: " + e.getMessage());
            }
        }
    }
}
