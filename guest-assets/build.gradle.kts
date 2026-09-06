plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("guestAssetsProjectDir", projectDir.absolutePath)
}

tasks.jar {
    archiveFileName = "guest-assets-tools.jar"
    manifest {
        attributes["Main-Class"] = "io.github.nyzeep.droidlayer.guestassets.GuestAssetsTool"
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
