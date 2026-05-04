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
}
