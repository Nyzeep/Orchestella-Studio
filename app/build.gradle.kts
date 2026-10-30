import java.nio.file.Files
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

// AX-08: the accelerator lane is a build-time opt-in and defaults to off, so a
// plain build keeps the historical QEMU-only behaviour. Enabling it only makes
// the assembly ask AcceleratorPolicy; a QEMU_ONLY verdict still refuses the lane.
val acceleratorLaneEnabled: Boolean = providers.gradleProperty("orchestella.acceleratorLane")
    .orNull
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" }
    ?: false

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.nyzeep.orchestella"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "27.0.12077973"

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
        // 0.6.1 / 601 — the rootfs-catalog repair: every distro but Ubuntu could not
        // download at all (pruned LXC snapshots + a self-hosted release that did not
        // exist), and the mirror probe now ranks by sampled transfer speed instead of
        // HEAD latency. No new user-visible feature, so PATCH (VERSIONING.md §3.2).
        // versionCode = MAJOR*10000 + MINOR*100 + PATCH (VERSIONING.md §2).
        versionCode = 601
        versionName = "0.6.1"
        buildConfigField("boolean", "DEVELOPMENT_PREVIEW", "true")
        buildConfigField("boolean", "ACCELERATOR_LANE_ENABLED", acceleratorLaneEnabled.toString())
        // Payload builds store native libraries uncompressed and 16 KB aligned
        // for the AVF loader; all other channels keep the extracted layout.
        manifestPlaceholders["orcExtractNativeLibs"] =
            if (fastlanePayloadLibDir == null) "true" else "false"
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
            // AVF loads the app payload from the APK and requires an
            // uncompressed, 16 KB-aligned .so entry. Payload builds therefore
            // use direct (non-legacy) native-library packaging; every other
            // build keeps the historical extracted layout.
            useLegacyPackaging = fastlanePayloadLibDir == null
            keepDebugSymbols += "**/liborchestella_qemu.so"
            keepDebugSymbols += "**/libdroidlayer_qemu.so"
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
// channel-identifying copy required by VERSIONING.md section 5.3.
if (buildChannel != "release") {
    val renameChannelApk = tasks.register<Copy>("renameChannelApk") {
        description = "Copies the internal debug APK to its channel-qualified name."
        from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
        into(layout.buildDirectory.dir("outputs/apk/channel"))
        rename { channelApkName!! }
    }
    tasks.matching { it.name == "assembleDebug" }.configureEach {
        finalizedBy(renameChannelApk)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
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
    implementation(libs.material)
    implementation(libs.appcompat)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Pin the in-process hidden-API exemption (Apache-2.0, LSPosed). It grants
    // no permission and is installed once before any AVF class load.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    androidTestImplementation(project(":runtime:engine-fastlane"))
    // DV-PVM-02: the QA instrumentation bridges the payload's vsock port with
    // the production DistccTcpVsockBridge instead of a second byte pump.
    androidTestImplementation(project(":runtime:fastlane-distcc"))
    androidTestImplementation(project(":runtime:fastlane-terminal"))
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
        val zipalign = sdk?.let { File(it, "build-tools/36.1.0/zipalign") }
        val zipalignExe = sdk?.let { File(it, "build-tools/36.1.0/zipalign.exe") }
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
