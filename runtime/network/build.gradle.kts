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

val networkTestOutput = layout.buildDirectory.dir("network-manager-test")
tasks.register<JavaExec>("portForwardingManagerContractTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.network.PortForwardingManagerContractTest")
    args(networkTestOutput.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn("portForwardingManagerContractTest")
}
