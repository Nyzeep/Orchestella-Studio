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

tasks.register<JavaExec>("deviceCapabilityEvaluatorTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.capability.DeviceCapabilityEvaluatorTest")
}

tasks.register<JavaExec>("avfCapabilityEvaluatorTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.capability.AvfCapabilityEvaluatorTest")
}

tasks.register<JavaExec>("avfAuthorizationWizardModelTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.capability.AvfAuthorizationWizardModelTest")
}

tasks.register<JavaExec>("networkCapabilityDeclarationTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.droidlayer.runtime.capability.NetworkCapabilityDeclarationTest")
}

tasks.named("check") {
    dependsOn(
        "deviceCapabilityEvaluatorTest",
        "avfCapabilityEvaluatorTest",
        "avfAuthorizationWizardModelTest",
        "networkCapabilityDeclarationTest",
    )
}
