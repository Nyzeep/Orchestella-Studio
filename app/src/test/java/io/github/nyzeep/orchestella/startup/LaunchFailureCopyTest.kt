package io.github.nyzeep.orchestella.startup

import io.github.nyzeep.orchestella.ErrorCopy
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-08 单源化收口（T1）：[LaunchFailureCopy] 仅是 [ErrorCopy] 的委托壳，
 * 本测试不再各自维护一份文案锁定，改为锁定<b>委托一致性</b>——每个输入族的
 * describe 结果必须等于 ErrorCopy.resolve 给出的 Entry 固定码（即
 * {@code ErrorCopy.detailMessage} 副文本「（错误码 E-XXX）」中的那串码），且
 * 固定码契约只增不改名。人类可读文案本身由 ErrorCopyTest 与 strings.xml 的
 * error_* 键锁定。
 */
public final class LaunchFailureCopyTest {

    /** 委托一致性断言：固定码与 ErrorCopy 单源表逐一比对。 */
    private fun assertDelegatesTo(failure: Throwable, expectedCode: String) {
        val entry = ErrorCopy.resolve(failure)
        assertEquals("ErrorCopy must own the $expectedCode family", expectedCode, entry.code)
        assertEquals(
            "describe must delegate to ErrorCopy for ${failure.javaClass.name}",
            entry.code,
            LaunchFailureCopy.describe(failure),
        )
    }

    @Test
    fun ioExceptionDelegatesToIoGenericFamily() {
        assertDelegatesTo(IOException("disk full"), ErrorCopy.CODE_IO_GENERIC)
    }

    @Test
    fun invalidTransitionDelegatesToLifecycleFamily() {
        assertDelegatesTo(
            InstanceLifecycle.InvalidTransitionException("CREATED -> RUNNING"),
            ErrorCopy.CODE_LIFECYCLE,
        )
    }

    @Test
    fun laneStartExceptionDelegatesToLaneFamily() {
        assertDelegatesTo(LaneStartException("PAYLOAD_LOAD_FAILED"), ErrorCopy.CODE_LANE)
    }

    @Test
    fun avfEngineExceptionRefinesByReason() {
        assertDelegatesTo(
            AvfEngineException(AvfEngineException.PERMISSION_DENIED, "x"),
            ErrorCopy.CODE_AVF_PERMISSION,
        )
        assertDelegatesTo(
            AvfEngineException(AvfEngineException.HARDWARE_UNSUPPORTED, "x"),
            ErrorCopy.CODE_AVF_UNSUPPORTED,
        )
        assertDelegatesTo(
            AvfEngineException(AvfEngineException.CONFIG_MISMATCH, "x"),
            ErrorCopy.CODE_AVF_CONFIG,
        )
        // 未知 reason 落到 AVF 通用码，而不是未知错误兜底。
        assertDelegatesTo(
            AvfEngineException("SOME_FUTURE_REASON", "x"),
            ErrorCopy.CODE_AVF_GENERIC,
        )
    }

    @Test
    fun securityMemoryAndStateKeepDedicatedFamilies() {
        assertDelegatesTo(SecurityException("denied"), ErrorCopy.CODE_PERM_DENIED)
        assertDelegatesTo(OutOfMemoryError("heap"), ErrorCopy.CODE_OOM)
        assertDelegatesTo(IllegalStateException("bad state"), ErrorCopy.CODE_STATE_INVALID)
    }

    @Test
    fun unknownThrowableFallsBackToUnknownCode() {
        assertDelegatesTo(RuntimeException("boom"), ErrorCopy.CODE_UNKNOWN)
    }

    @Test
    fun invalidTransitionDoesNotCollapseIntoGenericStateFamily() {
        // most-specific-first 回归：InvalidTransitionException 是 IllegalStateException
        // 的子类，必须命中专用 E-LIFECYCLE 而不是 state-invalid 通用码。
        val genericState = ErrorCopy.resolve(IllegalStateException("plain")).code
        val lifecycle = LaunchFailureCopy.describe(
            InstanceLifecycle.InvalidTransitionException("CREATED -> RUNNING")
        )
        assertEquals(ErrorCopy.CODE_LIFECYCLE, lifecycle)
        assertNotEquals(genericState, lifecycle)
    }

    @Test
    fun knownTypesDoNotCollapseIntoGenericFallback() {
        // 类型分支与兜底分支彼此不同，防止映射表被意外压平。
        val generic = LaunchFailureCopy.describe(RuntimeException("boom"))
        assertNotEquals(generic, LaunchFailureCopy.describe(IOException("generic")))
        assertNotEquals(generic, LaunchFailureCopy.describe(LaneStartException("X")))
        assertNotEquals(generic, LaunchFailureCopy.describe(OutOfMemoryError("heap")))
    }

    @Test
    fun describeIsNeverBlankAndNeverExposesHostDetail() {
        val samples = listOf<Throwable>(
            IOException("ENOSPC-secret-host-detail"),
            SecurityException(),
            IllegalStateException(),
            RuntimeException(),
            LaneStartException("X"),
            AvfEngineException("SOME_FUTURE_REASON", "secret-host-detail"),
        )
        for (sample in samples) {
            val code = LaunchFailureCopy.describe(sample)
            assertFalse("code must not be blank for $sample", code.isBlank())
            assertTrue("code must keep the E- contract for $sample", code.startsWith("E-"))
            assertFalse(
                "code must not echo exception message for $sample",
                code.contains("secret-host-detail"),
            )
        }
    }
}
