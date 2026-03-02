package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [squashArgs] — the argument building logic used by [CliExecutor.squash].
 */
class CliExecutorSquashTest {
    private val revision = ChangeId("abc123def456", "abc123de", null)

    @Nested
    inner class `whole squash` {
        @Test
        fun `squash -r revision`() {
            val result = squashArgs(revision)

            result shouldBe listOf("squash", "-r", "abc123def456")
        }

        @Test
        fun `squash working copy`() {
            val result = squashArgs(WorkingCopy)

            result shouldBe listOf("squash", "-r", "@")
        }
    }

    @Nested
    inner class `selective squash with file paths` {
        @Test
        fun `squash with single file path`() {
            val result = squashArgs(revision, filePaths = listOf("src/main.kt"))

            result shouldBe listOf("squash", "-r", "abc123def456", "src/main.kt")
        }

        @Test
        fun `squash with multiple file paths`() {
            val result = squashArgs(
                revision,
                filePaths = listOf("src/main.kt", "src/utils.kt", "README.md")
            )

            result shouldBe listOf("squash", "-r", "abc123def456", "src/main.kt", "src/utils.kt", "README.md")
        }
    }

    @Nested
    inner class `with description` {
        @Test
        fun `squash with -m flag`() {
            val result = squashArgs(revision, description = Description("Combined change"))

            result shouldBe listOf("squash", "-r", "abc123def456", "-m", "Combined change")
        }

        @Test
        fun `squash with description and file paths`() {
            val result = squashArgs(
                revision,
                filePaths = listOf("src/main.kt"),
                description = Description("Partial squash")
            )

            result shouldBe listOf("squash", "-r", "abc123def456", "-m", "Partial squash", "src/main.kt")
        }
    }

    @Nested
    inner class `keep emptied` {
        @Test
        fun `squash with --keep-emptied`() {
            val result = squashArgs(revision, keepEmptied = true)

            result shouldBe listOf("squash", "-r", "abc123def456", "--keep-emptied")
        }

        @Test
        fun `squash with all options`() {
            val result = squashArgs(
                revision,
                filePaths = listOf("src/main.kt"),
                description = Description("Combined"),
                keepEmptied = true
            )

            result shouldBe listOf(
                "squash",
                "-r",
                "abc123def456",
                "-m",
                "Combined",
                "--keep-emptied",
                "src/main.kt"
            )
        }
    }
}
