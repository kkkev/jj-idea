package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ResolveCommitIdTest {
    private val repo = mockk<JujutsuRepository>()

    @Test
    fun `a commit id is returned as is without consulting the log`() {
        val commitId = CommitId("0123456789abcdef", "01")

        repo.resolveCommitId(commitId) shouldBe commitId

        verify(exactly = 0) { repo.getLogEntry(any<Revision>()) }
    }

    @Test
    fun `another revision resolves to the commit id of its log entry`() {
        val revision = RevisionExpression("main")
        val entry = mockk<LogEntry> { every { commitId } returns CommitId("fedcba98", "fe") }
        every { repo.getLogEntry(revision) } returns entry

        repo.resolveCommitId(revision) shouldBe CommitId("fedcba98", "fe")
    }

    @Test
    fun `an unresolvable revision gives null`() {
        val revision = RevisionExpression("nope")
        every { repo.getLogEntry(revision) } throws IllegalArgumentException("no such revision")

        repo.resolveCommitId(revision) shouldBe null
    }
}
