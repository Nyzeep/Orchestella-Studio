plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.register<JavaExec>("distroManifestContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.distro.DistroManifestContractTest")
}

tasks.register<JavaExec>("officialAlpineCatalogTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.distro.OfficialAlpineCatalogTest")
}

tasks.register<JavaExec>("officialDebianCatalogTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.distro.OfficialDebianCatalogTest")
}

tasks.register<JavaExec>("distroFlavorTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.distro.DistroFlavorTest")
}

tasks.named("check") {
    dependsOn(
        "distroManifestContractTest",
        "officialAlpineCatalogTest",
        "officialDebianCatalogTest",
        "distroFlavorTest"
    )
}
