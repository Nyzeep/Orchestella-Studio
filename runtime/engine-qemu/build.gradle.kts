plugins {
    `java-library`
}

dependencies {
    api(project(":runtime:contract"))
    testImplementation(libs.junit)
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

tasks.register<JavaExec>("qemuProcessLauncherTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncherTest")
}

tasks.register<JavaExec>("qemuBridgeAdapterTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.QemuBridgeAdapterTest")
}

val runtimeArtifactManifestOutput = layout.buildDirectory.dir("runtime-artifact-manifest-contract")
tasks.register<JavaExec>("runtimeArtifactManifestTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifestTest")
    args(runtimeArtifactManifestOutput.get().asFile.absolutePath)
}

tasks.register<JavaExec>("runtimeAssetInstallerTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeAssetInstallerTest")
    args(layout.buildDirectory.dir("runtime-asset-installer-test").get().asFile.absolutePath)
}

tasks.register<JavaExec>("embeddedProvisionInputTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.EmbeddedProvisionInputTest")
}

tasks.register<JavaExec>("qemuLaunchSpecTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.QemuLaunchSpecTest")
}

tasks.register<JavaExec>("qemuGpuLaunchSpecTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.QemuGpuLaunchSpecTest")
}

tasks.register<JavaExec>("virglMemoryQuotaTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.VirglMemoryQuotaTest")
}

tasks.register<JavaExec>("runtimeArtifactManifestSchemaTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifestSchemaTest")
}

tasks.register<JavaExec>("qemuHostfwdContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineqemu.QemuHostfwdContractTest")
}

tasks.named("check") {
    dependsOn(
        "qemuProcessLauncherTest",
        "qemuBridgeAdapterTest",
        "runtimeArtifactManifestTest",
        "runtimeArtifactManifestSchemaTest",
        "runtimeAssetInstallerTest",
        "embeddedProvisionInputTest",
        "qemuLaunchSpecTest",
        "qemuGpuLaunchSpecTest",
        "virglMemoryQuotaTest",
        "qemuHostfwdContractTest",
    )
}

