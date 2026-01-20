import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "in.kkkev"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val platformVersion = project.property("platformVersion") as String
        intellijIdeaCommunity(platformVersion)

        // VCS modules - including the VCS itself as a plugin
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.impl")

        // Test framework for IntelliJ Platform tests
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    // Test framework
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")

    // Workarounds for IJPL-157292 and IJPL-159134
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testRuntimeOnly("junit:junit:4.13.2")

    // Kotest assertions
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")

    // MockK for mocking
    testImplementation("io.mockk:mockk:1.13.9")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // testRuntimeOnly("idea:ideaIC:aarch64:2025.2")
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

ktlint {
    version = "1.5.0"
    // Clear and rebuild rules
    enableExperimentalRules = false
}

// Configure test task to run with IntelliJ Platform classes on classpath
// but without the IDE bootstrap machinery (custom classloader, coroutines agent, etc.)
// that the IntelliJ Platform plugin configures by default.
tasks.test {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs

    // Use the test compile classpath which resolves IntelliJ Platform modules correctly
    classpath = configurations["testCompileClasspath"] +
        configurations["testRuntimeClasspath"] +
        sourceSets["test"].output +
        sourceSets["main"].output

    // Use standard JDK 21, not JBR
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )

    // JVM arguments required for IntelliJ Platform test framework
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.font=ALL-UNNAMED"
    )
}

// Convenience task that runs both tests and linting
tasks.named("check") {
    dependsOn("test", "ktlintCheck")
}
