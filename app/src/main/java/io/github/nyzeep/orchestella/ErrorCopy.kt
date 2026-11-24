package io.github.nyzeep.orchestella

import androidx.annotation.StringRes
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import java.io.IOException

/**
 * Round 2026-11-07 Lane D: human-readable error copy mapping.
 *
 * Replaces bare exception names in user surfaces ("初始化失败：IOException")
 * with a short Chinese headline plus a stable fixed code, and a hint for the
 * next action. Headlines/hints live in strings.xml (keys prefixed `error_`);
 * this object only decides WHICH entry a throwable belongs to, so the table
 * itself stays a pure JVM decision with unit-test coverage.
 *
 * Consumers today: the creation wizard (WizardController, UI-12 Compose 版),
 * TerminalActivity (reconcile error line) and MainActivity (launch-failure status row). Since the
 * 2026-11-08 unification (T1) the former launch-path table
 * [io.github.nyzeep.orchestella.startup.LaunchFailureCopy] delegates here, so
 * this is the single source: the codes are contract-stable — never rename a
 * code, only add new ones.
 */
object ErrorCopy {

    /** One error family: headline + stable code + suggested next action. */
    data class Entry(
        @StringRes val headlineRes: Int,
        val code: String,
        @StringRes val hintRes: Int,
    )

    /**
     * Maps a throwable to its copy entry. Checks run most-specific-first;
     * note SocketTimeoutException extends InterruptedIOException, so the
     * network-timeout branch must stay above the interrupted-IO branch, and
     * InvalidTransitionException extends IllegalStateException, so the
     * lifecycle branch must stay above the state-invalid branch.
     */
    @JvmStatic
    fun resolve(error: Throwable?): Entry {
        if (error == null) return Entry(
            R.string.error_unknown, CODE_UNKNOWN, R.string.error_hint_see_log)
        return when (error) {
            is java.net.SocketTimeoutException -> Entry(
                R.string.error_net_timeout, CODE_NET_TIMEOUT, R.string.error_hint_check_network)
            is java.net.ConnectException -> Entry(
                R.string.error_net_refused, CODE_NET_REFUSED, R.string.error_hint_check_mirror)
            is java.net.UnknownHostException -> Entry(
                R.string.error_net_dns, CODE_NET_DNS, R.string.error_hint_check_mirror)
            is javax.net.ssl.SSLException -> Entry(
                R.string.error_net_tls, CODE_NET_TLS, R.string.error_hint_check_clock)
            is java.io.FileNotFoundException, is java.nio.file.NoSuchFileException -> Entry(
                R.string.error_file_missing, CODE_FILE_MISSING, R.string.error_hint_retry)
            // InterruptedIOException extends IOException: it must be matched
            // before the generic IO branch (but after the socket-timeout case
            // above, which is a subclass of it).
            is java.io.InterruptedIOException, is InterruptedException -> Entry(
                R.string.error_interrupted, CODE_INTERRUPTED, R.string.error_hint_retry)
            is InstanceLaunchCoordinator.AtomicFallbackRefusedException -> when {
                error.decision.blockers().contains(
                    ExecutionPolicyContract.AtomicFallbackBlocker.OLD_PROCESS_EXIT_UNCONFIRMED,
                ) -> Entry(
                    R.string.error_uml_fallback_unconfirmed_stop,
                    CODE_UML_FALLBACK_UNCONFIRMED_STOP,
                    R.string.error_hint_uml_unconfirmed_stop,
                )
                error.decision.blockers().contains(
                    ExecutionPolicyContract.AtomicFallbackBlocker.USER_DISK_ALREADY_WRITTEN,
                ) -> Entry(
                    R.string.error_uml_fallback_dirty_disk,
                    CODE_UML_FALLBACK_DIRTY_DISK,
                    R.string.error_hint_uml_dirty_disk,
                )
                else -> Entry(
                    R.string.error_uml_fallback_blocked,
                    CODE_UML_FALLBACK_BLOCKED,
                    R.string.error_hint_export_or_upgrade,
                )
            }
            is java.io.IOException -> when {
                looksLikeDiskFull(error) -> Entry(
                    R.string.error_storage_full, CODE_STORAGE_FULL, R.string.error_hint_check_storage)
                else -> Entry(
                    R.string.error_io_generic, CODE_IO_GENERIC, R.string.error_hint_retry)
            }
            // 2026-11-08 单源化（T1）：启动路径专用族（原 LaunchFailureCopy 代码
            // 常量表）并入本表，文案落在 strings.xml 的 error_* 键。判定顺序
            // most-specific-first：InvalidTransitionException 是 IllegalStateException
            // 的子类，必须先于下方 state-invalid 分支命中。
            is InstanceLifecycle.InvalidTransitionException -> Entry(
                R.string.error_lifecycle, CODE_LIFECYCLE, R.string.error_hint_check_state)
            is AvfEngineException -> when (error.reason()) {
                AvfEngineException.PERMISSION_DENIED -> Entry(
                    R.string.error_avf_permission, CODE_AVF_PERMISSION,
                    R.string.error_hint_check_permission)
                AvfEngineException.HARDWARE_UNSUPPORTED -> Entry(
                    R.string.error_avf_unsupported, CODE_AVF_UNSUPPORTED,
                    R.string.error_hint_see_log)
                AvfEngineException.CONFIG_MISMATCH -> Entry(
                    R.string.error_avf_config, CODE_AVF_CONFIG,
                    R.string.error_hint_see_log)
                else -> Entry(
                    R.string.error_avf_generic, CODE_AVF_GENERIC, R.string.error_hint_retry)
            }
            is LaneStartException -> Entry(
                R.string.error_lane, CODE_LANE, R.string.error_hint_retry)
            is OutOfMemoryError -> Entry(
                R.string.error_oom, CODE_OOM, R.string.error_hint_retry)
            is java.util.concurrent.TimeoutException -> Entry(
                R.string.error_wait_timeout, CODE_WAIT_TIMEOUT, R.string.error_hint_retry)
            is ExecutionPolicyContract.ExecutionPolicyRefusalException -> when (error.verdict().code()) {
                ExecutionPolicyContract.PreflightCode.UNKNOWN_EXECUTION_STRATEGY -> Entry(
                    R.string.error_exec_strategy_unknown,
                    CODE_EXEC_STRATEGY_UNKNOWN,
                    R.string.error_hint_export_or_upgrade)
                ExecutionPolicyContract.PreflightCode.UNRECOGNIZED_VOLUME_FORMAT,
                ExecutionPolicyContract.PreflightCode.VOLUME_FORMAT_CONFLICT -> Entry(
                    R.string.error_volume_format_conflict,
                    CODE_VOLUME_FORMAT_CONFLICT,
                    R.string.error_hint_export_or_upgrade)
                ExecutionPolicyContract.PreflightCode.DEVICE_FLOOR_UNSUPPORTED -> Entry(
                    R.string.error_device_floor_unsupported,
                    CODE_DEVICE_FLOOR_UNSUPPORTED,
                    R.string.error_hint_device_floor)
                else -> Entry(
                    R.string.error_exec_strategy_unavailable,
                    CODE_EXEC_STRATEGY_UNAVAILABLE,
                    R.string.error_hint_see_log)
            }
            is IllegalArgumentException -> Entry(
                R.string.error_arg_invalid, CODE_ARG_INVALID, R.string.error_hint_recheck_config)
            is IllegalStateException -> Entry(
                R.string.error_state_invalid, CODE_STATE_INVALID, R.string.error_hint_check_state)
            is SecurityException -> Entry(
                R.string.error_perm_denied, CODE_PERM_DENIED, R.string.error_hint_check_permission)
            else -> Entry(
                R.string.error_unknown, CODE_UNKNOWN, R.string.error_hint_see_log)
        }
    }

    /** "headline（错误码 code）" for one-line surfaces such as status rows and toasts. */
    @JvmStatic
    fun detailMessage(context: android.content.Context, error: Throwable?): String {
        val entry = resolve(error)
        return context.getString(entry.headlineRes) +
            context.getString(R.string.error_code_suffix, entry.code)
    }

    private fun looksLikeDiskFull(error: IOException): Boolean {
        val message = error.message ?: return false
        return message.contains("No space left", ignoreCase = true) ||
            message.contains("ENOSPC", ignoreCase = true) ||
            message.contains("空间不足")
    }

    // Fixed codes: stable contract for diagnostics and cross-lane reuse.
    const val CODE_NET_TIMEOUT = "E-NET-TIMEOUT"
    const val CODE_NET_REFUSED = "E-NET-REFUSED"
    const val CODE_NET_DNS = "E-NET-DNS"
    const val CODE_NET_TLS = "E-NET-TLS"
    const val CODE_FILE_MISSING = "E-FILE-MISSING"
    const val CODE_STORAGE_FULL = "E-STORAGE-FULL"
    const val CODE_IO_GENERIC = "E-IO-GENERIC"
    const val CODE_WAIT_TIMEOUT = "E-WAIT-TIMEOUT"
    const val CODE_ARG_INVALID = "E-ARG-INVALID"
    const val CODE_STATE_INVALID = "E-STATE-INVALID"
    const val CODE_PERM_DENIED = "E-PERM-DENIED"
    const val CODE_INTERRUPTED = "E-INTERRUPTED"
    // 2026-11-08 单源化（T1）：原 LaunchFailureCopy 启动路径专用族并入，码只增不改名。
    const val CODE_LIFECYCLE = "E-LIFECYCLE"
    const val CODE_AVF_PERMISSION = "E-AVF-PERMISSION"
    const val CODE_AVF_UNSUPPORTED = "E-AVF-UNSUPPORTED"
    const val CODE_AVF_CONFIG = "E-AVF-CONFIG"
    const val CODE_AVF_GENERIC = "E-AVF-GENERIC"
    const val CODE_LANE = "E-LANE"
    const val CODE_OOM = "E-OOM"
    const val CODE_EXEC_STRATEGY_UNKNOWN = "E-EXEC-STRATEGY"
    const val CODE_VOLUME_FORMAT_CONFLICT = "E-VOL-FORMAT"
    const val CODE_EXEC_STRATEGY_UNAVAILABLE = "E-EXEC-UNAVAILABLE"
    const val CODE_DEVICE_FLOOR_UNSUPPORTED = "E-DEVICE-FLOOR"
    const val CODE_UML_FALLBACK_UNCONFIRMED_STOP = "E-UML-STOP-UNCONFIRMED"
    const val CODE_UML_FALLBACK_DIRTY_DISK = "E-UML-DISK-WRITTEN"
    const val CODE_UML_FALLBACK_BLOCKED = "E-UML-FALLBACK-BLOCKED"
    const val CODE_UNKNOWN = "E-UNKNOWN"
}
