package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CliExecutorFileTrackTest {
    @Test
    fun `fileTrackArgs - single path`() {
        fileTrackArgs(listOf("foo.txt")).args shouldBe listOf("file", "track", "--include-ignored", "cwd:\"foo.txt\"")
    }

    @Test
    fun `fileTrackArgs - multiple paths`() {
        fileTrackArgs(listOf("foo.txt", "bar/baz.txt")).args shouldBe
            listOf("file", "track", "--include-ignored", "cwd:\"foo.txt\"", "cwd:\"bar/baz.txt\"")
    }

    @Test
    fun `fileTrackArgs - no paths`() {
        fileTrackArgs(emptyList()).args shouldBe listOf("file", "track", "--include-ignored")
    }

    @Test
    fun `fileUntrackArgs - single path`() {
        fileUntrackArgs(listOf("foo.txt")).args shouldBe listOf("file", "untrack", "cwd:\"foo.txt\"")
    }

    @Test
    fun `fileUntrackArgs - multiple paths`() {
        fileUntrackArgs(listOf("foo.txt", "bar/baz.txt")).args shouldBe
            listOf("file", "untrack", "cwd:\"foo.txt\"", "cwd:\"bar/baz.txt\"")
    }

    @Test
    fun `fileUntrackArgs - no paths`() {
        fileUntrackArgs(emptyList()).args shouldBe listOf("file", "untrack")
    }

    @Test
    fun `file track and untrack are reversible - local repo scope, unlike bookmark track-untrack`() {
        fileTrackArgs(listOf("foo.txt")).reversibility shouldBe Reversibility.REVERSIBLE
        fileUntrackArgs(listOf("foo.txt")).reversibility shouldBe Reversibility.REVERSIBLE
    }
}
