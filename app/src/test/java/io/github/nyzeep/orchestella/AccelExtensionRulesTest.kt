package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for the two-package accelerator decision rules. The Android
 * resolver shell (PM queries, package-context asset reads) is exercised on
 * device; these tests pin the decision surface itself.
 */
class AccelExtensionRulesTest {

    @Test
    fun extensionPackageNameAppendsSuffix() {
        assertEquals(
            "io.github.nyzeep.orchestella.accel",
            AccelExtensionRules.extensionPackageName("io.github.nyzeep.orchestella"),
        )
        assertEquals(
            "io.github.nyzeep.orchestella.qa.dvpvm.accel",
            AccelExtensionRules.extensionPackageName("io.github.nyzeep.orchestella.qa.dvpvm"),
        )
    }

    @Test
    fun extensionPackageNameRejectsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            AccelExtensionRules.extensionPackageName(" ")
        }
    }

    @Test
    fun missingExtensionIsReportedHonestly() {
        val decision = AccelExtensionRules.evaluate(false, null, 503L, null)
        assertEquals(AccelExtensionRules.Status.EXTENSION_MISSING, decision.status)
        assert(decision.detail.contains("未安装")) { decision.detail }
    }

    @Test
    fun versionMismatchIsRefusedWithBothVersions() {
        val decision = AccelExtensionRules.evaluate(true, 502L, 503L, "clang")
        assertEquals(AccelExtensionRules.Status.VERSION_MISMATCH, decision.status)
        assert(decision.detail.contains("502")) { decision.detail }
        assert(decision.detail.contains("503")) { decision.detail }
    }

    @Test
    fun missingManifestIsRefusedSeparately() {
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, null)
        assertEquals(AccelExtensionRules.Status.MANIFEST_UNREADABLE, decision.status)
    }

    @Test
    fun fakeEngineIsRefusedFailClosed() {
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, "fake")
        assertEquals(AccelExtensionRules.Status.ENGINE_REJECTED, decision.status)
        assert(decision.detail.contains("fake")) { decision.detail }
    }

    @Test
    fun emptyEngineIsRefusedFailClosed() {
        // Pre-field manifest: "unknown" is not "clang".
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, "")
        assertEquals(AccelExtensionRules.Status.ENGINE_REJECTED, decision.status)
    }

    @Test
    fun readyExtensionIsAdmitted() {
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, "clang")
        assertEquals(AccelExtensionRules.Status.OK, decision.status)
    }

    /**
     * Package visibility contract (session 24).
     *
     * Android 11+ hides packages the manifest does not declare in <queries>,
     * and [android.content.pm.PackageManager.getApplicationInfo] then throws
     * NameNotFoundException — indistinguishable, on the resolver's side, from a
     * genuinely absent extension. That is exactly the 36-domain
     * `budget_rejected … ACCELERATOR_UNAVAILABLE` chain: the extension was
     * installed and correct, the query just could not see it. The declared name
     * is a placeholder because the installed extension carries this build's
     * applicationId suffix, so the manifest and the Gradle injection must agree
     * on the same name — asserted from both sides below.
     */
    @Test
    fun manifestDeclaresExtensionPackageInQueries() {
        val manifest = sourceFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        val queries = manifest.substringAfter("<queries>", "").substringBefore("</queries>", "")
        assert(queries.isNotEmpty()) { "AndroidManifest.xml must keep a <queries> block" }
        assert(queries.contains("""<package android:name="${'$'}{accelExtensionPackage}" />""")) {
            "the accelerator extension must be declared in <queries>, otherwise " +
                "PackageManager hides it and the resolver reports EXTENSION_MISSING:\n$queries"
        }
        // The placeholder only works if Gradle injects it; an unexpanded
        // ${...} would ship as a literal (and invalid) package name.
        assert(queries.contains("moe.shizuku.privileged.api")) {
            "the pre-existing Shizuku visibility declaration must stay"
        }
    }

    @Test
    fun gradleDerivesTheDeclaredNameFromTheChannelSuffix() {
        val buildScript = appModuleBuildScript()
        assert(buildScript.contains("""manifestPlaceholders["accelExtensionPackage"]""")) {
            "app/build.gradle.kts must inject accelExtensionPackage; the manifest placeholder " +
                "has no other source"
        }
        assert(buildScript.contains("fun accelExtensionPackageFor(mainApplicationIdSuffix: String?)")) {
            "the injected name must be composed from the applicationId suffix, so release, " +
                "dev and qa builds each declare their own extension name"
        }
        assert(buildScript.contains("""(mainApplicationIdSuffix ?: "") + ".accel"""")) {
            "the composed name must end in .accel — " +
                "AccelExtensionRules.extensionPackageName appends exactly that suffix"
        }
        // The release gate parses this literal to prove the defaultConfig id; a
        // variable there would silently disable that gate.
        assert(buildScript.contains("""applicationId = "io.github.nyzeep.orchestella"""")) {
            "defaultConfig must keep the literal applicationId that " +
                "tools/release/check_release_fields.py parses"
        }
        assertEquals(".accel", AccelExtensionRules.EXTENSION_SUFFIX)
    }

    /**
     * The app module's build script, resolved by content rather than by path:
     * Gradle runs unit tests with the module directory as the working
     * directory (so `build.gradle.kts` is the app script), while an IDE runner
     * may start at the repository root (so `app/build.gradle.kts` is). Reading
     * the wrong one would silently assert against the root script.
     */
    /**
     * Single-package round (2026-11-14): the resolver must resolve the
     * embedded shape first — the complete package carries the compiler
     * manifest in its OWN assets and loads the payload from its OWN APK.
     * The resolver is Android-context-bound (no JVM construction), so the
     * wiring is asserted structurally, in the same style as the manifest
     * and gradle checks above: own-assets read present, evaluated before
     * the extension query, version lock self-vs-self, payload path = the
     * application's own sourceDir, and the two-package query retained as
     * the defense path.
     */
    @Test
    fun resolverResolvesTheEmbeddedShapeBeforeTheExtensionQuery() {
        val source = sourceFile(
            "src/main/java/io/github/nyzeep/orchestella/AccelExtensionResolver.kt",
            "app/src/main/java/io/github/nyzeep/orchestella/AccelExtensionResolver.kt",
        )
        assert(source.contains("readOwnManifest()")) {
            "the resolver must consult its own assets (readOwnManifest) — the embedded " +
                "shape's signal is the presence of assets/PVM-COMPILER-MANIFEST in this app"
        }
        assert(source.contains("AccelExtensionRules.evaluate(true, mainVersionCode, mainVersionCode, engine)")) {
            "the embedded verdict must lock the version self-versus-self through the same " +
                "rules (one verdict shape, engine gate still applied)"
        }
        assert(source.contains("applicationInfo.sourceDir")) {
            "the embedded payload path must be this app's own APK (the platform's setApkPath " +
                "contract), not the extension's"
        }
        assert(source.indexOf("readOwnManifest()") < source.indexOf("extensionPackageName(application.packageName)")) {
            "the embedded resolution must run BEFORE the two-package query — a stale extension " +
                "installed next to a complete package must not win"
        }
        assert(source.contains("catch (absent: PackageManager.NameNotFoundException)")) {
            "the two-package query stays as the defense path (EXTENSION_MISSING stays reachable " +
                "for split builds)"
        }
    }

    private fun appModuleBuildScript(): String {
        val readable = listOf("build.gradle.kts", "app/build.gradle.kts")
            .map { java.io.File(it) }
            .filter { it.isFile }
            .map { it.readText() }
        val matches = readable.filter { it.contains("accelExtensionPackage") }
        if (matches.size != 1) {
            throw AssertionError(
                "expected exactly one readable build script to declare accelExtensionPackage " +
                    "(workdir=${java.io.File(".").absolutePath}, readable=${readable.size}, " +
                    "matches=${matches.size})"
            )
        }
        return matches.single()
    }

    private fun sourceFile(vararg candidates: String): String {
        for (candidate in candidates) {
            val file = java.io.File(candidate)
            if (file.isFile) return file.readText()
        }
        throw AssertionError("none of these files exist: ${candidates.joinToString()}")
    }
}
