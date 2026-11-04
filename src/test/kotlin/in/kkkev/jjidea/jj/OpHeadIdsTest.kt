package `in`.kkkev.jjidea.jj

import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class OpHeadIdsTest {
    @AfterEach
    fun tearDown() = OpHeadIds.clear()

    private fun ok(stdout: String) =
        CommandResult.Success.Irreversible(stdout, "", CommandResult.Success.Irreversible.Reason.READ_ONLY)

    @Test
    fun `first sight primes, same head is no change, new head is a change once`() {
        OpHeadIds.record("/r", "aaa") shouldBe false
        OpHeadIds.record("/r", "aaa") shouldBe false
        OpHeadIds.record("/r", "bbb") shouldBe true
        OpHeadIds.record("/r", "bbb") shouldBe false
    }

    @Test
    fun `repos are tracked independently`() {
        OpHeadIds.record("/a", "aaa")
        OpHeadIds.record("/b", "bbb") shouldBe false
        OpHeadIds.record("/a", "ccc") shouldBe true
    }

    @Test
    fun `a head already recorded by one consumer is not a change for the other`() {
        OpHeadIds.record("/r", "aaa")
        OpHeadIds.record("/r", "bbb") shouldBe true // e.g. the fetch saw it first
        OpHeadIds.record("/r", "bbb") shouldBe false // so the poll does not refresh again
    }

    @Test
    fun `full operation ids are normalised to the 12-char template prefix`() {
        val full = "abcdef012345" + "9".repeat(116)
        OpHeadIds.record("/r", "000000000000")
        OpHeadIds.record("/r", full) shouldBe true
        OpHeadIds.record("/r", "abcdef012345") shouldBe false
    }

    @Test
    fun `polled repo tracking scopes per owner`() {
        val ownerA = Any()
        val ownerB = Any()
        OpHeadIds.isPolled("/a") shouldBe false

        OpHeadIds.setPolled(ownerA, listOf("/a", "/b"))
        OpHeadIds.setPolled(ownerB, listOf("/b"))
        OpHeadIds.isPolled("/a") shouldBe true
        OpHeadIds.isPolled("/b") shouldBe true

        OpHeadIds.setPolled(ownerA, emptySet())
        OpHeadIds.isPolled("/a") shouldBe false
        OpHeadIds.isPolled("/b") shouldBe true
    }

    @Test
    fun `read trims the id and reports failure or empty output as null`() {
        val executor = mockk<CommandExecutor>()
        every { executor.opLog(1, OpHeadIds.TEMPLATE) } returns ok("abc123\n")
        OpHeadIds.read(executor) shouldBe "abc123"

        every { executor.opLog(1, OpHeadIds.TEMPLATE) } returns ok("\n")
        OpHeadIds.read(executor) shouldBe null

        every { executor.opLog(1, OpHeadIds.TEMPLATE) } returns CommandResult.Failure.Exited("", "boom", 1)
        OpHeadIds.read(executor) shouldBe null
    }
}
