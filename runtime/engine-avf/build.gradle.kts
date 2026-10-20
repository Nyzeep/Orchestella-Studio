plugins {
    `java-library`
}

dependencies {
    api(project(":runtime:contract"))
    // Test-only collaborators: the lifecycle contract test drives the normal
    // Instance state machine over the AVF adapter, the interop test
    // asserts root-volume/kernel/initramfs identity against the QEMU spec, and
    // the forwarding-parity test re-runs the network module's manager contract
    // suite with the AVF vsock engine (Ticket 36 AC-3). None is a main-scope
    // dependency; the engine stays strategy-neutral.
    testImplementation(project(":runtime:instance"))
    testImplementation(project(":runtime:engine-qemu"))
    testImplementation(project(":runtime:network"))
    // The strategy-neutral manager contract suite (test classes) re-run on the
    // AVF vsock engine by the forwarding-parity contract test (Ticket 36 AC-3).
    testImplementation(project(mapOf("path" to ":runtime:network", "configuration" to "testArtifacts")))
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
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchSpecTest")
}

tasks.register<JavaExec>("avfVirtualMachineEngineTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineEngineTest")
}

tasks.register<JavaExec>("avfBridgeOverVsockContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfBridgeOverVsockContractTest")
}

val avf9pShareOutput = layout.buildDirectory.dir("avf-9p-share-contract")
tasks.register<JavaExec>("avf9pShareContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.Avf9pShareContractTest")
    // the scenarios create temp dirs on their own; the output dir keeps the
    // task side-effect free for Gradle's up-to-date checks
    outputs.dir(avf9pShareOutput)
    doFirst { avf9pShareOutput.get().asFile.mkdirs() }
}

tasks.register<JavaExec>("avfTerminalStreamContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfTerminalStreamContractTest")
}

val avfLifecycleOutput = layout.buildDirectory.dir("avf-lifecycle-contract")
tasks.register<JavaExec>("avfInstanceLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfInstanceLifecycleContractTest")
    args(avfLifecycleOutput.get().asFile.absolutePath)
}

val avfReconcileOutput = layout.buildDirectory.dir("avf-reconcile-contract")
tasks.register<JavaExec>("avfReconciliationLifecycleContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(
        "io.github.nyzeep.orchestella.runtime.engineavf.AvfReconciliationLifecycleContractTest")
    args(avfReconcileOutput.get().asFile.absolutePath)
}

tasks.register<JavaExec>("avfQemuInteropTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfQemuInteropTest")
}

tasks.register<JavaExec>("avfNetworkAssemblyContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfNetworkAssemblyContractTest")
}

tasks.register<JavaExec>("avfVsockPortForwarderTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfVsockPortForwarderTest")
}

tasks.register<JavaExec>("avfNetworkForwardingParityContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfNetworkForwardingParityContractTest")
}

tasks.register<JavaExec>("avfWaylandChannelContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfWaylandChannelContractTest")
}

tasks.register<JavaExec>("avfGraphicsVsockProtocolTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfGraphicsVsockProtocolTest")
}

tasks.register<JavaExec>("avfInputEventForwardingTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfInputEventForwardingTest")
}

tasks.register<JavaExec>("avfAudioVsockProtocolTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfAudioVsockProtocolTest")
}

tasks.register<JavaExec>("avfClipboardOverVsockTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfClipboardOverVsockTest")
}

tasks.register<JavaExec>("avfInputEventForwardingParityTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfInputEventForwardingParityTest")
}

tasks.register<JavaExec>("avfCapabilityMatrixAudioTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfCapabilityMatrixAudioTest")
}

tasks.register<JavaExec>("reflectiveAvfVirtualMachineApiTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfVirtualMachineApiTest")
}

tasks.register<JavaExec>("avfVirtualMachineEngineResourceTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineEngineResourceTest")
}

tasks.register<JavaExec>("avfRuntimeOwnershipTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.engineavf.AvfRuntimeOwnershipTest")
}

val avfAllTestTasks = listOf(
    "reflectiveAvfVirtualMachineApiTest",
    "avfRuntimeOwnershipTest",
    "avfVirtualMachineEngineResourceTest",
    "avfLaunchSpecTest",
    "avfVirtualMachineEngineTest",
    "avfBridgeOverVsockContractTest",
    "avf9pShareContractTest",
    "avfTerminalStreamContractTest",
    "avfInstanceLifecycleContractTest",
    "avfReconciliationLifecycleContractTest",
    "avfQemuInteropTest",
    "avfNetworkAssemblyContractTest",
    "avfVsockPortForwarderTest",
    "avfNetworkForwardingParityContractTest",
    "avfWaylandChannelContractTest",
    "avfGraphicsVsockProtocolTest",
    "avfInputEventForwardingTest",
    "avfAudioVsockProtocolTest",
    "avfClipboardOverVsockTest",
    "avfInputEventForwardingParityTest",
    "avfCapabilityMatrixAudioTest",
)

tasks.named("test") {
    dependsOn(avfAllTestTasks)
}
