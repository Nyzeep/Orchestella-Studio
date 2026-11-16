package io.github.nyzeep.orchestella.runtime.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * OPT-05 contract: [StopOutcome] is the single shared stop vocabulary.
 * Guards the field constraints of [StopOutcome.Unconfirmed], the exhaustive
 * three-kind shape of the sealed hierarchy, and the static rule that no
 * `Unconfirmed -> Confirmed` upgrade exists anywhere in the vocabulary API.
 */
class StopOutcomeContractTest {

    // ---- Unconfirmed 字段非空 / 合法域约束 ----

    @Test
    fun unconfirmedAcceptsWellFormedFieldsAndKeepsThemNonNull() {
        val outcome = StopOutcome.Unconfirmed(
            stage = "STOP_UNCONFIRMED", pid = 4242L, grace = 3000L, exitCode = 137)
        assertEquals("STOP_UNCONFIRMED", outcome.stage)
        assertEquals(4242L, outcome.pid)
        assertEquals(3000L, outcome.grace)
        assertEquals(137, outcome.exitCode)
    }

    @Test
    fun unconfirmedAcceptsDocumentedUnknownSentinels() {
        val outcome = StopOutcome.Unconfirmed(
            stage = "WAIT_FAILED",
            pid = StopOutcome.UNKNOWN_PID,
            grace = 0L,
            exitCode = StopOutcome.UNKNOWN_EXIT_CODE)
        assertEquals(-1L, outcome.pid)
        assertEquals(-1, outcome.exitCode)
    }

    @Test
    fun unconfirmedRejectsBlankStage() {
        assertRejected { StopOutcome.Unconfirmed("", 0L, 0L, 0) }
        assertRejected { StopOutcome.Unconfirmed("   ", 0L, 0L, 0) }
    }

    @Test
    fun unconfirmedRejectsNegativeGrace() {
        assertRejected { StopOutcome.Unconfirmed("STOP_UNCONFIRMED", 0L, -1L, 0) }
    }

    @Test
    fun unconfirmedRejectsPidBelowUnknownSentinel() {
        assertRejected { StopOutcome.Unconfirmed("STOP_UNCONFIRMED", -2L, 0L, 0) }
    }

    @Test
    fun unconfirmedRejectsExitCodeBelowUnknownSentinel() {
        assertRejected { StopOutcome.Unconfirmed("STOP_UNCONFIRMED", 0L, 0L, -2) }
    }

    // ---- 三类结果穷举 ----

    @Test
    fun sealedHierarchyHasExactlyTheThreeKinds() {
        assertTrue(StopOutcome::class.java.isSealed)
        val permitted = StopOutcome::class.java.permittedSubclasses
        val kinds = permitted.map { it.simpleName }.toSet()
        assertEquals(setOf("Confirmed", "Unconfirmed", "AlreadyStopped"), kinds)
        assertEquals(3, permitted.size)
    }

    @Test
    fun confirmedAndAlreadyStoppedAreDistinctSingletons() {
        assertSame(StopOutcome.Confirmed, StopOutcome.Confirmed)
        assertSame(StopOutcome.AlreadyStopped, StopOutcome.AlreadyStopped)
        assertNotEquals(StopOutcome.Confirmed, StopOutcome.AlreadyStopped)
    }

    @Test
    fun unconfirmedWithoutDiagnosticsUsesDocumentedSentinels() {
        val outcome = StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED")
        assertEquals("UNCONFIRMED", outcome.stage)
        assertEquals(StopOutcome.UNKNOWN_PID, outcome.pid)
        assertEquals(0L, outcome.grace)
        assertEquals(StopOutcome.UNKNOWN_EXIT_CODE, outcome.exitCode)
    }

    // ---- 静态断言：不存在 Unconfirmed -> Confirmed 的映射 ----

    @Test
    fun noApiTransformsUnconfirmedIntoConfirmed() {
        for (owner in listOf(
                StopOutcome::class, StopOutcome.Confirmed::class,
                StopOutcome.Unconfirmed::class, StopOutcome.AlreadyStopped::class,
                StopOutcome.Companion::class)) {
            for (method in owner.java.declaredMethods) {
                val returnsConfirmed = method.returnType == StopOutcome.Confirmed::class.java
                val returnsStopOutcome = method.returnType == StopOutcome::class.java
                val takesUnconfirmed = method.parameterTypes.any {
                    it == StopOutcome.Unconfirmed::class.java
                }
                if ((returnsConfirmed || returnsStopOutcome) && takesUnconfirmed) {
                    fail("禁止的升格映射 API: ${owner.java.name}.${method.name}")
                }
            }
        }
    }

    @Test
    fun unconfirmedIsNotSubstitutableForConfirmed() {
        // 类型层面：Unconfirmed 与 Confirmed 是不同的叶子类型，互不可赋值。
        val outcome: StopOutcome = StopOutcome.Unconfirmed("STAGE", 1L, 1L, 1)
        assertTrue(outcome is StopOutcome.Unconfirmed)
        assertTrue(!java.util.Set.of(StopOutcome.Confirmed).contains(outcome))
    }

    private fun assertRejected(block: () -> StopOutcome.Unconfirmed) {
        try {
            block()
            fail("Unconfirmed 非空/合法域约束缺失：非法入参被接受")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}
