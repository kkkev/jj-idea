package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [commitArgs] and [commitInteractiveArgs] — the argument building logic used
 * by [CliExecutor.commit] and [CliExecutor.commitInteractive].
 */
class CliExecutorCommitTest {
    @Nested
    inner class `basic commit` {
        @Test
        fun `commit has no -r`() {
            val result = commitArgs(description = Description("First half")).args

            result shouldBe listOf("commit", "--message=First half")
        }
    }

    @Nested
    inner class `with file paths` {
        @Test
        fun `commit with single file path`() {
            val result = commitArgs(filePaths = listOf("src/main.kt"), description = Description("d")).args

            result shouldBe listOf("commit", "--message=d", "cwd:\"src/main.kt\"")
        }

        @Test
        fun `commit with multiple file paths`() {
            val result = commitArgs(
                filePaths = listOf("src/main.kt", "src/utils.kt", "README.md"),
                description = Description("d")
            ).args

            result shouldBe listOf(
                "commit",
                "--message=d",
                "cwd:\"src/main.kt\"",
                "cwd:\"src/utils.kt\"",
                "cwd:\"README.md\""
            )
        }
    }
}

/**
 * Tests for [commitInteractiveArgs] — the diff-editor-driven interactive commit variant.
 */
class CliExecutorCommitInteractiveTest {
    private val description = Description("d")

    @Nested
    inner class `basic interactive commit` {
        @Test
        fun `includes --tool flag`() {
            val result = commitInteractiveArgs(description, tool = "my-tool").args
            result shouldContain "--tool=my-tool"
        }

        @Test
        fun `does not include file paths`() {
            val result = commitInteractiveArgs(
                description,
                tool = "my-tool"
                // Even if filePaths were passed (they aren't in the signature), none appear.
            ).args
            // No file paths: after "--tool=my-tool" there should be nothing else.
            val toolIdx = result.indexOfFirst { it.startsWith("--tool=") }
            result.drop(toolIdx + 1).isEmpty() shouldBe true
        }

        @Test
        fun `has no -r`() {
            val result = commitInteractiveArgs(description, tool = "my-tool").args
            result shouldNotContain "-r"
        }
    }

    @Nested
    inner class `config args ordering` {
        @Test
        fun `config args emitted before the commit subcommand`() {
            val result = commitInteractiveArgs(
                description,
                tool = "my-tool",
                configArgs = listOf("ui.diff-editor=my-tool", "merge-tools.my-tool.program=java")
            ).args
            val commitIdx = result.indexOf("commit")
            // Both --config flags must appear before "commit".
            result.subList(0, commitIdx) shouldContain "--config"
            result.subList(0, commitIdx) shouldContain "ui.diff-editor=my-tool"
        }

        @Test
        fun `each config arg is prefixed with --config`() {
            val result = commitInteractiveArgs(
                description,
                tool = "my-tool",
                configArgs = listOf("k1=v1", "k2=v2")
            ).args
            // Pairs: --config k1=v1, --config k2=v2
            val configIdx1 = result.indexOf("k1=v1")
            result[configIdx1 - 1] shouldBe "--config"
            val configIdx2 = result.indexOf("k2=v2")
            result[configIdx2 - 1] shouldBe "--config"
        }
    }

    @Nested
    inner class `with description` {
        @Test
        fun `includes -m flag`() {
            val result = commitInteractiveArgs(
                Description("First part"),
                tool = "t"
            ).args
            result shouldContain "--message=First part"
        }
    }

    @Test
    fun `commit and commitInteractive are reversible`() {
        commitArgs(description = description).reversibility shouldBe Reversibility.REVERSIBLE
        commitInteractiveArgs(description, tool = "t").reversibility shouldBe Reversibility.REVERSIBLE
    }
}
