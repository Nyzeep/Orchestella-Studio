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

val normalLifecycleOutput = layout.buildDirectory.dir("normal-lifecycle-contract")
tasks.register<JavaExec>("normalInstanceLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.NormalInstanceLifecycleContractTest")
    args(normalLifecycleOutput.get().asFile.absolutePath)
}

val recoveryLifecycleOutput = layout.buildDirectory.dir("recovery-lifecycle-contract")
tasks.register<JavaExec>("reconciliationAndRecoveryLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.ReconciliationAndRecoveryLifecycleContractTest")
    args(recoveryLifecycleOutput.get().asFile.absolutePath)
}

val storageContractOutput = layout.buildDirectory.dir("storage-contract")
tasks.register<JavaExec>("instanceStorageContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.InstanceStorageContractTest")
    args(storageContractOutput.get().asFile.absolutePath)
}

val deleteContractOutput = layout.buildDirectory.dir("delete-lifecycle-contract")
tasks.register<JavaExec>("instanceLifecycleDeleteTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycleDeleteTest")
    args(deleteContractOutput.get().asFile.absolutePath)
}

val cloneContractOutput = layout.buildDirectory.dir("clone-lifecycle-contract")
tasks.register<JavaExec>("instanceLifecycleCloneTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycleCloneTest")
    args(cloneContractOutput.get().asFile.absolutePath)
}

tasks.register<JavaExec>("sparseDiskResizerTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.SparseDiskResizerTest")
}

tasks.register<JavaExec>("instanceUserConfigTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfigTest")
}

val powerControllerOutput = layout.buildDirectory.dir("power-controller-test")
tasks.register<JavaExec>("instancePowerControllerTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.InstancePowerControllerTest")
    args(powerControllerOutput.get().asFile.absolutePath)
}

val snapshotManagerOutput = layout.buildDirectory.dir("snapshot-manager-test")
tasks.register<JavaExec>("instanceSnapshotManagerTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.instance.InstanceSnapshotManagerTest")
    args(snapshotManagerOutput.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(
        "normalInstanceLifecycleContractTest",
        "reconciliationAndRecoveryLifecycleContractTest",
        "instanceStorageContractTest",
        "instanceLifecycleDeleteTest",
        "instanceLifecycleCloneTest",
        "sparseDiskResizerTest",
        "instanceUserConfigTest",
        "instancePowerControllerTest",
        "instanceSnapshotManagerTest"
    )
}

tasks.named<Test>("test") {
    dependsOn("instancePowerControllerTest", "instanceSnapshotManagerTest")
}

