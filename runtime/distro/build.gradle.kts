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

tasks.register<JavaExec>("officialAlpineCatalogTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.OfficialAlpineCatalogTest")
}

tasks.register<JavaExec>("officialDebianCatalogTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalogTest")
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

tasks.register<JavaExec>("alpineEssentialPackageSuiteTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.distro.AlpineEssentialPackageSuiteTest")
}

val allCustomTestTasks = listOf(
    "distroManifestContractTest",
    "officialAlpineCatalogTest",
    "officialDebianCatalogTest",
    "distroFlavorTest",
    "packageManagerTypeTest",
    "distroCatalogRegistryTest",
    "alpineEssentialPackageSuiteTest"
)

tasks.named<Test>("test") {
    dependsOn(allCustomTestTasks)
}

tasks.named("check") {
    dependsOn(allCustomTestTasks)
}


