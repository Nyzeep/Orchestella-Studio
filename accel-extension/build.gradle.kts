import java.nio.file.Files

// W-A (session 18): the two-package accelerator split. This module packages
// the pVM payload and compiler engine that the main :app module no longer
// ships by default: arm64-v8a/libfastlane_payload.so, the engine libraries
// named by PVM-COMPILER-MANIFEST, and the manifest asset itself. The main
// package locates this APK at runtime (PackageManager query on
// <applicationId>.accel) and redirects the protected-VM payload to it via
// VirtualMachineConfig.Builder.setApkPath — the payload binary is loaded by
// the platform from an APK, never from a nativeLibraryDir.
//
// Both payload inputs reuse the same Gradle properties the main module
// historically consumed, so one staging directory feeds either packaging
// shape. Unlike :app, the properties are REQUIRED here: an extension APK
// without the payload it advertises would be a lie in package form.

val fastlanePayloadLibDir: String? = providers.gradleProperty("orchestella.fastlane.payloadLibDir")
    .orNull
    ?.trim()
    ?.ifEmpty { null }
val pvmCompilerLibDir: String? = providers.gradleProperty("orchestella.pvmCompiler.libDir")
    .orNull
    ?.trim()
    ?.ifEmpty { null }

// Inputs are validated at EXECUTION time, not configuration time: a plain
// repo build (which never passes payload properties) must still configure
// every module, including this one. Assembling the extension without both
// staging roots fails right before packaging with the actionable message.

val payloadRoot: File? = fastlanePayloadLibDir?.let { rootProject.file(it) }
val compilerRoot: File? = pvmCompilerLibDir?.let { rootProject.file(it) }

val validateExtensionPayloadInputs = tasks.register("validateExtensionPayloadInputs") {
    group = "verification"
    description = "Fails unless both pVM payload staging roots are present and well-formed."
    doLast {
        val problems = buildList {
            if (fastlanePayloadLibDir == null) {
                add("orchestella.fastlane.payloadLibDir is missing")
            }
            if (pvmCompilerLibDir == null) {
                add("orchestella.pvmCompiler.libDir is missing")
            }
            if (fastlanePayloadLibDir != null && fastlanePayloadLibDir == pvmCompilerLibDir) {
                add("orchestella.pvmCompiler.libDir must differ from orchestella.fastlane.payloadLibDir")
            }
            payloadRoot?.let { root ->
                if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
                    add("orchestella.fastlane.payloadLibDir must be an existing ordinary directory: $root")
                } else if (!File(root, "arm64-v8a/libfastlane_payload.so").isFile) {
                    add("Fast Lane payload is missing arm64-v8a/libfastlane_payload.so: $root")
                } else if (File(root, "arm64-v8a/libvm_payload.so").exists()) {
                    add("libvm_payload.so is an AVF APEX library and must not be packaged from $root")
                }
                Unit
            }
            compilerRoot?.let { root ->
                if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
                    add("orchestella.pvmCompiler.libDir must be an existing ordinary directory: $root")
                } else {
                    if (!File(root, "arm64-v8a/libLLVM.so").isFile) {
                        add("PVM compiler engine is missing arm64-v8a/libLLVM.so: $root")
                    }
                    if (File(root, "arm64-v8a/libfastlane_payload.so").exists()) {
                        add("libfastlane_payload.so comes from orchestella.fastlane.payloadLibDir " +
                            "and must not be duplicated in $root")
                    }
                    if (!File(root, "assets/PVM-COMPILER-MANIFEST").isFile) {
                        add("PVM compiler manifest is missing: assets/PVM-COMPILER-MANIFEST under " +
                            "orchestella.pvmCompiler.libDir (regenerate the staging directory with " +
                            "tools/pvm-compiler/assemble_payload_libs.py)")
                    }
                }
                Unit
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                ":accel-extension payload inputs are invalid — the two-package split moves the " +
                    "pVM assets out of :app, so there is no payload-less variant of this module:\n  " +
                    problems.joinToString("\n  ")
            )
        }
    }
}

// Channels mirror :app so the extension's applicationId is the main package's
// applicationId plus ".accel" for every channel (the runtime derives the
// extension name exactly that way — AccelExtensionRules.extensionPackageName).
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
        "orchestella.buildChannel=qa requires orchestella.buildPurpose " +
            "(mirrors the :app channel contract)"
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

// Release signing mirrors :app — same four orchestella.signing.* properties,
// and the same hard failure instead of a debug-signed package that looks
// shippable. The extension specifically needs the v3 APK Signature Scheme
// block: the AVF payload loader materializes an idsig from it when
// VirtualMachineConfig.setApkPath points at this APK, so a v2-only key would
// break the lane this package exists for.
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

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.nyzeep.orchestella.accel"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        // Base id mirrors :app; the channel suffix below completes the mirror.
        applicationId = "io.github.nyzeep.orchestella"
        minSdk = 26
        // W^X precedent (QA-verified): targetSdk 28 lands in the legacy
        // untrusted_app_27 domain. This module runs no code and spawns no
        // processes — the value is kept at 28 for uniformity with the
        // documented accelerator-lane device baseline, not out of necessity.
        targetSdk = 28
        // D4 version lock: the runtime resolver refuses an extension whose
        // versionCode differs from the main package. Keep in lockstep with
        // :app's versionCode — the mismatch surfaces as VERSION_MISMATCH.
        versionCode = 601
        versionName = "0.6.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // Session 19 (T-A.4): the AVF payload loader reads the v3 APK
        // Signature Scheme block when it materializes an idsig for
        // VirtualMachineConfig.setApkPath. A v2-only extension APK is refused
        // at VM run with "No APK Signature Scheme block ... ID: 4031998144".
        // v4 keeps the idsig file next to the APK, matching :app's debug config.
        getByName("debug") {
            enableV3Signing = true
            enableV4Signing = true
        }
        // Created only when all four properties are present; a release build
        // without them fails in validateReleaseSigning before packaging.
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(signingStoreFileProperty!!)
                storePassword = signingStorePasswordProperty
                keyAlias = signingKeyAliasProperty
                keyPassword = signingKeyPasswordProperty
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // The ".accel" tail marks the extension itself; the runtime only ever
            // appends ".accel" to the MAIN package's id, so it must be present for
            // every channel, including a release-channel debug packaging check.
            applicationIdSuffix = (internalApplicationIdSuffix ?: "") + ".accel"
            if (buildChannel != "release") {
                versionNameSuffix = (internalVersionSuffix ?: "") + ".accel"
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        release {
            // Session 27: the suffix used to live only in the debug block, so a release
            // build shipped `io.github.nyzeep.orchestella` — byte-for-byte the MAIN
            // application id, with the same versionCode and the same signing key. Installing
            // it would have replaced the user's app with a package that has no launcher.
            // The extension identity is `<main id>.accel` in every build type.
            applicationIdSuffix = ".accel"
            // The published extension is signed with the release key when it is
            // configured. Without it the build fails (see
            // validateReleaseSigning) rather than shipping a debug-signed APK
            // under the release applicationId; local packaging checks should
            // use assembleDebug instead.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = false
    }

    sourceSets.getByName("main") {
        if (payloadRoot != null) {
            jniLibs.srcDir(payloadRoot)
        }
        if (compilerRoot != null) {
            jniLibs.srcDir(compilerRoot)
            assets.srcDir(File(compilerRoot, "assets"))
        }
    }

    packaging {
        jniLibs {
            // Same contract as :app payload builds: the AVF loader requires
            // uncompressed, page-aligned .so entries inside the APK.
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += "PropertyEscape"
        // targetSdk 28 is pinned on purpose (see defaultConfig): this package ships no
        // code and its only consumer is the AVF payload loader, but the extension still
        // mirrors the main package's documented accelerator baseline. The Play-store
        // rule below does not apply to a self-distributed APK, so the check would only
        // block our own release build.
        disable += "ExpiredTargetSdkVersion"
    }
}

// Fail only release packaging when the explicit signing material is missing;
// debug builds (the internal channels and the CI packaging fixture) keep working.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Fails unless the four explicit release signing properties are present and readable."
    doLast {
        if (missingSigningProperties.isNotEmpty()) {
            throw GradleException(
                "Extension release signing is not configured. Missing Gradle properties: " +
                    missingSigningProperties.joinToString(", ") +
                    ". The published extension must carry the same release identity as the " +
                    "main package; use :accel-extension:assembleDebug for local packaging checks."
            )
        }
        val keystore = file(signingStoreFileProperty!!)
        if (!keystore.isFile) {
            throw GradleException(
                "Release signing storeFile does not exist or is not a regular file: $keystore"
            )
        }
    }
}
tasks.matching {
    it.name in setOf("assembleRelease", "bundleRelease", "installRelease", "packageRelease", "preReleaseBuild")
}.configureEach {
    dependsOn(validateReleaseSigning)
}

// Channel-qualified output name, mirroring :app's renameChannelApk pattern.
val channelApkName: String? = internalVersionSuffix?.let {
    "Orchestella-Accelerator-v${android.defaultConfig.versionName}$it.apk"
}
if (buildChannel != "release" && channelApkName != null) {
    val renameChannelApk = tasks.register<Copy>("renameChannelApk") {
        description = "Copies the extension debug APK to its channel-qualified name."
        from(layout.buildDirectory.file("outputs/apk/debug/accel-extension-debug.apk"))
        into(layout.buildDirectory.dir("outputs/apk/channel"))
        rename { channelApkName }
    }
    tasks.matching { it.name == "assembleDebug" }.configureEach {
        finalizedBy(renameChannelApk)
    }
}

// Size report hook for the dual-package CI comparison: prints the produced
// APK sizes so the build log carries the evidence directly.
val reportExtensionApkSize = tasks.register("reportExtensionApkSize") {
    group = "verification"
    description = "Prints the accelerator extension APK size for the dual-package comparison."
    doLast {
        val apks = layout.buildDirectory.dir("outputs/apk/debug").get().asFileTree
            .matching { include("*.apk") }
        apks.files
            .sortedByDescending { it.length() }
            .forEach { apk ->
                println("ACCEL-EXT-SIZE: ${apk.name} ${apk.length()} bytes")
            }
    }
}
tasks.matching { it.name == "assembleDebug" }.configureEach {
    mustRunAfter(validateExtensionPayloadInputs)
    dependsOn(validateExtensionPayloadInputs)
    finalizedBy(reportExtensionApkSize)
}
