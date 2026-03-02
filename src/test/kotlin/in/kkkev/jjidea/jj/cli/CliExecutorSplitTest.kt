package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [splitArgs] — the argument building logic used by [CliExecutor.split].
 */
class CliExecutorSplitTest {
    private val revision = ChangeId("abc123def456", "abc123de", null)

    @Nested
    inner class `basic split` {
        @Test
        fun `split -r revision`() {
            val result = splitArgs(revision)

            result shouldBe listOf("split", "-r", "abc123def456")
        }

        @Test
        fun `split working copy`() {
            val result = splitArgs(WorkingCopy)

            result shouldBe listOf("split", "-r", "@")
        }
    }

    @Nested
    inner class `with file paths` {
        @Test
        fun `split with single file path`() {
            val result = splitArgs(revision, filePaths = listOf("src/main.kt"))

            result shouldBe listOf("split", "-r", "abc123def456", "src/main.kt")
        }

        @Test
        fun `split with multiple file paths`() {
            val result = splitArgs(
                revision,
                filePaths = listOf("src/main.kt", "src/utils.kt", "README.md")
            )

            result shouldBe listOf("split", "-r", "abc123def456", "src/main.kt", "src/utils.kt", "README.md")
        }
    }

    @Nested
    inner class `with description` {
        @Test
        fun `split with -m flag`() {
            val result = splitArgs(revision, description = Description("First half"))

            result shouldBe listOf("split", "-r", "abc123def456", "-m", "First half")
        }

        @Test
        fun `split with description and file paths`() {
            val result = splitArgs(
                revision,
                filePaths = listOf("src/main.kt"),
                description = Description("Core changes")
            )

            result shouldBe listOf("split", "-r", "abc123def456", "-m", "Core changes", "src/main.kt")
        }
    }

    @Nested
    inner class `parallel flag` {
        @Test
        fun `split with --parallel`() {
            val result = splitArgs(revision, parallel = true)

            result shouldBe listOf("split", "-r", "abc123def456", "--parallel")
        }

        @Test
        fun `split with all options`() {
            val result = splitArgs(
                revision,
                filePaths = listOf("src/main.kt"),
                description = Description("First commit"),
                parallel = true
            )

            result shouldBe listOf(
                "split",
                "-r",
                "abc123def456",
                "-m",
                "First commit",
                "--parallel",
                "src/main.kt"
            )
        }
    }
}
