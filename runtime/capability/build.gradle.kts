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

tasks.register<JavaExec>("deviceCapabilityEvaluatorTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluatorTest")
}

tasks.register<JavaExec>("avfCapabilityEvaluatorTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluatorTest")
}

tasks.register<JavaExec>("avfAuthorizationWizardModelTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModelTest")
}

tasks.register<JavaExec>("shizukuClientControllerTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.capability.ShizukuClientControllerTest")
}

tasks.register<JavaExec>("networkCapabilityDeclarationTest") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.nyzeep.orchestella.runtime.capability.NetworkCapabilityDeclarationTest")
}

tasks.named("check") {
    dependsOn(
        "deviceCapabilityEvaluatorTest",
        "avfCapabilityEvaluatorTest",
        "avfAuthorizationWizardModelTest",
        "shizukuClientControllerTest",
        "networkCapabilityDeclarationTest",
    )
}
