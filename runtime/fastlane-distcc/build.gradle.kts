plugins {
    `java-library`
}

dependencies {
    api(project(":runtime:engine-fastlane"))
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

// AX-06 host e2e report: opt-in, fixed evidence fields only (no paths/env).
val distccE2eReport = providers.gradleProperty("fastlane.distcc.report")
tasks.named<Test>("test") {
    if (distccE2eReport.isPresent) {
        systemProperty("fastlane.distcc.report", distccE2eReport.get())
    }
}
