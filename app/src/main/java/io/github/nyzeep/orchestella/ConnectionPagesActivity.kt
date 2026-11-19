package io.github.nyzeep.orchestella

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.screens.connections.PortForwardingPage
import io.github.nyzeep.orchestella.ui.screens.connections.SshManagerPage
import io.github.nyzeep.orchestella.ui.screens.connections.VmHardwarePage
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import java.io.IOException
import java.nio.file.Files

/**
 * Host for the connection pages reached from the legacy Activities
 * (`InstanceDetailActivity` and the settings sub-pages) that still live in the
 * View world.
 *
 * The pages themselves stay host-agnostic: they take `onBack`, and this
 * Activity passes `finish()`. The shell host passes `OeBackStack.pop()` to the
 * same parameter, which is what keeps the visible back button and the system
 * back key on one behaviour.
 *
 * VM hardware needs a result (CPU/RAM/disk/GPU), so it returns the selection
 * through `setResult`; the caller applies it with the existing ViewModel.
 */
class ConnectionPagesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE = "extra_connections_page"
        const val EXTRA_INSTANCE_ID = "extra_instance_id"
        const val EXTRA_DEFAULT_HOST = "extra_default_host"
        const val EXTRA_DEFAULT_PORT = "extra_default_port"
        const val EXTRA_DEFAULT_USER = "extra_default_user"

        const val PAGE_SSH = "ssh"
        const val PAGE_PORT_FORWARDING = "port_forwarding"
        const val PAGE_VM_HARDWARE = "vm_hardware"

        const val RESULT_CPU_CORES = "result_cpu_cores"
        const val RESULT_RAM_MEGABYTES = "result_ram_megabytes"
        const val RESULT_DISK_BYTES = "result_disk_bytes"
        const val RESULT_GPU_ENABLED = "result_gpu_enabled"

        fun sshIntent(
            context: Context,
            defaultInstanceId: String? = null,
            defaultHost: String = "127.0.0.1",
            defaultPort: Int = 2222,
            defaultUser: String = "root",
        ): Intent = Intent(context, ConnectionPagesActivity::class.java).apply {
            putExtra(EXTRA_PAGE, PAGE_SSH)
            putExtra(EXTRA_INSTANCE_ID, defaultInstanceId)
            putExtra(EXTRA_DEFAULT_HOST, defaultHost)
            putExtra(EXTRA_DEFAULT_PORT, defaultPort)
            putExtra(EXTRA_DEFAULT_USER, defaultUser)
        }

        fun portForwardingIntent(context: Context, instanceId: String): Intent =
            Intent(context, ConnectionPagesActivity::class.java).apply {
                putExtra(EXTRA_PAGE, PAGE_PORT_FORWARDING)
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }

        fun vmHardwareIntent(context: Context, instanceId: String): Intent =
            Intent(context, ConnectionPagesActivity::class.java).apply {
                putExtra(EXTRA_PAGE, PAGE_VM_HARDWARE)
                putExtra(EXTRA_INSTANCE_ID, instanceId)
            }
    }

    private val themeState = mutableStateOf(ThemeCatalog.default)
    private val pureBlackState = mutableStateOf(false)
    private val textScaleState = mutableStateOf(1f)
    private var reduceMotion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val page = intent.getStringExtra(EXTRA_PAGE)
        if (page == null) {
            finish()
            return
        }
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)

        val themeStore = ThemeStore(this)
        val initialTheme = themeStore.resolve()
        val initialPureBlack = initialTheme.isDark && themeStore.uiPureBlack()
        themeState.value = initialTheme
        pureBlackState.value = initialPureBlack
        textScaleState.value = themeStore.textScaleFactor()
        reduceMotion = !ValueAnimator.areAnimatorsEnabled()

        val portForwardingManager = if (page == PAGE_PORT_FORWARDING) {
            instanceId?.let { id ->
                try {
                    PortForwardingManager.forInstance(id, filesDir.toPath().resolve("network"))
                } catch (_: IOException) {
                    Toast.makeText(
                        this,
                        R.string.toast_port_forwarding_open_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                    null
                }
            }
        } else {
            null
        }

        val hardwareInstance = if (page == PAGE_VM_HARDWARE) {
            instanceId?.let { id ->
                try {
                    InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances")).load(id)
                } catch (_: IOException) {
                    Toast.makeText(this, R.string.connections_vm_instance_missing, Toast.LENGTH_SHORT).show()
                    finish()
                    null
                }
            }
        } else {
            null
        }
        val hardwareProfile = if (page == PAGE_VM_HARDWARE) {
            HostHardwareDetector.detect(this)
        } else {
            null
        }
        // G-07：实例运行中时带实时 GPU 决策（G-06 状态源，进程内注册表直读，
        // 无磁盘 IO）；无会话为 null，副文案按能力声明单源给静态文案。
        val gpuAccelerationStatus = if (page == PAGE_VM_HARDWARE) {
            instanceId?.let { ExecutionSessionRegistry.get(it) }?.gpuAccelerationStatus()
        } else {
            null
        }

        if (page == PAGE_PORT_FORWARDING && (instanceId == null || portForwardingManager == null)) {
            finish()
            return
        }
        if (page == PAGE_VM_HARDWARE && (hardwareInstance == null || hardwareProfile == null)) {
            finish()
            return
        }

        val back: () -> Unit = { closePage() }
        val compose = ComposeView(this).apply {
            setBackgroundColor(
                if (initialPureBlack) Color.BLACK else initialTheme.background,
            )
        }
        setContentView(compose)
        compose.setContent {
            Oe3Theme(
                theme = themeState.value,
                pureBlack = pureBlackState.value,
                textScale = textScaleState.value,
                reduceMotion = reduceMotion,
            ) {
                val isDark = themeState.value.isDark
                DisposableEffect(isDark) {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = !isDark
                    controller.isAppearanceLightNavigationBars = !isDark
                    onDispose {}
                }
                // 本宿主没有底部坞：页面读的底部预留必须归零，否则会空出 83dp
                // 的坞位；系统导航条的内边距由这一层统一补（页面自身不再各自
                // 调 navigationBarsPadding，避免两套口径）。
                CompositionLocalProvider(LocalOe3PageBottomPadding provides 0.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ComposeColor(Oe3ThemeValues.colors.background))
                            .navigationBarsPadding(),
                    ) {
                        when (page) {
                            PAGE_SSH -> SshManagerPage(
                                onBack = back,
                                defaultInstanceId = instanceId,
                                defaultHost = intent.getStringExtra(EXTRA_DEFAULT_HOST) ?: "127.0.0.1",
                                defaultPort = intent.getIntExtra(EXTRA_DEFAULT_PORT, 2222),
                                defaultUser = intent.getStringExtra(EXTRA_DEFAULT_USER) ?: "root",
                            )

                            PAGE_PORT_FORWARDING -> PortForwardingPage(
                                instanceId = requireNotNull(instanceId),
                                manager = requireNotNull(portForwardingManager),
                                onBack = back,
                            )

                            PAGE_VM_HARDWARE -> VmHardwarePage(
                                instance = requireNotNull(hardwareInstance),
                                hostProfile = requireNotNull(hardwareProfile),
                                onBack = back,
                                gpuAccelerationStatus = gpuAccelerationStatus,
                                onSaved = { selection ->
                                    setResult(
                                        RESULT_OK,
                                        Intent().apply {
                                            putExtra(RESULT_CPU_CORES, selection.cpuCores)
                                            putExtra(RESULT_RAM_MEGABYTES, selection.ramMegabytes)
                                            selection.expandedDiskBytes?.let {
                                                putExtra(RESULT_DISK_BYTES, it)
                                            }
                                            putExtra(RESULT_GPU_ENABLED, selection.gpuAccelerationEnabled)
                                        },
                                    )
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val themeStore = ThemeStore(this)
        val resolvedTheme = themeStore.resolve()
        themeState.value = resolvedTheme
        pureBlackState.value = resolvedTheme.isDark && themeStore.uiPureBlack()
        textScaleState.value = themeStore.textScaleFactor()
        reduceMotion = !ValueAnimator.areAnimatorsEnabled()
    }

    /** Single back action for the page's visible button (system back also finishes). */
    internal fun closePage() {
        finish()
    }
}
