package io.github.nyzeep.orchestella.runtime.engineqemu;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import java.util.Objects;

/**
 * Encapsulates QEMU-specific hostfwd option and monitor command formatting.
 */
public final class QemuHostfwdFormatter {
    private QemuHostfwdFormatter() {}

    /**
     * Formats the hostfwd pattern: [protocol]:[bindAddress]:[hostPort]-:[guestPort].
     * Example: "tcp:127.0.0.1:8080-:80"
     */
    public static String formatHostfwdPattern(PortForwardingRule rule) {
        Objects.requireNonNull(rule, "rule");
        return rule.protocol().wireName()
                + ":" + rule.bindAddress()
                + ":" + rule.hostPort()
                + "-:" + rule.guestPort();
    }

    /**
     * Formats the HMP command to add a host forwarding rule.
     * Example: "hostfwd_add net0 tcp:127.0.0.1:8080-:80"
     */
    public static String formatHostfwdAddHmp(String netdevId, PortForwardingRule rule) {
        Objects.requireNonNull(netdevId, "netdevId");
        return "hostfwd_add " + netdevId + " " + formatHostfwdPattern(rule);
    }

    /**
     * Formats the HMP command to remove a host forwarding rule.
     * In QEMU, hostfwd_remove requires only [netdevId] [protocol]:[bindAddress]:[hostPort].
     * Example: "hostfwd_remove net0 tcp:127.0.0.1:8080"
     */
    public static String formatHostfwdRemoveHmp(String netdevId, PortForwardingRule rule) {
        Objects.requireNonNull(netdevId, "netdevId");
        Objects.requireNonNull(rule, "rule");
        return "hostfwd_remove " + netdevId + " "
                + rule.protocol().wireName()
                + ":" + rule.bindAddress()
                + ":" + rule.hostPort();
    }

    /**
     * Formats the QMP human-monitor-command JSON to add a host forwarding rule.
     */
    public static String formatQmpHostfwdAdd(String netdevId, PortForwardingRule rule) {
        String hmp = formatHostfwdAddHmp(netdevId, rule);
        return "{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\""
                + escapeJson(hmp) + "\"}}";
    }

    /**
     * Formats the QMP human-monitor-command JSON to remove a host forwarding rule.
     */
    public static String formatQmpHostfwdRemove(String netdevId, PortForwardingRule rule) {
        String hmp = formatHostfwdRemoveHmp(netdevId, rule);
        return "{\"execute\":\"human-monitor-command\",\"arguments\":{\"command-line\":\""
                + escapeJson(hmp) + "\"}}";
    }

    /**
     * Formats the QMP native block_resize command JSON.
     * Example: {"execute":"block_resize","arguments":{"device":"root","size":4294967296}}
     */
    public static String formatQmpBlockResize(String deviceOrNode, long newSizeBytes) {
        Objects.requireNonNull(deviceOrNode, "deviceOrNode");
        if (newSizeBytes <= 0) {
            throw new IllegalArgumentException("newSizeBytes must be positive: " + newSizeBytes);
        }
        return "{\"execute\":\"block_resize\",\"arguments\":{\"device\":\""
                + escapeJson(deviceOrNode) + "\",\"size\":" + newSizeBytes + "}}";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
