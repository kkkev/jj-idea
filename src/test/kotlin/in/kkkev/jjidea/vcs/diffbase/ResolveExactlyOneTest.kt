package `in`.kkkev.jjidea.vcs.diffbase

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Unit tests for [resolveExactlyOne], the "exactly one revision" check shared by
 * [DiffbaseService.resolve] (the cached hot path) and
 * [in.kkkev.jjidea.actions.diffbase.SetDiffbaseAction] (one-shot validation before writing a
 * custom-revset override) — see jj-idea-g1io. [DiffbaseServiceTest] already exercises the same
 * three outcomes through [DiffbaseService.resolve]; these tests cover the extracted function
 * directly, including the outcome the caching wrapper discards ([ResolveResult.Ambiguous]'s count).
 */
class ResolveExactlyOneTest {
    private lateinit var logService: LogService
    private lateinit var repo: JujutsuRepository

    private fun setup() {
        logService = mockk()
        repo = mockk {
            every { logService } returns this@ResolveExactlyOneTest.logService
        }
    }

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc"
    )

    @Test
    fun `resolves to Single with the one matching entry's id`() {
        setup()
        every {
            logService.getLog(revset = Expression("trunk()"), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc")))

        resolveExactlyOne(repo, "trunk()") shouldBe ResolveResult.Single(ChangeId("abc", "abc", null))
    }

    @Test
    fun `resolves to None when the revset matches nothing`() {
        setup()
        every {
            logService.getLog(revset = Expression("none()"), limit = 2, quiet = true)
        } returns Result.success(emptyList())

        resolveExactlyOne(repo, "none()") shouldBe ResolveResult.None
    }

    @Test
    fun `resolves to Ambiguous with the matched count when more than one entry matches`() {
        setup()
        every {
            logService.getLog(revset = Expression("heads(mutable())"), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc"), entry("def")))

        resolveExactlyOne(repo, "heads(mutable())") shouldBe ResolveResult.Ambiguous(2)
    }

    @Test
    fun `resolves to None when the log command fails`() {
        setup()
        every {
            logService.getLog(revset = Expression("zz("), limit = 2, quiet = true)
        } returns Result.failure(RuntimeException("bad revset"))

        resolveExactlyOne(repo, "zz(") shouldBe ResolveResult.None
    }

    @Test
    fun `issues exactly one bounded log call, limit = 2`() {
        setup()
        every {
            logService.getLog(revset = Expression("trunk()"), limit = 2, quiet = true)
        } returns Result.success(listOf(entry("abc")))

        resolveExactlyOne(repo, "trunk()")

        verify(exactly = 1) { logService.getLog(revset = Expression("trunk()"), limit = 2, quiet = true) }
    }
}
