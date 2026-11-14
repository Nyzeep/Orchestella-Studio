import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Optional QEMU payload roots (Ticket 26 C gate): all three Gradle properties
// must be provided together, as ordinary existing directories, or configuration
// fails. Channels are deliberate: the manifest, kernel Image and GPL records
// ship as assets (byte-exact; aapt only mangles *.gz names there, and these are
// read via AssetManager at runtime), the initramfs ships as a java resource
// (assets would let aapt decompress and rename *.gz files; read via the
// classloader), and the native library ships via jniLibs. When absent the APK
// builds without the payload exactly as before.
val qemuNativeLibDir: String? = (properties["orchestella.qemu.nativeLibDir"] ?: properties["droidlayer.qemu.nativeLibDir"]) as String?
val qemuAssetsDir: String? = (properties["orchestella.qemu.assetsDir"] ?: properties["droidlayer.qemu.assetsDir"]) as String?
val qemuInitramfsDir: String? = (properties["orchestella.qemu.initramfsDir"] ?: properties["droidlayer.qemu.initramfsDir"]) as String?

// Fast Lane sealed toolbox. The directory contains bin/ at its root and is
// added as a Java resource source set, so AGP packages bin/ into the APK root
// before zipalign and signing. No post-signing APK rewriting is used.
val fastlaneToolboxDir: String? = providers.gradleProperty("orchestella.fastlane.toolboxDir")
    .orNull
    ?.trim()
    ?.ifEmpty { null }

// Fast Lane protected-payload native library. The directory must contain
// arm64-v8a/libfastlane_payload.so and must never contain the link-time
// libvm_payload.so stub (the AVF APEX provides that library on device).
val fastlanePayloadLibDir: String? = providers.gradleProperty("orchestella.fastlane.payloadLibDir")
    .orNull
    ?.trim()
    ?.ifEmpty { null }

// Windows has no python3 shim by default (python3 may be the Microsoft Store
// alias). CI and Linux keep python3; local Windows runs can set PYTHON.
val pythonExecutable: String = providers.environmentVariable("PYTHON")
    .orNull
    ?.trim()
    ?.ifEmpty { null }
    ?: if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"

// Build channels separate public releases from internal development and isolated QA
// builds (docs/standards/VERSIONING.md section 5). The default stays "release":
// a plain :app:assembleDebug keeps the historical applicationId and versionName.
val buildChannel: String = providers.gradleProperty("orchestella.buildChannel")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.ifEmpty { null }
    ?: "release"
require(buildChannel in setOf("release", "dev", "qa")) {
    "orchestella.buildChannel must be one of release, dev, qa; got '$buildChannel'"
}
val buildPurpose: String? = providers.gradleProperty("orchestella.buildPurpose")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.ifEmpty { null }
if (buildChannel == "qa") {
    require(buildPurpose != null) {
        "orchestella.buildChannel=qa requires -Porchestella.buildPurpose=<purpose>"
    }
    require(buildPurpose.matches(Regex("[a-z0-9]{1,32}"))) {
        "orchestella.buildPurpose must use only lowercase letters and digits (applicationId segments cannot contain hyphens): '$buildPurpose'"
    }
} else if (buildPurpose != null) {
    require(false) {
        "orchestella.buildPurpose is only valid with orchestella.buildChannel=qa"
    }
}

fun gitValue(vararg arguments: String): String? = try {
    val process = ProcessBuilder(listOf("git") + arguments)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    process.waitFor()
    output.ifEmpty { null }
} catch (failure: Exception) {
    null
}

val gitShortSha: String? = gitValue("rev-parse", "--short=7", "HEAD")
    ?.takeIf { it.matches(Regex("[0-9a-f]{7,40}")) }
val gitTrackedDirty: Boolean = !gitValue("status", "--porcelain", "--untracked-files=no").isNullOrEmpty()
if (buildChannel != "release") {
    require(gitShortSha != null) {
        "orchestella.buildChannel=$buildChannel requires a git checkout to resolve the short commit SHA"
    }
}
val internalVersionSuffix: String? = when (buildChannel) {
    "dev" -> "-dev.$gitShortSha" + if (gitTrackedDirty) ".dirty" else ""
    "qa" -> "-qa.$buildPurpose.$gitShortSha" + if (gitTrackedDirty) ".dirty" else ""
    else -> null
}
val internalApplicationIdSuffix: String? = when (buildChannel) {
    "dev" -> ".dev"
    "qa" -> ".qa.$buildPurpose"
    else -> null
}
// Package visibility (Android 11+): <queries> in the manifest must name the
// accelerator extension APK, or PackageManager hides it and
// getApplicationInfo() throws NameNotFoundException, which the resolver can
// only report as EXTENSION_MISSING. The extension's package name is
// AccelExtensionRules.extensionPackageName(mainPackageName) — main plus
// ".accel" — so the declared name has to follow whatever applicationIdSuffix
// the current build type applies. This is the single source for both the
// default (no suffix) and the channel-suffixed variants.
//
// The base id is also spelled out as a literal inside the android config block
// below: the release gate (tools/release/check_release_fields.py) parses that
// literal, so it must not be replaced by this constant. Both are asserted
// equal by AccelExtensionRulesTest.
val applicationIdBase = "io.github.nyzeep.orchestella"
fun accelExtensionPackageFor(mainApplicationIdSuffix: String?): String =
    applicationIdBase + (mainApplicationIdSuffix ?: "") + ".accel"
// Release signing is explicit. There is deliberately no fallback password or
// implicit keystore: a release build without all four properties fails before
// packaging (see validateReleaseSigning below).
val signingStoreFileProperty: String? = providers.gradleProperty("orchestella.signing.storeFile").orNull?.trim()?.ifEmpty { null }
val signingStorePasswordProperty: String? = providers.gradleProperty("orchestella.signing.storePassword").orNull?.ifEmpty { null }
val signingKeyAliasProperty: String? = providers.gradleProperty("orchestella.signing.keyAlias").orNull?.trim()?.ifEmpty { null }
val signingKeyPasswordProperty: String? = providers.gradleProperty("orchestella.signing.keyPassword").orNull?.ifEmpty { null }
val missingSigningProperties: List<String> = buildList {
    if (signingStoreFileProperty == null) add("orchestella.signing.storeFile")
    if (signingStorePasswordProperty == null) add("orchestella.signing.storePassword")
    if (signingKeyAliasProperty == null) add("orchestella.signing.keyAlias")
    if (signingKeyPasswordProperty == null) add("orchestella.signing.keyPassword")
}
val releaseSigningConfigured: Boolean = missingSigningProperties.isEmpty()

// The accelerator lane is on by default. AX-08 made it a build-time opt-in
// (default off) while the lane was an unreleased experiment; since the
// two-package split the pVM payload ships as a separate extension APK, so an
// off-by-default lane makes the published build permanently unable to
// accelerate — the extension installs, resolves and then is never consulted.
// Enabling the lane only makes the assembly ask AcceleratorPolicy: a QEMU_ONLY
// verdict (no extension, version mismatch, unsupported platform, thermal or
// battery refusal) still refuses the lane and falls back to plain QEMU
// compilation, so the failure mode stays fail-closed. Pass
// -Porchestella.acceleratorLane=false to reproduce the historical QEMU-only build.
val acceleratorLaneEnabled: Boolean = providers.gradleProperty("orchestella.acceleratorLane")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" }
    ?: true

// UML payload productization (ACR passed 2026-11-12, D1-D5 all Go). 方案 A
// bundles the U1 fast-profile artifacts into the git-ignored payload paths at
// build time; 方案 B opens the flag gate in front of the rootfs download entry.
//
// 2026-11-14 single-package round: the complete-package shape (qa.fullpkg and
// every future payload-bearing build) carries the UML engine in the APK, so the
// four flags default ON — the packaged reality is "bundled fast profile present,
// fast launch shape, compile bridge wired, rootfs repair channel open". A bare
// local build without the machine staging dirs still behaves honestly: the
// staging tasks run, record the absence (WARN + build/*.flag) and skip, so the
// runtime answers MISSING_ASSET / SPAWN_FAILED (named detail) at width 0 — the
// same fail-honest contracts as before, only reached by default. An explicit
// -Porchestella.uml.*=false still turns any flag off (CI variants and negative
// tests) — the gradle property, when present, always wins over the default.
// Runtime flag semantics are unchanged: BuildConfig booleans only, no runtime
// SharedPreferences anywhere in this round.
val umlBundledFastProfile: Boolean = providers.gradleProperty("orchestella.uml.bundledFastProfile")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" }
    ?: true
val umlRootfsDownload: Boolean = providers.gradleProperty("orchestella.uml.rootfsDownload")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" }
    ?: true
// UML compile bridge (fabric-route doc D-1, Gate 1 wiring, ACR 2026-11-13 方案 A):
// the face's compile Channel seam gains the UML-TCP lane (vsock=pVM / TCP=UML is
// the既定 wire design). Default ON since the single-package round: the complete
// package ships the net-helper pair, so wired is the factory fact. Never derived
// from orchestella.uml.fastProfile: launch shape != routability is the locked
// honest semantics (the two flags answer different questions and stay
// independent).
val umlCompileBridge: Boolean = providers.gradleProperty("orchestella.uml.compileBridge")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" }
    ?: true
val umlFastProfileDir: String? = providers.gradleProperty("orchestella.uml.fastProfileDir")
    .orNull
    ?.trim()
    ?.ifEmpty { null }
// fastProfileDir is optional on purpose (ACR D1): the release gate states
// -Porchestella.uml.bundledFastProfile=true on the hosted clean checkout too,
// where the U1 artifacts cannot exist (git-ignored machine inputs, like the
// QEMU payload). stageUmlFastProfile records that absence and skips; a build
// that DOES provide the dir is fail-closed (missing artifacts / sha mismatch
// still fail the build) — the packaging machine's path.
// No cross-constraint: the two routes are independent and may combine.
// Gate 2 net helper (ACR 2026-11-13 §8, the umnet supervisor, 候选 a): the
// git-ignored machine-input pair umnet_bionic/passt_bionic (static bionic
// ELFs; passt fork pin 25f9c1f+1a0c657) stages into jniLibs as
// libuml_net.so/libuml_passt.so under this stated parameter — the same
// stated-parameter mode as fastProfileDir, no extra Gradle flag: the dir's
// presence IS the staging opt-in (packaging is independent of the behavior
// flag; orchestella.uml.compileBridge governs the spawner rewrite, not the
// packaging). Without the dir the staging records the absence and skips
// with a WARN + marker (the bundledFastProfile-missing-dir shape); a
// provided dir stays fail-closed (missing artifacts / pin mismatch fail the
// build). Not passed by release.yml this round — see the release.yml
// comment before the release run line.
val umlNetHelperDir: String? = providers.gradleProperty("orchestella.uml.netHelperDir")
    .orNull
    ?.trim()
    ?.ifEmpty { null }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.nyzeep.orchestella"
    compileSdk = 36
    buildToolsVersion = libs.versions.buildtools.get()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.nyzeep.orchestella"
        minSdk = 26
        // QA-only escape hatch for the SELinux W^X exec policy: targetSdk >= 29
        // lands in the untrusted_app_34 domain, which may not execve app-owned
        // binaries (the QEMU lane's gvproxy/qemu spawns), while the legacy
        // untrusted_app domain keeps that permission (the Termux approach).
        // Release/dev/QA-default stay on 36; a QA build may opt down explicitly.
        targetSdk = if (buildChannel == "qa") {
            providers.gradleProperty("orchestella.qaTargetSdk").orNull?.trim()?.toIntOrNull() ?: 36
        } else {
            36
        }
        // 0.6.3 / 603 — optimization & hardening pass: launch-path deep modules
        // (InstanceLaunchCoordinator / ControlChannelLifecycle / UmlKeepAliveSession) with
        // contract tests, cold-start IO moved off the main thread, wizard input debounce,
        // reset no-op root fix, BACK confirm while creating, storage-full precheck, theme
        // runtime tiers and a uniform icon set. No new shell capability, so MINOR stays at
        // 6 and PATCH moves (VERSIONING.md §3.2).
        // versionCode = MAJOR*10000 + MINOR*100 + PATCH (VERSIONING.md §2).
        versionCode = 603
        versionName = "0.6.3"
        buildConfigField("boolean", "DEVELOPMENT_PREVIEW", "true")
        buildConfigField("boolean", "ACCELERATOR_LANE_ENABLED", acceleratorLaneEnabled.toString())
        // UML payload routes (ACR passed 2026-11-12; gradle defaults stay off,
        // the release pipeline states them explicitly): the wiring owner passes
        // these into UmlKernelAssets.verifyBundledFastProfile / the RootfsDownload
        // gate; off answers a disabled report / closed gate with zero side effects.
        buildConfigField("boolean", "UML_BUNDLED_FAST_PROFILE", umlBundledFastProfile.toString())
        buildConfigField("boolean", "UML_ROOTFS_DOWNLOAD_ENABLED", umlRootfsDownload.toString())
        // UML fast-profile launch shape (research/uml-fastboot README §4);
        // consumed by UmlFaceLauncher.FastProfile.currentBuild() through the
        // assembly's umlFace slot (ACR D4 wiring). Default ON since the
        // single-package round (the complete package's factory launch shape);
        // an explicit -Porchestella.uml.fastProfile=false still restores the
        // runner shape (CI variants and negative tests).
        buildConfigField("boolean", "UML_FAST_PROFILE",
            (providers.gradleProperty("orchestella.uml.fastProfile").orNull?.trim()?.toBoolean() ?: true).toString())
        // UML compile bridge (fabric-route doc D-1, Gate 1 wiring): consumed by
        // the wiring owner (UmlCompileBridgeWiring) — flag on arms the face with
        // the UML-TCP lane channel (UmlChannels.overTcpLane over the bridge's
        // UmlTcpChannelFactory) and binds the fabric submitter listener; flag
        // off keeps today's byte-identical arm path (channel=unwired).
        buildConfigField("boolean", "UML_COMPILE_BRIDGE", umlCompileBridge.toString())
        // Single-package round (2026-11-14): extractNativeLibs stays true for
        // every build. The manifest flag decides whether the installer
        // unpacks native libraries into nativeLibraryDir — the UML lane
        // execs libuml_kernel.so / libuml_stub.so / libuml_net.so /
        // libuml_passt.so and the QEMU lane execs gvproxy/qemu from there,
        // so an unextracted layout (extractNativeLibs=false leaves
        // nativeLibraryDir empty on device) kills every exec-based engine.
        // The pVM five-lib payload is independent of this flag: its AVF
        // loader reads the APK entries directly and only demands stored
        // (uncompressed) 16 KB page-aligned entries, which the repack
        // pipeline guarantees post-packaging (see repackPvmPayload below).
        manifestPlaceholders["orcExtractNativeLibs"] = "true"
        // Release build type adds no suffix, so the unsuffixed name is the
        // default; the debug channel branches below override it.
        manifestPlaceholders["accelExtensionPackage"] = accelExtensionPackageFor(null)

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            buildStagingDirectory = rootProject.layout.buildDirectory.dir("native").get().asFile
        }
    }

    if (qemuNativeLibDir != null || qemuAssetsDir != null || qemuInitramfsDir != null) {
        require(qemuNativeLibDir != null && qemuAssetsDir != null && qemuInitramfsDir != null) {
            "droidlayer.qemu.nativeLibDir, droidlayer.qemu.assetsDir and droidlayer.qemu.initramfsDir must be provided together"
        }
        val nativeLibRoot = file(qemuNativeLibDir)
        val assetsRoot = file(qemuAssetsDir)
        val initramfsRoot = file(qemuInitramfsDir)
        require(nativeLibRoot.isDirectory && !Files.isSymbolicLink(nativeLibRoot.toPath())) {
            "QEMU native-lib payload root must be a direct ordinary directory"
        }
        require(assetsRoot.isDirectory && !Files.isSymbolicLink(assetsRoot.toPath())) {
            "QEMU assets payload root must be a direct ordinary directory"
        }
        require(initramfsRoot.isDirectory && !Files.isSymbolicLink(initramfsRoot.toPath())) {
            "QEMU initramfs payload root must be a direct ordinary directory"
        }
        sourceSets.getByName("main") {
            jniLibs.srcDir(nativeLibRoot)
            assets.srcDir(assetsRoot)
            resources.srcDir(initramfsRoot)
        }
    }

    if (fastlaneToolboxDir != null) {
        val toolboxRoot = rootProject.file(fastlaneToolboxDir)
        require(toolboxRoot.isDirectory && !Files.isSymbolicLink(toolboxRoot.toPath())) {
            "orchestella.fastlane.toolboxDir must be an existing ordinary directory: $toolboxRoot"
        }
        require(File(toolboxRoot, "bin/manifest.json").isFile) {
            "Fast Lane toolbox is missing bin/manifest.json: $toolboxRoot (run tools/fastlane-build/fastlane_build.py build first)"
        }
        sourceSets.getByName("main").resources.srcDir(toolboxRoot)
    }

    if (fastlanePayloadLibDir != null) {
        val payloadRoot = rootProject.file(fastlanePayloadLibDir)
        require(payloadRoot.isDirectory && !Files.isSymbolicLink(payloadRoot.toPath())) {
            "orchestella.fastlane.payloadLibDir must be an existing ordinary directory: $payloadRoot"
        }
        val payloadSo = File(payloadRoot, "arm64-v8a/libfastlane_payload.so")
        require(payloadSo.isFile && !Files.isSymbolicLink(payloadSo.toPath())) {
            "Fast Lane payload is missing arm64-v8a/libfastlane_payload.so: $payloadRoot"
        }
        require(!File(payloadRoot, "arm64-v8a/libvm_payload.so").exists()) {
            "libvm_payload.so is an AVF APEX library and must not be packaged from $payloadRoot"
        }
        sourceSets.getByName("main").jniLibs.srcDir(payloadRoot)
    }

    // PVM-08: optional compiler-side shared libraries (libLLVM.so and
    // libclang-cpp.so) that must land in the same APK lib/arm64-v8a/ directory
    // as libfastlane_payload.so, so the Microdroid linker resolves them by
    // soname next to the payload instead of via absolute paths. They are only
    // meaningful next to the payload: extractNativeLibs/useLegacyPackaging key
    // off the payload property, and a payload-less APK has no consumer for
    // them, so requiring the payload property keeps the two in lockstep.
    val pvmCompilerLibDir: String? = providers.gradleProperty("orchestella.pvmCompiler.libDir")
        .orNull
        ?.trim()
        ?.ifEmpty { null }
    if (pvmCompilerLibDir != null) {
        require(fastlanePayloadLibDir != null) {
            "orchestella.pvmCompiler.libDir requires orchestella.fastlane.payloadLibDir: " +
                "the compiler libraries are resolved next to the payload"
        }
        val compilerRoot = rootProject.file(pvmCompilerLibDir)
        require(compilerRoot.isDirectory && !Files.isSymbolicLink(compilerRoot.toPath())) {
            "orchestella.pvmCompiler.libDir must be an existing ordinary directory: $compilerRoot"
        }
        for (requiredSo in listOf("libLLVM.so", "libclang-cpp.so")) {
            require(File(compilerRoot, "arm64-v8a/$requiredSo").isFile) {
                "PVM compiler lib dir is missing arm64-v8a/$requiredSo: $compilerRoot"
            }
        }
        require(!File(compilerRoot, "arm64-v8a/libfastlane_payload.so").exists()) {
            "libfastlane_payload.so comes from orchestella.fastlane.payloadLibDir and must not be duplicated in $compilerRoot"
        }
        require(!File(compilerRoot, "arm64-v8a/libvm_payload.so").exists()) {
            "libvm_payload.so is an AVF APEX library and must not be packaged from $compilerRoot"
        }
        sourceSets.getByName("main").jniLibs.srcDir(compilerRoot)

        // PVM-09: the staging directory also carries an assets/ side with the
        // compiler-service manifest (assets/PVM-COMPILER-MANIFEST). It is a
        // build product generated by tools/pvm-compiler/assemble_payload_libs.py,
        // never committed: it names the exact libraries and hashes that this
        // build packages, so it must be generated per build. Only assets/ is
        // added as an asset source - arm64-v8a/ belongs to jniLibs above and
        // must never be duplicated into assets.
        val compilerAssetsRoot = File(compilerRoot, "assets")
        val compilerManifest = File(compilerAssetsRoot, "PVM-COMPILER-MANIFEST")
        require(compilerManifest.isFile) {
            "PVM compiler manifest is missing: assets/PVM-COMPILER-MANIFEST under orchestella.pvmCompiler.libDir " +
                "(regenerate the staging directory with tools/pvm-compiler/assemble_payload_libs.py)"
        }
        sourceSets.getByName("main").assets.srcDir(compilerAssetsRoot)

        // Re-check the manifest against the staged .so files on every build:
        // the properties above only prove the two required libraries exist, not
        // that the manifest describes these exact bytes.
        val verifyPvmCompilerManifest = tasks.register<Exec>("verifyPvmCompilerManifest") {
            group = "verification"
            description = "Verifies assets/PVM-COMPILER-MANIFEST against the staged arm64-v8a/*.so files."
            workingDir(rootProject.projectDir)
            commandLine(
                pythonExecutable,
                "tools/pvm-compiler/assemble_payload_libs.py",
                "--verify-manifest",
                compilerRoot.absolutePath
            )
        }
        tasks.named("preBuild") {
            dependsOn(verifyPvmCompilerManifest)
        }
    }

    signingConfigs {
        // The device AVF idsig path requires a v3 APK Signature Scheme block
        // (v2-only APKs fail at VirtualMachineManager.create). Enabling v3/v4
        // keeps the existing debug certificate and QA channel unchanged.
        getByName("debug") {
            enableV3Signing = true
            enableV4Signing = true
        }
        // Legacy isolated-QA channel. This is a debug key with historical fixed
        // credentials; it is never selected for a release build.
        val phase5Keystore = providers.gradleProperty("orchestella.phase5.debugKeystore").orNull
        if (phase5Keystore != null) {
            create("phase5Debug") {
                storeFile = file(phase5Keystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // Only create the release config when every explicit property is present.
        // Missing properties are reported by validateReleaseSigning before packaging.
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(signingStoreFileProperty!!)
                storePassword = signingStorePasswordProperty
                keyAlias = signingKeyAliasProperty
                keyPassword = signingKeyPasswordProperty
                // Same reason as the debug config above: the AVF payload loader
                // materializes an idsig from the v3 block. A v2-only release key
                // would fail at VirtualMachineManager.create on devices where the
                // pVM lane is reachable, which is exactly the lane a signed
                // release is meant to ship. v4 also keeps a local idsig for
                // sideload verification.
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            if (buildChannel != "release") {
                // Internal channels always use the debug key, even when a release
                // keystore is configured for this machine.
                applicationIdSuffix = internalApplicationIdSuffix
                versionNameSuffix = internalVersionSuffix
                signingConfig = signingConfigs.getByName("debug")
                manifestPlaceholders["accelExtensionPackage"] =
                    accelExtensionPackageFor(internalApplicationIdSuffix)
            } else {
                // Historical default behavior: phase5 QA wins, otherwise a fully
                // configured release key may sign debug builds on developer machines.
                val releaseSigning = signingConfigs.findByName("release")
                if (signingConfigs.findByName("phase5Debug") != null) {
                    applicationIdSuffix = ".phase5"
                    versionNameSuffix = "-phase5-local"
                    signingConfig = signingConfigs.getByName("phase5Debug")
                    manifestPlaceholders["accelExtensionPackage"] = accelExtensionPackageFor(".phase5")
                } else if (releaseSigning?.storeFile?.exists() == true) {
                    signingConfig = releaseSigning
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // useLegacyPackaging is decoupled from the pVM payload parameters
            // (2026-11-14 single-package decision, device-verified): it stays
            // true for every build so the installer unpacks native libraries
            // into nativeLibraryDir — the exec-based engines (UML kernel and
            // net helpers, QEMU's qemu/gvproxy spawns) cannot run from an
            // unextracted layout. The pVM payload's AVF-loader contract is
            // satisfied separately: the five payload libraries are rewritten
            // from compressed entries to stored, 16 KB page-aligned entries
            // after packaging (repackPvmPayload task chain below), which is
            // exactly the entry shape the loader reads directly from the APK.
            useLegacyPackaging = true
            keepDebugSymbols += "**/liborchestella_qemu.so"
            keepDebugSymbols += "**/libdroidlayer_qemu.so"
            // The UML fast-profile accounting manifest
            // (assets/uml-fast-profile-v1.sha256, written by stageUmlFastProfile)
            // accounts for the staged bytes of the two UML native libs, and
            // tools/release/check_uml_payload.py re-proves shipped-bytes-equal-
            // manifest at the APK level. AGP's default debug-symbol strip would
            // rewrite libuml_kernel.so between the merge and the APK (measured
            // 2026-11-13: staged 9517760 B / sha 21e1c3f9… → APK entry 8071168 B
            // / sha 52764628…), breaking that chain while the stub (nothing to
            // strip) survived byte-identically. Keep both unstripped so the
            // shipped bytes stay the pinned bytes the manifest accounts for
            // (same reason as the QEMU payload libs above; ~1.4 MB APK cost).
            keepDebugSymbols += "**/libuml_kernel.so"
            keepDebugSymbols += "**/libuml_stub.so"
            // Gate 2 net helper (2026-11-13, umnet 候选 a): libuml_net.so /
            // libuml_passt.so are static bionic executables carrying a .so
            // name (the umnet supervisor and its passt child, staged by
            // stageUmlNetHelper). AGP's default debug-symbol strip rewrites
            // the ELF exactly as it rewrote libuml_kernel.so (measured same
            // round), which would break the uml-net-helper-v1.sha256
            // accounting chain the same way — staged bytes must stay the
            // pinned bytes. Unstripped, same reason as the payload libs above.
            keepDebugSymbols += "**/libuml_net.so"
            keepDebugSymbols += "**/libuml_passt.so"
            // The pVM five-lib payload (single-package round): the AVF loader
            // and the PVM-COMPILER-MANIFEST pin chain account for the staged
            // bytes verbatim. AGP's default debug-symbol strip rewrites the
            // ELF between merge and APK exactly as it rewrote libuml_kernel.so
            // (measured 2026-11-13), which would break the repack gate's
            // byte-for-byte manifest reconciliation (repack_pvm_payload.py
            // re-proves shipped-bytes-equal-manifest against the three
            // manifest-pinned compiler libs). Keep all five unstripped so the
            // shipped bytes stay the pinned bytes.
            keepDebugSymbols += "**/libfastlane_payload.so"
            keepDebugSymbols += "**/libpvm_compiler_payload.so"
            keepDebugSymbols += "**/libLLVM.so"
            keepDebugSymbols += "**/libclang-cpp.so"
            keepDebugSymbols += "**/libc++_shared.so"
        }
    }

    aaptOptions {
        noCompress += listOf("Image", "initramfs.cpio.gz", "cpio.gz", "gz")
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += "PropertyEscape"
    }
}

// ---- Single-package repack pipeline (2026-11-14) ----
// Mixed packaging, device-verified: useLegacyPackaging stays true above so the
// installer unpacks the exec-based engines into nativeLibraryDir, and the pVM
// payload's AVF-loader contract is satisfied post-packaging. The chain runs
// only when both pVM staging parameters are present (payload five-lib set
// staged), and goes: package (AGP) → repack (five payload entries deflate →
// stored, content bytes reconciled against the staged sources and the APK's
// own PVM-COMPILER-MANIFEST) → zipalign -P 16 → apksigner re-sign (debug key;
// the rewrite invalidates AGP's signature by construction) → apksigner verify.
// The channel copy (renameChannelApk) ships the final APK, not AGP's raw
// output. Release wiring lands with the release pipeline's single-package
// adoption: the same chain instantiated over the release APK and the release
// keystore — it is deliberately not registered for release in this round.
val pvmCompilerLibDirParam: String? = providers.gradleProperty("orchestella.pvmCompiler.libDir")
    .orNull
    ?.trim()
    ?.ifEmpty { null }
val pvmRepackEnabled: Boolean = fastlanePayloadLibDir != null && pvmCompilerLibDirParam != null

if (pvmRepackEnabled) {
    val repackSdkDir: File? = sequenceOf(
        providers.environmentVariable("ANDROID_HOME").orNull,
        providers.environmentVariable("ANDROID_SDK_ROOT").orNull,
    ).firstOrNull { !it.isNullOrBlank() }?.let { File(it.trim()) }
        ?: run {
            val localProps = rootProject.file("local.properties")
            if (localProps.isFile) {
                val props = Properties()
                localProps.inputStream().use { props.load(it) }
                props.getProperty("sdk.dir")?.let { File(it.trim()) }
            } else {
                null
            }
        }
    val buildToolsRelative = "build-tools/${libs.versions.buildtools.get()}"
    val isWindowsHost = System.getProperty("os.name").lowercase().contains("windows")
    val zipalignTool: File? = repackSdkDir?.resolve(buildToolsRelative)?.let { dir ->
        val exe = File(dir, "zipalign.exe")
        val plain = File(dir, "zipalign")
        when {
            exe.isFile -> exe
            plain.isFile -> plain
            else -> null
        }
    }
    val apksignerTool: File? = repackSdkDir?.resolve(buildToolsRelative)?.let { dir ->
        val bat = File(dir, "apksigner.bat")
        val plain = File(dir, "apksigner")
        when {
            isWindowsHost && bat.isFile -> bat
            plain.isFile -> plain
            else -> null
        }
    }
    require(zipalignTool != null) {
        "repackPvmPayload: zipalign not found under " +
            (repackSdkDir?.resolve(buildToolsRelative)?.absolutePath ?: "ANDROID_HOME") +
            " — set ANDROID_HOME (or sdk.dir in local.properties)"
    }
    require(apksignerTool != null) {
        "repackPvmPayload: apksigner not found under " +
            (repackSdkDir?.resolve(buildToolsRelative)?.absolutePath ?: "ANDROID_HOME") +
            " — set ANDROID_HOME (or sdk.dir in local.properties)"
    }

    // Same identity as AGP's debug signing: the default debug keystore and its
    // documented constants. Property overrides exist for machines with a
    // relocated keystore; the release keystore is intentionally NOT reachable
    // here — this chain signs QA/internal builds only this round.
    val repackStoreFile: File = providers.gradleProperty("orchestella.repack.debugKeystore")
        .orNull?.let { rootProject.file(it) }
        ?: android.signingConfigs.getByName("debug").storeFile
        ?: File(System.getProperty("user.home"), ".android/debug.keystore")
    val repackStorePass: String = providers.gradleProperty("orchestella.repack.debugStorePassword")
        .orNull ?: "android"
    val repackKeyAlias: String = providers.gradleProperty("orchestella.repack.debugKeyAlias")
        .orNull ?: "androiddebugkey"
    val repackKeyPass: String = providers.gradleProperty("orchestella.repack.debugKeyPassword")
        .orNull ?: "android"
    require(repackStoreFile.isFile) {
        "repackPvmPayload: debug keystore not found: $repackStoreFile " +
            "(set -Porchestella.repack.debugKeystore or let AGP generate the default)"
    }

    val fullpkgDir = layout.buildDirectory.dir("outputs/apk/fullpkg")
    val debugApkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val storedApkFile = fullpkgDir.map { it.file("app-debug-stored.apk") }
    val alignedApkFile = fullpkgDir.map { it.file("app-debug-aligned.apk") }
    val fullpkgApkFile = fullpkgDir.map { it.file("app-debug-fullpkg.apk") }
    val idsigFile = fullpkgDir.map { it.file("app-debug-fullpkg.apk.idsig") }
    val payloadRootDir = rootProject.file(fastlanePayloadLibDir!!)
    val compilerRootDir = rootProject.file(pvmCompilerLibDirParam!!)

    val repackPvmPayloadStore = tasks.register<Exec>("repackPvmPayloadStore") {
        group = "verification"
        description = "Rewrites the five staged pVM payload entries to stored with byte reconciliation."
        dependsOn("assembleDebug")
        workingDir(rootProject.projectDir)
        inputs.file(debugApkFile)
        inputs.dir(payloadRootDir)
        inputs.dir(compilerRootDir)
        outputs.file(storedApkFile)
        commandLine(
            pythonExecutable,
            "tools/release/repack_pvm_payload.py",
            "--apk", debugApkFile.get().asFile.absolutePath,
            "--output", storedApkFile.get().asFile.absolutePath,
            "--payload-lib-dir", payloadRootDir.absolutePath,
            "--compiler-lib-dir", compilerRootDir.absolutePath,
        )
    }

    val zipalignFullpkg = tasks.register<Exec>("zipalignFullpkg") {
        group = "verification"
        description = "16 KB page-aligns the repacked full-package APK."
        dependsOn(repackPvmPayloadStore)
        inputs.file(storedApkFile)
        outputs.file(alignedApkFile)
        commandLine(
            zipalignTool!!.absolutePath,
            "-f", "-P", "16", "4",
            storedApkFile.get().asFile.absolutePath,
            alignedApkFile.get().asFile.absolutePath,
        )
    }

    val signFullpkg = tasks.register<Exec>("signFullpkg") {
        group = "verification"
        description = "Re-signs the aligned full-package APK (v2+v3+v4, debug key)."
        dependsOn(zipalignFullpkg)
        inputs.file(alignedApkFile)
        outputs.file(fullpkgApkFile)
        outputs.file(idsigFile)
        // apksigner.bat is a batch wrapper: Windows executes it through cmd.
        val launcher = if (isWindowsHost && apksignerTool.name.endsWith(".bat")) "cmd" else null
        if (launcher != null) {
            commandLine(
                launcher, "/c", apksignerTool.absolutePath, "sign",
                "--ks", repackStoreFile.absolutePath,
                "--ks-pass", "pass:$repackStorePass",
                "--ks-key-alias", repackKeyAlias,
                "--key-pass", "pass:$repackKeyPass",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--v4-signing-enabled", "true",
                "--out", fullpkgApkFile.get().asFile.absolutePath,
                alignedApkFile.get().asFile.absolutePath,
            )
        } else {
            commandLine(
                apksignerTool.absolutePath, "sign",
                "--ks", repackStoreFile.absolutePath,
                "--ks-pass", "pass:$repackStorePass",
                "--ks-key-alias", repackKeyAlias,
                "--key-pass", "pass:$repackKeyPass",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true",
                "--v4-signing-enabled", "true",
                "--out", fullpkgApkFile.get().asFile.absolutePath,
                alignedApkFile.get().asFile.absolutePath,
            )
        }
    }

    val verifyFullpkgSignature = tasks.register<Exec>("verifyFullpkgSignature") {
        group = "verification"
        description = "Verifies the full-package APK signature chain."
        dependsOn(signFullpkg)
        inputs.file(fullpkgApkFile)
        outputs.file(fullpkgDir.map { it.file("signature-verified.flag") })
        doLast {
            fullpkgDir.get().asFile.resolve("signature-verified.flag").writeText("ok\n")
        }
        // apksigner.bat runs through cmd on Windows; --verbose names every
        // scheme block so the log attests v2/v3 without a second invocation.
        val launcher = if (isWindowsHost && apksignerTool.name.endsWith(".bat")) "cmd" else null
        if (launcher != null) {
            commandLine(
                launcher, "/c", apksignerTool.absolutePath, "verify",
                "--verbose", fullpkgApkFile.get().asFile.absolutePath,
            )
        } else {
            commandLine(
                apksignerTool.absolutePath, "verify",
                "--verbose", fullpkgApkFile.get().asFile.absolutePath,
            )
        }
    }

    tasks.matching { it.name == "renameChannelApk" }.configureEach {
        dependsOn(verifyFullpkgSignature)
    }
}

val channelApkName: String? = internalVersionSuffix?.let {
    "Orchestella-Studio-v${android.defaultConfig.versionName}$it.apk"
}

// Fail only release packaging when the explicit signing material is incomplete;
// debug builds on the same machine keep working.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Fails unless the four explicit release signing properties are present and readable."
    doLast {
        if (missingSigningProperties.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Missing Gradle properties: " +
                    missingSigningProperties.joinToString(", ") +
                    ". Provide all of orchestella.signing.storeFile, " +
                    "orchestella.signing.storePassword, orchestella.signing.keyAlias and " +
                    "orchestella.signing.keyPassword."
            )
        }
        val keystore = file(signingStoreFileProperty!!)
        if (!keystore.isFile) {
            throw GradleException(
                "Release signing storeFile does not exist or is not a regular file: " + keystore
            )
        }
    }
}
tasks.matching {
    it.name in setOf("assembleRelease", "bundleRelease", "installRelease", "packageRelease", "preReleaseBuild")
}.configureEach {
    dependsOn(validateReleaseSigning)
}

// Internal builds keep both the stable app-debug.apk path used by tooling and a
// channel-identifying copy required by VERSIONING.md section 5.3. When the pVM
// payload repack pipeline is enabled (both staging parameters present), the
// channel copy is the repacked, re-signed full-package APK, not AGP's raw
// packaging output — the channel artifact must be the shippable bytes.
if (buildChannel != "release") {
    val renameChannelApk = tasks.register<Copy>("renameChannelApk") {
        description = "Copies the internal debug APK to its channel-qualified name."
        if (pvmRepackEnabled) {
            from(layout.buildDirectory.file("outputs/apk/fullpkg/app-debug-fullpkg.apk"))
        } else {
            from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
        }
        into(layout.buildDirectory.dir("outputs/apk/channel"))
        rename { channelApkName!! }
    }
    tasks.matching { it.name == "assembleDebug" }.configureEach {
        finalizedBy(renameChannelApk)
    }
    if (pvmRepackEnabled) {
        tasks.matching { it.name == "renameChannelApk" }.configureEach {
            dependsOn("verifyFullpkgSignature")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":runtime:capability"))
    implementation(project(":runtime:distro"))
    implementation(project(":runtime:engine-avf"))
    implementation(project(":runtime:engine-qemu"))
    implementation(project(":runtime:instance"))
    // AX-08: pure-JVM accelerator fact/decision policy consumed by the assembly
    // root. It adds no Android dependency.
    implementation(project(":runtime:fastlane-capability"))
    implementation(project(":runtime:network"))
    // Protected-lane warm pool: pure-JVM Fast Lane session/control codec. It
    // adds no Android dependency and is only exercised when the pool switch is on.
    implementation(project(":runtime:fastlane-terminal"))
    // PVM-06 wiring: the pool-backed distcc dispatcher implements the bridge's
    // ChannelFactory, so the app must see the cross-lane distcc types. Pure JVM
    // (engine-fastlane + contract); no Android dependency joins the classpath.
    implementation(project(":runtime:fastlane-distcc"))
    implementation(libs.material)
    implementation(libs.appcompat)
    // MainActivity drives Dispatchers.Main (see MainActivity:1405/1409). The
    // Main dispatcher is registered through kotlinx-coroutines-android's
    // MainDispatcherFactory, so -android must be declared explicitly; a
    // core-only classpath compiles but fails at runtime with
    // "Module with the Main dispatcher had failed to initialize".
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // Pin the in-process hidden-API exemption (Apache-2.0, LSPosed). It grants
    // no permission and is installed once before any AVF class load.
    implementation(libs.hiddenapibypass)
    androidTestImplementation(project(":runtime:engine-fastlane"))
    // DV-PVM-02: the QA instrumentation bridges the payload's vsock port with
    // the production DistccTcpVsockBridge instead of a second byte pump.
    androidTestImplementation(project(":runtime:fastlane-distcc"))
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("runPureJvmAppTests") {
    dependsOn("compileDebugUnitTestSources")
    val testClasses = files(
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    )
    val testClasspath = configurations.getByName("debugUnitTestRuntimeClasspath")
    classpath = testClasses + testClasspath
    mainClass.set("org.junit.runner.JUnitCore")
    args(
        "io.github.nyzeep.orchestella.InteractiveTerminalEngineTest",
        "io.github.nyzeep.orchestella.InstanceCredentialsTest",
        "io.github.nyzeep.orchestella.SetupWizardModelTest",
        "io.github.nyzeep.orchestella.DistroInstanceCreatorTest"
    )
}

tasks.register<JavaExec>("runPhase5JvmAppTests") {
    dependsOn("compileDebugUnitTestSources")
    classpath = files(
        layout.buildDirectory.dir("intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debug")
    ) + configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("org.junit.runner.JUnitCore")
    args(
        "io.github.nyzeep.orchestella.DesktopPresentationGateTest",
        "io.github.nyzeep.orchestella.DesktopKeyMapperTest",
        "io.github.nyzeep.orchestella.RfbDisplaySessionTest",
        "io.github.nyzeep.orchestella.DoubleBufferedRendererTest",
        "io.github.nyzeep.orchestella.FirstBootProvisionPipelineTest",
        "io.github.nyzeep.orchestella.QemuRuntimeBootstrapperTest",
        "io.github.nyzeep.orchestella.NetworkGatewayDaemonTest",
        "io.github.nyzeep.orchestella.PortForwardingPresenterTest"
    )
}

// Q1: fail the build, not the first boot, when the QEMU runtime payload is
// absent or stale in this checkout. A clean clone used to assemble a
// BUILD SUCCESSFUL APK that carried assets/runtime-artifacts-v1.sha256 and
// none of the artifacts it names, so the failure only surfaced when the user
// started an instance. The three payload files are git-ignored and can only
// come from an explicit payload build or from the external -P payload roots.
//
// All seven manifest entries are verified. NOTICE.QEMU and QEMU-GPL-SOURCES.md
// drifted from the manifest between e16af2c and the 2026-11-12 rewrite (the two
// text files were edited without re-pinning); the pins were re-written from the
// tracked file contents that round, so a full-entry check is green again and
// future drift fails the build instead of rotting silently.
val allowMissingRuntimePayload: Boolean = providers.gradleProperty("orchestella.assets.allowMissingPayload")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" }
    ?: false

val verifyRuntimeAssets = tasks.register("verifyRuntimeAssets") {
    group = "verification"
    description = "Fails unless Image, initramfs.cpio.gz and the QEMU ELF exist and match runtime-artifacts-v1.sha256."
    doLast {
        val repoRoot = rootProject.projectDir
        // Messages stay repo-relative: absolute machine paths must not leak
        // into build logs or review transcripts.
        val relative: (File) -> String = { it.relativeToOrSelf(repoRoot).invariantSeparatorsPath }
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var read = input.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        // Exemption 1: the external payload mode (-Porchestella.qemu.*, or the
        // legacy -Pdroidlayer.qemu.* prefix still used by qemu-android.yml).
        // The source sets point outside the checkout, so the in-tree files are
        // irrelevant; only prove the three roots are populated.
        if (qemuNativeLibDir != null || qemuAssetsDir != null || qemuInitramfsDir != null) {
            logger.lifecycle(
                "verifyRuntimeAssets: external QEMU payload mode " +
                    "(-Porchestella.qemu.*/-Pdroidlayer.qemu.*): checking that the three payload " +
                    "roots are populated and skipping the in-tree artifact check."
            )
            val problems = mutableListOf<String>()
            listOf(
                "qemu.nativeLibDir" to qemuNativeLibDir,
                "qemu.assetsDir" to qemuAssetsDir,
                "qemu.initramfsDir" to qemuInitramfsDir
            ).forEach { (label, value) ->
                if (value == null) {
                    problems += "  -$label was not provided (all three roots are required together)"
                    return@forEach
                }
                val root = rootProject.file(value)
                when {
                    !root.isDirectory -> problems += "  -$label is not an existing directory"
                    !root.walkTopDown().any { it.isFile } ->
                        problems += "  -$label contains no files (empty external payload root)"
                }
            }
            if (problems.isNotEmpty()) {
                problems.forEach { logger.error("verifyRuntimeAssets: $it") }
                throw GradleException(
                    "External QEMU payload mode is configured but the payload roots are unusable.\n" +
                        problems.joinToString("\n") +
                        "\nProvide three non-empty roots via -Porchestella.qemu.* or -Pdroidlayer.qemu.*, " +
                        "or build the in-tree payload with guest-assets/run-in-builder.sh."
                )
            }
            return@doLast
        }

        // Exemption 2: an explicit, recorded opt-out for clean checkouts that
        // are not supposed to carry the payload (PR gates, docs-only jobs).
        if (allowMissingRuntimePayload) {
            val flagFile = layout.buildDirectory.file("runtime-assets-missing.flag").get().asFile
            flagFile.parentFile.mkdirs()
            flagFile.writeText(
                "timestamp=${Instant.now()}\n" +
                    "reason=-Porchestella.assets.allowMissingPayload=true\n" +
                    "detail=Image, initramfs.cpio.gz and lib/arm64-v8a/libdroidlayer_qemu.so were " +
                    "not verified; an APK from this build may carry runtime-artifacts-v1.sha256 " +
                    "without the artifacts it names.\n"
            )
            logger.warn(
                "verifyRuntimeAssets: WARNING: runtime payload verification is EXPLICITLY DISABLED " +
                    "(-Porchestella.assets.allowMissingPayload=true). This build may produce an APK that " +
                    "has runtime-artifacts-v1.sha256 but not Image, initramfs.cpio.gz or " +
                    "lib/arm64-v8a/libdroidlayer_qemu.so, and that APK cannot boot the QEMU lane. " +
                    "Marker written to build/runtime-assets-missing.flag."
            )
            return@doLast
        }

        val required = linkedMapOf(
            "Image" to File(repoRoot, "app/src/main/assets/Image"),
            "initramfs.cpio.gz" to File(repoRoot, "app/src/main/resources/initramfs.cpio.gz"),
            "QEMU-BUILD-INPUTS" to File(repoRoot, "app/src/main/assets/QEMU-BUILD-INPUTS"),
            "QEMU-GPL-SOURCES.md" to File(repoRoot, "app/src/main/assets/QEMU-GPL-SOURCES.md"),
            "LICENSE.GPL-2.0" to File(repoRoot, "app/src/main/assets/LICENSE.GPL-2.0"),
            "NOTICE.QEMU" to File(repoRoot, "app/src/main/assets/NOTICE.QEMU"),
            "lib/arm64-v8a/libdroidlayer_qemu.so" to
                File(repoRoot, "app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so")
        )
        val manifestFile = File(repoRoot, "app/src/main/assets/runtime-artifacts-v1.sha256")
        // "<64 lowercase hex><two spaces><name>" per line; line order is not an
        // invariant (the QEMU ELF is last, QEMU-BUILD-INPUTS is third).
        val entryPattern = Regex("^([0-9a-f]{64})  (.+)$")
        val expectedDigests = linkedMapOf<String, String>()
        if (manifestFile.isFile) {
            manifestFile.readLines().forEach { line ->
                val match = entryPattern.matchEntire(line.trim())
                if (match != null) expectedDigests[match.groupValues[2]] = match.groupValues[1]
            }
        }

        val missing = mutableListOf<String>()
        val empty = mutableListOf<String>()
        val unlisted = mutableListOf<String>()
        val mismatched = mutableListOf<Triple<String, String, String>>()
        for ((name, file) in required) {
            when {
                !file.isFile -> missing += "$name (${relative(file)})"
                file.length() == 0L -> empty += "$name (${relative(file)})"
                else -> {
                    val expected = expectedDigests[name]
                    if (expected == null) {
                        unlisted += name
                    } else {
                        val actual = sha256Of(file)
                        if (!actual.equals(expected, ignoreCase = true)) {
                            mismatched += Triple(name, expected, actual)
                        }
                    }
                }
            }
        }

        if (!manifestFile.isFile || missing.isNotEmpty() || empty.isNotEmpty() ||
            unlisted.isNotEmpty() || mismatched.isNotEmpty()
        ) {
            val details = mutableListOf<String>()
            details += "The QEMU runtime payload in this checkout is incomplete or does not match " +
                "app/src/main/assets/runtime-artifacts-v1.sha256."
            if (!manifestFile.isFile) {
                details += "  - missing manifest: app/src/main/assets/runtime-artifacts-v1.sha256 (tracked file)"
            }
            if (missing.isNotEmpty()) details += "  - missing files: ${missing.joinToString(", ")}"
            if (empty.isNotEmpty()) details += "  - empty files: ${empty.joinToString(", ")}"
            if (unlisted.isNotEmpty()) {
                details += "  - not described by the manifest: ${unlisted.joinToString(", ")}"
            }
            mismatched.forEach { (name, expected, actual) ->
                details += "  - sha256 mismatch: $name expected=$expected actual=$actual"
            }
            details += "How to obtain the payload:"
            details += "  - local build: build the guest assets with guest-assets/run-in-builder.sh " +
                "(produces Image and initramfs.cpio.gz) and stage the QEMU ELF at " +
                "app/src/main/jniLibs/arm64-v8a/libdroidlayer_qemu.so, then rerun."
            details += "  - CI: pass an external payload with -Porchestella.qemu.nativeLibDir/" +
                "assetsDir/initramfsDir (or the legacy -Pdroidlayer.qemu.* prefix); that mode skips " +
                "this check and only requires the three roots to be populated."
            details += "  - intentional absence: rerun with -Porchestella.assets.allowMissingPayload=true " +
                "to opt out explicitly; it warns and writes build/runtime-assets-missing.flag."
            details.forEach { logger.error("verifyRuntimeAssets: $it") }
            throw GradleException("QEMU runtime payload check failed.\n" + details.joinToString("\n"))
        }

        // A passing check invalidates any marker left by an earlier opted-out
        // run; a stale flag would misreport this build as payload-less.
        layout.buildDirectory.file("runtime-assets-missing.flag").get().asFile.delete()
        logger.lifecycle(
            "verifyRuntimeAssets: all ${required.size} manifest entries (Image, initramfs.cpio.gz, " +
                "QEMU-BUILD-INPUTS, QEMU-GPL-SOURCES.md, LICENSE.GPL-2.0, NOTICE.QEMU, " +
                "lib/arm64-v8a/libdroidlayer_qemu.so) match app/src/main/assets/runtime-artifacts-v1.sha256."
        )
    }
}

// UML payload productization, 方案 A skeleton (default OFF, ACR-pending): stage
// the U1 fast-profile artifacts into the git-ignored payload paths with sha
// accounting — the same fail-the-build-not-the-first-boot philosophy as
// verifyRuntimeAssets above. The pins are parsed from the PIN_MANIFEST constant
// in UmlKernelAssets.java, so the build-time accounting and the runtime
// verification read one single pin source. Artifacts land in:
//   src/main/jniLibs/arm64-v8a/libuml_kernel.so   <- linux-fast (git-ignored)
//   src/main/jniLibs/arm64-v8a/libuml_stub.so     <- stub_exe   (git-ignored)
//   src/main/resources/alpine-minirootfs-3.22.2-aarch64.tar.gz (git-ignored, optional)
// and the accounting manifest is written to src/main/assets/uml-fast-profile-v1.sha256
// (git-ignored build product; packaged names, so the runtime can consume it).
// Nothing runs when orchestella.uml.bundledFastProfile is off (the default).
// When the flag is on but no fastProfileDir is provided (the hosted release
// gate states the bundled flag explicitly, ACR D1 — its clean checkout cannot
// carry the git-ignored U1 artifacts), staging records the absence and skips:
// no jniLibs, no accounting manifest, so the packaged APK carries no UML
// kernel and the runtime answers MISSING_ASSET honestly (width 0, never a
// faked face). A provided dir stays fail-closed, unchanged.
val stageUmlFastProfile = tasks.register("stageUmlFastProfile") {
    group = "verification"
    description = "Stages U1 fast-profile artifacts into the git-ignored payload paths with sha accounting."
    onlyIf { umlBundledFastProfile }
    doLast {
        val repoRoot = projectDir
        val relative: (File) -> String = { it.relativeToOrSelf(repoRoot).invariantSeparatorsPath }
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var read = input.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (umlFastProfileDir == null) {
            val flagFile =
                layout.buildDirectory.file("uml-fast-profile-staging-skipped.flag").get().asFile
            flagFile.parentFile.mkdirs()
            flagFile.writeText(
                "timestamp=${Instant.now()}\n" +
                    "reason=-Porchestella.uml.bundledFastProfile=true without " +
                    "-Porchestella.uml.fastProfileDir\n" +
                    "detail=no U1 artifacts in this checkout; the APK carries no UML kernel " +
                    "asset and UmlFaceLauncher answers MISSING_ASSET (width 0).\n"
            )
            logger.warn(
                "stageUmlFastProfile: WARNING: bundledFastProfile is on but no " +
                    "orchestella.uml.fastProfileDir was provided; staging skipped and recorded " +
                    "in build/uml-fast-profile-staging-skipped.flag. The packaged APK carries " +
                    "no UML kernel asset. Provide the pinned artifacts " +
                    "(research/uml-fastboot/manifest.md §3) on the packaging machine to stage " +
                    "them for real."
            )
            return@doLast
        }

        // Single pin source: the PIN_MANIFEST constant lines inside
        // UmlKernelAssets.java ("<64-hex>␠␠<name>" string literals).
        val kernelAssetsSource = file(
            "src/main/java/io/github/nyzeep/orchestella/UmlKernelAssets.java")
        val pinPattern = Regex("\"([0-9a-f]{64})  ([A-Za-z0-9._\\-]+)\"")
        val pins = linkedMapOf<String, String>()
        kernelAssetsSource.readLines().forEach { line ->
            pinPattern.find(line)?.let { pins[it.groupValues[2]] = it.groupValues[1] }
        }
        require(pins.containsKey("linux-fast") && pins.containsKey("stub_exe")) {
            "stageUmlFastProfile: could not parse the fast-profile pins from " +
                "UmlKernelAssets.java PIN_MANIFEST — the constant format must have drifted."
        }

        val profileRoot = rootProject.file(umlFastProfileDir)
        if (!profileRoot.isDirectory) {
            throw GradleException(
                "stageUmlFastProfile: orchestella.uml.fastProfileDir is not an existing directory: " +
                    relative(profileRoot)
            )
        }

        // artifact name -> (staged payload path); the rootfs tar is optional.
        val staged = linkedMapOf(
            "linux-fast" to file("src/main/jniLibs/arm64-v8a/libuml_kernel.so"),
            "stub_exe" to file("src/main/jniLibs/arm64-v8a/libuml_stub.so")
        )
        val optionalStaged = linkedMapOf(
            "alpine-minirootfs-3.22.2-aarch64.tar.gz" to
                file("src/main/resources/alpine-minirootfs-3.22.2-aarch64.tar.gz")
        )

        val problems = mutableListOf<String>()
        staged.forEach { (artifact, target) ->
            val source = File(profileRoot, artifact)
            if (!source.isFile) {
                problems += "  - missing artifact: $artifact (${relative(source)})"
                return@forEach
            }
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
            val actual = sha256Of(target)
            val expected = pins[artifact]
            if (expected == null || !actual.equals(expected, ignoreCase = true)) {
                problems += "  - sha256 mismatch: $artifact expected=$expected actual=$actual"
            }
        }
        optionalStaged.forEach { (artifact, target) ->
            val source = File(profileRoot, artifact)
            if (source.isFile) {
                source.copyTo(target, overwrite = true)
                val actual = sha256Of(target)
                val expected = pins[artifact]
                if (expected == null || !actual.equals(expected, ignoreCase = true)) {
                    problems += "  - sha256 mismatch: $artifact expected=$expected actual=$actual"
                }
            }
        }

        if (problems.isNotEmpty()) {
            problems.forEach { logger.error("stageUmlFastProfile: $it") }
            throw GradleException(
                "The U1 fast-profile staging failed sha accounting.\n" +
                    problems.joinToString("\n") +
                    "\nProvide the pinned artifacts (research/uml-fastboot/manifest.md §3) in " +
                    "orchestella.uml.fastProfileDir, or rerun without " +
                    "-Porchestella.uml.bundledFastProfile=true."
            )
        }

        // Accounting manifest: packaged names, so the runtime's manifest parser
        // can consume the same file P2 wires through PackagedAssetStreams.
        val accounting = file("src/main/assets/uml-fast-profile-v1.sha256")
        accounting.parentFile.mkdirs()
        accounting.writeText(
            (staged + optionalStaged.filterKeys { File(profileRoot, it).isFile })
                .entries.joinToString("") { (artifact, target) ->
                    val packagedName = when (artifact) {
                        "linux-fast" -> "libuml_kernel.so"
                        "stub_exe" -> "libuml_stub.so"
                        else -> artifact
                    }
                    pins[artifact] + "  " + packagedName + "\n"
                }
        )
        logger.lifecycle(
            "stageUmlFastProfile: staged ${staged.size + optionalStaged.count { File(profileRoot, it.key).isFile }} " +
                "fast-profile artifacts with matching pins; accounting manifest at " +
                relative(accounting) + "."
        )
    }
}
if (umlBundledFastProfile) {
    tasks.named("preBuild") { dependsOn(stageUmlFastProfile) }
}

// Gate 2 net-helper staging (ACR 2026-11-13 §8, umnet 候选 a), the
// stageUmlFastProfile discipline applied to the net supervisor pair. The
// artifacts are git-ignored machine inputs (built per the passt-android fork
// pin 25f9c1f+1a0c657 and the build-net-bionic recipe, check_bin three-check
// proven; round 2026-11-13 preflight sha256). They land in:
//   src/main/jniLibs/arm64-v8a/libuml_net.so    <- umnet_bionic (git-ignored)
//   src/main/jniLibs/arm64-v8a/libuml_passt.so  <- passt_bionic (git-ignored)
// and the accounting manifest is written to src/main/assets/uml-net-helper-v1.sha256
// (git-ignored build product; packaged names, two sha lines).
// Staging semantics, mirroring stageUmlFastProfile:
//   - neither the dir nor the compileBridge flag: the task is not scheduled —
//     a bare local build stays silent (the default);
//   - compileBridge on but no dir: the task runs, records the absence
//     (WARN + build/uml-net-helper-staging-skipped.flag) and skips — no
//     jniLibs, no accounting manifest, so the packaged APK carries no net
//     helper and the flag-on spawner answers SPAWN_FAILED
//     (uml-net-helper missing) honestly at runtime;
//   - a dir provided: fail-closed staging — missing artifacts or a pin
//     mismatch fail the build, never stage a half pair.
// 2026-11-14 Lane A (umlroute E2E): umnet_bionic repinned to the forward-enabled
// rebuild. The gate2-net build hardcodes passt `-t none` (no inbound forwarding,
// umnet.c harness), which left the D-1 uml-tcp lane's dial target
// (127.0.0.1:3632) with no listener — Connection refused, compile route dead.
// The rebuild changes exactly one thing: passt's TCP inbound spec becomes
// `-t 127.0.0.1/3632` — the loopback-only forward of the guest distccd port the
// D-1 lane dials (no UDP inbound, no LAN exposure; preflight ④'s expected bind).
// Patched source: umnet.c copied from the harness tree with the `-t` line and
// its comment updated; build recipe unchanged (NDK API 30, -static, 16K page).
// passt_bionic stays byte-identical to the Gate 2 pin.
//
// 2026-11-14 single-package round re-pin (same commit as the embed wiring):
// the original Lane A artifacts (04c065c8…/a8451a01…) were destroyed with their
// machine directories, so Lane T2-a rebuilt the pair from the same source pins
// (passt fork v0.2.0 + the same-repo umnet harness with the lane-A `-t
// 127.0.0.1/3632` patch). Both artifacts pass the check_bin three checks; the
// byte difference from the old pins is build-path DWARF source-path records
// only — the umnet semantics are unchanged (same 3632 inbound forward, same
// supervisor contract), which the rebuild's diff of the patched umnet.c and the
// passt inbound bind attested. Pin discipline is the same as the Lane A re-pin:
// changed artifact bytes → pins re-stated in the same commit that ships them.
val umlNetHelperPins: Map<String, String> = linkedMapOf(
    "umnet_bionic" to "858efb9b9e5ae6ba17207b93079bf7794283c954aa5d95e7ec458fc87d6367ae",
    "passt_bionic" to "f42171416bc1e0d78029fd16cf3ec3cff53b642a62246b4507c1464fabf6f9f9",
)

val stageUmlNetHelper = tasks.register("stageUmlNetHelper") {
    group = "verification"
    description = "Stages the umnet/passt net-helper bionic pair into jniLibs with sha accounting."
    onlyIf { umlCompileBridge || umlNetHelperDir != null }
    doLast {
        val repoRoot = projectDir
        val relative: (File) -> String = { it.relativeToOrSelf(repoRoot).invariantSeparatorsPath }
        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var read = input.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        if (umlNetHelperDir == null) {
            val flagFile =
                layout.buildDirectory.file("uml-net-helper-staging-skipped.flag").get().asFile
            flagFile.parentFile.mkdirs()
            flagFile.writeText(
                "timestamp=${Instant.now()}\n" +
                    "reason=-Porchestella.uml.compileBridge=true without " +
                    "-Porchestella.uml.netHelperDir\n" +
                    "detail=no net-helper artifacts in this checkout; the APK carries no " +
                    "libuml_net.so/libuml_passt.so and the compile-bridge spawner answers " +
                    "SPAWN_FAILED (uml-net-helper missing) honestly.\n"
            )
            logger.warn(
                "stageUmlNetHelper: WARNING: the compile bridge is on but no " +
                    "orchestella.uml.netHelperDir was provided; staging skipped and recorded " +
                    "in build/uml-net-helper-staging-skipped.flag. The packaged APK carries " +
                    "no net helper, so a flag-on face refuses to spawn with the named " +
                    "uml-net-helper missing detail instead of faking a routable kernel. " +
                    "Provide the pinned umnet/passt bionic pair on the packaging machine to " +
                    "stage it for real."
            )
            return@doLast
        }

        val helperRoot = rootProject.file(umlNetHelperDir)
        if (!helperRoot.isDirectory) {
            throw GradleException(
                "stageUmlNetHelper: orchestella.uml.netHelperDir is not an existing directory: " +
                    relative(helperRoot)
            )
        }

        // artifact name -> (staged payload path); the packaged names ride the
        // jniLibs convention (lib*.so) so the app domain execs them from
        // nativeLibraryDir.
        val staged = linkedMapOf(
            "umnet_bionic" to file("src/main/jniLibs/arm64-v8a/libuml_net.so"),
            "passt_bionic" to file("src/main/jniLibs/arm64-v8a/libuml_passt.so")
        )

        val problems = mutableListOf<String>()
        staged.forEach { (artifact, target) ->
            val source = File(helperRoot, artifact)
            if (!source.isFile) {
                problems += "  - missing artifact: $artifact (${relative(source)})"
                return@forEach
            }
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
            val actual = sha256Of(target)
            val expected = umlNetHelperPins[artifact]
            if (expected == null || !actual.equals(expected, ignoreCase = true)) {
                problems += "  - sha256 mismatch: $artifact expected=$expected actual=$actual"
            }
        }

        if (problems.isNotEmpty()) {
            problems.forEach { logger.error("stageUmlNetHelper: $it") }
            throw GradleException(
                "The umnet/passt net-helper staging failed sha accounting.\n" +
                    problems.joinToString("\n") +
                    "\nProvide the pinned pair (2026-11-14 rebuild, passt fork v0.2.0 + " +
                    "the lane-A umnet harness with the 127.0.0.1/3632 inbound forward; " +
                    "see the umlNetHelperPins comment) in orchestella.uml.netHelperDir, or " +
                    "rerun without -Porchestella.uml.netHelperDir."
            )
        }

        // Accounting manifest: packaged names, two sha lines, the same
        // <64-hex>␠␠<name> grammar the other manifests speak.
        val accounting = file("src/main/assets/uml-net-helper-v1.sha256")
        accounting.parentFile.mkdirs()
        accounting.writeText(
            staged.entries.joinToString("") { (artifact, _) ->
                val packagedName = when (artifact) {
                    "umnet_bionic" -> "libuml_net.so"
                    "passt_bionic" -> "libuml_passt.so"
                    else -> artifact
                }
                umlNetHelperPins[artifact] + "  " + packagedName + "\n"
            }
        )
        logger.lifecycle(
            "stageUmlNetHelper: staged ${staged.size} net-helper artifacts with matching pins; " +
                "accounting manifest at " + relative(accounting) + "."
        )
    }
}
if (umlCompileBridge || umlNetHelperDir != null) {
    tasks.named("preBuild") { dependsOn(stageUmlNetHelper) }
}

tasks.register<Copy>("packageOrchestellaRelease") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    into(rootProject.layout.buildDirectory.dir("release"))
    include("*.apk")
    rename { "Orchestella-Studio-v${android.defaultConfig.versionName}-release.apk" }
}

// Zero-device Fast Lane gates. None of these tasks touch adb, a device, or a
// release channel. The APK check reads the debug APK produced by AGP, which
// has already packaged bin/ as a resource before zipalign/signing.
val checkFastlaneLicenses = tasks.register<Exec>("checkFastlaneLicenses") {
    group = "verification"
    description = "Checks Fast Lane toolbox licenses, pinned SHA256 values and NOTICE/SBOM coverage."
    workingDir(rootProject.projectDir)
    commandLine(pythonExecutable, "tools/fastlane-build/fastlane_build.py", "check-licenses")
}

val verifyFastlaneToolboxApk = fastlaneToolboxDir?.let { toolboxProperty ->
    tasks.register<Exec>("verifyFastlaneToolboxApk") {
        group = "verification"
        description = "Verifies APK-root bin/ entries, SHA256 values, exec bits and 16 KB ELF alignment."
        dependsOn("assembleDebug")
        workingDir(rootProject.projectDir)
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val runtimeManifest = rootProject.file(toolboxProperty).resolve("bin/manifest.json")
        val command = mutableListOf(
            pythonExecutable,
            "tools/fastlane-build/fastlane_build.py",
            "verify-apk",
            "--apk",
            apk.absolutePath,
            "--runtime-manifest",
            runtimeManifest.absolutePath
        )
        val sdk = providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        // Same catalog source as buildToolsVersion above, otherwise the lookup
        // path silently drifts from the toolchain the APK was built with.
        val buildToolsDir = "build-tools/${libs.versions.buildtools.get()}"
        val zipalign = sdk?.let { File(it, "$buildToolsDir/zipalign") }
        val zipalignExe = sdk?.let { File(it, "$buildToolsDir/zipalign.exe") }
        val zipalignPath = when {
            zipalign?.isFile == true -> zipalign
            zipalignExe?.isFile == true -> zipalignExe
            else -> null
        }
        if (zipalignPath != null) {
            command += listOf("--zipalign", zipalignPath.absolutePath)
        }
        commandLine(command)
    }
}

// The payload gate is deliberately separate from the toolbox gate: it proves
// the APK entry itself is stored, aligned and exported for the AVF loader.
val verifyFastlanePayloadApk = fastlanePayloadLibDir?.let { payloadProperty ->
    tasks.register<Exec>("verifyFastlanePayloadApk") {
        group = "verification"
        description = "Verifies the packaged arm64 Fast Lane payload, its symbol, zipalign and the absence of libvm_payload.so."
        dependsOn("assembleDebug")
        workingDir(rootProject.projectDir)
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val sourceSo = rootProject.file(payloadProperty)
            .resolve("arm64-v8a/libfastlane_payload.so")
        val packagedSo = layout.buildDirectory.file(
            "intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libfastlane_payload.so"
        ).get().asFile
        val command = mutableListOf(
            pythonExecutable,
            "tools/fastlane-build/verify_payload_apk.py",
            "--apk",
            apk.absolutePath,
            "--source-so",
            sourceSo.absolutePath,
            "--packaged-so",
            packagedSo.absolutePath
        )
        val sdk = providers.environmentVariable("ANDROID_HOME").orNull
            ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        if (sdk != null) {
            command += listOf("--sdk", sdk)
        }
        commandLine(command)
    }
}

tasks.register("fastlaneZeroDeviceCheck") {
    group = "verification"
    description = "Runs the host-only Fast Lane JVM tests, license gate and, when configured, the APK toolbox gate."
    dependsOn(
        ":runtime:engine-fastlane:test",
        ":runtime:fastlane-terminal:test",
        ":runtime:fastlane-capability:test",
        ":runtime:fastlane-proxy:test",
        ":runtime:fastlane-storage:test",
        ":runtime:fastlane-telemetry:test",
        ":runtime:fastlane-distcc:test",
        checkFastlaneLicenses,
    )
    verifyFastlaneToolboxApk?.let { dependsOn(it) }
    verifyFastlanePayloadApk?.let { dependsOn(it) }
}

val syncGuestShellAssets by tasks.registering(Sync::class) {
    from(rootProject.file("guest-assets/shell"))
    into(file("src/main/assets/shell"))
}

val syncGuestInitAndRelay by tasks.registering(Copy::class) {
    from(rootProject.file("guest-assets/initramfs/init")) {
        rename { "guest_init" }
    }
    from(rootProject.file("guest-assets/scripts/orchestella-proxy-relay.pl")) {
        rename { "proxy_relay" }
    }
    into(file("src/main/resources"))
}

tasks.matching {
    it.name in listOf("mergeDebugAssets", "mergeReleaseAssets", "generateDebugAssets", "generateReleaseAssets", "preBuild", "preDebugBuild", "preReleaseBuild", "processDebugResources", "processReleaseResources", "processDebugJavaRes", "processReleaseJavaRes")
}.configureEach {
    dependsOn(syncGuestShellAssets, syncGuestInitAndRelay)
}

// The payload gate sits at the very front of assembly, next to
// syncGuestShellAssets/syncGuestInitAndRelay, so a checkout without the payload
// stops before any compilation or packaging work happens.
tasks.named("preBuild") {
    dependsOn(verifyRuntimeAssets)
}
