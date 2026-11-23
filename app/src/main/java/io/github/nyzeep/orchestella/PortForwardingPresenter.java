package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.Capability;
import io.github.nyzeep.orchestella.runtime.contract.PortConflictException;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Shows persisted configuration separately from confirmed session listeners. */
public final class PortForwardingPresenter {
    public interface View {
        void displayRules(List<RuleItemModel> rules);
        void showValidationError(String message);
        void clearInputForm();
        void showSuccess(String message);
        void showError(String message);
        default void showLiveEngine(boolean available) {}
    }

    public interface LiveEngine extends PortForwardingEngine.Live {}

    public static final class RuleItemModel {
        private final String id;
        private final Protocol protocol;
        private final int hostPort;
        private final int guestPort;
        private final String description;
        private final String savedDescription;
        private final boolean enabled;
        private final boolean canToggle;
        private final boolean canEdit;

        private RuleItemModel(PortForwardingRule rule, String status, boolean enabled,
                boolean canToggle, boolean canEdit) {
            this.id = rule.id();
            this.protocol = rule.protocol();
            this.hostPort = rule.hostPort();
            this.guestPort = rule.guestPort();
            this.savedDescription = rule.description();
            this.description = savedDescription.isEmpty() ? status : savedDescription + " · " + status;
            this.enabled = enabled;
            this.canToggle = canToggle;
            this.canEdit = canEdit;
        }

        public String getId() { return id; }
        public Protocol getProtocol() { return protocol; }
        public int getHostPort() { return hostPort; }
        public int getGuestPort() { return guestPort; }
        public String getDescription() { return description; }
        public String getSavedDescription() { return savedDescription; }
        public boolean isEnabled() { return enabled; }
        public boolean canToggle() { return canToggle; }
        public boolean canEdit() { return canEdit; }
    }

    private final String instanceId;
    private final PortForwardingManager manager;
    private final Supplier<PortForwardingEngine.Live> engineProvider;
    private final Map<String, PortForwardingRule> disabled = new LinkedHashMap<>();
    private volatile View view;

    public PortForwardingPresenter(String instanceId, PortForwardingManager manager) {
        this(instanceId, manager, () -> null);
    }

    public PortForwardingPresenter(String instanceId, PortForwardingManager manager, PortForwardingEngine.Live engine) {
        this(instanceId, manager, () -> engine);
    }

    private PortForwardingPresenter(String instanceId, PortForwardingManager manager,
            Supplier<PortForwardingEngine.Live> engineProvider) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.engineProvider = engineProvider;
    }

    public static PortForwardingPresenter forActiveSession(
            String instanceId, PortForwardingManager manager) {
        return new PortForwardingPresenter(instanceId, manager, () ->
                forwardingFrom(ExecutionSessionRegistry.get(instanceId)));
    }

    private static PortForwardingEngine.Live forwardingFrom(ExecutionSession session) {
        if (session == null || !session.capabilityMatrix().supports(Capability.PORT_FORWARDING)) {
            return null;
        }
        return session.portForwardingEngine();
    }

    private PortForwardingEngine.Live liveEngine() {
        PortForwardingEngine.Live engine = engineProvider.get();
        return engine != null && engine.isRunning() ? engine : null;
    }

    public String getInstanceId() { return instanceId; }
    public boolean hasLiveEngine() { return liveEngine() != null; }
    public void attachView(View view) { this.view = view; }
    public void detachView() { view = null; }

    public synchronized void loadRules() {
        PortForwardingEngine.Live engine = liveEngine();
        View attached = view;
        if (attached == null) return;
        boolean live = engine != null;
        if (live) {
            try {
                engine.refresh();
            } catch (IOException failure) {
                live = false;
                attached.showError("无法确认会话转发状态");
            }
        }
        List<RuleItemModel> items = new ArrayList<>();
        for (PortForwardingRule rule : manager.listRules()) {
            boolean supported = supportsRule(rule, engine);
            boolean applied = live && supported && engine.isApplied(rule);
            boolean reservedGuestPort = !supported
                    && PortForwardingEngine.Live.supportsIpv4LoopbackTcp(rule);
            String status;
            if (PortForwardingRule.isReservedHostPort(rule.hostPort())) {
                status = "此宿主端口由编译通道保留";
            } else if (reservedGuestPort) {
                status = "当前运行时保留的 Guest 端口";
            } else if (!supported) {
                status = "仅支持 TCP 127.0.0.1";
            } else {
                status = applied ? "已应用到当前会话" : "已保存配置，当前未确认生效";
            }
            boolean canEdit = supported || reservedGuestPort;
            items.add(new RuleItemModel(rule, status, applied, live && supported, canEdit));
        }
        for (PortForwardingRule rule : disabled.values()) {
            items.add(new RuleItemModel(rule, "已关闭并从启动配置移除", false,
                    live && supportsRule(rule, engine), false));
        }
        attached.showLiveEngine(live);
        attached.displayRules(items);
    }

    private static boolean supportsRule(PortForwardingRule rule, PortForwardingEngine.Live engine) {
        return !PortForwardingRule.isReservedHostPort(rule.hostPort())
                && (engine == null ? PortForwardingEngine.Live.supportsIpv4LoopbackTcp(rule)
                        : engine.supportsRule(rule));
    }

    private boolean rejectReservedGuestPort(PortForwardingRule rule, PortForwardingEngine.Live engine) {
        if (engine != null && !engine.supportsRule(rule)
                && PortForwardingEngine.Live.supportsIpv4LoopbackTcp(rule)) {
            View attached = view;
            if (attached != null) {
                attached.showValidationError(
                        "Guest port " + rule.guestPort() + " is reserved by the active runtime.");
            }
            return true;
        }
        return false;
    }

    public synchronized void addRule(String hostText, String guestText, Protocol protocol, String description) {
        if (protocol != null && protocol != Protocol.TCP) {
            View attached = view;
            if (attached != null) attached.showValidationError("当前会话只支持本机 TCP 转发");
            return;
        }
        if (!validateInput(hostText, guestText, null)) return;
        PortForwardingRule rule = PortForwardingRule.builder()
                .hostPort(Integer.parseInt(hostText.trim()))
                .guestPort(Integer.parseInt(guestText.trim()))
                .description(description)
                .build();
        PortForwardingEngine.Live engine = liveEngine();
        if (rejectReservedGuestPort(rule, engine)) return;
        try {
            manager.addRule(rule, engine);
            View attached = view;
            if (attached != null) {
                attached.clearInputForm();
                attached.showSuccess(engine != null && engine.isApplied(rule) ? "规则已启用"
                        : "规则已保存，将在支持端口转发的会话中应用");
            }
        } catch (IOException | PortConflictException failure) {
            showChangeFailure(failure, "无法启用规则，请检查端口占用或会话状态");
        }
        loadRules();
    }

    public synchronized void updateRule(
            String ruleId, String hostText, String guestText, String description) {
        PortForwardingRule previous = manager.getRule(ruleId).orElse(null);
        if (previous == null) {
            View attached = view;
            if (attached != null) {
                attached.clearInputForm();
                attached.showError("规则已变化，请重新打开");
            }
            loadRules();
            return;
        }
        PortForwardingEngine.Live engine = liveEngine();
        boolean editable = supportsRule(previous, engine)
                || PortForwardingEngine.Live.supportsIpv4LoopbackTcp(previous);
        if (!editable) {
            View attached = view;
            if (attached != null) attached.showValidationError("此规则当前无法编辑");
            return;
        }
        if (!validateInput(hostText, guestText, ruleId)) return;
        PortForwardingRule replacement = PortForwardingRule.builder(previous)
                .hostPort(Integer.parseInt(hostText.trim()))
                .guestPort(Integer.parseInt(guestText.trim()))
                .description(description)
                .build();
        if (rejectReservedGuestPort(replacement, engine)) return;
        try {
            boolean updated = manager.updateRule(replacement, engine);
            View attached = view;
            if (attached != null) {
                if (updated) {
                    attached.clearInputForm();
                    attached.showSuccess("规则已更新");
                } else {
                    attached.clearInputForm();
                    attached.showError("规则已变化，请重新打开");
                }
            }
        } catch (PortForwardingEngine.ListenerRetargetUnsupported unsupported) {
            View attached = view;
            if (attached != null) attached.showValidationError("更换宿主端口后才能更新当前监听的 Guest 端口");
            return;
        } catch (IOException | PortConflictException failure) {
            showChangeFailure(failure, "无法更新规则，原配置已保留");
        }
        loadRules();
    }

    private boolean validateInput(String host, String guest, String editingRuleId) {
        List<PortForwardingRule> others = new ArrayList<>(manager.listRules());
        if (editingRuleId != null) others.removeIf(rule -> rule.id().equals(editingRuleId));
        PortForwardingRuleValidator.Result validation = PortForwardingRuleValidator.validateRule(
                host, guest, Protocol.TCP, others);
        View attached = view;
        if (!validation.isValid() && attached != null) {
            attached.showValidationError(validation.getErrorMessage());
        }
        return validation.isValid();
    }

    public synchronized void toggleRule(String ruleId, boolean enabled) {
        PortForwardingRule rule = manager.getRule(ruleId).orElse(disabled.get(ruleId));
        if (rule == null) return;
        PortForwardingEngine.Live engine = liveEngine();
        if (engine == null || !supportsRule(rule, engine)) {
            View attached = view;
            if (attached != null) attached.showError("当前会话无法应用此规则");
            loadRules();
            return;
        }
        try {
            if (enabled) {
                if (manager.getRule(ruleId).isPresent()) {
                    manager.applyRule(ruleId, engine);
                } else {
                    manager.addRule(rule, engine);
                }
                disabled.remove(ruleId);
            } else {
                manager.removeRule(ruleId, engine);
                disabled.put(ruleId, rule);
            }
        } catch (IOException | PortConflictException failure) {
            showChangeFailure(failure, "无法切换规则，请检查端口占用或会话状态");
        }
        loadRules();
    }

    public synchronized void deleteRule(String ruleId) {
        try {
            boolean removed = manager.removeRule(ruleId, liveEngine());
            removed |= disabled.remove(ruleId) != null;
            View attached = view;
            if (attached != null) attached.showSuccess(removed ? "规则已删除" : "规则不存在");
        } catch (IOException failure) {
            showChangeFailure(failure, "规则删除失败，原配置已保留");
        }
        loadRules();
    }

    private void showChangeFailure(Exception failure, String message) {
        View attached = view;
        if (attached != null) {
            attached.showError(failure.getSuppressed().length == 0 ? message
                    : "规则回滚未确认，请检查会话状态");
        }
    }
}
