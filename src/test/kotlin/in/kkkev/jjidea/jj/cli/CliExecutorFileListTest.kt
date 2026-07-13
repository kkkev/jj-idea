package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CliExecutorFileListTest {
    @Test
    fun `fileListArgs - single path`() {
        fileListArgs(listOf("foo.txt")) shouldBe listOf("file", "list", "foo.txt")
    }

    @Test
    fun `fileListArgs - multiple paths`() {
        fileListArgs(listOf("foo.txt", "bar/baz.txt")) shouldBe listOf("file", "list", "foo.txt", "bar/baz.txt")
    }

    @Test
    fun `fileListArgs - no paths`() {
        fileListArgs(emptyList()) shouldBe listOf("file", "list")
    }
}
