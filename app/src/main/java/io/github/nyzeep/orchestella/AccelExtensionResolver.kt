package io.github.nyzeep.orchestella

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.PvmCompilerManifest

/**
 * Android shell around [AccelExtensionRules]: queries the extension package,
 * reads its compiler manifest through a package context, and folds both into
 * one [Resolution].
 *
 * Cross-package facts used here are all platform-supported, permission-free
 * reads: [PackageManager.getApplicationInfo] for presence/version, and
 * [Context.createPackageContext] with [Context.CONTEXT_IGNORE_SECURITY] for
 * the manifest asset. The extension's APK file itself is never read by this
 * process — the payload path is only carried for the virtualization service
 * (see PvmPayloadLaneStarter / FastLaneLaunchSpec.withPayloadApkPath).
 */
class AccelExtensionResolver(private val context: Context) {

    data class Resolution(
        val decision: AccelExtensionRules.Decision,
        val manifest: PvmCompilerManifest?,
        /** The extension APK's absolute path, or null when not installed. */
        val payloadApkPath: String?,
        /** The extension's versionCode, or null when not installed. */
        val extVersionCode: Long?,
    ) {
        val ok: Boolean get() = decision.status == AccelExtensionRules.Status.OK
    }

    fun resolve(): Resolution {
        val application = context.applicationContext
        val mainVersionCode = versionCodeOf(application.packageName)
        val extPackage = AccelExtensionRules.extensionPackageName(application.packageName)
        val extInfo = try {
            application.packageManager.getApplicationInfo(extPackage, 0)
        } catch (absent: PackageManager.NameNotFoundException) {
            return resolution(
                AccelExtensionRules.evaluate(false, null, mainVersionCode, null),
                manifest = null,
                payloadApkPath = null,
                extVersionCode = null,
            )
        }
        val extVersionCode = versionCodeOf(extPackage)
        val manifest = readExtensionManifest(extPackage)
        // Explicit engine() call: Kotlin would otherwise resolve `engine` to
        // the private Java field instead of the accessor method.
        val engine: String? = manifest?.engine()
        return resolution(
            AccelExtensionRules.evaluate(true, extVersionCode, mainVersionCode, engine),
            manifest,
            extInfo.sourceDir,
            extVersionCode,
        )
    }

    private fun resolution(
        decision: AccelExtensionRules.Decision,
        manifest: PvmCompilerManifest?,
        payloadApkPath: String?,
        extVersionCode: Long?,
    ) = Resolution(decision, manifest, payloadApkPath, extVersionCode)

    private fun versionCodeOf(packageName: String): Long = try {
        val info: PackageInfo = context.packageManager.getPackageInfo(packageName, 0)
        info.longVersionCode
    } catch (absent: PackageManager.NameNotFoundException) {
        // The main package is always installed when this code runs; a failure
        // here means a broken PM state, which no decision can fix.
        0L
    }

    /**
     * Reads PVM-COMPILER-MANIFEST from the extension's assets. Returns null on
     * any absence or failure — the rules then say MANIFEST_UNREADABLE, never a
     * guessed verdict.
     */
    private fun readExtensionManifest(extPackage: String): PvmCompilerManifest? = try {
        val extensionContext =
            context.createPackageContext(extPackage, Context.CONTEXT_IGNORE_SECURITY)
        extensionContext.assets.open(MANIFEST_ASSET).use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > MAX_MANIFEST_BYTES) {
                null
            } else {
                PvmCompilerManifest.parse(String(bytes, Charsets.UTF_8)).orElse(null)
            }
        }
    } catch (failure: Exception) {
        Log.i(TAG, "extension manifest unreadable: ${failure.javaClass.simpleName}")
        null
    }

    companion object {
        private const val TAG = "AccelExtension"
        private const val MANIFEST_ASSET = AndroidAcceleratorFactProvider.PVM_MANIFEST_ASSET
        private const val MAX_MANIFEST_BYTES = 64 * 1024

        /**
         * Manifest-only probe for fact sources: the parsed extension manifest
         * when the whole decision is [AccelExtensionRules.Status.OK], otherwise
         * empty so the facts collapse to "no payload compiler" (the same
         * conservative answer the rules give).
         */
        @JvmStatic
        fun admissibleExtensionManifest(context: Context): PvmCompilerManifest? {
            val resolution = AccelExtensionResolver(context).resolve()
            return if (resolution.ok) resolution.manifest else null
        }
    }
}
