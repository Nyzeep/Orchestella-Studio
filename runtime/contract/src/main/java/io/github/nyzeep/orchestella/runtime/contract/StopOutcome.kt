package io.github.nyzeep.orchestella.runtime.contract

/**
 * OPT-05: 统一停止结果词汇表（sealed）。
 *
 * 停止结果此前在各层各自解释：`InstanceLaunchSequence.ExitStatus`、
 * `RuntimeProcessSupervisor.StopResult`、`AvfStopResult`、`FastLaneStopResult`、
 * `LaneStopOutcome`、`ProtectedWarmPool`/`WorkerPool` 的 `Code`/`ShutdownResult`、
 * `ProductionStageRecorder` 与 `LaunchTimeline` 的 stop 阶段都在各自表达
 * "停止是否被确认"。本类型是唯一共享词汇：各层通过各自模块里的纯函数适配器
 * 把既有结果映射到这里，映射不改变任何调用点行为，只为后续 ExitArbiter 铺路。
 *
 * 三种结果：
 * - [Confirmed]：owner 已确认运行时不再存活、宿主通道已释放。元数据可落 STOPPED，
 *   固定端口/预算可回收，允许后续重启。
 * - [Unconfirmed]：无法确认运行时是否已停止。**契约**：
 *   1. 实例元数据保持 RUNNING，不得写成 STOPPED；
 *   2. 必须保留 session owner（前台绑定、generation guard、退出 watcher 不释放）；
 *   3. 禁止以该结果为依据重启（端口、预算与可能仍存活的进程都未回收）；
 *      仅允许 owner 对同一会话重试停止。
 * - [AlreadyStopped]：运行时早已不存活（幂等停止的第二次观察），与 [Confirmed]
 *   一样允许回收，但不得重复记一次成功停止的补偿记录。
 *
 * 映射纪律（静态保证）：本类型是 sealed，且不提供任何把 [Unconfirmed] 升格为
 * [Confirmed] 的转换；各适配器也不得引入这种映射——"未确认"永远不会被词汇表
 * 洗成"已确认"，只有真实 owner 的再次确认（或幂等的 [AlreadyStopped] 观察）
 * 能产生 [Confirmed]。
 *
 * 纯 JVM 类型，无 Android 依赖。
 */
sealed class StopOutcome {

    /** owner 已确认运行时停止且通道释放。 */
    object Confirmed : StopOutcome()

    /**
     * 无法确认运行时是否已停止。
     *
     * @property stage 产生该结果的阶段/原因标识（来源词汇的常量名或诊断阶段名，
     *   非空白；例如 `"STOP_UNCONFIRMED"`、supervisor 诊断的 `"not-started"`）。
     * @property pid 观察到的宿主进程 pid；未知时为 [UNKNOWN_PID]（-1）。
     * @property grace 停止被授予的宽限期（毫秒，>= 0；未记录时为 0）。
     * @property exitCode 观察到的退出码；未知时为 [UNKNOWN_EXIT_CODE]（-1）。
     */
    data class Unconfirmed(
        val stage: String,
        val pid: Long,
        val grace: Long,
        val exitCode: Int,
    ) : StopOutcome() {
        init {
            // 非空约束：stage 必须是可追溯的非空白标识；pid/exitCode 只允许
            // 文档哨兵 UNKNOWN_PID/UNKNOWN_EXIT_CODE 表示未知；grace 是授予的
            // 宽限毫秒，非负。字段本身是 Kotlin 非空类型，编译期即禁止 null。
            require(stage.isNotBlank()) { "stage must not be blank" }
            require(grace >= 0L) { "grace must be >= 0, was $grace" }
            require(pid >= UNKNOWN_PID) { "pid must be >= UNKNOWN_PID($UNKNOWN_PID), was $pid" }
            require(exitCode >= UNKNOWN_EXIT_CODE) {
                "exitCode must be >= UNKNOWN_EXIT_CODE($UNKNOWN_EXIT_CODE), was $exitCode"
            }
        }
    }

    /** 运行时早已不存活：幂等停止的第二次观察，语义上等价于已停止。 */
    object AlreadyStopped : StopOutcome()

    companion object {
        /** [Unconfirmed.pid] 的"未知"哨兵值，与 supervisor 诊断的约定一致。 */
        const val UNKNOWN_PID: Long = -1L

        /** [Unconfirmed.exitCode] 的"未知"哨兵值，与退出观察的约定一致。 */
        const val UNKNOWN_EXIT_CODE: Int = -1

        /** 没有任何诊断时的 [Unconfirmed] 工厂：pid/exitCode 未知，未记录宽限。 */
        @JvmStatic
        fun unconfirmedWithoutDiagnostics(stage: String): Unconfirmed =
            Unconfirmed(stage, UNKNOWN_PID, 0L, UNKNOWN_EXIT_CODE)
    }
}
