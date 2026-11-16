package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.StopOutcome
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult
import io.github.nyzeep.orchestella.runtime.fastlane.ProtectedFailureCode
import java.io.File
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * OPT-05: exhaustive mapping contract from the App layer's stop vocabularies
 * onto the shared [StopOutcome]. The adapters are pure functions over the
 * existing production enums/strings; no production call site changes. Each
 * existing enum constant is enumerated exactly once, and every UNCONFIRMED
 * input keeps its honest result: no mapping ever produces Confirmed from
 * Unconfirmed evidence.
 */
class StopOutcomeAdaptersTest {

    // ---- RuntimeProcessSupervisor.StopResult（根 supervisor 词汇）----

    @Test
    fun supervisorStopResultMapsExhaustively() {
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcome(RuntimeProcessSupervisor.StopResult.CONFIRMED))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcome(RuntimeProcessSupervisor.StopResult.UNCONFIRMED))
        assertSame(
            StopOutcome.AlreadyStopped,
            StopOutcomeAdapters.toStopOutcome(RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED))
    }

    @Test
    fun supervisorUnconfirmedCarriesDiagnosticsWhenPresent() {
        val diagnostics = RuntimeProcessSupervisor.Diagnostics(
            "gateway-release-timeout", 4242L, 3000L, true, 137)
        val outcome = StopOutcomeAdapters.toStopOutcome(
            RuntimeProcessSupervisor.StopResult.UNCONFIRMED, diagnostics)
        val unconfirmed = outcome as StopOutcome.Unconfirmed
        assertEquals("gateway-release-timeout", unconfirmed.stage)
        assertEquals(4242L, unconfirmed.pid)
        assertEquals(3000L, unconfirmed.grace)
        assertEquals(137, unconfirmed.exitCode)
    }

    @Test
    fun supervisorUnconfirmedWithNoneDiagnosticsKeepsProductionSentinels() {
        // Diagnostics.none() 的生产哨兵：stage="not-started", pid=-1, grace=0, exitCode=-1。
        val outcome = StopOutcomeAdapters.toStopOutcome(
            RuntimeProcessSupervisor.StopResult.UNCONFIRMED,
            RuntimeProcessSupervisor.Diagnostics("not-started", -1L, 0L, false, -1))
        assertEquals(
            StopOutcome.Unconfirmed("not-started", -1L, 0L, -1), outcome)
    }

    @Test
    fun confirmedAndAlreadyStoppedIgnoreDiagnostics() {
        val diagnostics = RuntimeProcessSupervisor.Diagnostics(
            "gateway-release-timeout", 4242L, 3000L, true, 137)
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcome(
                RuntimeProcessSupervisor.StopResult.CONFIRMED, diagnostics))
        assertSame(
            StopOutcome.AlreadyStopped,
            StopOutcomeAdapters.toStopOutcome(
                RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED, diagnostics))
    }

    // ---- InstanceLaunchSequence.ExitStatus / ExitResult ----

    @Test
    fun exitStatusMapsExhaustively() {
        val exitCode = 137
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcome(
                InstanceLaunchSequence.ExitStatus.NORMAL, exitCode))
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcome(
                InstanceLaunchSequence.ExitStatus.CRASHED, exitCode))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("WAIT_FAILED").copy(exitCode = -1),
            StopOutcomeAdapters.toStopOutcome(
                InstanceLaunchSequence.ExitStatus.WAIT_FAILED, -1))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED").copy(exitCode = exitCode),
            StopOutcomeAdapters.toStopOutcome(
                InstanceLaunchSequence.ExitStatus.STOP_UNCONFIRMED, exitCode))
        // STALE 不是停止事实：代际已被取代，本会话对停止没有发言权。
        assertNull(
            StopOutcomeAdapters.toStopOutcome(
                InstanceLaunchSequence.ExitStatus.STALE, exitCode))
    }

    @Test
    fun exitResultDelegatesWithObservedExitCode() {
        val unconfirmed = InstanceLaunchSequence.ExitResult(
            InstanceLaunchSequence.ExitStatus.STOP_UNCONFIRMED, 42)
        assertEquals(
            StopOutcome.Unconfirmed("STOP_UNCONFIRMED",
                StopOutcome.UNKNOWN_PID, 0L, 42),
            StopOutcomeAdapters.toStopOutcome(unconfirmed))

        val normal = InstanceLaunchSequence.ExitResult(
            InstanceLaunchSequence.ExitStatus.NORMAL, 0)
        assertSame(StopOutcome.Confirmed, StopOutcomeAdapters.toStopOutcome(normal))
    }

    // ---- ProtectedWarmPool.Code / ShutdownResult ----

    @Test
    fun warmPoolEvictionCodeMapsStopUnconfirmedOnly() {
        for (code in ProtectedWarmPool.Code.values()) {
            val outcome = StopOutcomeAdapters.toStopOutcome(code)
            if (code == ProtectedWarmPool.Code.STOP_UNCONFIRMED) {
                assertEquals(
                    StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"), outcome)
            } else {
                assertNull(code.name, outcome)
            }
        }
    }

    @Test
    fun warmPoolShutdownResultMapsExhaustively() {
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcome(ProtectedWarmPool.ShutdownResult.DRAINED))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("TIMED_OUT"),
            StopOutcomeAdapters.toStopOutcome(ProtectedWarmPool.ShutdownResult.TIMED_OUT))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcome(ProtectedWarmPool.ShutdownResult.STOP_UNCONFIRMED))
    }

    // ---- WorkerPool.Code / ShutdownResult ----

    @Test
    fun workerPoolEvictionCodeMapsStopUnconfirmedOnly() {
        for (code in WorkerPool.Code.values()) {
            val outcome = StopOutcomeAdapters.toStopOutcome(code)
            if (code == WorkerPool.Code.STOP_UNCONFIRMED) {
                assertEquals(
                    StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"), outcome)
            } else {
                assertNull(code.name, outcome)
            }
        }
    }

    @Test
    fun workerPoolShutdownResultMapsExhaustively() {
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcome(WorkerPool.ShutdownResult.DRAINED))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("TIMED_OUT"),
            StopOutcomeAdapters.toStopOutcome(WorkerPool.ShutdownResult.TIMED_OUT))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcome(WorkerPool.ShutdownResult.STOP_UNCONFIRMED))
    }

    // ---- ProductionStageRecorder.Failure 与 trace 终态码 ----

    @Test
    fun recorderFailureMapsStopFailedOnly() {
        for (failure in ProductionStageRecorder.Failure.values()) {
            val outcome = StopOutcomeAdapters.toStopOutcome(failure)
            if (failure == ProductionStageRecorder.Failure.STOP_FAILED) {
                assertEquals(
                    StopOutcome.unconfirmedWithoutDiagnostics("STOP_FAILED"), outcome)
            } else {
                assertNull(failure.name, outcome)
            }
        }
    }

    @Test
    fun recorderTraceCodeMapsStopUnconfirmedOnly() {
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcomeFromRecorderTrace("STOP_UNCONFIRMED"))
        for (nonStop in listOf(
                "RETIRED", "ALREADY_RECORDED", "OBSERVED", "DISABLED",
                "IDENTITY_MISMATCH", "STAGE_REJECTED", "ROUTE_REJECTED",
                "STOP_COMPLETED", "", "UNRECOGNIZED_FUTURE_CODE")) {
            assertNull(nonStop, StopOutcomeAdapters.toStopOutcomeFromRecorderTrace(nonStop))
        }
        assertNull(StopOutcomeAdapters.toStopOutcomeFromRecorderTrace(null))
    }

    // ---- LaunchTimeline.Stage ----

    @Test
    fun launchTimelineStageMapsStopStagesOnly() {
        for (stage in LaunchTimeline.Stage.values()) {
            val outcome = StopOutcomeAdapters.toStopOutcome(stage)
            when (stage) {
                LaunchTimeline.Stage.STOP_CONFIRMED ->
                    assertSame(StopOutcome.Confirmed, outcome)
                LaunchTimeline.Stage.STOP_UNCONFIRMED ->
                    assertEquals(
                        StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"), outcome)
                else -> assertNull(stage.name, outcome)
            }
        }
    }

    // ---- DualLaneCoordinator 车道事件 detail 字符串 ----

    @Test
    fun laneStopDetailMapsExhaustively() {
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcomeFromLaneDetail("CONFIRMED"))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcomeFromLaneDetail("UNCONFIRMED"))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcomeFromLaneDetail("STOP_UNCONFIRMED"))
        assertSame(
            StopOutcome.AlreadyStopped,
            StopOutcomeAdapters.toStopOutcomeFromLaneDetail("ALREADY_STOPPED"))
        for (nonStop in listOf("DRAINED", "TIMED_OUT", "", "UNRECOGNIZED_FUTURE_DETAIL")) {
            assertNull(nonStop, StopOutcomeAdapters.toStopOutcomeFromLaneDetail(nonStop))
        }
        assertNull(StopOutcomeAdapters.toStopOutcomeFromLaneDetail(null))
    }

    // ---- FastLaneStopResult / FastLaneException / ProtectedFailureCode ----

    @Test
    fun fastLaneStopResultMapsExhaustively() {
        assertSame(
            StopOutcome.Confirmed,
            StopOutcomeAdapters.toStopOutcome(FastLaneStopResult.CONFIRMED))
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcome(FastLaneStopResult.UNCONFIRMED))
        assertSame(
            StopOutcome.AlreadyStopped,
            StopOutcomeAdapters.toStopOutcome(FastLaneStopResult.ALREADY_STOPPED))
    }

    @Test
    fun fastLaneReasonMapsStopUnconfirmedOnly() {
        assertEquals(
            StopOutcome.unconfirmedWithoutDiagnostics(FastLaneException.STOP_UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcomeFromFastLaneReason(
                FastLaneException.STOP_UNCONFIRMED))
        for (nonStop in listOf(
                FastLaneException.CONFIG_INVALID,
                FastLaneException.HIDDEN_API_BLOCKED,
                FastLaneException.PERMISSION_DENIED,
                FastLaneException.PROTECTED_UNAVAILABLE,
                FastLaneException.PAYLOAD_VERIFY_FAILED,
                FastLaneException.VM_CREATE_FAILED,
                FastLaneException.VSOCK_FAILED,
                "UNRECOGNIZED_FUTURE_REASON")) {
            assertNull(nonStop, StopOutcomeAdapters.toStopOutcomeFromFastLaneReason(nonStop))
        }
        assertNull(StopOutcomeAdapters.toStopOutcomeFromFastLaneReason(null))
    }

    @Test
    fun protectedFailureCodeMapsStopClassOnly() {
        for (code in ProtectedFailureCode.values()) {
            val outcome = StopOutcomeAdapters.toStopOutcome(code)
            when (code) {
                ProtectedFailureCode.STOP_UNCONFIRMED ->
                    assertEquals(
                        StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED"), outcome)
                ProtectedFailureCode.DRAIN_TIMEOUT ->
                    assertEquals(
                        StopOutcome.unconfirmedWithoutDiagnostics("DRAIN_TIMEOUT"), outcome)
                else -> assertNull(code.name, outcome)
            }
        }
    }

    // ---- 全局诚实性：任何 UNCONFIRMED 证据都不得映射成 Confirmed ----

    @Test
    fun noUnconfirmedEvidenceEverBecomesConfirmed() {
        val unconfirmedOutcomes = listOf(
            StopOutcomeAdapters.toStopOutcome(RuntimeProcessSupervisor.StopResult.UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcome(
                RuntimeProcessSupervisor.StopResult.UNCONFIRMED,
                RuntimeProcessSupervisor.Diagnostics("not-started", -1L, 0L, false, -1)),
            StopOutcomeAdapters.toStopOutcome(InstanceLaunchSequence.ExitStatus.WAIT_FAILED, -1),
            StopOutcomeAdapters.toStopOutcome(
                InstanceLaunchSequence.ExitStatus.STOP_UNCONFIRMED, 137),
            StopOutcomeAdapters.toStopOutcome(ProtectedWarmPool.ShutdownResult.TIMED_OUT),
            StopOutcomeAdapters.toStopOutcome(ProtectedWarmPool.ShutdownResult.STOP_UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcome(WorkerPool.ShutdownResult.STOP_UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcome(ProductionStageRecorder.Failure.STOP_FAILED),
            StopOutcomeAdapters.toStopOutcomeFromRecorderTrace("STOP_UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcome(LaunchTimeline.Stage.STOP_UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcomeFromLaneDetail("UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcomeFromLaneDetail("STOP_UNCONFIRMED"),
            StopOutcomeAdapters.toStopOutcome(FastLaneStopResult.UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcomeFromFastLaneReason(
                FastLaneException.STOP_UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcome(ProtectedFailureCode.STOP_UNCONFIRMED),
            StopOutcomeAdapters.toStopOutcome(ProtectedFailureCode.DRAIN_TIMEOUT),
        )
        assertTrue(unconfirmedOutcomes.none { it is StopOutcome.Confirmed })
    }

    // ---- 静态断言：main 源集不存在 Unconfirmed -> Confirmed 的映射 ----

    @Test
    fun noMainSourceMapsUnconfirmedToConfirmed() {
        val root = repoRoot()
        val mainRoots = mutableListOf<Path>()
        mainRoots.add(root.resolve("app/src/main"))
        mainRoots.add(root.resolve("accel-extension/src/main"))
        val runtimeDirs = root.resolve("runtime").toFile().listFiles(File::isDirectory)
        assertTrue("runtime/* modules not found under $root", runtimeDirs != null)
        for (module in runtimeDirs!!) {
            mainRoots.add(module.toPath().resolve("src/main"))
        }
        // 契约形态：任何把 Unconfirmed 通过 `->` 映射到 Confirmed 的表达式或
        // 模式分支都是禁止的升格映射。
        // 局限声明：这是逐行正则，两行式 switch（`case UNCONFIRMED:` 换行
        // `return ... Confirmed;`）会漏报；本单适配器全部为单行 when/ternary
        // 表达式，全仓人工逐行复核兜底。若后续新增跨行映射写法，应先把本
        // tripwire 升级为按文件归一化空白后再匹配。
        val forbidden = Regex("""Unconfirmed[^\r\n]*->\s*(StopOutcome\.)?Confirmed\b""")
        val offenders = mutableListOf<String>()
        for (base in mainRoots) {
            if (!base.toFile().exists()) continue
            base.toFile().walkTopDown()
                .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (forbidden.containsMatchIn(line)) {
                            offenders.add("${file.path}:${index + 1}")
                        }
                    }
                }
        }
        assertTrue(
            "禁止的 Unconfirmed -> Confirmed 升格映射出现在: $offenders",
            offenders.isEmpty())
    }

    private fun repoRoot(): Path {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            if (dir.resolve("settings.gradle.kts").toFile().exists()) return dir
            val parent = dir.parent ?: return@repeat
            dir = parent
        }
        fail("settings.gradle.kts not found upward from ${System.getProperty("user.dir")}")
        throw IllegalStateException("unreachable")
    }
}
