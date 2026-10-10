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
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.DistroManifestContractTest")
}

tasks.register<JavaExec>("officialDebianCatalogTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalogTest")
}

tasks.register<JavaExec>("officialAlmaLinuxCatalogTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.OfficialAlmaLinuxCatalogTest")
}

tasks.register<JavaExec>("officialRockyLinuxCatalogTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.OfficialRockyLinuxCatalogTest")
}

tasks.register<JavaExec>("distroFlavorTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.DistroFlavorTest")
}

tasks.register<JavaExec>("packageManagerTypeTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.PackageManagerTypeTest")
}

tasks.register<JavaExec>("distroCatalogRegistryTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistryTest")
}

val allCustomTestTasks = listOf(
    "distroManifestContractTest",
    "officialDebianCatalogTest",
    "officialAlmaLinuxCatalogTest",
    "officialRockyLinuxCatalogTest",
    "distroFlavorTest",
    "packageManagerTypeTest",
    "distroCatalogRegistryTest"
)

tasks.named<Test>("test") {
    dependsOn(allCustomTestTasks)
}

tasks.named("check") {
    dependsOn(allCustomTestTasks)
}


