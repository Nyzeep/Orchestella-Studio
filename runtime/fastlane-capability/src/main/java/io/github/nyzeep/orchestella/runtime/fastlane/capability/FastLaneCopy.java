package io.github.nyzeep.orchestella.runtime.fastlane.capability;

/** Product copy for the capability matrix; no Android resources and no policy. */
public final class FastLaneCopy {
    private FastLaneCopy() {}

    public static final String NOT_FULL_LINUX =
            "Fast Lane 不是 Full Linux：不提供 systemd、Docker、原生网络、GPU、音频/USB 或原生包安装。";

    public static String badge(FastLaneDecision decision) {
        return switch (decision.lane()) {
            case FAST -> "Fast Lane · protected 硬件 CPU 密封工作区";
            case FULL -> "Full Linux · QEMU";
            case UNSUPPORTED -> "不可用";
        };
    }

    public static String limits() {
        return NOT_FULL_LINUX;
    }

    public static String capabilitySummary(FastLaneCapabilities capabilities) {
        StringBuilder text = new StringBuilder();
        append(text, "终端", capabilities.terminal());
        append(text, "硬件 CPU", capabilities.hardwareCpu());
        append(text, "加密存储", capabilities.encryptedStorage());
        append(text, "密封工具", capabilities.sealedToolbox());
        append(text, "TCP 代理", capabilities.tcpProxy());
        append(text, "导出/导入", capabilities.exportImport());
        append(text, "distcc offload", capabilities.distccOffload());
        append(text, "原生网络", capabilities.nativeNetwork());
        append(text, "原生包安装", capabilities.nativePackageInstall());
        append(text, "Docker", capabilities.docker());
        append(text, "systemd", capabilities.systemd());
        append(text, "GPU", capabilities.gpu());
        return text.toString();
    }

    private static void append(StringBuilder text, String name, boolean supported) {
        if (text.length() > 0) {
            text.append(" · ");
        }
        text.append(name).append(supported ? "=yes" : "=no");
    }

    public static String explain(Lane lane, FastLaneReason reason) {
        if (lane == Lane.FAST) {
            return "选择 Fast Lane：protected 硬件 CPU 提供终端与编译工作区。" + NOT_FULL_LINUX;
        }
        String why = switch (reason) {
            case REQUIRES_FULL_LINUX -> "工作负载需要 systemd/Docker/原生网络/GPU/包安装";
            case NO_AVF -> "设备没有可用的 AVF 虚拟化框架";
            case HIDDEN_API_BLOCKED -> "平台隐藏 API 被拦，无法访问 protected VM 接口";
            case PERMISSION_DENIED -> "缺少 MANAGE_VIRTUAL_MACHINE 授权";
            case PROTECTED_UNAVAILABLE -> "设备未声明 protected VM 能力";
            case STORAGE_UNAVAILABLE -> "加密存储不可用";
            case PROXY_UNAVAILABLE -> "TCP 代理不可用";
            case TOOLBOX_UNAVAILABLE -> "密封工具箱不可用";
            case DISTCC_UNAVAILABLE -> "distcc offload 不可用";
            case UNKNOWN_DEVICE -> "设备能力未知，按保守策略保留 Full Lane";
            case UNKNOWN_WORKLOAD -> "工作负载未知，按保守策略保留 Full Lane";
            case FAST_LANE_AVAILABLE -> "Fast Lane 可用";
        };
        if (lane == Lane.UNSUPPORTED) {
            return "无可用车道：" + why + "。" + NOT_FULL_LINUX;
        }
        return "保留 Full Lane（QEMU）：" + why + "。" + NOT_FULL_LINUX;
    }
}
