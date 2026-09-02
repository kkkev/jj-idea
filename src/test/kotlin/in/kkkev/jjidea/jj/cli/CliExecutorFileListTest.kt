package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CliExecutorFileListTest {
    @Test
    fun `fileListArgs - single path`() {
        fileListArgs(listOf("foo.txt")).args shouldBe listOf("file", "list", "cwd:\"foo.txt\"")
    }

    @Test
    fun `fileListArgs - multiple paths`() {
        fileListArgs(listOf("foo.txt", "bar/baz.txt")).args shouldBe
            listOf("file", "list", "cwd:\"foo.txt\"", "cwd:\"bar/baz.txt\"")
    }

    @Test
    fun `fileListArgs - no paths`() {
        fileListArgs(emptyList()).args shouldBe listOf("file", "list")
    }

    @Test
    fun `file list is read-only`() {
        fileListArgs(listOf("foo.txt")).reversibility shouldBe Reversibility.READ_ONLY
    }
}
