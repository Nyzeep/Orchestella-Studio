package io.github.nyzeep.orchestella.ui.acceptance

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.robolectric.Shadows
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts
import io.github.nyzeep.orchestella.ui.screens.config.ConfigOptions
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreen
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreenState
import io.github.nyzeep.orchestella.ui.screens.config.ConfigStore
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardActions
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardCpuUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditState
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditStore
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardInstanceUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardRamUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardRecentUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardScreen
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardStatus
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardStorageUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardUiState
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosActions
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosScreen
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosUiState
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareActions
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareBatteryFacts
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareDeviceFacts
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareEngineFacts
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareScreen
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareUiState
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceGroup
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceGroupUi
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceRowUi
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceStatus
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesActions
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesScreen
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesUiState
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardFacts
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Self-contained support utilities for the R1–R5 E2E / Acceptance suite.
 * Uses only public APIs from `app/src/main` so `ui/acceptance/` has zero
 * coupling to existing M1–M3 test files.
 */
object AcceptanceTestSupport {

    private const val GIB = 1024L * 1024L * 1024L
    private const val MIB = 1024L * 1024L

    // -------------------------------------------------------------------------
    // In-memory stores
    // -------------------------------------------------------------------------

    class InMemorySharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()
        private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.let { LinkedHashSet(it) } ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = EditorImpl()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners.add(listener)
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
            if (listener != null) listeners.remove(listener)
        }

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) {
                    if (value == null) removals.add(key) else pending[key] = value
                }
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) {
                    if (values == null) removals.add(key) else pending[key] = LinkedHashSet(values)
                }
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) {
                    pending.remove(key)
                    removals.add(key)
                }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
                pending.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                val changed = applyChanges()
                changed.forEach { key ->
                    listeners.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
                }
                return true
            }

            override fun apply() {
                commit()
            }

            private fun applyChanges(): List<String> {
                val changed = mutableListOf<String>()
                if (clearAll) {
                    changed.addAll(data.keys)
                    data.clear()
                }
                removals.forEach { key ->
                    if (data.containsKey(key)) {
                        data.remove(key)
                        changed.add(key)
                    }
                }
                pending.forEach { (key, value) ->
                    if (data[key] != value) {
                        data[key] = value
                        changed.add(key)
                    }
                }
                return changed.distinct()
            }
        }
    }

    class InMemoryConfigStore : ConfigStore {
        private val strings = LinkedHashMap<String, String>()
        private val booleans = LinkedHashMap<String, Boolean>()

        override fun getString(key: String): String? = strings[key]
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = booleans[key] ?: defaultValue
        override fun putString(key: String, value: String) {
            strings[key] = value
        }

        override fun putBoolean(key: String, value: Boolean) {
            booleans[key] = value
        }
    }

    class InMemoryDashboardEditStore(
        initial: Map<String, String> = emptyMap(),
    ) : DashboardEditStore {
        private val values = LinkedHashMap(initial)
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        fun snapshot(): Map<String, String> = LinkedHashMap(values)
    }

    // -------------------------------------------------------------------------
    // Repo & Python token-gate runner
    // -------------------------------------------------------------------------

    fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val candidates = listOf(
            cwd,
            File(cwd, "Orchestella_dev"),
            cwd.parentFile,
        ).filterNotNull()
        return candidates.firstOrNull { dir ->
            File(dir, "app/src/main/AndroidManifest.xml").isFile
        } ?: error("Cannot locate Orchestella_dev root from ${cwd.absolutePath}")
    }

    fun appMainDir(): File = File(projectRoot(), "app/src/main")

    fun uiSourceRoot(): File = File(appMainDir(), "java/io/github/nyzeep/orchestella/ui")

    data class CheckTokensResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    fun runCheckTokens(
        kotlinRoot: File = uiSourceRoot(),
        tokensFile: File = File(projectRoot(), "docs/design-system/tokens/oe3.tokens.json"),
    ): CheckTokensResult {
        val root = projectRoot()
        val script = File(root, "docs/design-system/tools/check_tokens.py")
        require(script.isFile) { "Missing check_tokens.py at ${script.absolutePath}" }

        val executables = listOf(
            listOf("python"),
            listOf("py", "-3"),
            listOf("python3"),
        )
        var lastError: Throwable? = null
        for (exe in executables) {
            try {
                val cmd = exe + listOf(
                    script.absolutePath,
                    "--kotlin-root",
                    kotlinRoot.absolutePath,
                    "--tokens",
                    tokensFile.absolutePath,
                )
                val pb = ProcessBuilder(cmd)
                pb.directory(root)
                pb.environment()["PYTHONIOENCODING"] = "utf-8"
                val proc = pb.start()
                val stdout = proc.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val stderr = proc.errorStream.bufferedReader(Charsets.UTF_8).readText()
                val code = proc.waitFor()
                return CheckTokensResult(exitCode = code, stdout = stdout, stderr = stderr)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("No Python interpreter available to run check_tokens.py", lastError)
    }

    // -------------------------------------------------------------------------
    // WCAG Luminance, Contrast & Synchronous Robolectric Pixel Sampling
    // -------------------------------------------------------------------------

    fun wcagLuminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val c = channel / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linear((argb ushr 16) and 0xFF)
        val g = linear((argb ushr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun wcagContrast(firstArgb: Int, secondArgb: Int): Double {
        val l1 = wcagLuminance(firstArgb)
        val l2 = wcagLuminance(secondArgb)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    fun perceptualTone(argb: Int): Double {
        val luminance = wcagLuminance(argb)
        return if (luminance <= 216.0 / 24389.0) {
            luminance * (24389.0 / 27.0)
        } else {
            116.0 * Math.cbrt(luminance) - 16.0
        }
    }

    fun captureRootBitmap(compose: AndroidComposeTestRule<*, out ComponentActivity>): Bitmap {
        var result: Bitmap? = null
        var copyStatus = -1
        compose.runOnIdle {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            val host = checkNotNull(content.getChildAt(0))
            val loc = IntArray(2)
            host.getLocationInWindow(loc)
            val srcRect = android.graphics.Rect(
                loc[0],
                loc[1],
                loc[0] + host.width,
                loc[1] + host.height,
            )
            val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                compose.activity.window,
                srcRect,
                bitmap,
                { status ->
                    copyStatus = status
                    result = bitmap
                },
                Handler(Looper.getMainLooper()),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        check(copyStatus == PixelCopy.SUCCESS && result != null) {
            "PixelCopy failed with status=$copyStatus"
        }
        return checkNotNull(result)
    }

    fun captureWindowContentBitmap(
        compose: AndroidComposeTestRule<*, out ComponentActivity>,
    ): Bitmap {
        val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val loc = IntArray(2)
        content.getLocationInWindow(loc)
        val srcRect = android.graphics.Rect(
            loc[0],
            loc[1],
            loc[0] + content.width,
            loc[1] + content.height,
        )
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        var copyStatus = -1
        val latch = java.util.concurrent.CountDownLatch(1)
        val thread = android.os.HandlerThread("oe3-pixelcopy").apply { start() }
        try {
            PixelCopy.request(
                compose.activity.window,
                srcRect,
                bitmap,
                { status ->
                    copyStatus = status
                    latch.countDown()
                },
                Handler(thread.looper),
            )
            check(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "Timed out waiting for PixelCopy" }
        } finally {
            thread.quitSafely()
        }
        check(copyStatus == PixelCopy.SUCCESS) {
            "PixelCopy failed with status=$copyStatus"
        }
        return bitmap
    }

    fun sampleAverageColor(bitmap: Bitmap, xRange: IntRange, yRange: IntRange): Color {
        val xs = xRange.first.coerceIn(0, bitmap.width - 1)..xRange.last.coerceIn(0, bitmap.width - 1)
        val ys = yRange.first.coerceIn(0, bitmap.height - 1)..yRange.last.coerceIn(0, bitmap.height - 1)
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var count = 0
        for (y in ys) {
            for (x in xs) {
                val px = bitmap.getPixel(x, y)
                rSum += ((px ushr 16) and 0xFF) / 255.0
                gSum += ((px ushr 8) and 0xFF) / 255.0
                bSum += (px and 0xFF) / 255.0
                count++
            }
        }
        require(count > 0) { "Empty sample region ($xs x $ys) in ${bitmap.width}x${bitmap.height}" }
        return Color(
            red = (rSum / count).toFloat(),
            green = (gSum / count).toFloat(),
            blue = (bSum / count).toFloat(),
            alpha = 1f,
        )
    }

    fun colorDistance(a: Color, b: Color): Double {
        val dr = (a.red - b.red) * 255.0
        val dg = (a.green - b.green) * 255.0
        val db = (a.blue - b.blue) * 255.0
        return sqrt(dr * dr + dg * dg + db * db)
    }

    fun luminanceDelta(a: Color, b: Color): Double =
        abs(wcagLuminance(a.toArgbCompat()) - wcagLuminance(b.toArgbCompat()))

    private fun Color.toArgbCompat(): Int {
        val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    // -------------------------------------------------------------------------
    // Geometry, Clock & Touch-Target Assertions
    // -------------------------------------------------------------------------

    fun advanceComposeFrames(compose: ComposeTestRule, frames: Int = 70) {
        repeat(frames) {
            compose.mainClock.advanceTimeByFrame()
        }
    }

    fun assertTouchTargetAtLeast48Dp(
        node: SemanticsNodeInteraction,
        density: Density,
        label: String,
    ) {
        val floor = with(density) { 48.dp.toPx() } - 0.5f
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        assertTrue(
            "$label touch width ${bounds.width}px < 48dp (${floor}px)",
            bounds.width >= floor,
        )
        assertTrue(
            "$label touch height ${bounds.height}px < 48dp (${floor}px)",
            bounds.height >= floor,
        )
    }

    fun assertControlLayoutAtLeast48Dp(
        node: SemanticsNodeInteraction,
        density: Density,
        label: String,
    ) {
        val floor = with(density) { 48.dp.toPx() } - 0.5f
        val bounds = node.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$label must occupy at least 48dp × 48dp in layout, measured ${bounds.width}px × ${bounds.height}px",
            bounds.width >= floor && bounds.height >= floor,
        )
    }

    // -------------------------------------------------------------------------
    // Screen & Model Fixture Builders (src/main public APIs only)
    // -------------------------------------------------------------------------

    fun sampleDashboardState(): DashboardUiState = DashboardUiState(
        instanceCount = 2,
        runningCount = 1,
        primary = DashboardInstanceUi(
            id = "inst-ubuntu-01",
            distroId = "ubuntu",
            name = "Ubuntu 24.04",
            status = DashboardStatus.Running,
            engineLabel = "AVF",
            cpuCores = 4,
            ramMb = 4096,
            diskGb = 16,
            canOpenTerminal = true,
        ),
        storage = DashboardStorageUi(
            totalBytes = 128L * GIB,
            usedBytes = 48L * GIB,
            fraction = 48f / 128f,
            percent = 38,
        ),
        cpu = DashboardCpuUi(
            hostCores = 8,
            instanceCores = 4,
            fraction = 0.5f,
            enabled = true,
        ),
        ram = DashboardRamUi(
            usedBytes = 4L * GIB,
            totalBytes = 8L * GIB,
            fraction = 0.5f,
            enabled = true,
        ),
        recent = listOf(
            DashboardRecentUi(
                id = "inst-debian-02",
                distroId = "debian",
                name = "Debian 13",
                status = DashboardStatus.Stopped,
            ),
        ),
    )

    fun sampleInstancesState(
        engineLabel: String = "AVF 虚拟化",
    ): InstancesUiState {
        val running = InstanceRowUi(
            id = "inst-ubuntu-01",
            shortId = "u2404a1b",
            distroId = "ubuntu",
            name = "Ubuntu 24.04",
            status = InstanceStatus.Running,
            engineLabel = engineLabel,
            specsText = "4 vCPU · 4 GB · 16 GB",
            ipAddress = "10.0.2.15",
            canOpenTerminal = true,
            canLaunch = false,
            canLaunchDesktop = true,
            canPowerOff = true,
            canShowLogs = true,
            canExport = false,
            canDelete = true,
        )
        val stopped = InstanceRowUi(
            id = "inst-debian-02",
            shortId = "deb13c2d",
            distroId = "debian",
            name = "Debian 13",
            status = InstanceStatus.Stopped,
            engineLabel = engineLabel,
            specsText = "2 vCPU · 2 GB · 8 GB",
            ipAddress = null,
            canOpenTerminal = false,
            canLaunch = true,
            canLaunchDesktop = false,
            canPowerOff = false,
            canShowLogs = true,
            canExport = true,
            canDelete = true,
        )
        val crashed = InstanceRowUi(
            id = "inst-fedora-03",
            shortId = "fed43e4f",
            distroId = "fedora",
            name = "Fedora 43",
            status = InstanceStatus.Crashed,
            engineLabel = engineLabel,
            specsText = "2 vCPU · 2 GB · 8 GB",
            ipAddress = null,
            canOpenTerminal = false,
            canLaunch = true,
            canLaunchDesktop = false,
            canPowerOff = true,
            canShowLogs = true,
            canExport = true,
            canDelete = true,
        )
        return InstancesUiState(
            groups = listOf(
                InstanceGroupUi(InstanceGroup.Running, listOf(running)),
                InstanceGroupUi(InstanceGroup.Stopped, listOf(stopped)),
                InstanceGroupUi(InstanceGroup.Error, listOf(crashed)),
            ),
            totalCount = 3,
            runningCount = 1,
        )
    }

    fun sampleHardwareState(): HardwareUiState.Ready = HardwareUiState.from(
        device = HardwareDeviceFacts(
            alias = "Pixel Test",
            model = "Pixel 9 Pro",
            androidRelease = "15",
            sdkInt = 35,
            abis = listOf("arm64-v8a"),
            socModel = "Tensor G4",
            kernelVersion = "6.6.30-android15",
            screenSpec = "1080×2400",
            gpuRenderer = "Mali-G715",
            selinuxStatus = "Enforcing",
        ),
        hardware = HostHardwareProfile(8, 12L * GIB, 6L * GIB, 512L * MIB, false),
        storage = StorageFacts(totalBytes = 128L * GIB, freeBytes = 80L * GIB),
        engines = HardwareEngineFacts(
            avfStatus = AvfCapabilityEvaluator.AvfStatus.READY,
            avfReasons = listOf("AVF 预检通过"),
            avfModeSummary = "Protected / Non-Protected VM",
            qemuAvailable = true,
            umlRunningWidth = 1,
            umlChannelWired = true,
        ),
        battery = HardwareBatteryFacts(percent = 85, temperatureCelsius = 31.5f),
    )

    fun missingSensorsHardwareState(): HardwareUiState.Ready = HardwareUiState.from(
        device = HardwareDeviceFacts(
            alias = null,
            model = "Generic Device",
            androidRelease = "",
            sdkInt = 0,
            abis = emptyList(),
            socModel = null,
            kernelVersion = null,
            screenSpec = null,
            gpuRenderer = null,
            selinuxStatus = null,
        ),
        hardware = HostHardwareProfile(4, 0L, 0L, 0L, false),
        storage = StorageFacts.None,
        engines = HardwareEngineFacts(
            avfStatus = AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN,
            qemuAvailable = false,
        ),
        battery = HardwareBatteryFacts(percent = null, temperatureCelsius = null),
    )

    fun sampleDistrosState(): DistrosUiState =
        DistrosUiState.from(catalog = DistroCatalogRegistry.getInstance())

    fun sampleConfigState(options: ConfigOptions = ConfigOptions()): ConfigScreenState =
        ConfigScreenState(
            versionName = "0.6.3",
            engineSummary = "AVF 已就绪 · QEMU 可用",
            options = options,
        )

    fun samplePages(
        dashboardState: DashboardUiState = sampleDashboardState(),
        dashboardActions: DashboardActions = DashboardActions(),
        dashboardEdit: DashboardEditState = DashboardEditState(store = null),
        instancesState: InstancesUiState = sampleInstancesState(),
        instancesActions: InstancesActions = InstancesActions(),
        hardwareState: HardwareUiState = sampleHardwareState(),
        hardwareActions: HardwareActions = HardwareActions(),
        distrosState: DistrosUiState = sampleDistrosState(),
        distrosActions: DistrosActions = DistrosActions(),
        configState: ConfigScreenState = sampleConfigState(),
        onConfigRoute: (io.github.nyzeep.orchestella.ui.screens.config.ConfigRoute) -> Unit = {},
        onConfigOptionsChange: (ConfigOptions) -> Unit = {},
    ): Oe3Pages = Oe3Pages(
        workstation = {
            DashboardScreen(
                state = dashboardState,
                actions = dashboardActions,
                edit = dashboardEdit,
            )
        },
        instances = { InstancesScreen(state = instancesState, actions = instancesActions) },
        hardware = { HardwareScreen(state = hardwareState, actions = hardwareActions) },
        distros = { DistrosScreen(state = distrosState, actions = distrosActions) },
        config = {
            ConfigScreen(
                state = configState,
                onRoute = onConfigRoute,
                onOptionsChange = onConfigOptionsChange,
            )
        },
    )

    fun sampleWizardModel(
        distroId: String = "ubuntu",
        totalRamBytes: Long = 12L * GIB,
        availRamBytes: Long = 6L * GIB,
    ): InstanceCreationWizardModel {
        val manifest = DistroCatalogRegistry.getInstance().get(distroId)
        val profile = HostHardwareProfile(8, totalRamBytes, availRamBytes, 512L * MIB, false)
        return InstanceCreationWizardModel(
            distroId = manifest?.id() ?: distroId,
            distroVersion = manifest?.version() ?: "24.04",
            hardwareProfile = profile,
        )
    }

    fun sampleWizardFacts(
        availableBytes: Long = 64L * GIB,
        existingCommittedBytes: Long = 0L,
        diskInputInvalid: Boolean = false,
        storageCheckPending: Boolean = false,
    ): WizardFacts = WizardFacts(
        availableBytes = availableBytes,
        existingCommittedBytes = existingCommittedBytes,
        diskInputInvalid = diskInputInvalid,
        storageCheckPending = storageCheckPending,
    )
}
