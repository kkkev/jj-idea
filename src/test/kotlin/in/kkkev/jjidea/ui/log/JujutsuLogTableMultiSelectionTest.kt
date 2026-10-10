package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * GitHub #145 (jj-idea-3mjy): a multi-row selection used to be dropped by any data refresh or
 * filter change, because only a *single* selected entry was carried across. With paged loading's
 * idle trickle landing a page every second or two, that made multi-select unusable. The whole
 * selection - and its lead/anchor - is now carried by entry identity.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableMultiSelectionTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>()

    private fun entry(changeId: String, author: String = "alice@example.com") = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        parentIds = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = false,
        authorTimestamp = null,
        committerTimestamp = null,
        author = VcsUserImpl("Someone", author),
        committer = null
    )

    private val a = entry("aaa")
    private val b = entry("bbb")
    private val c = entry("ccc")
    private val d = entry("ddd")

    private fun tableWith(entries: List<LogEntry>): JujutsuLogTable {
        val table = JujutsuLogTable(project.get())
        Disposer.register(project.get(), table)
        table.setEntries(entries)
        return table
    }

    private fun JujutsuLogTable.selectedIds() = selectedEntries.map { it.id.full }

    @Test
    fun `a refresh with identical entries keeps every selected row`() {
        val table = tableWith(listOf(a, b, c, d))
        table.setRowSelectionInterval(0, 0)
        table.addRowSelectionInterval(2, 2)

        table.setEntries(listOf(a, b, c, d))

        table.selectedIds() shouldBe listOf("aaa", "ccc")
    }

    @Test
    fun `selection follows the entries when rows are prepended`() {
        val table = tableWith(listOf(a, b, c))
        table.setRowSelectionInterval(1, 1)
        table.addRowSelectionInterval(2, 2)

        table.setEntries(listOf(d, a, b, c)) // a new commit lands at the top

        table.selectedIds() shouldBe listOf("bbb", "ccc")
    }

    @Test
    fun `a selected entry that disappears is dropped and the rest survive`() {
        val table = tableWith(listOf(a, b, c, d))
        table.setRowSelectionInterval(0, 0)
        table.addRowSelectionInterval(1, 1)
        table.addRowSelectionInterval(3, 3)

        table.setEntries(listOf(a, c, d)) // b was abandoned

        table.selectedIds() shouldBe listOf("aaa", "ddd")
    }

    @Test
    fun `selection is cleared when none of the selected entries survive`() {
        val table = tableWith(listOf(a, b, c))
        table.setRowSelectionInterval(0, 0)
        table.addRowSelectionInterval(1, 1)

        table.setEntries(listOf(c))

        table.selectedIds() shouldBe emptyList()
    }

    @Test
    fun `the lead and anchor rows are restored`() {
        val table = tableWith(listOf(a, b, c, d))
        table.setRowSelectionInterval(1, 3) // anchor b, lead d

        table.setEntries(listOf(d, a, b, c, entry("eee")))

        table.selectedIds() shouldBe
            listOf("bbb", "ccc", "ddd").sortedBy { listOf("ddd", "aaa", "bbb", "ccc").indexOf(it) }
        table.selectionModel.leadSelectionIndex shouldBe 0 // d moved to row 0
        table.selectionModel.anchorSelectionIndex shouldBe 2 // b moved to row 2
    }

    @Test
    fun `a filter change keeps the visible part of a multi-selection`() {
        val bob = entry("bob", author = "bob@example.com")
        val table = tableWith(listOf(a, bob, b, c))
        table.setRowSelectionInterval(0, 3)

        table.logModel.setAuthorFilter(setOf("alice@example.com")) // hides bob

        table.selectedIds() shouldBe listOf("aaa", "bbb", "ccc")
    }

    @Test
    fun `a single selected row is still carried`() {
        val table = tableWith(listOf(a, b, c))
        table.setRowSelectionInterval(1, 1)

        table.setEntries(listOf(d, a, b, c))

        table.selectedEntry?.id?.full shouldBe "bbb"
    }
}
