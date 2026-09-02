package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CliExecutorResolveTest {
    @Test
    fun `resolveListArgs - working copy default`() {
        resolveListArgs().args shouldBe listOf("resolve", "--list", "-r", "@")
    }

    @Test
    fun `resolveListArgs - explicit revision`() {
        val revision = ChangeId("abc123def456", "abc123de", null)
        resolveListArgs(revision).args shouldBe listOf("resolve", "--list", "-r", "abc123def456")
    }

    @Test
    fun `resolveArgs - single path - working copy default`() {
        resolveArgs(listOf("a.txt"), ":ours").args shouldBe
            listOf("resolve", "-r", "@", "--tool", ":ours", "cwd:\"a.txt\"")
    }

    @Test
    fun `resolveArgs - multiple paths`() {
        resolveArgs(listOf("a.txt", "b.txt"), ":theirs").args shouldBe
            listOf("resolve", "-r", "@", "--tool", ":theirs", "cwd:\"a.txt\"", "cwd:\"b.txt\"")
    }

    @Test
    fun `resolveArgs - explicit revision`() {
        val revision = ChangeId("abc123def456", "abc123de", null)
        resolveArgs(listOf("a.txt"), ":ours", revision).args shouldBe
            listOf("resolve", "-r", "abc123def456", "--tool", ":ours", "cwd:\"a.txt\"")
    }

    @Test
    fun `resolveListArgs is read-only, resolveArgs is reversible`() {
        resolveListArgs().reversibility shouldBe Reversibility.READ_ONLY
        resolveArgs(listOf("a.txt"), ":ours").reversibility shouldBe Reversibility.REVERSIBLE
    }
}
