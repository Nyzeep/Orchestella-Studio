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

val normalLifecycleOutput = layout.buildDirectory.dir("normal-lifecycle-contract")
tasks.register<JavaExec>("normalInstanceLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.instance.NormalInstanceLifecycleContractTest")
    args(normalLifecycleOutput.get().asFile.absolutePath)
}

val recoveryLifecycleOutput = layout.buildDirectory.dir("recovery-lifecycle-contract")
tasks.register<JavaExec>("reconciliationAndRecoveryLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.instance.ReconciliationAndRecoveryLifecycleContractTest")
    args(recoveryLifecycleOutput.get().asFile.absolutePath)
}

val storageContractOutput = layout.buildDirectory.dir("storage-contract")
tasks.register<JavaExec>("instanceStorageContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.instance.InstanceStorageContractTest")
    args(storageContractOutput.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(
        "normalInstanceLifecycleContractTest",
        "reconciliationAndRecoveryLifecycleContractTest",
        "instanceStorageContractTest"
    )
}
