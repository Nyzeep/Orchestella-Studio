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

tasks.named<Test>("test") {
    // Strategy-neutral contract suites are executed via JavaExec tasks
    // (portForwardingManagerContractTest, resilientDownloadEngineContractTest)
    // to ensure portability across environments with non-ASCII workspace paths.
    exclude("**/*")
}

val networkTestOutput = layout.buildDirectory.dir("network-manager-test")
tasks.register<JavaExec>("portForwardingManagerContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.network.PortForwardingManagerContractTest")
    args(networkTestOutput.get().asFile.absolutePath)
}

tasks.register<JavaExec>("resilientDownloadEngineContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.network.download.ResilientDownloadEngineContractTest")
}

tasks.register<JavaExec>("bridgeProxyGatewayContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.network.BridgeProxyGatewayContractTest")
}

// The manager contract suite is strategy-neutral: other engine modules re-run
// it against their own live forwarding engine (Ticket 36 AC-3), so the test
// classes are published as consumable artifacts.
tasks.register<Jar>("testJar") {
    archiveClassifier.set("tests")
    from(sourceSets["test"].output)
}

configurations.create("testArtifacts")

artifacts {
    add("testArtifacts", tasks.named("testJar"))
}

tasks.named("check") {
    dependsOn(
        "portForwardingManagerContractTest",
        "resilientDownloadEngineContractTest",
        "bridgeProxyGatewayContractTest"
    )
}
