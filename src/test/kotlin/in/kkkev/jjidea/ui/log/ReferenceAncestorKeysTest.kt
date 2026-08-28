package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [ancestorKeys], the pure BFS behind [JujutsuReferenceFilterComponent]'s "References"
 * filter (jj-idea-1ra9). Every jj repo's root commit shares the identical (synthetic) change id
 * "zzzzzzzz...", so the walk must be scoped to [ChangeKey] (repo + id), not a bare [ChangeId], or
 * it silently crosses into another repo's ancestry once it reaches a root.
 */
class ReferenceAncestorKeysTest {
    private val repoA = mockk<JujutsuRepository>()
    private val repoB = mockk<JujutsuRepository>()

    private fun entry(
        repo: JujutsuRepository,
        changeId: String,
        parentIds: List<String> = emptyList(),
        bookmarks: List<Bookmark> = emptyList(),
        isWorkingCopy: Boolean = false
    ) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $changeId",
        bookmarks = bookmarks,
        parentIds = parentIds.map { ChangeId(it, it, null) },
        isWorkingCopy = isWorkingCopy
    )

    private fun key(repo: JujutsuRepository, changeId: String) = ChangeKey(repo, ChangeId(changeId, changeId, null))

    @Test
    fun `walking a bookmark's ancestry never crosses into another repo sharing the root id`() {
        // Both repos' roots share id "zzzzzzzz", mirroring jj's real synthetic root commit id.
        val rootA = entry(repoA, "zzzzzzzz")
        val midA = entry(repoA, "aaa111", parentIds = listOf("zzzzzzzz"))
        val mainA = entry(repoA, "bbb222", parentIds = listOf("aaa111"), bookmarks = listOf(Bookmark("main")))
        val rootB = entry(repoB, "zzzzzzzz")
        val otherB = entry(repoB, "ccc333", parentIds = listOf("zzzzzzzz"))

        val entries = listOf(rootA, midA, mainA, rootB, otherB)

        val result = ancestorKeys(entries) { it.bookmarks.any { b -> b.name.name == "main" } }

        result.shouldContainExactlyInAnyOrder(
            key(repoA, "bbb222"),
            key(repoA, "aaa111"),
            key(repoA, "zzzzzzzz")
        )
    }

    @Test
    fun `a name present in two repos returns both repos' ancestries`() {
        val rootA = entry(repoA, "zzzzzzzz")
        val mainA = entry(repoA, "aaa111", parentIds = listOf("zzzzzzzz"), bookmarks = listOf(Bookmark("main")))
        val rootB = entry(repoB, "zzzzzzzz")
        val mainB = entry(repoB, "bbb222", parentIds = listOf("zzzzzzzz"), bookmarks = listOf(Bookmark("main")))

        val entries = listOf(rootA, mainA, rootB, mainB)

        val result = ancestorKeys(entries) { it.bookmarks.any { b -> b.name.name == "main" } }

        result.shouldContainExactlyInAnyOrder(
            key(repoA, "aaa111"),
            key(repoA, "zzzzzzzz"),
            key(repoB, "bbb222"),
            key(repoB, "zzzzzzzz")
        )
    }

    @Test
    fun `working copy present in two repos returns both working copies and their own ancestry`() {
        // Regression test for jj-idea-2xf3: previously only the first repo's @ was matched.
        val rootA = entry(repoA, "zzzzzzzz")
        val wcA = entry(repoA, "aaa111", parentIds = listOf("zzzzzzzz"), isWorkingCopy = true)
        val rootB = entry(repoB, "zzzzzzzz")
        val wcB = entry(repoB, "bbb222", parentIds = listOf("zzzzzzzz"), isWorkingCopy = true)

        val entries = listOf(rootA, wcA, rootB, wcB)

        val result = ancestorKeys(entries) { it.isWorkingCopy }

        result.shouldContainExactlyInAnyOrder(
            key(repoA, "aaa111"),
            key(repoA, "zzzzzzzz"),
            key(repoB, "bbb222"),
            key(repoB, "zzzzzzzz")
        )
    }

    @Test
    fun `no match returns null`() {
        val entries = listOf(entry(repoA, "aaa111"))

        ancestorKeys(entries) { it.bookmarks.any { b -> b.name.name == "nonexistent" } }.shouldBeNull()
    }

    @Test
    fun `scales linearly - a long chain is walked in one pass, not once per node`() {
        val n = 20_000
        var matchCalls = 0
        val entries = (0 until n).map { i ->
            entry(repoA, "c$i", parentIds = if (i == n - 1) emptyList() else listOf("c${i + 1}"))
        }

        val result = ancestorKeys(entries) {
            matchCalls++
            it == entries.first()
        }

        result?.size shouldBe n
        // matches() is called once per candidate entry during seeding, never per BFS step -
        // a reintroduced allEntries.find{} inside the walk would blow this up to O(n^2).
        matchCalls shouldBe n
    }
}
