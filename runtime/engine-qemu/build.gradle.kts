plugins {
    `java-library`
}

dependencies {
    api(project(":runtime:contract"))
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

tasks.register<JavaExec>("qemuBridgeAdapterTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineqemu.QemuBridgeAdapterTest")
}

val runtimeArtifactManifestOutput = layout.buildDirectory.dir("runtime-artifact-manifest-contract")
tasks.register<JavaExec>("runtimeArtifactManifestTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeArtifactManifestTest")
    args(runtimeArtifactManifestOutput.get().asFile.absolutePath)
}

tasks.register<JavaExec>("runtimeAssetInstallerTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeAssetInstallerTest")
    args(layout.buildDirectory.dir("runtime-asset-installer-test").get().asFile.absolutePath)
}

tasks.register<JavaExec>("embeddedProvisionInputTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineqemu.EmbeddedProvisionInputTest")
}

tasks.register<JavaExec>("qemuLaunchSpecTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineqemu.QemuLaunchSpecTest")
}

tasks.register<JavaExec>("runtimeArtifactManifestSchemaTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineqemu.RuntimeArtifactManifestSchemaTest")
}

tasks.register<JavaExec>("qemuHostfwdContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineqemu.QemuHostfwdContractTest")
}

tasks.named("check") {
    dependsOn(
        "qemuBridgeAdapterTest",
        "runtimeArtifactManifestTest",
        "runtimeArtifactManifestSchemaTest",
        "runtimeAssetInstallerTest",
        "embeddedProvisionInputTest",
        "qemuLaunchSpecTest",
        "qemuHostfwdContractTest",
    )
}
