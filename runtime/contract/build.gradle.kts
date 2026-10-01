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
    mainClass.set("io.github.nyzeep.droidlayer.runtime.contract.BridgeHandshakeContractTest")
}

tasks.register<JavaExec>("bridgeControlContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.contract.BridgeControlContractTest")
}

tasks.register<JavaExec>("fullLinuxContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContractTest")
}

tasks.register<JavaExec>("networkContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.contract.NetworkContractTest")
}

tasks.register<JavaExec>("bridgeClientContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.contract.BridgeClientContractTest")
}

tasks.register<JavaExec>("portForwardingContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.contract.PortForwardingContractTest")
}

tasks.register<JavaExec>("sharedDirectoryContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.contract.SharedDirectoryContractTest")
}

tasks.named("check") {
    dependsOn(
        "bridgeHandshakeContractTest",
        "bridgeControlContractTest",
        "bridgeClientContractTest",
        "fullLinuxContractTest",
        "networkContractTest",
        "portForwardingContractTest",
        "sharedDirectoryContractTest"
    )
}

