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

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("guestAssetsProjectDir", projectDir.absolutePath)
}

tasks.jar {
    archiveFileName = "guest-assets-tools.jar"
    manifest {
        attributes["Main-Class"] = "io.github.nyzeep.orchestella.guestassets.GuestAssetsTool"
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
