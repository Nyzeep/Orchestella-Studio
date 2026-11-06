package io.github.nyzeep.orchestella.startup

import android.content.Context
import io.github.nyzeep.orchestella.PackagedAssetStreams
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest
import java.io.IOException
import java.nio.file.Paths
import java.util.concurrent.Executor

/**
 * QEMU 运行时完整性校验的进程级缓存与后台执行（冷启动主线程卫生）。
 *
 * <p>冷启动审计（2026-11-07）：原 [MainActivity] 的 {@code verifiedQemuStatus()} 在
 * onCreate 渲染系统状态时于<b>主线程</b>同步打开 APK ZipFile，并对
 * {@code libdroidlayer_qemu.so} 全量 SHA-256 —— 这是冷启动最大的单点 IO（数十 MB
 * 哈希）。本类把计算派发到调用方 executor，结果进程级记忆化；首个渲染先出无该行
 * 的状态文本，校验完成后回调补齐。
 *
 * <p>记忆化语义：校验输入（APK 与 native 库）在进程存活期内不变，缓存不改变判定
 * 结果；进程重启（含应用更新）自然失效重算。文案仍由调用方用既有字符串资源渲染，
 * 本类只产出判定，不含 UI 文案（JVM 可测）。
 */
object RuntimeVerification {

    /** 校验判定：与原 verifiedQemuStatus 的两条返回路径一一对应。 */
    sealed interface Result {
        /** 清单解析通过且 libdroidlayer_qemu.so 哈希匹配。 */
        data object Verified : Result

        /** 校验拒绝；[reason] 是清单的稳定、非机密原因码（进日志/副文本）。 */
        data class Unavailable(val reason: String) : Result
    }

    @Volatile
    private var cached: Result? = null

    /** 已完成的校验结果；未开始或进行中返回 null。 */
    fun cached(): Result? = cached

    /**
     * 取校验结果：命中缓存同步回调；未命中则把 [compute] 派发到 [executor]，完成后
     * 回调一次。并发/重复请求共享同一次计算，不会重复哈希。
     *
     * <p>回调在 [executor] 线程执行；需要触碰 UI 的调用方自行 post 回主线程。
     */
    fun verifyAsync(compute: () -> Result, executor: Executor, callback: (Result) -> Unit) {
        cached?.let { existing ->
            callback(existing)
            return
        }
        executor.execute {
            if (cached == null) {
                cached = compute()
            }
            callback(cached!!)
        }
    }

    /** JVM 测试用：清空进程级缓存。生产代码不得调用。 */
    fun resetForTest() {
        cached = null
    }

    /**
     * 生产 compute：打开 APK 资产清单并校验 QEMU so 完整性。含 Android 依赖，
     * 生产路径唯一；异常面与原实现一致（完整性拒绝→原因码，IO 失败→固定文案码）。
     */
    fun computeProduction(context: Context): Result {
        return try {
            PackagedAssetStreams.openAsset(context, RuntimeArtifactManifest.SCHEMA_FILE_NAME).use { input ->
                val manifest = RuntimeArtifactManifest.parse(input)
                val nativeLibraryDirectory = context.applicationInfo.nativeLibraryDir
                    ?: return Result.Unavailable("nativeLibraryDir unavailable")
                manifest.requireVerifiedInstalledQemu(Paths.get(nativeLibraryDirectory))
                Result.Verified
            }
        } catch (rejection: RuntimeArtifactManifest.IntegrityRejectedException) {
            Result.Unavailable(rejection.reason())
        } catch (_: IOException) {
            Result.Unavailable("runtime manifest unavailable")
        }
    }
}
