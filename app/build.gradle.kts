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
    require(buildPurpose.matches(Regex("[a-z0-9-]{1,32}"))) {
        "orchestella.buildPurpose must use only lowercase letters, digits and hyphens: '$buildPurpose'"
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
        targetSdk = 36
        versionCode = 503
        versionName = "0.5.3"
        buildConfigField("boolean", "DEVELOPMENT_PREVIEW", "true")

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

    signingConfigs {
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
            } else {
                // Historical default behavior: phase5 QA wins, otherwise a fully
                // configured release key may sign debug builds on developer machines.
                val releaseSigning = signingConfigs.findByName("release")
                if (signingConfigs.findByName("phase5Debug") != null) {
                    applicationIdSuffix = ".phase5"
                    versionNameSuffix = "-phase5-local"
                    signingConfig = signingConfigs.getByName("phase5Debug")
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
            useLegacyPackaging = true
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
    implementation(project(":runtime:network"))
    implementation(libs.material)
    implementation(libs.appcompat)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
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
        "io.github.nyzeep.orchestella.QemuWaylandChannelTest",
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
