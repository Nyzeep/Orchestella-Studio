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

tasks.register<JavaExec>("bridgeHandshakeContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.BridgeHandshakeContractTest")
}

tasks.register<JavaExec>("bridgeControlContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.BridgeControlContractTest")
}

tasks.register<JavaExec>("fullLinuxContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.FullLinuxContractTest")
}

tasks.register<JavaExec>("networkContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.NetworkContractTest")
}

tasks.register<JavaExec>("bridgeClientContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.BridgeClientContractTest")
}

tasks.register<JavaExec>("portForwardingContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.PortForwardingContractTest")
}

tasks.register<JavaExec>("sharedDirectoryContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContractTest")
}

tasks.register<JavaExec>("storageContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.StorageContractTest")
}

tasks.register<JavaExec>("displayControlContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.DisplayControlContractTest")
}

tasks.register<JavaExec>("bridgeControlDisplayTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.BridgeControlDisplayTest")
}

tasks.register<JavaExec>("inputEventSerializationTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.InputEventSerializationTest")
}

tasks.register<JavaExec>("bridgeControlClipboardTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.BridgeControlClipboardTest")
}

tasks.register<JavaExec>("audioStreamContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.AudioStreamContractTest")
}

tasks.register<JavaExec>("pointerEventRelativeMotionTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.PointerEventRelativeMotionTest")
}

tasks.register<JavaExec>("fullLinuxContractAudioCapabilityTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.FullLinuxContractAudioCapabilityTest")
}

tasks.register<JavaExec>("vmHardwareConfigTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfigTest")
}

tasks.named("check") {
    dependsOn(
        "bridgeHandshakeContractTest",
        "bridgeControlContractTest",
        "bridgeClientContractTest",
        "fullLinuxContractTest",
        "networkContractTest",
        "portForwardingContractTest",
        "sharedDirectoryContractTest",
        "storageContractTest",
        "displayControlContractTest",
        "bridgeControlDisplayTest",
        "inputEventSerializationTest",
        "bridgeControlClipboardTest",
        "audioStreamContractTest",
        "pointerEventRelativeMotionTest",
        "fullLinuxContractAudioCapabilityTest",
        "vmHardwareConfigTest"
    )
}

