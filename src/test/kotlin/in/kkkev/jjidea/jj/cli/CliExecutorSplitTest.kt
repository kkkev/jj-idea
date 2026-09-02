package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [splitArgs] and [splitInteractiveArgs] — the argument building logic used
 * by [CliExecutor.split] and [CliExecutor.splitInteractive].
 */
class CliExecutorSplitTest {
    private val revision = ChangeId("abc123def456", "abc123de", null)

    @Nested
    inner class `basic split` {
        @Test
        fun `split -r revision`() {
            val result = splitArgs(revision).args

            result shouldBe listOf("split", "-r", "abc123def456")
        }

        @Test
        fun `split working copy`() {
            val result = splitArgs(WorkingCopy).args

            result shouldBe listOf("split", "-r", "@")
        }
    }

    @Nested
    inner class `with file paths` {
        @Test
        fun `split with single file path`() {
            val result = splitArgs(revision, filePaths = listOf("src/main.kt")).args

            result shouldBe listOf("split", "-r", "abc123def456", "cwd:\"src/main.kt\"")
        }

        @Test
        fun `split with multiple file paths`() {
            val result = splitArgs(
                revision,
                filePaths = listOf("src/main.kt", "src/utils.kt", "README.md")
            ).args

            result shouldBe listOf(
                "split",
                "-r",
                "abc123def456",
                "cwd:\"src/main.kt\"",
                "cwd:\"src/utils.kt\"",
                "cwd:\"README.md\""
            )
        }
    }

    @Nested
    inner class `with description` {
        @Test
        fun `split with message`() {
            val result = splitArgs(revision, description = Description("First half")).args

            result shouldBe listOf("split", "-r", "abc123def456", "--message=First half")
        }

        @Test
        fun `split with description and file paths`() {
            val result = splitArgs(
                revision,
                filePaths = listOf("src/main.kt"),
                description = Description("Core changes")
            ).args

            result shouldBe listOf("split", "-r", "abc123def456", "--message=Core changes", "cwd:\"src/main.kt\"")
        }
    }

    @Nested
    inner class `parallel flag` {
        @Test
        fun `split with --parallel`() {
            val result = splitArgs(revision, parallel = true).args

            result shouldBe listOf("split", "-r", "abc123def456", "--parallel")
        }

        @Test
        fun `split with all options`() {
            val result = splitArgs(
                revision,
                filePaths = listOf("src/main.kt"),
                description = Description("First commit"),
                parallel = true
            ).args

            result shouldBe listOf(
                "split",
                "-r",
                "abc123def456",
                "--message=First commit",
                "--parallel",
                "cwd:\"src/main.kt\""
            )
        }
    }

    @Nested
    inner class `insert-before flag (jj-idea-tkog)` {
        @Test
        fun `split with -B revision, positioned right after -r`() {
            val result = splitArgs(revision, insertBefore = revision).args

            result shouldBe listOf("split", "-r", "abc123def456", "-B", "abc123def456")
        }

        @Test
        fun `absent when insertBefore is null`() {
            val result = splitArgs(revision, filePaths = listOf("src/main.kt")).args

            result shouldNotContain "-B"
        }

        @Test
        fun `combines with description and file paths in the same order as the no-flag case`() {
            val result = splitArgs(
                revision,
                filePaths = listOf("src/main.kt"),
                description = Description("New parent"),
                insertBefore = revision
            ).args

            result shouldBe listOf(
                "split",
                "-r",
                "abc123def456",
                "-B",
                "abc123def456",
                "--message=New parent",
                "cwd:\"src/main.kt\""
            )
        }
    }
}

/**
 * Tests for [splitInteractiveArgs] — the diff-editor-driven interactive split variant.
 */
class CliExecutorSplitInteractiveTest {
    private val revision = ChangeId("abc123def456", "abc123de", null)

    @Nested
    inner class `basic interactive split` {
        @Test
        fun `includes --tool flag`() {
            val result = splitInteractiveArgs(revision, tool = "my-tool").args
            result shouldContain "--tool=my-tool"
        }

        @Test
        fun `does not include file paths`() {
            val result = splitInteractiveArgs(
                revision,
                tool = "my-tool"
                // Even if filePaths were passed (they aren't in the signature), none appear.
            ).args
            // The only positional args should be the revision value; no file paths.
            // Concretely: after "--tool=my-tool" there should be nothing else.
            val toolIdx = result.indexOfFirst { it.startsWith("--tool=") }
            result.drop(toolIdx + 1).isEmpty() shouldBe true
        }

        @Test
        fun `includes -r revision`() {
            val result = splitInteractiveArgs(revision, tool = "my-tool").args
            result shouldContain "-r"
            result shouldContain "abc123def456"
        }
    }

    @Nested
    inner class `config args ordering` {
        @Test
        fun `config args emitted before the split subcommand`() {
            val result = splitInteractiveArgs(
                revision,
                tool = "my-tool",
                configArgs = listOf("ui.diff-editor=my-tool", "merge-tools.my-tool.program=java")
            ).args
            val splitIdx = result.indexOf("split")
            // Both --config flags must appear before "split".
            result.subList(0, splitIdx) shouldContain "--config"
            result.subList(0, splitIdx) shouldContain "ui.diff-editor=my-tool"
        }

        @Test
        fun `each config arg is prefixed with --config`() {
            val result = splitInteractiveArgs(
                revision,
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
    inner class `description and parallel` {
        @Test
        fun `includes -m flag when description provided`() {
            val result = splitInteractiveArgs(
                revision,
                description = Description("First part"),
                tool = "t"
            ).args
            result shouldContain "--message=First part"
        }

        @Test
        fun `includes --parallel flag`() {
            val result = splitInteractiveArgs(revision, parallel = true, tool = "t").args
            result shouldContain "--parallel"
        }

        @Test
        fun `no --parallel by default`() {
            val result = splitInteractiveArgs(revision, tool = "t").args
            result shouldNotContain "--parallel"
        }
    }

    @Nested
    inner class `insert-before flag (jj-idea-tkog)` {
        @Test
        fun `split --tool with -B revision, positioned right after -r`() {
            val result = splitInteractiveArgs(revision, tool = "t", insertBefore = revision).args

            val rIdx = result.indexOf("-r")
            result[rIdx + 1] shouldBe "abc123def456"
            result[rIdx + 2] shouldBe "-B"
            result[rIdx + 3] shouldBe "abc123def456"
        }

        @Test
        fun `absent when insertBefore is null`() {
            val result = splitInteractiveArgs(revision, tool = "t").args
            result shouldNotContain "-B"
        }
    }

    @Test
    fun `split and splitInteractive are reversible`() {
        splitArgs(revision).reversibility shouldBe Reversibility.REVERSIBLE
        splitInteractiveArgs(revision, tool = "t").reversibility shouldBe Reversibility.REVERSIBLE
    }
}
