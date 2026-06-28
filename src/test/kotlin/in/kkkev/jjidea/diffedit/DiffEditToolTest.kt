package `in`.kkkev.jjidea.diffedit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Tests for [DiffEditTool]: staging-tree construction and config-args generation.
 */
class DiffEditToolTest {
    @TempDir
    lateinit var tempDir: Path

    // ---- buildStagingTree ----

    @Test
    fun `buildStagingTree writes non-null content to staging directory`() {
        val content = mapOf("src/Auth.kt" to "class Auth\n", "src/Logger.kt" to "class Logger\n")
        val stagingDir = DiffEditTool.buildStagingTree(content)

        try {
            stagingDir.resolve("src/Auth.kt").readText() shouldBe "class Auth\n"
            stagingDir.resolve("src/Logger.kt").readText() shouldBe "class Logger\n"
        } finally {
            stagingDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `buildStagingTree omits files with null content`() {
        val content = mapOf("src/Auth.kt" to "class Auth\n", "src/Removed.kt" to null)
        val stagingDir = DiffEditTool.buildStagingTree(content)

        try {
            stagingDir.resolve("src/Auth.kt").exists() shouldBe true
            stagingDir.resolve("src/Removed.kt").exists() shouldBe false
        } finally {
            stagingDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `buildStagingTree creates nested directories`() {
        val content = mapOf("a/b/c/deep.txt" to "deep content")
        val stagingDir = DiffEditTool.buildStagingTree(content)

        try {
            stagingDir.resolve("a/b/c/deep.txt").readText() shouldBe "deep content"
        } finally {
            stagingDir.toFile().deleteRecursively()
        }
    }

    // ---- diffEditConfigArgs ----

    @Test
    fun `diffEditConfigArgs returns three config entries`() {
        val staging = Files.createTempDirectory("test-staging")
        try {
            val args = DiffEditTool.diffEditConfigArgs("my-tool", staging)
            args.size shouldBe 3
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diffEditConfigArgs registers program and edit-args under tool name`() {
        val staging = Files.createTempDirectory("test-staging")
        try {
            val args = DiffEditTool.diffEditConfigArgs("jj-idea-hunk-apply", staging)
            args[0] shouldStartWith "merge-tools.jj-idea-hunk-apply.program="
            args[1] shouldStartWith "merge-tools.jj-idea-hunk-apply.edit-args="
            args[2] shouldBe "ui.diff-editor=jj-idea-hunk-apply"
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diffEditConfigArgs edit-args includes dollar-left and dollar-right placeholders`() {
        val staging = Files.createTempDirectory("test-staging")
        try {
            val args = DiffEditTool.diffEditConfigArgs("jj-idea-hunk-apply", staging)
            val editArgs = args[1]
            editArgs shouldContain "\$left"
            editArgs shouldContain "\$right"
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diffEditConfigArgs edit-args includes main class name`() {
        val staging = Files.createTempDirectory("test-staging")
        try {
            val args = DiffEditTool.diffEditConfigArgs("jj-idea-hunk-apply", staging)
            val editArgs = args[1]
            editArgs shouldContain "HunkApplyMain"
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `diffEditConfigArgs edit-args does not duplicate java executable`() {
        // Regression: program=$java and edit-args must NOT also start with $java,
        // otherwise jj runs `java java -cp ...` and the path is treated as the main class.
        val staging = Files.createTempDirectory("test-staging")
        try {
            val args = DiffEditTool.diffEditConfigArgs("jj-idea-hunk-apply", staging)
            val program = args[0].substringAfter("program=")
            val editArgs = args[1].substringAfter("edit-args=")
            // edit-args must start with ["-cp", ...], not with the java executable
            editArgs shouldStartWith "[\"-cp\""
            // program must reference java
            program shouldContain "java"
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `discoverClasspath returns non-empty path`() {
        // Regression: under PluginClassLoader, codeSource.location is null — discovery must
        // fall back to resource-URL parsing rather than returning an empty string.
        val cp = DiffEditTool.discoverClasspath()
        (cp.isNotEmpty()) shouldBe true
    }

    @Test
    fun `discoverClasspath returns a path that exists on disk`() {
        val cp = DiffEditTool.discoverClasspath()
        // In tests the URL is a file: URL to the build/classes dir, which must exist.
        java.io.File(cp.split(java.io.File.pathSeparatorChar).first()).exists() shouldBe true
    }

    @Test
    fun `diffEditConfigArgs dash-cp argument is non-empty`() {
        val staging = Files.createTempDirectory("test-staging")
        try {
            val args = DiffEditTool.diffEditConfigArgs("jj-idea-hunk-apply", staging)
            val editArgs = args[1].substringAfter("edit-args=")
            // Parse the TOML inline array to find the value after "-cp".
            // Format: ["-cp","<value>","<mainClass>",...]
            val values = editArgs.trim('[', ']').split(",").map { it.trim('"') }
            val cpIndex = values.indexOf("-cp")
            cpIndex shouldBe 0
            (values[cpIndex + 1].isNotEmpty()) shouldBe true
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    // ---- tomlArray ----

    @Test
    fun `tomlArray encodes list as TOML inline array`() {
        DiffEditTool.tomlArray(listOf("a", "b", "c")) shouldBe """["a","b","c"]"""
    }

    @Test
    fun `tomlArray escapes backslash and double-quote`() {
        DiffEditTool.tomlArray(listOf("C:\\path", "say \"hi\"")) shouldBe
            """["C:\\path","say \"hi\""]"""
    }
}
