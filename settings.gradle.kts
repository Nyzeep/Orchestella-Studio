pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Orchestella-Studio"
include(":app")
include(":runtime:contract")
include(":runtime:distro")
include(":runtime:instance")
include(":runtime:capability")
include(":runtime:engine-qemu")
include(":runtime:engine-avf")
include(":runtime:network")
