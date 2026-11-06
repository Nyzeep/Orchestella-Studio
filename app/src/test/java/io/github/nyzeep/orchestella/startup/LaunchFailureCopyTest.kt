package io.github.nyzeep.orchestella.startup

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
 * 锁定启动失败文案映射（Lane C 协同落点）：按异常类型给出人类可读短文案，
 * 固定异常码由调用方保留在副文本/日志。映射表变化必须显式改本测试。
 */
public final class LaunchFailureCopyTest {

    @Test
    fun ioExceptionMapsToStorageCopy() {
        val text = LaunchFailureCopy.describe(IOException("disk full"))
        assertEquals("实例文件读写失败，请检查存储空间后重试", text)
    }

    @Test
    fun invalidTransitionMapsToStateCopy() {
        val text = LaunchFailureCopy.describe(
            InstanceLifecycle.InvalidTransitionException("CREATED -> RUNNING")
        )
        assertEquals("实例状态不允许本次启动，请刷新实例列表后重试", text)
    }

    @Test
    fun laneStartExceptionMapsToLaneCopy() {
        val text = LaunchFailureCopy.describe(LaneStartException("PAYLOAD_LOAD_FAILED"))
        assertEquals("加速车道启动失败，本次启动已中止", text)
    }

    @Test
    fun avfEngineExceptionRefinesByReason() {
        assertEquals(
            "AVF 授权已失效，请重新完成授权向导",
            LaunchFailureCopy.describe(AvfEngineException(AvfEngineException.PERMISSION_DENIED, "x")),
        )
        assertEquals(
            "当前设备硬件不支持 AVF 硬件虚拟化",
            LaunchFailureCopy.describe(AvfEngineException(AvfEngineException.HARDWARE_UNSUPPORTED, "x")),
        )
        // 未知 reason 落到 AVF 通用文案，而不是未知错误兜底。
        assertEquals(
            "AVF 硬件虚拟机启动失败，可稍后重试",
            LaunchFailureCopy.describe(AvfEngineException("SOME_FUTURE_REASON", "x")),
        )
    }

    @Test
    fun securityAndMemoryAndStateHaveDedicatedCopy() {
        assertEquals(
            "缺少必要的系统权限，请检查系统授权设置",
            LaunchFailureCopy.describe(SecurityException("denied")),
        )
        assertEquals(
            "系统内存不足，请关闭部分应用后重试",
            LaunchFailureCopy.describe(OutOfMemoryError("heap")),
        )
        assertEquals(
            "启动流程状态异常，请重试；持续失败请查看运行日志",
            LaunchFailureCopy.describe(IllegalStateException("bad state")),
        )
    }

    @Test
    fun unknownThrowableFallsBackToGenericCopy() {
        val text = LaunchFailureCopy.describe(RuntimeException("boom"))
        assertEquals("发生未知错误，请重试；持续失败请查看运行日志", text)
    }

    @Test
    fun copyIsNeverBlankAndNeverExposesClassJargon() {
        val samples = listOf<Throwable>(
            IOException("ENOSPC"),
            SecurityException(),
            IllegalStateException(),
            RuntimeException(),
            LaneStartException("X"),
        )
        for (sample in samples) {
            val text = LaunchFailureCopy.describe(sample)
            assertFalse("copy must not be blank for $sample", text.isBlank())
            assertFalse("copy must not echo exception message for $sample", text.contains("ENOSPC"))
            assertFalse("copy must not echo class name for $sample", text.contains("Exception"))
        }
    }

    @Test
    fun knownTypesDoNotCollapseIntoGenericFallback() {
        // 类型分支与兜底分支彼此不同，防止映射表被意外压平。
        val generic = LaunchFailureCopy.describe(RuntimeException())
        assertNotEquals(generic, LaunchFailureCopy.describe(IOException()))
        assertNotEquals(generic, LaunchFailureCopy.describe(LaneStartException("X")))
        assertTrue(LaunchFailureCopy.describe(IOException()).isNotEmpty())
    }
}
