package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.NoSuchFileException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

/**
 * Round 2026-11-07 Lane D: the humanized error copy mapping table. Codes are
 * a stable contract consumed by user surfaces; these tests pin every family
 * so a refactor cannot silently reshuffle a code or demote a specific case.
 */
class ErrorCopyTest {

    private fun headline(entry: ErrorCopy.Entry): Int = entry.headlineRes
    private fun hint(entry: ErrorCopy.Entry): Int = entry.hintRes

    @Test
    fun nullThrowableMapsToUnknown() {
        val entry = ErrorCopy.resolve(null)
        assertEquals(ErrorCopy.CODE_UNKNOWN, entry.code)
        assertEquals(R.string.error_unknown, headline(entry))
    }

    @Test
    fun unclassifiedThrowableMapsToUnknown() {
        val entry = ErrorCopy.resolve(RuntimeException("boom"))
        assertEquals(ErrorCopy.CODE_UNKNOWN, entry.code)
    }

    @Test
    fun socketTimeoutWinsOverInterruptedIo() {
        // SocketTimeoutException extends InterruptedIOException: the specific
        // network branch must stay reachable.
        val entry = ErrorCopy.resolve(SocketTimeoutException("read timed out"))
        assertEquals(ErrorCopy.CODE_NET_TIMEOUT, entry.code)
        assertEquals(R.string.error_net_timeout, headline(entry))
        assertEquals(R.string.error_hint_check_network, hint(entry))
    }

    @Test
    fun connectFailureMapsToRefused() {
        val entry = ErrorCopy.resolve(ConnectException("Connection refused"))
        assertEquals(ErrorCopy.CODE_NET_REFUSED, entry.code)
        assertEquals(R.string.error_net_refused, headline(entry))
    }

    @Test
    fun dnsFailureMapsToNameResolution() {
        val entry = ErrorCopy.resolve(UnknownHostException("mirror.example"))
        assertEquals(ErrorCopy.CODE_NET_DNS, entry.code)
        assertEquals(R.string.error_net_dns, headline(entry))
    }

    @Test
    fun tlsFailureMapsToSecureChannel() {
        val entry = ErrorCopy.resolve(SSLHandshakeException("handshake failed"))
        assertEquals(ErrorCopy.CODE_NET_TLS, entry.code)
        assertEquals(R.string.error_net_tls, headline(entry))
    }

    @Test
    fun missingFileMapsToFileMissing() {
        val entry = ErrorCopy.resolve(FileNotFoundException("rootfs.raw"))
        assertEquals(ErrorCopy.CODE_FILE_MISSING, entry.code)
        assertEquals(R.string.error_file_missing, headline(entry))
        val nioEntry = ErrorCopy.resolve(NoSuchFileException("rootfs.raw"))
        assertEquals(ErrorCopy.CODE_FILE_MISSING, nioEntry.code)
    }

    @Test
    fun diskFullIoMapsToStorageFull() {
        val entry = ErrorCopy.resolve(IOException("write failed: No space left on device"))
        assertEquals(ErrorCopy.CODE_STORAGE_FULL, entry.code)
        assertEquals(R.string.error_storage_full, headline(entry))
        assertEquals(R.string.error_hint_check_storage, hint(entry))
    }

    @Test
    fun genericIoMapsToIoGeneric() {
        val entry = ErrorCopy.resolve(IOException("pipe closed"))
        assertEquals(ErrorCopy.CODE_IO_GENERIC, entry.code)
        assertEquals(R.string.error_io_generic, headline(entry))
    }

    @Test
    fun waitTimeoutMapsToWaitTimeout() {
        val entry = ErrorCopy.resolve(TimeoutException("deadline exceeded"))
        assertEquals(ErrorCopy.CODE_WAIT_TIMEOUT, entry.code)
        assertEquals(R.string.error_wait_timeout, headline(entry))
    }

    @Test
    fun argumentViolationMapsToConfigHint() {
        val entry = ErrorCopy.resolve(IllegalArgumentException("bad disk size"))
        assertEquals(ErrorCopy.CODE_ARG_INVALID, entry.code)
        assertEquals(R.string.error_hint_recheck_config, hint(entry))
    }

    @Test
    fun stateViolationMapsToStateHint() {
        val entry = ErrorCopy.resolve(IllegalStateException("still running"))
        assertEquals(ErrorCopy.CODE_STATE_INVALID, entry.code)
        assertEquals(R.string.error_hint_check_state, hint(entry))
    }

    @Test
    fun securityViolationMapsToPermission() {
        val entry = ErrorCopy.resolve(SecurityException("not allowed"))
        assertEquals(ErrorCopy.CODE_PERM_DENIED, entry.code)
        assertEquals(R.string.error_perm_denied, headline(entry))
    }

    @Test
    fun interruptedOperationIsDistinguishedFromNetworkTimeout() {
        val entry = ErrorCopy.resolve(java.io.InterruptedIOException("abort"))
        assertEquals(ErrorCopy.CODE_INTERRUPTED, entry.code)
        assertEquals(R.string.error_interrupted, headline(entry))
        val threadEntry = ErrorCopy.resolve(InterruptedException("stop"))
        assertEquals(ErrorCopy.CODE_INTERRUPTED, threadEntry.code)
    }

    // 2026-11-08 T1 单源化：启动路径专用族（原 LaunchFailureCopy 映射表）并入
    // ErrorCopy 后的锁定用例；文案照抄迁移至 strings.xml 的 error_* 键。

    @Test
    fun invalidTransitionMapsToLifecycleFamily() {
        val entry = ErrorCopy.resolve(
            InstanceLifecycle.InvalidTransitionException("CREATED -> RUNNING")
        )
        assertEquals(ErrorCopy.CODE_LIFECYCLE, entry.code)
        assertEquals(R.string.error_lifecycle, headline(entry))
        assertEquals(R.string.error_hint_check_state, hint(entry))
    }

    @Test
    fun invalidTransitionWinsOverGenericStateFamily() {
        // 子类判定顺序回归：InvalidTransitionException 是 IllegalStateException 的
        // 子类，必须命中专用 E-LIFECYCLE，而不是被 state-invalid 分支吞掉。
        val lifecycle = ErrorCopy.resolve(
            InstanceLifecycle.InvalidTransitionException("t")
        )
        val state = ErrorCopy.resolve(IllegalStateException("t"))
        assertEquals(ErrorCopy.CODE_LIFECYCLE, lifecycle.code)
        assertNotEquals(state.code, lifecycle.code)
    }

    @Test
    fun avfEngineExceptionRefinesByReason() {
        val permission = ErrorCopy.resolve(
            AvfEngineException(AvfEngineException.PERMISSION_DENIED, "x")
        )
        assertEquals(ErrorCopy.CODE_AVF_PERMISSION, permission.code)
        assertEquals(R.string.error_avf_permission, headline(permission))

        val unsupported = ErrorCopy.resolve(
            AvfEngineException(AvfEngineException.HARDWARE_UNSUPPORTED, "x")
        )
        assertEquals(ErrorCopy.CODE_AVF_UNSUPPORTED, unsupported.code)
        assertEquals(R.string.error_avf_unsupported, headline(unsupported))

        val config = ErrorCopy.resolve(
            AvfEngineException(AvfEngineException.CONFIG_MISMATCH, "x")
        )
        assertEquals(ErrorCopy.CODE_AVF_CONFIG, config.code)
        assertEquals(R.string.error_avf_config, headline(config))

        // 未知 reason 落到 AVF 通用码，而不是未知错误兜底。
        val generic = ErrorCopy.resolve(
            AvfEngineException("SOME_FUTURE_REASON", "x")
        )
        assertEquals(ErrorCopy.CODE_AVF_GENERIC, generic.code)
        assertEquals(R.string.error_avf_generic, headline(generic))
        assertNotEquals(ErrorCopy.CODE_UNKNOWN, generic.code)
    }

    @Test
    fun laneStartExceptionMapsToLaneFamily() {
        val entry = ErrorCopy.resolve(LaneStartException("PAYLOAD_LOAD_FAILED"))
        assertEquals(ErrorCopy.CODE_LANE, entry.code)
        assertEquals(R.string.error_lane, headline(entry))
    }

    @Test
    fun outOfMemoryMapsToOomFamily() {
        val entry = ErrorCopy.resolve(OutOfMemoryError("heap"))
        assertEquals(ErrorCopy.CODE_OOM, entry.code)
        assertEquals(R.string.error_oom, headline(entry))
    }

    @Test
    fun everyFamilyOwnsADistinctStableCode() {
        val codes = listOf(
            ErrorCopy.resolve(SocketTimeoutException("t")).code,
            ErrorCopy.resolve(ConnectException("c")).code,
            ErrorCopy.resolve(UnknownHostException("u")).code,
            ErrorCopy.resolve(SSLHandshakeException("s")).code,
            ErrorCopy.resolve(FileNotFoundException("f")).code,
            ErrorCopy.resolve(IOException("No space left on device")).code,
            ErrorCopy.resolve(IOException("generic")).code,
            ErrorCopy.resolve(TimeoutException("w")).code,
            ErrorCopy.resolve(IllegalArgumentException("i")).code,
            ErrorCopy.resolve(IllegalStateException("l")).code,
            ErrorCopy.resolve(SecurityException("p")).code,
            ErrorCopy.resolve(java.io.InterruptedIOException("n")).code,
            // 2026-11-08 T1 并入的启动路径专用族。
            ErrorCopy.resolve(InstanceLifecycle.InvalidTransitionException("t")).code,
            ErrorCopy.resolve(AvfEngineException(AvfEngineException.PERMISSION_DENIED, "a")).code,
            ErrorCopy.resolve(LaneStartException("X")).code,
            ErrorCopy.resolve(OutOfMemoryError("o")).code,
            ErrorCopy.resolve(null).code,
        )
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.startsWith("E-") })
    }

    @Test
    fun headlinesAndHintsDoNotCollide() {
        val entries = listOf(
            ErrorCopy.resolve(SocketTimeoutException("t")),
            ErrorCopy.resolve(ConnectException("c")),
            ErrorCopy.resolve(UnknownHostException("u")),
            ErrorCopy.resolve(SSLHandshakeException("s")),
            ErrorCopy.resolve(FileNotFoundException("f")),
            ErrorCopy.resolve(IOException("No space left on device")),
            ErrorCopy.resolve(IOException("generic")),
            ErrorCopy.resolve(TimeoutException("w")),
            ErrorCopy.resolve(IllegalArgumentException("i")),
            ErrorCopy.resolve(IllegalStateException("l")),
            ErrorCopy.resolve(SecurityException("p")),
            ErrorCopy.resolve(java.io.InterruptedIOException("n")),
            // 2026-11-08 T1 并入的启动路径专用族。
            ErrorCopy.resolve(InstanceLifecycle.InvalidTransitionException("t")),
            ErrorCopy.resolve(AvfEngineException(AvfEngineException.PERMISSION_DENIED, "a")),
            ErrorCopy.resolve(LaneStartException("X")),
            ErrorCopy.resolve(OutOfMemoryError("o")),
            ErrorCopy.resolve(null),
        )
        assertEquals(entries.size, entries.map { it.headlineRes }.toSet().size)
        assertNotEquals(entries[0].hintRes, entries[1].hintRes)
    }
}
