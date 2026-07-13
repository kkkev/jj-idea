package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CliExecutorFileTrackTest {
    @Test
    fun `fileTrackArgs - single path`() {
        fileTrackArgs(listOf("foo.txt")) shouldBe listOf("file", "track", "--include-ignored", "foo.txt")
    }

    @Test
    fun `fileTrackArgs - multiple paths`() {
        fileTrackArgs(listOf("foo.txt", "bar/baz.txt")) shouldBe
            listOf("file", "track", "--include-ignored", "foo.txt", "bar/baz.txt")
    }

    @Test
    fun `fileTrackArgs - no paths`() {
        fileTrackArgs(emptyList()) shouldBe listOf("file", "track", "--include-ignored")
    }

    @Test
    fun `fileUntrackArgs - single path`() {
        fileUntrackArgs(listOf("foo.txt")) shouldBe listOf("file", "untrack", "foo.txt")
    }

    @Test
    fun `fileUntrackArgs - multiple paths`() {
        fileUntrackArgs(listOf("foo.txt", "bar/baz.txt")) shouldBe
            listOf("file", "untrack", "foo.txt", "bar/baz.txt")
    }

    @Test
    fun `fileUntrackArgs - no paths`() {
        fileUntrackArgs(emptyList()) shouldBe listOf("file", "untrack")
    }
}
