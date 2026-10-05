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

tasks.register<JavaExec>("avfQemuInteropTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfQemuInteropTest")
}

tasks.register<JavaExec>("avfNetworkAssemblyContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.engineavf.AvfNetworkAssemblyContractTest")
}

tasks.named("check") {
    dependsOn(
        "avfLaunchSpecTest",
        "avfVirtualMachineEngineTest",
        "avfBridgeOverVsockContractTest",
        "avfTerminalStreamContractTest",
        "avfInstanceLifecycleContractTest",
        "avfQemuInteropTest",
        "avfNetworkAssemblyContractTest",
    )
}
