pluginManagement {
    // Mirrors are an explicit regional opt-in, not a fallback on HTTP 5xx.
    val useAliyunMirror = providers.gradleProperty("orchestella.repositories.useAliyunMirror")
        .map { it.toBooleanStrict() }
        .getOrElse(false)
    repositories {
        if (useAliyunMirror) {
            maven {
                name = "aliyunGoogle"
                url = uri("https://maven.aliyun.com/repository/google")
                content {
                    includeGroupByRegex("androidx(\\..*)?")
                    includeGroupByRegex("com\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google(\\..*)?")
                }
            }
            maven {
                name = "aliyunPublic"
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
        google()
        mavenCentral()
        // Plugin markers (for example org.jetbrains.kotlin.android) are
        // published to the Gradle Plugin Portal. Maven Central mirrors them
        // inconsistently and rate-limits CI, so plugins resolve from the
        // canonical portal while project dependencies stay on Google/Maven.
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val useAliyunMirror = providers.gradleProperty("orchestella.repositories.useAliyunMirror")
        .map { it.toBooleanStrict() }
        .getOrElse(false)
    repositories {
        if (useAliyunMirror) {
            maven {
                name = "aliyunGoogle"
                url = uri("https://maven.aliyun.com/repository/google")
                content {
                    includeGroupByRegex("androidx(\\..*)?")
                    includeGroupByRegex("com\\.android(\\..*)?")
                    includeGroupByRegex("com\\.google(\\..*)?")
                }
            }
            maven {
                name = "aliyunPublic"
                url = uri("https://maven.aliyun.com/repository/public")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Orchestella-Studio"
include(":app")
include(":accel-extension")
include(":runtime:contract")
include(":runtime:distro")
include(":runtime:instance")
include(":runtime:capability")
include(":runtime:engine-qemu")
include(":runtime:engine-avf")
include(":runtime:engine-fastlane")
include(":runtime:fastlane-distcc")
include(":runtime:fastlane-telemetry")
include(":runtime:fastlane-capability")
include(":runtime:fastlane-storage")
include(":runtime:fastlane-proxy")
include(":runtime:fastlane-terminal")
include(":runtime:network")
