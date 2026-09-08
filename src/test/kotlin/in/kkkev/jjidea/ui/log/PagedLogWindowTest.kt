package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PagedLogWindow] — the frontier-cursor paging mechanism behind jj-idea-2c8k
 * (GitHub #69). See docs/design/jj-idea-2c8k-paged-log-loading.md for the full mechanism,
 * correctness invariant, and empirical validation against real/synthetic jj repos; these tests
 * pin the pure bookkeeping logic in isolation (no real jj process, no IntelliJ platform).
 *
 * Pages are fed in via [PagedLogWindow.recordPage] as scripted sequences the test controls
 * directly — this tests whether [PagedLogWindow] updates its own frontier/exhaustion state
 * correctly given what it's told, not whether a real `jj log` would actually return those exact
 * pages for the revsets [PagedLogWindow] constructs (that end-to-end claim is covered separately
 * by a `contractTest` against real jj, per the design doc's validation matrix).
 */
class PagedLogWindowTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(id: String, vararg parentIds: String, offset: Int? = null) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, offset),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id",
        parentIds = parentIds.map { ChangeId(it, it) }
    )

    @Test
    fun `carrying the frontier forward across pages prevents starving a slower branch`() {
        // Two disjoint chains: A -> P_A (root), B -> P_B (root). Two heads: A, B.
        // Simulate jj returning only A's own row on page 1 (as if B's branch simply didn't rank
        // within this page's --limit) - a naive implementation that recomputes the frontier from
        // only the just-fetched page's own parents (discarding whatever of the original seed
        // wasn't re-mentioned) would lose B here, permanently. Carrying the seeded frontier
        // forward (this design) must not.
        val window = PagedLogWindow(Expression("all()"), pageSize = 1)
        window.seed(listOf(ChangeId("A", "A"), ChangeId("B", "B")))

        window.recordPage(listOf(entry("A", "P_A")))
        window.recordPage(listOf(entry("B", "P_B")))
        window.recordPage(listOf(entry("P_A")))
        window.recordPage(listOf(entry("P_B")))

        window.entries.map { it.id.full } shouldContainExactly listOf("A", "B", "P_A", "P_B")
        window.isExhausted shouldBe true
        window.pageCount shouldBe 4
    }

    @Test
    fun `a naive fresh-each-page frontier would have starved the slower branch (documents the bug)`() {
        // Same shape as above, but manually reproducing the buggy "recompute frontier only from
        // this page's own rows" logic, to document exactly what went wrong and confirm this test
        // suite would have caught it.
        val shown = mutableSetOf<String>()
        var frontier = setOf("A", "B") // seeded, but about to be discarded each page (the bug)
        val page1 = listOf(entry("A", "P_A"))
        page1.forEach { shown += it.id.full }
        // Buggy: frontier recomputed ONLY from page1's own parents, ignoring the rest of the seed.
        frontier = page1.flatMap { it.parentIds.map(ChangeId::full) }.filterNot { it in shown }.toSet()
        frontier shouldContainExactlyInAnyOrder listOf("P_A") // "B" is gone - the bug, reproduced
    }

    @Test
    fun `divergent ids render as offset-qualified, present-wrapped revset terms, never bare`() {
        val window = PagedLogWindow(Expression("all()"), pageSize = 10)
        window.seed(listOf(ChangeId("abc", "ab", 2)))

        val revset = window.pageRevset().toString()

        revset shouldBe "(all()) & ((present(abc/2)) | ancestors(present(abc/2)))"
    }

    @Test
    fun `frontierTooWide is a strict boundary check against the configured cap`() {
        val window = PagedLogWindow(Expression("all()"), pageSize = 500)
        window.seed((1..PagedLogWindow.FRONTIER_CAP).map { ChangeId("h$it", "h$it") })

        window.frontierTooWide(PagedLogWindow.FRONTIER_CAP) shouldBe false
        window.frontierTooWide(PagedLogWindow.FRONTIER_CAP - 1) shouldBe true
    }

    @Test
    fun `idsWithUnresolvedParent reports exactly the rows whose parent hasn't loaded yet`() {
        val window = PagedLogWindow(Expression("all()"), pageSize = 10)
        window.seed(listOf(ChangeId("A", "A"), ChangeId("C", "C")))

        // A's parent P_A is not yet loaded; C has no parent at all (a root).
        window.recordPage(listOf(entry("A", "P_A"), entry("C")))

        window.idsWithUnresolvedParent().map { it.full } shouldContainExactly listOf("A")

        // Once P_A loads, A is no longer pending.
        window.recordPage(listOf(entry("P_A")))
        window.idsWithUnresolvedParent() shouldBe emptySet()
    }

    @Test
    fun `an empty page terminates the walk instead of leaving the frontier stuck forever`() {
        // Regression test for a real infinite loop found via PagedLogWindowContractTest: with a
        // base revset that isn't closed under ancestry (e.g. bookmarks()), a frontier member's
        // true parent can be real but permanently excluded by the filter, so it never appears in
        // any page and never leaves the frontier - without this fix, isExhausted never becomes
        // true and a caller looping until it does hangs (and eventually OOMs).
        val window = PagedLogWindow(Expression("bookmarks()"), pageSize = 2)
        window.seed(listOf(ChangeId("bookmarked", "b")))

        window.recordPage(listOf(entry("bookmarked", "unbookmarked_parent")))
        window.isExhausted shouldBe false // frontier now has the unbookmarked parent

        window.recordPage(emptyList()) // simulates: unbookmarked_parent's ancestry never matches bookmarks()

        window.isExhausted shouldBe true
        window.entries.map { it.id.full } shouldContainExactly listOf("bookmarked")
    }

    @Test
    fun `reset clears pages, frontier, and seed state so the window can be re-walked`() {
        val window = PagedLogWindow(Expression("all()"), pageSize = 10)
        window.seed(listOf(ChangeId("A", "A")))
        window.recordPage(listOf(entry("A")))

        window.reset()

        window.entries shouldBe emptyList()
        window.pageCount shouldBe 0
        window.isExhausted shouldBe false // not seeded yet
    }
}
