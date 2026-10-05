package io.github.nyzeep.droidlayer

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.nyzeep.droidlayer.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.droidlayer.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.droidlayer.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.droidlayer.runtime.distro.DistroManifest
import io.github.nyzeep.droidlayer.runtime.distro.OfficialAlpineCatalog
import io.github.nyzeep.droidlayer.runtime.distro.OfficialDebianCatalog
import io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeArtifactManifest
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Paths
import java.time.Clock
import java.util.concurrent.Executors

/**
 * Minimal console: capability status, the ADR-0003 authorization wizard for
 * the hardware-virtualization upgrade, instance creation and launch. The
 * launch goes through {@link InstanceStrategyLauncher} so the hardware
 * strategy is preferred when ready and transparently degrades to the
 * software strategy with a recorded reason.
 */
class MainActivity : Activity() {
    private val operations = Executors.newSingleThreadExecutor()
    private lateinit var instanceStore: InstanceLifecycle.FileStore
    private lateinit var settingsManager: SettingsManager
    private lateinit var settingsButton: Button
    private lateinit var refusalBanner: Button
    private lateinit var createAlpineButton: Button
    private lateinit var createDebianButton: Button
    private lateinit var upgradeAuthorizationButton: Button
    private lateinit var createProgress: ProgressBar
    private lateinit var createStatus: TextView
    private lateinit var instanceList: LinearLayout
    private var avfReadiness: AvfCapabilityEvaluator.AvfReadiness? = null
    private var wizardModel: AvfAuthorizationWizardModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        val assessment = DeviceCapabilityEvaluator.evaluate(deviceProfile())
        avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
        wizardModel = AvfAuthorizationWizardModel.fromAssessment(avfReadiness!!)
        renderRuntimeStatus(assessment)
        settingsButton = findViewById(R.id.settings_button)
        refusalBanner = findViewById(R.id.refusal_banner)
        createAlpineButton = findViewById(R.id.create_alpine)
        createDebianButton = findViewById(R.id.create_debian)
        createProgress = findViewById(R.id.create_progress)
        createStatus = findViewById(R.id.create_status)
        instanceList = findViewById(R.id.instance_list)
        upgradeAuthorizationButton = findViewById(R.id.upgrade_authorization)
        instanceStore = InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"))

        settingsButton.setOnClickListener { showSettingsDialog() }
        val canCreate = assessment.canCreateFullLinuxInstance()
        setCreateButtonsEnabled(canCreate)
        if (!canCreate) {
            refusalBanner.visibility = View.VISIBLE
            refusalBanner.setOnClickListener { showRefusalDetailsDialog(assessment) }
        } else {
            refusalBanner.visibility = View.GONE
        }

        createAlpineButton.setOnClickListener { createDistro(OfficialAlpineCatalog.alpine()) }
        createDebianButton.setOnClickListener { createDistro(OfficialDebianCatalog.debian()) }
        upgradeAuthorizationButton.setOnClickListener { showAvfAuthorizationWizard() }
        refreshWizardEntry()
        showInstances()
    }

    override fun onResume() {
        super.onResume()
        if (::instanceStore.isInitialized) showInstances()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) operations.shutdown()
    }

    private fun setCreateButtonsEnabled(enabled: Boolean) {
        if (::createAlpineButton.isInitialized) createAlpineButton.isEnabled = enabled
        if (::createDebianButton.isInitialized) createDebianButton.isEnabled = enabled
    }

    /**
     * The badge is rendered only from CapabilityMatrix data (ADR-0002): the
     * hardware string once the AVF strategy is fully ready, the software
     * baseline otherwise.
     */
    private fun currentBadge(assessment: DeviceCapabilityEvaluator.Assessment): String {
        val readiness = avfReadiness
        if (assessment.canCreateFullLinuxInstance() && readiness != null &&
            readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY
        ) {
            return readiness.performanceBadge()
        }
        return assessment.performanceBadge()
    }

    private fun refreshWizardEntry() {
        val readiness = avfReadiness ?: return
        if (!::upgradeAuthorizationButton.isInitialized) return
        upgradeAuthorizationButton.visibility =
            if (readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun showAvfAuthorizationWizard() {
        wizardModel = wizardModel!!.start()
        showShizukuStep()
    }

    private fun showShizukuStep() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.wizard_title)
            .setMessage(R.string.wizard_shizuku_steps)
            .setPositiveButton(R.string.wizard_authorization_done) { _, _ -> recheckAuthorization() }
            .setNeutralButton(R.string.wizard_use_adb) { _, _ ->
                wizardModel = wizardModel!!.chooseAdbFallback()
                showAdbStep()
            }
            .setNegativeButton(R.string.wizard_later) { _, _ ->
                wizardModel = wizardModel!!.decline()
            }
            .show()
    }

    private fun showAdbStep() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.wizard_title)
            .setMessage(
                getString(
                    R.string.wizard_adb_steps,
                    AvfAuthorizationWizardModel.adbGrantCommand(packageName)))
            .setPositiveButton(R.string.wizard_authorization_done) { _, _ -> recheckAuthorization() }
            .setNeutralButton(R.string.wizard_copy_command) { _, _ ->
                copyAdbCommand()
                showAdbStep()
            }
            .setNegativeButton(R.string.wizard_back_to_shizuku) { _, _ ->
                wizardModel = wizardModel!!.backToShizuku()
                showShizukuStep()
            }
            .show()
    }

    private fun copyAdbCommand() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.wizard_copy_command),
                AvfAuthorizationWizardModel.adbGrantCommand(packageName)))
        Toast.makeText(this, R.string.wizard_command_copied, Toast.LENGTH_SHORT).show()
    }

    private fun recheckAuthorization() {
        val facts = AvfHostFactsProbe.probe(this)
        val grantedNow = facts.manageVirtualMachinePermission()
        wizardModel = wizardModel!!.confirmAuthorized(grantedNow)
        if (grantedNow) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(facts)
            refreshWizardEntry()
            rerenderRuntimeStatus()
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.wizard_authorized_success_title)
                .setMessage(R.string.wizard_authorized_success_msg)
                .setPositiveButton(R.string.credentials_close, null)
                .show()
        } else {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.wizard_vendor_failure_title)
                .setMessage(R.string.wizard_vendor_failure_steps)
                .setPositiveButton(R.string.credentials_close, null)
                .show()
        }
    }

    private fun renderRuntimeStatus(assessment: DeviceCapabilityEvaluator.Assessment) {
        findViewById<TextView>(R.id.runtime_status).text = buildString {
            appendLine(getString(R.string.runtime_class_full_linux))
            appendLine(currentBadge(assessment))
            if (assessment.canCreateFullLinuxInstance()) {
                appendLine(getString(R.string.support_floor_passed))
            } else {
                appendLine(getString(R.string.support_floor_refused, assessment.refusalReason()))
            }
            assessment.emulatorNotice()?.let(::appendLine)
            append(verifiedQemuStatus())
        }
    }

    private fun rerenderRuntimeStatus() {
        renderRuntimeStatus(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
    }

    private fun showInstances() {
        instanceList.removeAllViews()
        val instances = try {
            instanceStore.list()
        } catch (_: IOException) {
            createStatus.text = getString(R.string.create_failed, "instance metadata unavailable")
            return
        }
        if (instances.isEmpty()) {
            instanceList.addView(TextView(this).apply { text = getString(R.string.instances_empty) })
        }
        for (instance in instances) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val distroName = instance.distro().id().replaceFirstChar { it.uppercase() }
            row.addView(TextView(this).apply {
                text = getString(R.string.instance_row_distro, distroName, instance.distro().version(), instance.state().name)
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(android.widget.Button(this).apply {
                text = getString(R.string.instance_credentials)
                setOnClickListener { showCredentialsDialog(instance) }
            })
            if (instance.state().name == "STOPPED" || instance.state().name == "CREATED") {
                row.addView(android.widget.Button(this).apply {
                    text = getString(R.string.instance_launch)
                    setOnClickListener { launchInstance(instance) }
                })
            }
            instanceList.addView(row)
        }
    }

    private fun launchInstance(instance: InstanceLifecycle.Instance) {
        setCreateButtonsEnabled(false)
        createStatus.setText(R.string.launch_started)
        operations.execute {
            val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
            var running = false
            try {
                InstanceLaunchSequence.beforeLaunch(controller, instance)
                running = true
                val distroTitle = instance.distro().id().replaceFirstChar { it.uppercase() } + " " + instance.distro().version()
                val badge = currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
                settingsManager.lastActiveInstanceId = instance.id()
                DroidLayerForegroundService.start(this, instance.id(), distroTitle, badge, settingsManager.batteryPolicy)
                val rootfsFileName = when (instance.distro().id()) {
                    "debian" -> OfficialDebianCatalog.debian().rootfsFileName()
                    "alpine" -> OfficialAlpineCatalog.alpine().rootfsFileName()
                    else -> throw IllegalStateException("unsupported distro: " + instance.distro().id())
                }
                val parameters = InstanceStrategyLauncher.LaunchParameters(
                    instance.id(),
                    instance.bridgeToken(),
                    rootfsFileName,
                    instance.rootVolume().toString(),
                    filesDir.resolve("runtime").resolve(instance.id()).absolutePath,
                    filesDir.resolve("distro-inputs").absolutePath,
                    instance.guestPassword(),
                )
                val preferAvf =
                    avfReadiness?.status() == AvfCapabilityEvaluator.AvfStatus.READY
                val strategyLauncher = InstanceStrategyLauncher(
                    { params -> AvfInstanceLauncher(this).launch(params) },
                    { params -> QemuInstanceLauncher(this).launch(params) },
                    { failure ->
                        AvfDegradationLog(
                            filesDir.resolve("avf-degradations.txt").toPath(),
                        ).record(failure)
                    })
                val outcome = strategyLauncher.launch(parameters, preferAvf)
                val session = outcome.session
                Thread {
                    try {
                        session.waitFor()
                        DroidLayerForegroundService.stop(this)
                        InstanceLaunchSequence.afterGuestExit(controller, instance.id())
                        runOnUiThread {
                            createStatus.setText(R.string.launch_guest_exited)
                            setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                            showInstances()
                        }
                    } catch (ignored: InterruptedException) {
                        DroidLayerForegroundService.stop(this)
                        Thread.currentThread().interrupt()
                    } catch (failure: Exception) {
                        DroidLayerForegroundService.stop(this)
                        runOnUiThread {
                            createStatus.text = getString(R.string.launch_failed, failure.toString())
                            setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                            showInstances()
                        }
                    }
                }.apply { isDaemon = true }.start()
                runOnUiThread {
                    if (outcome.hardwareAccelerated()) {
                        createStatus.setText(R.string.launch_started_hardware)
                    } else if (outcome.degradationReason != null) {
                        createStatus.text = getString(
                            R.string.launch_degraded_to_software, outcome.degradationReason)
                    }
                    val intent = android.content.Intent(this, TerminalActivity::class.java)
                        .putExtra("bridgeToken", instance.bridgeToken())
                        .putExtra("instanceId", instance.id())
                    startActivity(intent)
                }
            } catch (failure: Exception) {
                DroidLayerForegroundService.stop(this)
                if (running) {
                    try {
                        InstanceLaunchSequence.afterGuestExit(controller, instance.id())
                    } catch (ignored: Exception) {
                        // best-effort revert of the RUNNING slot; the launch failure is reported
                    }
                }
                runOnUiThread {
                    createStatus.text = getString(R.string.launch_failed, failure.toString())
                    setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                    showInstances()
                }
            }
        }
    }

    private fun createDistro(manifest: DistroManifest) {
        val distroDisplayName = manifest.id().replaceFirstChar { it.uppercase() }
        val availableBytes = StatFs(filesDir.path).availableBytes
        if (availableBytes < DistroInstanceCreator.DEFAULT_ROOT_VOLUME_BYTES + manifest.sizeBytes() + 128L * 1024L * 1024L) {
            createStatus.setText(R.string.create_storage_low)
            return
        }
        setCreateButtonsEnabled(false)
        createProgress.visibility = View.VISIBLE
        createProgress.isIndeterminate = true
        createStatus.text = getString(R.string.create_downloading_distro, distroDisplayName)
        operations.execute {
            val result = try {
                val connection = manifest.source().toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                        connection.url.toURI() != manifest.source() ||
                        connection.contentLengthLong != manifest.sizeBytes()
                    ) throw IOException("unexpected " + distroDisplayName + " source response")
                    connection.inputStream.use { input ->
                        DistroInstanceCreator(
                            manifest,
                            InstanceLifecycle.Controller(instanceStore, Clock.systemUTC()),
                            filesDir.toPath(),
                        ).create(input, connection.contentLengthLong)
                    }
                    getString(R.string.create_success)
                } finally {
                    connection.disconnect()
                }
            } catch (rejected: DistroManifest.InputRejectedException) {
                getString(R.string.create_invalid_input_distro, distroDisplayName, rejected.reason())
            } catch (_: IOException) {
                getString(R.string.create_network_failed_distro, distroDisplayName)
            } catch (_: RuntimeException) {
                getString(R.string.create_failed, "private storage unavailable")
            }
            runOnUiThread {
                createProgress.visibility = View.GONE
                setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                createStatus.text = result
                showInstances()
            }
        }
    }

    private fun deviceProfile(): DeviceCapabilityEvaluator.DeviceProfile {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return DeviceCapabilityEvaluator.DeviceProfile(
            Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.toList(),
            memoryInfo.totalMem,
            isEmulator(),
        )
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            "google_sdk" == Build.PRODUCT

    private fun verifiedQemuStatus(): String =
        try {
            assets.open(RuntimeArtifactManifest.SCHEMA_FILE_NAME).use { input ->
                val manifest = RuntimeArtifactManifest.parse(input)
                val nativeLibraryDirectory = applicationInfo.nativeLibraryDir
                    ?: return getString(R.string.qemu_runtime_unavailable, "nativeLibraryDir unavailable")
                manifest.requireVerifiedInstalledQemu(Paths.get(nativeLibraryDirectory))
                getString(R.string.qemu_runtime_verified)
            }
        } catch (rejection: RuntimeArtifactManifest.IntegrityRejectedException) {
            getString(R.string.qemu_runtime_unavailable, rejection.reason())
        } catch (_: IOException) {
            getString(R.string.qemu_runtime_unavailable, "runtime manifest unavailable")
        }

    private fun showCredentialsDialog(instance: InstanceLifecycle.Instance) {
        val message = getString(R.string.credentials_message, instance.guestPassword())
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.credentials_title)
            .setMessage(message)
            .setPositiveButton(R.string.credentials_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GuestPassword", instance.guestPassword()))
                Toast.makeText(this, R.string.credentials_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.credentials_change) { _, _ ->
                showChangePasswordDialog(instance)
            }
            .setNegativeButton(R.string.credentials_close, null)
            .show()
    }

    private fun showChangePasswordDialog(instance: InstanceLifecycle.Instance) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.credentials_change_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
            addView(input)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.credentials_change_title)
            .setView(container)
            .setPositiveButton(R.string.credentials_change) { _, _ ->
                val newPass = input.text.toString().trim()
                if (newPass.length < 8 || newPass.contains(" ") || newPass.contains("\t") || newPass.contains("\n")) {
                    Toast.makeText(this, R.string.credentials_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                operations.execute {
                    try {
                        val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                        controller.updateGuestPassword(instance.id(), newPass)
                        runOnUiThread {
                            Toast.makeText(this, R.string.credentials_updated, Toast.LENGTH_SHORT).show()
                            showInstances()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message ?: "修改失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.credentials_close, null)
            .show()
    }

    private fun showRefusalDetailsDialog(assessment: DeviceCapabilityEvaluator.Assessment) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.refusal_dialog_title)
            .setMessage(getString(R.string.refusal_dialog_message, assessment.refusalReason()))
            .setPositiveButton(R.string.credentials_close, null)
            .show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val bootCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_boot_autostart)
            isChecked = settingsManager.isBootAutoStartEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isBootAutoStartEnabled = isChecked
            }
        }
        val bootDesc = TextView(this).apply {
            text = getString(R.string.settings_boot_autostart_desc)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(20, 0, 0, 16)
        }

        val updateCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_update_check)
            isChecked = settingsManager.isUpdateCheckEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isUpdateCheckEnabled = isChecked
            }
        }
        val updateDesc = TextView(this).apply {
            text = getString(R.string.settings_update_check_desc)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(20, 0, 0, 16)
        }

        val batteryLabel = TextView(this).apply {
            text = getString(R.string.settings_battery_policy)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(0, 8, 0, 8)
        }
        val batteryGroup = android.widget.RadioGroup(this)
        val balancedRadio = android.widget.RadioButton(this).apply {
            text = getString(R.string.settings_battery_balanced)
            isChecked = settingsManager.batteryPolicy == BatteryPolicy.BALANCED
        }
        val perfRadio = android.widget.RadioButton(this).apply {
            text = getString(R.string.settings_battery_performance)
            isChecked = settingsManager.batteryPolicy == BatteryPolicy.PERFORMANCE
        }
        batteryGroup.addView(balancedRadio)
        batteryGroup.addView(perfRadio)
        batteryGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == balancedRadio.id) {
                settingsManager.batteryPolicy = BatteryPolicy.BALANCED
            } else if (checkedId == perfRadio.id) {
                settingsManager.batteryPolicy = BatteryPolicy.PERFORMANCE
            }
        }

        val checkUpdateBtn = Button(this).apply {
            text = getString(R.string.settings_check_update_now)
            setOnClickListener { checkForUpdateManual() }
        }

        val phantomGuideBtn = Button(this).apply {
            text = getString(R.string.settings_phantom_guide)
            setOnClickListener { PhantomProcessKillerGuide.showDialog(this@MainActivity) }
        }

        container.addView(bootCheckbox)
        container.addView(bootDesc)
        container.addView(updateCheckbox)
        container.addView(updateDesc)
        container.addView(batteryLabel)
        container.addView(batteryGroup)
        container.addView(checkUpdateBtn)
        container.addView(phantomGuideBtn)

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.settings_close, null)
            .show()
    }

    private fun checkForUpdateManual() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        operations.execute {
            val checker = GitHubUpdateChecker(settingsManager)
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                "0.1.0-dev"
            }
            val result = checker.checkForUpdate(versionName)
            runOnUiThread {
                when (result.status()) {
                    GitHubUpdateChecker.Status.DISABLED -> {
                        android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.settings_update_check)
                            .setMessage(R.string.update_disabled_msg)
                            .setPositiveButton(R.string.credentials_close, null)
                            .show()
                    }
                    GitHubUpdateChecker.Status.UP_TO_DATE -> {
                        android.app.AlertDialog.Builder(this)
                            .setTitle(R.string.settings_update_check)
                            .setMessage(getString(R.string.update_up_to_date, result.latestVersion() ?: versionName))
                            .setPositiveButton(R.string.credentials_close, null)
                            .show()
                    }
                    GitHubUpdateChecker.Status.UPDATE_AVAILABLE -> {
                        android.app.AlertDialog.Builder(this)
                            .setTitle(getString(R.string.update_available_title, result.latestVersion()))
                            .setMessage(getString(R.string.update_available_msg, result.releaseNotes()))
                            .setPositiveButton(R.string.update_open_browser) { _, _ ->
                                if (result.releaseUrl() != null) {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(result.releaseUrl())
                                    )
                                    startActivity(intent)
                                }
                            }
                            .setNegativeButton(R.string.credentials_close, null)
                            .show()
                    }
                    GitHubUpdateChecker.Status.ERROR -> {
                        Toast.makeText(this, result.errorMessage() ?: "检查失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
