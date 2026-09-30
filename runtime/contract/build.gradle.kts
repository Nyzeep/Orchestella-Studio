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

tasks.named("check") {
    dependsOn("bridgeHandshakeContractTest", "bridgeControlContractTest", "fullLinuxContractTest")
}
