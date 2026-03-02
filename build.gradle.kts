import org.jetbrains.intellij.platform.gradle.TestFrameworkType

/**
 * Extracts changelog notes from CHANGELOG.md for the given version.
 * For SNAPSHOT versions, returns the [Unreleased] section.
 * For release versions, returns the section for that specific version.
 */
fun extractChangelogNotes(version: String): String {
    val changelogFile = file("CHANGELOG.md")
    if (!changelogFile.exists()) {
        return "<p>See <a href=\"https://github.com/kkkev/jj-idea/releases\">releases</a> for details.</p>"
    }

    val lines = changelogFile.readLines()
    val isSnapshot = version.contains("-SNAPSHOT")
    val targetVersion = if (isSnapshot) "Unreleased" else version.removeSuffix("-SNAPSHOT")

    // Find the target section and extract content until the next section or link references
    var inSection = false
    val contentLines = mutableListOf<String>()

    for (line in lines) {
        when {
            line.startsWith("## [$targetVersion]") -> inSection = true
            inSection && (line.startsWith("## [") || line.matches(Regex("""\[.+\]:.*"""))) -> break
            inSection -> contentLines.add(line)
        }
    }

    val markdownContent = contentLines.joinToString("\n").trim()

    if (markdownContent.isEmpty()) {
        return if (isSnapshot) {
            "<p>Development build. See <a href=\"https://github.com/kkkev/jj-idea/blob/master/CHANGELOG.md\">CHANGELOG.md</a> for upcoming changes.</p>"
        } else {
            "<p>See <a href=\"https://github.com/kkkev/jj-idea/releases/tag/v$version\">release notes</a> for details.</p>"
        }
    }

    // Convert markdown to HTML
    return markdownContent
        .replace(Regex("""^### (.+)$""", RegexOption.MULTILINE), "<h4>$1</h4>")
        .replace(Regex("""^- (.+)$""", RegexOption.MULTILINE), "<li>$1</li>")
        .replace(Regex("""(<li>.*</li>\n?)+""")) { "<ul>${it.value}</ul>" }
        .replace(Regex("""`([^`]+)`"""), "<code>$1</code>")
        .replace("\n\n", "<br/><br/>")
        .trim()
}

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "in.kkkev"
version = "0.2.0-SNAPSHOT"

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
        changeNotes = provider { extractChangelogNotes(project.version.toString()) }
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

// Capture IJPGP's test configuration before we override it for unit tests.
// This must come before the tasks.test block below.
val ijpgpTestTask = tasks.test.get()
val ijpgpClasspath = ijpgpTestTask.classpath
val ijpgpTestClassesDirs = ijpgpTestTask.testClassesDirs
val ijpgpJavaLauncher = ijpgpTestTask.javaLauncher
val ijpgpSystemProperties = ijpgpTestTask.systemProperties.toMap()
val ijpgpJvmArgProviders = ijpgpTestTask.jvmArgumentProviders.toList()

// Unit tests: stripped-down classpath without IJPGP bootstrap.
// Uses manual classpath and clears jvmArgumentProviders to avoid coroutines agent crash.
tasks.test {
    useJUnitPlatform {
        excludeTags("platform")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs

    classpath = configurations["testCompileClasspath"] +
        configurations["testRuntimeClasspath"] +
        sourceSets["test"].output +
        sourceSets["main"].output

    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )

    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
        "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.font=ALL-UNNAMED"
    )

    doFirst {
        jvmArgumentProviders.clear()
    }
}

// Platform tests: use IJPGP's original classpath + sandbox config.
// Filters out coroutines agent and old kotlinx-coroutines JARs to avoid version mismatch.
tasks.register<Test>("platformTest") {
    useJUnitPlatform {
        includeTags("platform")
    }

    dependsOn("prepareTestSandbox", "prepareTest")

    testClassesDirs = ijpgpTestClassesDirs
    // Use IJPGP's classpath but exclude old kotlinx-coroutines JARs (1.7.0 from kotest/mockk)
    // that conflict with the platform's bundled version (1.10.1-intellij).
    classpath = ijpgpClasspath.filter { !it.name.startsWith("kotlinx-coroutines-") }
    javaLauncher.set(ijpgpJavaLauncher)
    ijpgpSystemProperties.forEach { (k, v) -> if (v != null) systemProperty(k, v) }
    ijpgpJvmArgProviders.forEach { jvmArgumentProviders.add(it) }

    doFirst {
        // Keep all IJPGP args except the coroutines agent which crashes
        val allArgs = jvmArgumentProviders.flatMap { it.asArguments() }
            .filter { !it.contains("coroutines-javaagent") }
        jvmArgumentProviders.clear()
        jvmArgs(allArgs)
    }
}

// Convenience task that runs both tests and linting
tasks.named("check") {
    dependsOn("test", "platformTest", "ktlintCheck")
}
