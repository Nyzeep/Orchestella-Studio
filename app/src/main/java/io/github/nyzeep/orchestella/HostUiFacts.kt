package io.github.nyzeep.orchestella

import android.app.Activity
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 硬件页（UI3-07）事实注入的真实读取源（UI3-12 接线）。
 *
 * 三项都是「读得到才给值」：任何一项读不到返回 null，页面按工单口径显示
 * 占位符「—」，绝不编造。屏幕几何来自窗口管理器的真实度量，GPU 渲染器
 * 来自一次性离屏 EGL 上下文的 GL_RENDERER，SELinux 状态来自内核公开的
 * `/sys/fs/selinux/enforce`。不含设备唯一标识（序列号、IMEI、Android ID 等）。
 */
object HostUiFacts {

    /** 硬件页注入用快照；一次读取，Compose 侧经状态持有。 */
    data class Facts(
        val screenSpec: String? = null,
        val gpuRenderer: String? = null,
        val selinuxStatus: String? = null,
    )

    fun empty(): Facts = Facts()

    /** 一次读齐三项；在后台线程调用（GPU 读取会建离屏 EGL 上下文）。 */
    fun probe(activity: Activity): Facts = Facts(
        screenSpec = screenSpec(activity),
        gpuRenderer = gpuRenderer(),
        selinuxStatus = selinuxStatus(),
    )

    /** 物理屏幕几何 `宽x高 · dpi`；API < 30 走 deprecated getRealMetrics 旧缝。 */
    fun screenSpec(activity: Activity): String? = try {
        val windowManager = activity.getSystemService(WindowManager::class.java)
            ?: activity.window?.windowManager
        if (windowManager == null) {
            null
        } else {
            val dpi = activity.resources.displayMetrics.densityDpi
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = windowManager.currentWindowMetrics.bounds
                "${bounds.width()}x${bounds.height()} · ${dpi}dpi"
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                if (display == null) {
                    null
                } else {
                    @Suppress("DEPRECATION")
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(metrics)
                    "${metrics.widthPixels}x${metrics.heightPixels} · ${dpi}dpi"
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * GPU 渲染器字符串：建 1x1 pbuffer 的 ES2 离屏上下文读 GL_RENDERER，
     * 用完即弃。调用方须在非主线程；任何失败（无 GL、模拟器无渲染器）返回
     * null，不猜型号。
     */
    fun gpuRenderer(): String? = try {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) {
            null
        } else if (!EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) {
            null
        } else {
            val configAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val chosen = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, count, 0)
            val config = if (chosen && count[0] > 0) configs[0] else null
            if (config == null) {
                null
            } else {
                val surface = EGL14.eglCreatePbufferSurface(
                    display, config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
                )
                val context = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
                )
                val usable = surface != null && surface != EGL14.EGL_NO_SURFACE &&
                    context != null && context != EGL14.EGL_NO_CONTEXT
                val renderer = if (usable && EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    GLES20.glGetString(GLES20.GL_RENDERER)
                } else {
                    null
                }
                if (context != null && context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    EGL14.eglDestroyContext(display, context)
                }
                if (surface != null && surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface)
                }
                // 不调用 eglTerminate：默认 display 是进程级共享的，终结会砸掉
                // 其他线程已有的 EGL 状态；只释放本线程。
                EGL14.eglReleaseThread()
                renderer?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** 内核 SELinux 强制状态；enforce 节点不存在（未启用）时返回 null。 */
    fun selinuxStatus(): String? = try {
        val enforce = Files.readAllBytes(Paths.get("/sys/fs/selinux/enforce"))
        when (enforce.getOrNull(0)?.toInt()) {
            1 -> "Enforcing"
            0 -> "Permissive"
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
