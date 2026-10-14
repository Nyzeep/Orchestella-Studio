package io.github.nyzeep.orchestella;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A display-only view of the guest's explicit provisioning progress. */
public final class GuestRuntimeStatus {
    private static final Pattern MARKER = Pattern.compile(
            "ORCHESTELLA_GUEST_PROVISION status=([a-z-]+) stage=([a-z0-9-]+) reason=([a-z0-9-]+) attempt=([0-9]+)");
    private static final Set<String> STATES = Set.of(
            "pending", "running", "failed", "awaiting-init", "ready", "action-required", "busy");

    public final String status;
    public final String stage;
    public final String reason;

    private GuestRuntimeStatus(String status, String stage, String reason) {
        this.status = status;
        this.stage = stage;
        this.reason = reason;
    }

    public static GuestRuntimeStatus latest(String transcript) {
        if (transcript == null || transcript.isEmpty()) return null;
        int boot = transcript.lastIndexOf("Run /init as init process");
        Matcher matcher = MARKER.matcher(boot >= 0 ? transcript.substring(boot) : transcript);
        GuestRuntimeStatus latest = null;
        while (matcher.find()) {
            if (STATES.contains(matcher.group(1))) {
                latest = new GuestRuntimeStatus(matcher.group(1), matcher.group(2), matcher.group(3));
            }
        }
        return latest;
    }

    public String description() {
        switch (status) {
            case "ready": return "Linux 基础环境已验证：原生 init、基础包、DBus 与 SSH 服务就绪。";
            case "failed": return "Linux 初始化失败 · " + stage + "（" + reason
                    + "）。打开终端查看日志并执行 orchestella-guest-provision --retry。";
            case "action-required": return "基础环境需要处理 · " + stage + "（" + reason
                    + "）。请在终端查看具体说明。";
            case "awaiting-init": return "基础包已安装，正在交接原生 init 并验证服务。";
            case "running": return "正在配置 Linux · " + stage + "。首次安装可能需要数分钟。";
            case "busy": return "Linux 初始化任务正在运行，请等待当前操作完成。";
            default: return "Linux 基础环境待初始化，打开终端查看进度。";
        }
    }
}
