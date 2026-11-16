package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.StopOutcome
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult
import io.github.nyzeep.orchestella.runtime.fastlane.ProtectedFailureCode

/**
 * OPT-05：App 层各停止结果词汇到共享 [StopOutcome] 的纯函数映射适配器。
 *
 * 这些函数只读取既有生产类型（`RuntimeProcessSupervisor`、`InstanceLaunchSequence`、
 * `ProtectedWarmPool`、`WorkerPool`、`ProductionStageRecorder`、`LaunchTimeline`、
 * Fast Lane 词汇），是纯粹的词汇翻译；**不改变任何调用点的返回值**，生产调用点
 * 一行未动，仅为后续 ExitArbiter 统一停止语义铺路。
 *
 * 映射纪律（与 `StopOutcome` 的契约一致）：
 * - 任何 UNCONFIRMED 证据（supervisor 未确认、观察者失败、停止宽限超时、drain
 *   超时、trace 终态 STOP_UNCONFIRMED、STOP_FAILED）永远映射为
 *   [StopOutcome.Unconfirmed]，绝不升格为 [StopOutcome.Confirmed]。
 * - 只有真实 owner 的确认停止（或幂等的 ALREADY_STOPPED 观察）产生
 *   [StopOutcome.Confirmed] / [StopOutcome.AlreadyStopped]。
 * - 不是停止结果的枚举值（launch 失败码、无关 trace 码、非停止阶段、被取代的
 *   STALE 会话）映射为 `null`：映射是诚实的偏函数，不发明停止事实。
 *
 * Unconfirmed 语义：实例元数据保持 RUNNING；保留 session owner（前台绑定、
 * generation guard、退出 watcher）；禁止以该结果为依据重启，只允许 owner 对
 * 同一会话重试停止。
 */
object StopOutcomeAdapters {

    // ---- RuntimeProcessSupervisor.StopResult（根 supervisor 词汇）----

    /**
     * Exhaustive: CONFIRMED -> Confirmed, UNCONFIRMED -> Unconfirmed（本层
     * 无诊断时的诚实缺省），ALREADY_STOPPED -> AlreadyStopped。
     */
    fun toStopOutcome(result: RuntimeProcessSupervisor.StopResult): StopOutcome =
        when (result) {
            RuntimeProcessSupervisor.StopResult.CONFIRMED -> StopOutcome.Confirmed
            RuntimeProcessSupervisor.StopResult.UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED")
            RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED -> StopOutcome.AlreadyStopped
        }

    /**
     * 诊断增强映射：UNCONFIRMED 携带 supervisor 的观测事实
     * （阶段名、宿主 pid、授予的宽限毫秒、观察到的退出码）。CONFIRMED 与
     * ALREADY_STOPPED 忽略诊断。
     */
    fun toStopOutcome(
        result: RuntimeProcessSupervisor.StopResult,
        diagnostics: RuntimeProcessSupervisor.Diagnostics,
    ): StopOutcome =
        when (result) {
            RuntimeProcessSupervisor.StopResult.CONFIRMED -> StopOutcome.Confirmed
            RuntimeProcessSupervisor.StopResult.UNCONFIRMED -> StopOutcome.Unconfirmed(
                stage = diagnostics.stage(),
                pid = diagnostics.pid(),
                grace = diagnostics.graceMs(),
                exitCode = diagnostics.exitCode(),
            )
            RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED -> StopOutcome.AlreadyStopped
        }

    // ---- InstanceLaunchSequence.ExitStatus / ExitResult ----

    /**
     * 退出观察结果的映射：NORMAL 与 CRASHED 都是 owner 已确认停止后的诚实
     * 终态 -> Confirmed；WAIT_FAILED（观察者失败，无证据表明 VM 已停）与
     * STOP_UNCONFIRMED -> Unconfirmed；STALE（代际已被取代，本会话对停止无
     * 发言权）不是停止事实 -> null。exitCode 原样透传（未知时上游传 -1）。
     */
    fun toStopOutcome(status: InstanceLaunchSequence.ExitStatus, exitCode: Int): StopOutcome? =
        when (status) {
            InstanceLaunchSequence.ExitStatus.NORMAL -> StopOutcome.Confirmed
            InstanceLaunchSequence.ExitStatus.CRASHED -> StopOutcome.Confirmed
            InstanceLaunchSequence.ExitStatus.WAIT_FAILED ->
                StopOutcome.Unconfirmed("WAIT_FAILED", StopOutcome.UNKNOWN_PID, 0L, exitCode)
            InstanceLaunchSequence.ExitStatus.STOP_UNCONFIRMED -> StopOutcome.Unconfirmed(
                "STOP_UNCONFIRMED", StopOutcome.UNKNOWN_PID, 0L, exitCode)
            InstanceLaunchSequence.ExitStatus.STALE -> null
        }

    /** [InstanceLaunchSequence.ExitResult] 的整体映射（含观察到的退出码）。 */
    fun toStopOutcome(result: InstanceLaunchSequence.ExitResult): StopOutcome? =
        toStopOutcome(result.status(), result.exitCode())

    // ---- ProtectedWarmPool.Code / ShutdownResult ----

    /**
     * 池的逐出/审计码：只有 STOP_UNCONFIRMED 是停止结果（停止未确认，暖实例
     * 可能仍存活）；其余是创建/就绪/探活失败码，不是停止事实 -> null。
     */
    fun toStopOutcome(code: ProtectedWarmPool.Code): StopOutcome? =
        when (code) {
            ProtectedWarmPool.Code.STOP_UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
            else -> null
        }

    /**
     * 池 shutdown 结果：DRAINED -> Confirmed；TIMED_OUT（仍有 lease 未归还，
     * 无法确认全部停止）与 STOP_UNCONFIRMED -> Unconfirmed。
     */
    fun toStopOutcome(result: ProtectedWarmPool.ShutdownResult): StopOutcome =
        when (result) {
            ProtectedWarmPool.ShutdownResult.DRAINED -> StopOutcome.Confirmed
            ProtectedWarmPool.ShutdownResult.TIMED_OUT ->
                StopOutcome.unconfirmedWithoutDiagnostics("TIMED_OUT")
            ProtectedWarmPool.ShutdownResult.STOP_UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
        }

    // ---- WorkerPool.Code / ShutdownResult ----

    /** 同 [toStopOutcome]（Code 语义）：只有 STOP_UNCONFIRMED 是停止结果。 */
    fun toStopOutcome(code: WorkerPool.Code): StopOutcome? =
        when (code) {
            WorkerPool.Code.STOP_UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
            else -> null
        }

    /** 同 [toStopOutcome]（ShutdownResult 语义）：DRAINED 确认，其余未确认。 */
    fun toStopOutcome(result: WorkerPool.ShutdownResult): StopOutcome =
        when (result) {
            WorkerPool.ShutdownResult.DRAINED -> StopOutcome.Confirmed
            WorkerPool.ShutdownResult.TIMED_OUT ->
                StopOutcome.unconfirmedWithoutDiagnostics("TIMED_OUT")
            WorkerPool.ShutdownResult.STOP_UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
        }

    // ---- ProductionStageRecorder.Failure 与 trace 终态码 ----

    /**
     * 有界 trace 失败词汇：只有 STOP_FAILED 是停止结果（停止请求失败或未在
     * 时限内确认——两种生产路径：抛异常的 stop 与 VM_STOP_UNCONFIRMED）->
     * Unconfirmed；其余是 launch 失败码 -> null。
     */
    fun toStopOutcome(failure: ProductionStageRecorder.Failure): StopOutcome? =
        when (failure) {
            ProductionStageRecorder.Failure.STOP_FAILED ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_FAILED")
            else -> null
        }

    /**
     * trace 终态/观察码字符串：只有 `STOP_UNCONFIRMED` 终态码是停止结果 ->
     * Unconfirmed；其余（RETIRED、ALREADY_RECORDED、路由/身份拒绝等）不是
     * 停止事实 -> null。未知字符串一律 null，不猜。
     */
    fun toStopOutcomeFromRecorderTrace(traceCode: String?): StopOutcome? =
        if ("STOP_UNCONFIRMED" == traceCode) {
            StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
        } else {
            null
        }

    // ---- LaunchTimeline.Stage ----

    /**
     * 启动时间线阶段：只有 STOP_CONFIRMED -> Confirmed 与 STOP_UNCONFIRMED ->
     * Unconfirmed 是停止结果；STOP_REQUESTED 是意图不是结果，其余 launch
     * 阶段 -> null。
     */
    fun toStopOutcome(stage: LaunchTimeline.Stage): StopOutcome? =
        when (stage) {
            LaunchTimeline.Stage.STOP_CONFIRMED -> StopOutcome.Confirmed
            LaunchTimeline.Stage.STOP_UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
            else -> null
        }

    // ---- DualLaneCoordinator 车道事件 detail 字符串 ----

    /**
     * 车道停止事件 detail（生产侧为 `LaneStopOutcome.name()` 或字面量
     * `STOP_UNCONFIRMED`）：CONFIRMED -> Confirmed，ALREADY_STOPPED ->
     * AlreadyStopped，UNCONFIRMED / STOP_UNCONFIRMED -> Unconfirmed，其余 ->
     * null。
     */
    fun toStopOutcomeFromLaneDetail(detail: String?): StopOutcome? =
        when (detail) {
            "CONFIRMED" -> StopOutcome.Confirmed
            "UNCONFIRMED" -> StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED")
            "STOP_UNCONFIRMED" ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
            "ALREADY_STOPPED" -> StopOutcome.AlreadyStopped
            else -> null
        }

    // ---- Fast Lane 词汇（FastLaneStopResult / FastLaneException / ProtectedFailureCode）----

    /**
     * Fast Lane 停止结果：与 supervisor 词汇同形的三值枚举 ->
     * CONFIRMED/UNCONFIRMED/ALREADY_STOPPED 一一对应。
     */
    fun toStopOutcome(result: FastLaneStopResult): StopOutcome =
        when (result) {
            FastLaneStopResult.CONFIRMED -> StopOutcome.Confirmed
            FastLaneStopResult.UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED")
            FastLaneStopResult.ALREADY_STOPPED -> StopOutcome.AlreadyStopped
        }

    /**
     * Fast Lane 异常 reason：只有 STOP_UNCONFIRMED 是停止结果 -> Unconfirmed
     * （stage 原样保留 reason）；其余 reason 是 launch/装载失败 -> null。
     */
    fun toStopOutcomeFromFastLaneReason(reason: String?): StopOutcome? =
        if (FastLaneException.STOP_UNCONFIRMED == reason) {
            StopOutcome.unconfirmedWithoutDiagnostics(FastLaneException.STOP_UNCONFIRMED)
        } else {
            null
        }

    /**
     * protected-lane 规范失败码：STOP 类的 STOP_UNCONFIRMED 与 DRAIN_TIMEOUT
     * 表示无法确认停止 -> Unconfirmed；其余类（创建/装载/权限）不是停止事实
     * -> null。
     */
    fun toStopOutcome(code: ProtectedFailureCode): StopOutcome? =
        when (code) {
            ProtectedFailureCode.STOP_UNCONFIRMED ->
                StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED")
            ProtectedFailureCode.DRAIN_TIMEOUT ->
                StopOutcome.unconfirmedWithoutDiagnostics("DRAIN_TIMEOUT")
            else -> null
        }
}
