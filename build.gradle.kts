plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
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
    }

    // Test framework
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")

    // Kotest assertions
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")

    // MockK for mocking
    testImplementation("io.mockk:mockk:1.13.9")
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

tasks.test {
    useJUnitPlatform()
    // Disable instrumentation for simple unit tests
    enabled = false  // Disable until we set up proper IntelliJ test fixtures
}

// Create a simple test task without instrumentation
tasks.register<Test>("simpleTest") {
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    // Exclude tests that need IntelliJ Platform classes
    exclude("**/changes/**") // JujutsuRevisionNumberTest needs VcsRevisionNumber
    exclude("**/RequirementsTest.class") // Integration test placeholders
    exclude("**/JujutsuCommandAvailabilityTest.class") // Needs actual jj binary
    exclude("**/JujutsuLogEntryTest*.class") // Uses ChangeId which references Hash type
    exclude("**/JujutsuLogParserTest*.class") // Uses ChangeId which references Hash type
    exclude("**/jj/ChangeIdTest*.class") // ChangeId references Hash type in lazy property
    exclude("**/cli/AnnotationParserTest*.class") // Uses ChangeId which references Hash type
}
