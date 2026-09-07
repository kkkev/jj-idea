package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [JujutsuLogTableModel.entryFor] — the bookmarks panel's only way to turn a bookmark's
 * [ChangeId] into a real [LogEntry] for the log's change actions (New Change/Edit/Rebase/
 * Duplicate, jj-idea-p35f), reusing entries the log table already has loaded rather than a second
 * `jj log` invocation.
 *
 * Scale: [JujutsuLogTableModel.setEntries] builds the backing map once per load, O(N) in already-
 * loaded entries — no separate traversal from the existing `entries`/`filteredEntries` rebuild —
 * and [entryFor] itself is an O(1) map lookup, asserted below by resolving from a set of entries
 * far larger than any single lookup would need to scan.
 */
class JujutsuLogTableModelEntryForTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(id: String) = LogEntry(repo, ChangeId(id, id), CommitId(id.repeat(5)), "commit $id")

    @Test
    fun `resolves a loaded entry by its ChangeKey`() {
        val model = JujutsuLogTableModel()
        val target = entry("target1")
        model.setEntries((1..500).map { entry("filler$it") } + target)

        model.entryFor(ChangeKey(repo, target.id)) shouldBe target
    }

    @Test
    fun `returns null for a change outside the loaded window`() {
        val model = JujutsuLogTableModel()
        model.setEntries(listOf(entry("loaded")))

        model.entryFor(ChangeKey(repo, ChangeId("notLoaded", "notLoaded"))) shouldBe null
    }

    @Test
    fun `a filtered-out entry is still resolvable - it's a valid action target, not a display concern`() {
        val model = JujutsuLogTableModel()
        val hidden = entry("hidden")
        model.setEntries(listOf(hidden, entry("visible")))
        model.setFilter("visible")

        model.rowCount shouldBe 1
        model.entryFor(ChangeKey(repo, hidden.id)) shouldBe hidden
    }

    @Test
    fun `a fresh setEntries call replaces the lookup, dropping entries no longer loaded`() {
        val model = JujutsuLogTableModel()
        val first = entry("first")
        model.setEntries(listOf(first))

        model.setEntries(listOf(entry("second")))

        model.entryFor(ChangeKey(repo, first.id)) shouldBe null
    }
}
