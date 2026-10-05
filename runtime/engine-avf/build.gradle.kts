plugins {
    `java-library`
}

dependencies {
    api(project(":runtime:contract"))
    // Test-only collaborators: the lifecycle contract test drives the normal
    // Instance state machine over the AVF adapter, and the interop test
    // asserts root-volume/kernel/initramfs identity against the QEMU spec.
    // Neither is a main-scope dependency; the engine stays strategy-neutral.
    testImplementation(project(":runtime:instance"))
    testImplementation(project(":runtime:engine-qemu"))
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

tasks.register<JavaExec>("avfLaunchSpecTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfLaunchSpecTest")
}

tasks.register<JavaExec>("avfVirtualMachineEngineTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfVirtualMachineEngineTest")
}

tasks.register<JavaExec>("avfBridgeOverVsockContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfBridgeOverVsockContractTest")
}

val avf9pShareOutput = layout.buildDirectory.dir("avf-9p-share-contract")
tasks.register<JavaExec>("avf9pShareContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.Avf9pShareContractTest")
    // the scenarios create temp dirs on their own; the output dir keeps the
    // task side-effect free for Gradle's up-to-date checks
    outputs.dir(avf9pShareOutput)
    doFirst { avf9pShareOutput.get().asFile.mkdirs() }
}

tasks.register<JavaExec>("avfTerminalStreamContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfTerminalStreamContractTest")
}

val avfLifecycleOutput = layout.buildDirectory.dir("avf-lifecycle-contract")
tasks.register<JavaExec>("avfInstanceLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfInstanceLifecycleContractTest")
    args(avfLifecycleOutput.get().asFile.absolutePath)
}

val avfReconcileOutput = layout.buildDirectory.dir("avf-reconcile-contract")
tasks.register<JavaExec>("avfReconciliationLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(
        "io.github.nyzeep.droidlayer.runtime.engineavf.AvfReconciliationLifecycleContractTest")
    args(avfReconcileOutput.get().asFile.absolutePath)
}

tasks.register<JavaExec>("avfQemuInteropTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfQemuInteropTest")
}

tasks.named("check") {
    dependsOn(
        "avfLaunchSpecTest",
        "avfVirtualMachineEngineTest",
        "avfBridgeOverVsockContractTest",
        "avf9pShareContractTest",
        "avfTerminalStreamContractTest",
        "avfInstanceLifecycleContractTest",
        "avfReconciliationLifecycleContractTest",
        "avfQemuInteropTest",
    )
}
