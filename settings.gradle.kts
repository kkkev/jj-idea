plugins {
    // Auto-provisions JDKs for Gradle toolchains (e.g. the JDK 25 that 2026.2's bundled JBR
    // requires to run platformTest/runIde) into Gradle's managed directory, without touching
    // any system-wide JDK install.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jj-idea"
