plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// P3-4: single source of truth for the strict JVM compile contract. Every
// `java-library` module in this build (currently the 14 runtime/* modules)
// compiles with these flags; they used to be duplicated verbatim in each
// runtime/*/build.gradle.kts. The Android modules (:app, :accel-extension)
// never carried these flags and are deliberately not covered. A module that
// genuinely needs different options overrides them in its own script:
// module-level configureEach actions register after this one, and set()
// replaces the release value.
subprojects {
    plugins.withId("java-library") {
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(17)
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }
    }
}
