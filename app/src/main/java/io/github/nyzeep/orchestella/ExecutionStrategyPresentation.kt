package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities
import io.github.nyzeep.orchestella.runtime.contract.StorageContract

/**
 * Pure-JVM presentation helpers shared across Wizard, Instance List, and Instance Detail
 * so strategy labels, capability gaps, and root-disk descriptions follow one CapabilityMatrix source.
 */
object ExecutionStrategyPresentation {

    fun baselineMatrixFor(
        strategy: FullLinuxContract.ExecutionStrategy?,
    ): FullLinuxContract.CapabilityMatrix = when (strategy) {
        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL ->
            FullLinuxContract.CapabilityMatrix.userModeKernelBaseline()
        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION ->
            FullLinuxContract.CapabilityMatrix.hardwareVirtualizationBaseline()
        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, null ->
            FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline()
    }

    fun explicitStrategyBadge(
        strategyDecl: ExecutionPolicyContract.StrategyDeclaration,
    ): String? = when {
        strategyDecl.isKnown -> baselineMatrixFor(strategyDecl.strategy()).performanceBadge()
        strategyDecl.isUnknown -> "未识别策略（已阻断启动）"
        else -> null
    }

    fun strategySupportsGraphics(strategy: FullLinuxContract.ExecutionStrategy?): Boolean {
        val effective = strategy ?: FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION
        val candidate = FullLinuxContract.CapabilityMatrix.from(
            SessionCapabilities.builder(effective)
                .terminal(true)
                .bridge(true)
                .display(SessionCapabilities.DisplayTransport.RFB)
                .build(),
        )
        return candidate.supportsRfbDisplay()
    }

    fun strategyBindingLabel(
        identity: ExecutionPolicyContract.InstanceExecutionIdentity,
        matrix: FullLinuxContract.CapabilityMatrix,
    ): String = when {
        identity.strategy().isUnknown -> "未识别执行策略 · 已拒绝启动以保护原盘"
        identity.strategy().isKnown -> "${matrix.performanceBadge()}（显式指定）"
        else -> "${matrix.performanceBadge()}（默认路由）"
    }

    fun rootVolumeFormatLabel(
        formatDecl: ExecutionPolicyContract.VolumeFormatDeclaration,
    ): String = when {
        formatDecl.isUnknown -> "未识别根盘格式（已拒绝启动）"
        formatDecl.isKnown && formatDecl.format() == StorageContract.RootVolumeFormat.RAW_SPARSE ->
            "稀疏原始盘（显式声明）"
        else -> "稀疏原始盘（历史默认）"
    }

    fun unverifiedCapabilities(matrix: FullLinuxContract.CapabilityMatrix): List<String> = buildList {
        val cliOnlyMinimal = !matrix.supportsRfbDisplay() &&
            !matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND) &&
            !matrix.supportsSharedDirectory() &&
            !matrix.supportsGpuAcceleration() &&
            !matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION)
        if (cliOnlyMinimal) {
            add("systemd")
            add("容器（Podman/Docker）")
        }
        if (!matrix.supportsGpuAcceleration()) {
            add("GPU 加速")
        }
        if (!matrix.supportsRfbDisplay() &&
            !matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND)
        ) {
            add("图形桌面")
        }
        if (!matrix.supportsSharedDirectory()) {
            add("共享目录（9p）")
        }
    }

    fun fallbackReasonLabel(reason: ExecutionPolicyContract.FallbackReason): String = when (reason) {
        ExecutionPolicyContract.FallbackReason.NONE -> ""
        ExecutionPolicyContract.FallbackReason.HARDWARE_PERMISSION_DENIED -> "硬件虚拟化权限未授予"
        ExecutionPolicyContract.FallbackReason.HARDWARE_UNSUPPORTED -> "设备硬件暂不支持 AVF"
        ExecutionPolicyContract.FallbackReason.HARDWARE_CONFIG_MISMATCH -> "硬件虚拟化配置不兼容"
        ExecutionPolicyContract.FallbackReason.HARDWARE_UNAVAILABLE -> "硬件虚拟化通道暂不可用"
        ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_INCOMPLETE -> "独立 UML 预检尚未完成"
        ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_TIMEOUT -> "独立 UML 预检超时"
        ExecutionPolicyContract.FallbackReason.UML_LAUNCH_FAILED_ATOMIC_FALLBACK ->
            "独立 UML 启动失败并完成原子回退"
    }
}
