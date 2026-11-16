package io.github.nyzeep.orchestella

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import io.github.nyzeep.orchestella.ssh.SshManagerDialog
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.screens.config.ConfigDestination
import io.github.nyzeep.orchestella.ui.screens.config.ConfigOptions
import io.github.nyzeep.orchestella.ui.screens.config.ConfigPreferences
import io.github.nyzeep.orchestella.ui.screens.config.ConfigRoute
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreen
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreenState
import io.github.nyzeep.orchestella.ui.screens.config.SharedPreferencesConfigStore
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import io.github.nyzeep.orchestella.update.UpdateCenter
import io.github.nyzeep.orchestella.update.UpdateCoordinator
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 配置中心（UI3-04）：the settings entry is now a Compose directory page.
 *
 * <p>The old four-row XML directory and the level-1 AVF summary card are gone:
 * the AVF/QEMU/UML summary moved into the 「引擎与授权」sub-page, and every
 * former top-bar/tool entry is a catalog row. This Activity owns navigation
 * only — [ConfigScreen] emits a [ConfigRoute], and the dispatch below reuses
 * the existing Activities, dialogs and SAF adapters instead of rewriting them.
 *
 * <p>Theme parity: [ThemeApplier.prepare] keeps the AppCompat night mode in
 * sync, while [Oe3Theme] projects [ThemeStore]'s resolved palette into Compose.
 * Both are refreshed in [onResume] so a change made in the appearance sub-page
 * is visible on return.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var configPreferences: ConfigPreferences
    private lateinit var updateCenter: UpdateCenter

    private val instanceStore by lazy {
        InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"))
    }

    /** Tree import/export runs off the UI thread; shut down with the page. */
    private val dataExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "settings-data").apply { isDaemon = true }
    }

    private var pendingExportInstance: InstanceLifecycle.Instance? = null

    // Compose state. Values are re-read in onResume; nothing here is a second
    // store, only a render snapshot.
    private val themeState = mutableStateOf(ThemeCatalog.default)
    private val textScaleState = mutableStateOf(1f)
    private val optionsState = mutableStateOf(ConfigOptions())
    private val engineState = mutableStateOf("")
    private val updateState = mutableStateOf<UpdateCenter.Available?>(null)

    private val safExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val instance = pendingExportInstance ?: return@registerForActivityResult
        if (uri == null) {
            pendingExportInstance = null
            return@registerForActivityResult
        }
        dataExecutor.execute {
            try {
                val adapter = AndroidSafExportAdapter(
                    AndroidSafExportAdapter.DocumentStreamOpener {
                        requireColdBackup(instance.id())
                        contentResolver.openOutputStream(it)
                            ?: throw IOException("无法写入备份文件")
                    },
                    Clock.systemUTC(),
                )
                adapter.exportToDocumentUri(instance, uri)
                runOnUiThread {
                    Toast.makeText(this, R.string.config_export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.config_export_failed, e.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                pendingExportInstance = null
            }
        }
    }

    private val safImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        Toast.makeText(this, R.string.config_import_started, Toast.LENGTH_SHORT).show()
        dataExecutor.execute {
            try {
                val adapter = AndroidSafImportAdapter(this, Clock.systemUTC())
                val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                val imported = adapter.importArchiveToStorage(
                    uri,
                    filesDir.toPath().resolve("instances"),
                    controller,
                )
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.config_import_success, imported.id()),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.config_import_failed, e.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = findViewById<View>(R.id.settings_root_coordinator)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                statusBarInsets.left,
                statusBarInsets.top,
                statusBarInsets.right,
                navBarInsets.bottom,
            )
            insets
        }

        settingsManager = SettingsManager(this)
        configPreferences = ConfigPreferences(SharedPreferencesConfigStore(this))
        optionsState.value = configPreferences.readOptions()

        val themeStore = ThemeStore(this)
        themeState.value = themeStore.resolve()
        textScaleState.value = themeStore.textScaleFactor()
        engineState.value = engineSummary()

        updateCenter = UpdateCoordinator.center(this)
        updateState.value = updateCenter.pending(BuildConfig.VERSION_NAME)

        val compose = findViewById<ComposeView>(R.id.settings_compose_host)
        compose.setContent {
            Oe3Theme(theme = themeState.value, textScale = textScaleState.value) {
                ConfigScreen(
                    state = ConfigScreenState(
                        versionName = BuildConfig.VERSION_NAME,
                        engineSummary = engineState.value,
                        updateAvailable = updateState.value?.version(),
                        updateNote = updateState.value?.summary(),
                        options = optionsState.value,
                    ),
                    onRoute = ::navigate,
                    onOptionsChange = ::persistOptions,
                    onBack = { finish() },
                )
            }
        }

        ThemeApplier.bind(this)
    }

    override fun onResume() {
        super.onResume()
        // Appearance edits land in ThemeStore while this page is in the back
        // stack; re-read rather than recreating the activity.
        val themeStore = ThemeStore(this)
        themeState.value = themeStore.resolve()
        textScaleState.value = themeStore.textScaleFactor()
        optionsState.value = configPreferences.readOptions()
        engineState.value = engineSummary()
        updateState.value = updateCenter.pending(BuildConfig.VERSION_NAME)
    }

    override fun onDestroy() {
        super.onDestroy()
        dataExecutor.shutdown()
    }

    /** One-line engine verdict for the 引擎与授权 row (details live in its page). */
    private fun engineSummary(): String {
        val readiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
        return when (readiness.status()) {
            AvfCapabilityEvaluator.AvfStatus.READY -> getString(R.string.config_engine_ready)
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION ->
                getString(R.string.config_engine_needs_authorization)
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED ->
                getString(R.string.config_engine_mode_unsupported)
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN ->
                getString(R.string.config_engine_mode_unknown)
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED ->
                getString(R.string.config_engine_hidden_api_blocked)
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE ->
                getString(R.string.config_engine_reflection_unavailable)
            AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED ->
                getString(R.string.config_engine_unsupported)
            else -> getString(R.string.config_engine_unknown)
        }
    }

    private fun persistOptions(next: ConfigOptions) {
        val previous = optionsState.value
        optionsState.value = configPreferences.writeOptions(previous, next)
    }

    /** Exhaustive by construction: a new [ConfigDestination] breaks the build. */
    private fun navigate(route: ConfigRoute) {
        when (val destination = route.destination) {
            is ConfigDestination.SettingsSection ->
                startActivity(SettingsSubActivity.intent(this, destination.section))

            ConfigDestination.ThemeStudio -> showThemeStudioPlaceholder()
            ConfigDestination.SshManager -> SshManagerDialog.show(this)
            ConfigDestination.PortForwarding -> openPortForwarding()
            ConfigDestination.Logs -> openRuntimeLogs()
            ConfigDestination.Backup -> showBackupDialog()
            ConfigDestination.Import -> importInstance()
            ConfigDestination.Export -> chooseAndExportInstance()
            ConfigDestination.UpdateCheck -> openAbout()
            ConfigDestination.Licenses -> showLicensesDialog()
            ConfigDestination.AboutPage -> openAbout()
        }
    }

    private fun openAbout() {
        startActivity(Intent(this, AboutActivity::class.java))
    }

    private fun showThemeStudioPlaceholder() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.config_theme_studio_title)
            .setMessage(R.string.config_theme_studio_message)
            .setPositiveButton(R.string.config_dialog_ok, null)
            .show()
            .also(ThemeApplier::apply)
    }

    private fun showLicensesDialog() {
        val notice = try {
            assets.open("third-party-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: IOException) {
            getString(R.string.config_licenses_unavailable)
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            this.text = notice
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.config_licenses_title)
            .setView(scroll)
            .setPositiveButton(R.string.config_dialog_ok, null)
            .show()
            .also(ThemeApplier::apply)
    }

    private fun showBackupDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.config_backup_dialog_title)
            .setMessage(R.string.config_backup_dialog_message)
            .setItems(
                arrayOf(
                    getString(R.string.config_backup_export),
                    getString(R.string.config_backup_import),
                ),
            ) { _, which ->
                if (which == 0) chooseAndExportInstance() else importInstance()
            }
            .setNegativeButton(R.string.config_dialog_cancel, null)
            .show()
            .also(ThemeApplier::apply)
    }

    private fun importInstance() {
        safImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun chooseAndExportInstance() {
        val instances = runCatching { instanceStore.list() }.getOrDefault(emptyList())
        if (instances.isEmpty()) {
            Toast.makeText(this, R.string.config_no_instances, Toast.LENGTH_SHORT).show()
            return
        }
        if (instances.size == 1) {
            exportInstance(instances.first())
            return
        }
        val labels = instances.map { "${it.hostname()} · ${it.id()}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.config_choose_instance)
            .setItems(labels) { _, which -> exportInstance(instances[which]) }
            .setNegativeButton(R.string.config_dialog_cancel, null)
            .show()
            .also(ThemeApplier::apply)
    }

    private fun exportInstance(instance: InstanceLifecycle.Instance) {
        try {
            pendingExportInstance = requireColdBackup(instance.id())
            safExportLauncher.launch("${instance.id()}-backup.zip")
        } catch (e: IOException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    /** Same cold-backup guard as the instance action sheet (no rewrite of the rule). */
    private fun requireColdBackup(instanceId: String): InstanceLifecycle.Instance {
        val current = instanceStore.load(instanceId) ?: throw IOException("实例已不存在")
        if (current.state() == InstanceLifecycle.State.RUNNING ||
            current.state() == InstanceLifecycle.State.PROVISIONING ||
            current.state() == InstanceLifecycle.State.SUSPENDED ||
            ExecutionSessionRegistry.get(instanceId) != null ||
            InstanceLaunchGuard.isInFlight(instanceId)
        ) {
            throw IOException(getString(R.string.config_export_running))
        }
        return current
    }

    private fun openPortForwarding() {
        val instances = runCatching { instanceStore.list() }.getOrDefault(emptyList())
        val target = instances.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
            ?: instances.firstOrNull { it.id() == settingsManager.lastActiveInstanceId }
            ?: instances.firstOrNull()
        if (target == null) {
            Toast.makeText(this, R.string.config_no_instances, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val netPath = filesDir.toPath().resolve("network")
            Files.createDirectories(netPath)
            val store = FilePortForwardingStore(netPath)
            val manager = PortForwardingManager(target.id(), store, PortConflictDetector())
            PortForwardingBottomSheet(this, target.id(), manager).show()
        } catch (e: Exception) {
            Toast.makeText(this, "打开端口转发管理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openRuntimeLogs() {
        val instance = runCatching { instanceStore.list() }.getOrNull()
            ?.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
        startActivity(
            Intent(this, LogViewerActivity::class.java).apply {
                putExtra(
                    LogViewerActivity.EXTRA_KIND,
                    if (instance != null) LogViewerActivity.KIND_GUEST else LogViewerActivity.KIND_APP,
                )
                instance?.let { putExtra(LogViewerActivity.EXTRA_INSTANCE_ID, it.id()) }
            },
        )
    }
}
