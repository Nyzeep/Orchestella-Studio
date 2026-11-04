plugins {
    `java-library`
}

dependencies {
    api(project(":runtime:engine-fastlane"))
    testImplementation(libs.junit)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}
