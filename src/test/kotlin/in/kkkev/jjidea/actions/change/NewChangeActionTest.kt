package `in`.kkkev.jjidea.actions.change

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [resolveNewChangeTarget], the pure logic that decides which repository and parent
 * revisions a quick "New Change" should use, from the log selection alone - never guessing
 * across multiple repositories, and never falling back to anything outside the selection (the
 * log keeps `@` selected by default, so that case is already covered by the selection itself).
 */
class NewChangeActionTest {
    private val repo1 = mockk<JujutsuRepository>()
    private val repo2 = mockk<JujutsuRepository>()

    private fun entry(changeId: String, repo: JujutsuRepository) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId.take(2), null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = "Test commit",
        bookmarks = emptyList(),
        parentIdentifiers = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = true,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null,
        immutable = false
    )

    @Test
    fun `single selected entry targets its repo with itself as the sole parent`() {
        val e = entry("abc123", repo1)

        val target = resolveNewChangeTarget(listOf(e))

        target.shouldNotBeNull()
        target.repo shouldBe repo1
        target.parents shouldBe listOf(e.id)
    }

    @Test
    fun `multiple selected entries in the same repo become merge parents`() {
        val e1 = entry("abc123", repo1)
        val e2 = entry("def456", repo1)

        val target = resolveNewChangeTarget(listOf(e1, e2))

        target.shouldNotBeNull()
        target.repo shouldBe repo1
        target.parents shouldBe listOf(e1.id, e2.id)
    }

    @Test
    fun `selected entries spanning multiple repos are ambiguous`() {
        val e1 = entry("abc123", repo1)
        val e2 = entry("def456", repo2)

        val target = resolveNewChangeTarget(listOf(e1, e2))

        target.shouldBeNull()
    }

    @Test
    fun `empty selection has nothing to act on`() {
        val target = resolveNewChangeTarget(emptyList())

        target.shouldBeNull()
    }
}
