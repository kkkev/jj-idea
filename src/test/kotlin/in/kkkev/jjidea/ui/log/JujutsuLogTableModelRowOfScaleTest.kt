package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Operation-count scale test for [JujutsuLogTableModel.rowOf] and the `filteredRowByKey` map that
 * backs it (jj-idea-wrza's viewport anchor, which calls `rowOf` once per [JujutsuLogTable.setEntries]
 * to relocate the previously top-visible row - a regression that rescanned the whole filtered list
 * per call would be easy to miss since the result would still be correct, just slow at scale).
 *
 * `rowOf` itself is an O(1) map lookup by construction (`filteredRowByKey[key]`, same as
 * [JujutsuLogTableModel.entryFor]'s `entriesByKey[key]` - see that function's scale note). What a
 * regression could plausibly add is a *second* per-row scan of `newEntries` inside [setEntries]
 * itself (e.g. building `filteredRowByKey` via a linear search instead of a single indexed pass) -
 * this counts passes over the caller-supplied list via the same [Iterable.iterator]-counting
 * wrapper [in.kkkev.jjidea.ui.dnd.DragContextScaleTest] uses, adapted here since
 * [JujutsuLogTableModel] has no counter of its own to expose.
 */
class JujutsuLogTableModelRowOfScaleTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(id: Int) = LogEntry(
        repo = repo,
        id = ChangeId("e$id", "e$id", null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $id"
    )

    /** Tracks how many full passes ([iterator] calls) callers make over [delegate]. */
    private class CountingEntryList(private val delegate: List<LogEntry>) : List<LogEntry> by delegate {
        var passCount = 0
            private set

        override fun iterator(): Iterator<LogEntry> {
            passCount++
            return delegate.iterator()
        }
    }

    @Test
    fun `setEntries makes a bounded number of passes over a 50k-entry list, not one per row`() {
        val n = 50_000
        val entries = (0 until n).map { entry(it) }
        val counting = CountingEntryList(entries)
        val model = JujutsuLogTableModel()

        model.setEntries(counting)

        // addAll + associateBy is the existing cost (2 passes); a regression that rescanned
        // newEntries once per row while building filteredRowByKey would instead show ~n passes.
        counting.passCount shouldBeLessThan 5
    }

    @Test
    fun `rowOf resolves every row correctly at scale, including the first, middle, and last`() {
        val n = 50_000
        val entries = (0 until n).map { entry(it) }
        val model = JujutsuLogTableModel()
        model.setEntries(entries)

        model.rowOf(ChangeKey(repo, entries[0].id)) shouldBe 0
        model.rowOf(ChangeKey(repo, entries[n / 2].id)) shouldBe n / 2
        model.rowOf(ChangeKey(repo, entries[n - 1].id)) shouldBe n - 1
        model.rowOf(ChangeKey(repo, ChangeId("missing", "missing", null))) shouldBe null
    }
}
