package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [squashIntoArgs] — the argument building logic used by [CliExecutor.squashInto].
 */
class CliExecutorSquashIntoTest {
    private val src1 = ChangeId("abc123def456", "abc123de", null)
    private val src2 = ChangeId("789abcdef012", "789abcde", null)
    private val dest = ChangeId("fff111222333", "fff11122", null)

    @Nested
    inner class `single source` {
        @Test
        fun `squash --from src --into dest`() {
            val result = squashIntoArgs(listOf(src1), dest)

            result shouldBe listOf("squash", "--into", "fff111222333", "--from", "abc123def456")
        }

        @Test
        fun `working copy as source`() {
            val result = squashIntoArgs(listOf(WorkingCopy), dest)

            result shouldBe listOf("squash", "--into", "fff111222333", "--from", WorkingCopy.REF)
        }
    }

    @Nested
    inner class `multiple sources` {
        @Test
        fun `squash with two sources`() {
            val result = squashIntoArgs(listOf(src1, src2), dest)

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--from",
                "789abcdef012"
            )
        }
    }

    @Nested
    inner class `selective squash with file paths` {
        @Test
        fun `single file is passed after --`() {
            val result = squashIntoArgs(listOf(src1), dest, filePaths = listOf("src/main.kt"))

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--",
                "src/main.kt"
            )
        }

        @Test
        fun `multiple files`() {
            val result = squashIntoArgs(
                listOf(src1),
                dest,
                filePaths = listOf("src/main.kt", "README.md")
            )

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--",
                "src/main.kt",
                "README.md"
            )
        }

        @Test
        fun `empty file list omits the -- separator`() {
            val result = squashIntoArgs(listOf(src1), dest, filePaths = emptyList())

            result shouldBe listOf("squash", "--into", "fff111222333", "--from", "abc123def456")
        }
    }

    @Nested
    inner class `with description` {
        @Test
        fun `squash with message`() {
            val result = squashIntoArgs(listOf(src1), dest, description = Description("Combined"))

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--message=Combined"
            )
        }

        @Test
        fun `description with multiple sources and file paths`() {
            val result = squashIntoArgs(
                listOf(src1, src2),
                dest,
                filePaths = listOf("a.kt"),
                description = Description("Two-source")
            )

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--from",
                "789abcdef012",
                "--message=Two-source",
                "--",
                "a.kt"
            )
        }
    }

    @Nested
    inner class `keep emptied` {
        @Test
        fun `squash with --keep-emptied`() {
            val result = squashIntoArgs(listOf(src1), dest, keepEmptied = true)

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--keep-emptied"
            )
        }

        @Test
        fun `all options together`() {
            val result = squashIntoArgs(
                listOf(src1, src2),
                dest,
                filePaths = listOf("a.kt"),
                description = Description("All"),
                keepEmptied = true
            )

            result shouldBe listOf(
                "squash",
                "--into",
                "fff111222333",
                "--from",
                "abc123def456",
                "--from",
                "789abcdef012",
                "--message=All",
                "--keep-emptied",
                "--",
                "a.kt"
            )
        }
    }
}
