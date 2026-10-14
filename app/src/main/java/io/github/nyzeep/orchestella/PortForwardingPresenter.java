package io.github.nyzeep.orchestella;

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

/** Shows persisted configuration separately from confirmed, live gateway listeners. */
public final class PortForwardingPresenter {
    public interface View {
        void displayRules(List<RuleItemModel> rules);
        void showValidationError(String message);
        void clearInputForm();
        void showSuccess(String message);
        void showError(String message);
    }
    public interface LiveEngine extends PortForwardingEngine {
        void refresh() throws IOException;
        boolean isApplied(PortForwardingRule rule);
    }
    public static final class RuleItemModel {
        private final String id;
        private final Protocol protocol;
        private final int hostPort;
        private final int guestPort;
        private final String description;
        private final boolean enabled;
        public RuleItemModel(String id, Protocol protocol, int hostPort, int guestPort,
                String description, boolean enabled) {
            this.id = id; this.protocol = protocol; this.hostPort = hostPort; this.guestPort = guestPort;
            this.description = description != null ? description : ""; this.enabled = enabled;
        }
        public String getId() { return id; }
        public Protocol getProtocol() { return protocol; }
        public int getHostPort() { return hostPort; }
        public int getGuestPort() { return guestPort; }
        public String getDescription() { return description; }
        public boolean isEnabled() { return enabled; }
    }

    private final String instanceId;
    private final PortForwardingManager manager;
    private final LiveEngine engine;
    private volatile View view;
    private final Map<String, PortForwardingRule> disabled = new LinkedHashMap<>();
    public PortForwardingPresenter(String instanceId, PortForwardingManager manager) {
        this(instanceId, manager, null);
    }
    public PortForwardingPresenter(String instanceId, PortForwardingManager manager, LiveEngine engine) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.engine = engine;
    }
    public String getInstanceId() { return instanceId; }
    public boolean hasLiveEngine() { return engine != null && engine.isRunning(); }
    public void attachView(View view) { this.view = view; }
    public void detachView() { view = null; }

    public synchronized void loadRules() {
        boolean live = hasLiveEngine();
        if (live) {
            try { engine.refresh(); }
            catch (IOException failure) { live = false; if (view != null) view.showError("无法确认网关映射；未标记为已启用"); }
        }
        List<RuleItemModel> items = new ArrayList<>();
        for (PortForwardingRule rule : manager.listRules()) {
            boolean applied = live && engine.isApplied(rule);
            boolean supported = rule.protocol() == Protocol.TCP &&
                    PortForwardingRule.LOOPBACK_ADDRESS.equals(rule.bindAddress());
            String status = !supported ? "当前不支持此规则；仅支持 TCP 127.0.0.1 映射"
                    : applied ? "已应用到 QEMU 网关（服务需在 Linux 中运行）"
                    : "已保存配置，当前未确认生效";
            items.add(item(rule, status, applied));
        }
        for (PortForwardingRule rule : disabled.values()) items.add(item(rule, "已关闭并从启动配置移除", false));
        if (view != null) view.displayRules(items);
    }
    private RuleItemModel item(PortForwardingRule rule, String status, boolean applied) {
        String detail = rule.description().isEmpty() ? status : rule.description() + " · " + status;
        return new RuleItemModel(rule.id(), rule.protocol(), rule.hostPort(), rule.guestPort(), detail, applied);
    }

    public synchronized void addRule(String hostText, String guestText, Protocol protocol, String description) {
        if (protocol != null && protocol != Protocol.TCP) {
            if (view != null) view.showValidationError("当前网关只支持显式 TCP 本机映射");
            return;
        }
        PortForwardingRuleValidator.Result validation = PortForwardingRuleValidator.validateRule(
                hostText, guestText, protocol, manager.listRules());
        if (!validation.isValid()) {
            if (view != null) view.showValidationError(validation.getErrorMessage());
            return;
        }
        PortForwardingRule rule = PortForwardingRule.builder().hostPort(Integer.parseInt(hostText.trim()))
                .guestPort(Integer.parseInt(guestText.trim())).protocol(Protocol.TCP)
                .bindAddress(PortForwardingRule.LOOPBACK_ADDRESS).description(description).build();
        boolean applied = false;
        try {
            if (hasLiveEngine()) { engine.addRule(rule); applied = true; }
            manager.addRule(rule); // No engine attached: persist only after live application succeeded.
            if (view != null) {
                view.clearInputForm();
                view.showSuccess(applied ? "映射已应用到 QEMU 网关"
                        : "配置已保存，当前未生效；QEMU 启动时加载，AVF 尚未接入端口转发");
            }
        } catch (Exception failure) {
            if (applied) {
                try { engine.removeRule(rule); }
                catch (IOException rollback) { if (view != null) view.showError("保存失败且映射回滚未确认，请关机后检查网关"); }
            }
            if (view != null) view.showError("映射未完成，未声明为启用，请检查端口占用或网关状态");
        }
        loadRules();
    }

    public synchronized void toggleRule(String ruleId, boolean enabled) {
        PortForwardingRule rule = manager.getRule(ruleId).orElse(disabled.get(ruleId));
        if (rule == null) return;
        if (!hasLiveEngine() || rule.protocol() != Protocol.TCP ||
                !PortForwardingRule.LOOPBACK_ADDRESS.equals(rule.bindAddress())) {
            if (view != null) view.showError("当前没有可用的 QEMU TCP 网关；保存配置不等于映射已生效");
            loadRules();
            return;
        }
        boolean changed = false;
        try {
            if (enabled) {
                engine.addRule(rule); changed = true;
                if (!manager.getRule(ruleId).isPresent()) manager.addRule(rule);
                disabled.remove(ruleId);
            } else {
                engine.removeRule(rule); changed = true;
                manager.removeRule(ruleId);
                disabled.put(ruleId, rule);
            }
        } catch (Exception failure) {
            if (changed) {
                try { if (enabled) engine.removeRule(rule); else engine.addRule(rule); }
                catch (IOException rollback) { if (view != null) view.showError("映射回滚未确认，请关机后检查网关"); }
            }
            if (view != null) view.showError("映射切换失败，未保存新的启用状态");
        }
        loadRules();
    }

    public synchronized void deleteRule(String ruleId) {
        PortForwardingRule rule = manager.getRule(ruleId).orElse(null);
        boolean removedLive = false;
        try {
            if (rule != null && hasLiveEngine() && engine.isApplied(rule)) {
                engine.removeRule(rule);
                removedLive = true;
            }
            boolean removed = manager.removeRule(ruleId);
            removed |= disabled.remove(ruleId) != null;
            if (view != null) view.showSuccess(removed ? "规则已删除" : "规则不存在");
        } catch (Exception failure) {
            if (removedLive) {
                try { engine.addRule(rule); }
                catch (IOException rollback) { if (view != null) view.showError("删除回滚未确认，请关机后检查网关"); }
            }
            if (view != null) view.showError("规则删除失败，原配置保留");
        }
        loadRules();
    }
}
