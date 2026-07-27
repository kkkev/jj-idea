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
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
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

// IntelliJ IDEA Community (IC) was retired as a standalone published product as of
// 2025.3 (253); intellijIdea(version) is the replacement but resolves to the paid
// Ultimate (IU) coordinates for versions before that cutoff, which fails to resolve
// without a license. Pick the right helper per version so both legs of the platform
// version matrix (pre- and post-253) resolve correctly.
fun isPre253(platformVersion: String): Boolean {
    val (year, minor) = platformVersion.split(".").map { it.toInt() }
    return year < 2025 || (year == 2025 && minor < 3)
}

dependencies {
    intellijPlatform {
        val platformVersion = project.property("platformVersion") as String
        if (isPre253(platformVersion)) {
            intellijIdeaCommunity(platformVersion)
        } else {
            intellijIdea(platformVersion)
        }

        // VCS modules - including the VCS itself as a plugin
        bundledPlugin("Git4Idea")
        bundledModule("intellij.platform.vcs.impl")
        // 2025.3 (253) onward splits ChangesTree/TreeModelBuilder/IssueNavigationConfiguration/
        // VcsUserUtil etc. out of intellij.platform.vcs.impl into a new shared module, and
        // CloneDvcsValidationUtils out into vcs.dvcs.impl. Neither module exists pre-253.
        if (!isPre253(project.property("platformVersion") as String)) {
            bundledModule("intellij.platform.vcs.impl.shared")
            bundledModule("intellij.platform.vcs.dvcs.impl")
        }

        // Test framework for IntelliJ Platform tests
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)
    }

    // Test framework
    // 2025.3+ (253+) platforms' bundled JUnit5 test framework calls
    // ExtensionContext.getEnclosingTestClasses(), added in JUnit Jupiter 5.11; older versions
    // fail platformTest with NoSuchMethodError.
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")

    // Workarounds for IJPL-157292 and IJPL-159134
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testRuntimeOnly("junit:junit:4.13.2")

    // Kotest assertions
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")

    // MockK for mocking. 1.13.9's bundled ByteBuddy can't generate working proxies for
    // JDK25-compiled classes (2025.3+/253+ platforms) — @NotNull-instrumented methods like
    // AnActionEvent.getPresentation() silently fall through to the real (unmocked)
    // implementation instead of the stubbed return value, throwing at the @NotNull check.
    testImplementation("io.mockk:mockk:1.14.11")

    // kotest/mockk transitively pull an older kotlin-stdlib than this project's own compiler
    // (kotlin.stdlib.default.dependency=false only suppresses the *automatic* stdlib dependency
    // Kotlin Gradle Plugin would add for our own module — it doesn't stop other libraries from
    // requesting their own, older version). 2025.3+ (253+) platforms' own compiled code emits
    // coroutines debug metadata version 2; an older stdlib on the classpath only understands
    // version 1, and platformTest hangs/deadlocks inside platform coroutine machinery as a
    // result rather than failing cleanly. Pin explicitly so Gradle's conflict resolution picks
    // this version over the older transitive one.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")

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

    pluginVerification {
        ides {
            recommended()
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)

    // This plugin never bundles kotlin-stdlib (kotlin.stdlib.default.dependency=false in
    // gradle.properties) — at runtime it always uses whichever kotlin-stdlib the host IDE
    // has loaded, all the way down to sinceBuild=251 (2025.1). Pin apiVersion so the compiler
    // rejects stdlib calls newer than what that oldest supported platform bundles, rather than
    // deferring the failure to a NoSuchMethodError on a user's older IDE (or in HunkApplyMain's
    // subprocess — see DiffEditTool.discoverClasspath, which has the same constraint).
    // languageVersion is left at the compiler default so newer language syntax stays available.
    compilerOptions {
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
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
        excludeTags("platform", "contract")
    }
    maxHeapSize = "1g"
    testClassesDirs = sourceSets["test"].output.classesDirs

    // Clear whatever IJPGP configured directly on the shared, default test task object before
    // this block runs (this task is meant to be fully independent of IJPGP's platform bootstrap).
    systemProperties.clear()

    // Exclude specifically test-framework-junit5 (added by IJPGP's testFramework(JUnit5)
    // declaration): it registers a LauncherSessionListener service provider that JUnit5's
    // launcher eagerly loads before any tag filtering (excludeTags above) ever applies. Plain
    // test-framework/test-framework-common/test-framework-core stay on the classpath — plenty of
    // non-platform-tagged unit tests here legitimately use their Mock* classes
    // (e.g. com.intellij.mock.MockVirtualFile).
    val rawClasspath = configurations["testCompileClasspath"] +
        configurations["testRuntimeClasspath"] +
        sourceSets["test"].output +
        sourceSets["main"].output
    classpath = rawClasspath.filter { !it.name.startsWith("test-framework-junit5") }

    // 2025.3+ (253+) platform classes are uniformly compiled for a newer JVM bytecode version
    // (2026.2 ships JBR 25, class file version 69) than JDK 21 can load — including plain
    // interfaces/light types like com.intellij.openapi.vcs.FilePath that plain unit tests here
    // legitimately mock or implement without needing the full platform sandbox. Match
    // platformTest's launcher selection.
    javaLauncher.set(
        if (isPre253(project.property("platformVersion") as String)) {
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        } else {
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
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
    // IJPGP's captured javaLauncher doesn't track the platform's actual bundled JBR version;
    // 2025.3+ (253+) platforms bundle a JBR built for a newer class file version (2026.2 ships
    // JBR 25, class file version 69) that an older JDK can't load when it hits the platform's
    // own bootclasspath jars (e.g. nio-fs.jar). Request a matching toolchain explicitly.
    javaLauncher.set(
        if (isPre253(project.property("platformVersion") as String)) {
            ijpgpJavaLauncher
        } else {
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        }
    )
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

// Contract tests: run real jj commands to verify CLI output matches plugin's parsers.
// Same stripped-down classpath as unit tests (no IJPGP). Not included in check — requires jj installed.
tasks.register<Test>("contractTest") {
    useJUnitPlatform { includeTags("contract") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = configurations["testCompileClasspath"] +
        configurations["testRuntimeClasspath"] +
        sourceSets["test"].output +
        sourceSets["main"].output
    // Match tasks.test/platformTest's launcher selection: 2025.3+ (253+) platform classes are
    // compiled for a newer JVM bytecode version than JDK 21 can load.
    javaLauncher.set(
        if (isPre253(project.property("platformVersion") as String)) {
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        } else {
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
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
    doFirst { jvmArgumentProviders.clear() }
}

// Stub tests: run stub contract tests in isolation (no jj required).
// Same stripped-down classpath as unit tests.
tasks.register<Test>("stubTest") {
    useJUnitPlatform { includeTags("stub") }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = configurations["testCompileClasspath"] +
        configurations["testRuntimeClasspath"] +
        sourceSets["test"].output +
        sourceSets["main"].output
    // Match tasks.test/platformTest's launcher selection: 2025.3+ (253+) platform classes are
    // compiled for a newer JVM bytecode version than JDK 21 can load.
    javaLauncher.set(
        if (isPre253(project.property("platformVersion") as String)) {
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        } else {
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
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
    doFirst { jvmArgumentProviders.clear() }
}

// Convenience task that runs both tests and linting
tasks.named("check") {
    dependsOn("test", "platformTest", "ktlintCheck")
}

tasks.runIde {
    jvmArgs("-Didea.is.internal=true")
}
