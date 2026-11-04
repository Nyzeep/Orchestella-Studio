plugins {
    `java-library`
}

dependencies {
    testImplementation(libs.junit)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.named("check") {
    dependsOn("test")
}
