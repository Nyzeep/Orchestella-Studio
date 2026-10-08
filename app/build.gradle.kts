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
val qemuNativeLibDir: String? = properties["droidlayer.qemu.nativeLibDir"] as String?
val qemuAssetsDir: String? = properties["droidlayer.qemu.assetsDir"] as String?
val qemuInitramfsDir: String? = properties["droidlayer.qemu.initramfsDir"] as String?

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.nyzeep.droidlayer"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.nyzeep.droidlayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        create("release") {
            val keystorePath = (properties["orchestella.signing.storeFile"] as String?)
            val keystoreFile = if (keystorePath != null) {
                file(keystorePath)
            } else {
                rootProject.file(".toolchains/release.keystore")
            }
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = (properties["orchestella.signing.storePassword"] as String?) ?: "orchestella2026"
                keyAlias = (properties["orchestella.signing.keyAlias"] as String?) ?: "orchestella"
                keyPassword = (properties["orchestella.signing.keyPassword"] as String?) ?: "orchestella2026"
            }
        }
    }

    buildTypes {
        debug {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libdroidlayer_qemu.so"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += "PropertyEscape"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":runtime:capability"))
    implementation(project(":runtime:distro"))
    implementation(project(":runtime:engine-avf"))
    implementation(project(":runtime:engine-qemu"))
    implementation(project(":runtime:instance"))
    implementation(project(":runtime:network"))
    implementation(libs.material)
    implementation(libs.appcompat)
    testImplementation(libs.junit)
}

tasks.register<Copy>("packageOrchestellaRelease") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release"))
    into(rootProject.layout.buildDirectory.dir("release"))
    include("*.apk")
    rename { "Orchestella-Studio-v${android.defaultConfig.versionName}.apk" }
}

val syncGuestShellAssets by tasks.registering(Sync::class) {
    from(rootProject.file("guest-assets/shell"))
    into(file("src/main/assets/shell"))
}

val syncGuestInitAndRelay by tasks.registering(Copy::class) {
    from(rootProject.file("guest-assets/initramfs/init")) {
        rename { "guest_init" }
    }
    from(rootProject.file("guest-assets/scripts/droidlayer-proxy-relay.pl")) {
        rename { "proxy_relay" }
    }
    into(file("src/main/resources"))
}

tasks.matching {
    it.name in listOf("mergeDebugAssets", "mergeReleaseAssets", "generateDebugAssets", "generateReleaseAssets", "preBuild", "preDebugBuild", "preReleaseBuild", "processDebugResources", "processReleaseResources", "processDebugJavaRes", "processReleaseJavaRes")
}.configureEach {
    dependsOn(syncGuestShellAssets, syncGuestInitAndRelay)
}
