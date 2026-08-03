package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CliExecutorResolveTest {
    @Test
    fun `resolveListArgs - working copy default`() {
        resolveListArgs() shouldBe listOf("resolve", "--list", "-r", "@")
    }

    @Test
    fun `resolveListArgs - explicit revision`() {
        val revision = ChangeId("abc123def456", "abc123de", null)
        resolveListArgs(revision) shouldBe listOf("resolve", "--list", "-r", "abc123def456")
    }

    @Test
    fun `resolveArgs - single path - working copy default`() {
        resolveArgs(listOf("a.txt"), ":ours") shouldBe listOf("resolve", "-r", "@", "--tool", ":ours", "a.txt")
    }

    @Test
    fun `resolveArgs - multiple paths`() {
        resolveArgs(listOf("a.txt", "b.txt"), ":theirs") shouldBe
            listOf("resolve", "-r", "@", "--tool", ":theirs", "a.txt", "b.txt")
    }

    @Test
    fun `resolveArgs - explicit revision`() {
        val revision = ChangeId("abc123def456", "abc123de", null)
        resolveArgs(listOf("a.txt"), ":ours", revision) shouldBe
            listOf("resolve", "-r", "abc123def456", "--tool", ":ours", "a.txt")
    }
}
