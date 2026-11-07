package io.github.nyzeep.orchestella.startup

import io.github.nyzeep.orchestella.ErrorCopy

/**
 * 启动失败的异常类型 → 短文案映射（2026-11-07 零设备优化轮 Lane C 落地）。
 *
 * <p><b>2026-11-08 单源化：映射表并入 [ErrorCopy]</b>——异常族判定（含
 * InstanceLifecycle.InvalidTransitionException / AvfEngineException 按 reason
 * 分派 / LaneStartException / OutOfMemoryError 等专用族）统一收敛到
 * {@code ErrorCopy.resolve}，人类可读文案统一落在 strings.xml 的 {@code error_*}
 * 键（新族固定码 E-LIFECYCLE / E-AVF-* / E-LANE / E-OOM，契约只增不改名）。
 *
 * <p>本对象保留为兼容壳：固定码契约不变、签名不变，实现转发 ErrorCopy 单源表。
 * 纯 Kotlin、无 Android 依赖，故转发结果是族固定码（E-XXX，即 detailMessage
 * 副文本里的那串错误码）；需要「主文案（错误码 E-XXX）」完整展示文案时直接用
 * {@code ErrorCopy.detailMessage(context, failure)}——消费方 MainActivity 已于
 * 2026-11-08 迁移至该入口。
 */
object LaunchFailureCopy {

    /** 委托 ErrorCopy 单源映射表；返回异常所属族的固定码（E-XXX）。永不为空、永不暴露堆栈术语。 */
    fun describe(failure: Throwable): String = ErrorCopy.resolve(failure).code
}
