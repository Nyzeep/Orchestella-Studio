package io.github.nyzeep.orchestella;

import java.util.Objects;

/**
 * Battery execution policy for running instances (ADR-0011 / US-25).
 * Controls wakelock persistence and background execution aggressiveness.
 */
public enum BatteryPolicy {
    BALANCED("均衡", "后台运行时适当释放唤醒资源，兼顾算力与电池续航"),
    PERFORMANCE("性能", "保持最高前台优先级与持续唤醒锁，保障编译与长跑任务不中断");

    private final String displayName;
    private final String description;

    BatteryPolicy(String displayName, String description) {
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.description = Objects.requireNonNull(description, "description");
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }
}
