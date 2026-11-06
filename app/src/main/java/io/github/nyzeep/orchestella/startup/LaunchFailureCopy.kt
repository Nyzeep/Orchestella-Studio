package io.github.nyzeep.orchestella.startup

import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import java.io.IOException

/**
 * 启动失败的异常类型 → 人类可读短文案映射（2026-11-07 零设备优化轮 Lane C）。
 *
 * <p>现状问题：启动失败时 UI 只显示「启动失败：IOException」这类异常类名，用户无法
 * 判断发生了什么。本映射按异常类型给出短文案；<b>固定异常码不删除</b>——调用方把
 * 异常类名括注在状态行副文本，并继续写日志（{@code OrchestellaLaunch}）。
 *
 * <p>本轮免资源化：res 资源目录（含 strings.xml）归 B/D lane，此处文案以代码常量落位。
 * TODO(文案收口轮)：把以下文案统一迁入 strings.xml，由启动路径改读资源。
 *
 * <p>纯 Kotlin、无 Android 依赖，JVM 单测锁定映射表。
 */
object LaunchFailureCopy {

    /** 按异常类型映射短文案；未知类型回落到通用指引。永不为空、永不暴露堆栈术语。 */
    fun describe(failure: Throwable): String = when (failure) {
        is InstanceLifecycle.InvalidTransitionException ->
            "实例状态不允许本次启动，请刷新实例列表后重试"
        is AvfEngineException -> when (failure.reason()) {
            AvfEngineException.PERMISSION_DENIED -> "AVF 授权已失效，请重新完成授权向导"
            AvfEngineException.HARDWARE_UNSUPPORTED -> "当前设备硬件不支持 AVF 硬件虚拟化"
            AvfEngineException.CONFIG_MISMATCH -> "AVF 虚拟机配置已变化，请删除旧机后重试"
            else -> "AVF 硬件虚拟机启动失败，可稍后重试"
        }
        is LaneStartException -> "加速车道启动失败，本次启动已中止"
        is IOException -> "实例文件读写失败，请检查存储空间后重试"
        is SecurityException -> "缺少必要的系统权限，请检查系统授权设置"
        is OutOfMemoryError -> "系统内存不足，请关闭部分应用后重试"
        is IllegalStateException -> "启动流程状态异常，请重试；持续失败请查看运行日志"
        else -> "发生未知错误，请重试；持续失败请查看运行日志"
    }
}
