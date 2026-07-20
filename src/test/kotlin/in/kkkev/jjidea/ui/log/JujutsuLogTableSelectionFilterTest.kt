package `in`.kkkev.jjidea.ui.log

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-yje9: a filter change on the log table used to deselect the
 * currently-selected commit, or silently select the wrong one, even when it remained visible.
 * Every filter setter on [JujutsuLogTableModel] ends by rebuilding [JujutsuLogTableModel.filteredEntries]
 * and firing a full table-changed event; Swing's [javax.swing.JTable] does not reliably follow the
 * *entry* that was selected through that rebuild - it can leave the old row *index* selected
 * (silently pointing at a different, wrong entry), or drop the selection entirely.
 *
 * [JujutsuLogTable] installs [JujutsuLogTableModel.withSelectionPreserved] (see its `init` block) to
 * capture the selected entry's identity before a filter-driven rebuild and restore it by identity
 * afterwards - or explicitly clear the selection if the entry no longer matches the filter.
 *
 * Platform-tagged because it exercises a real Swing [JujutsuLogTable]/[com.intellij.ui.table.JBTable]
 * selection model, which needs IJPGP's full platform classpath (see project memory on IJPGP test
 * infrastructure).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableSelectionFilterTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>()

    private fun entry(changeId: String, author: String? = "alice@example.com") = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        parentIdentifiers = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = false,
        authorTimestamp = null,
        committerTimestamp = null,
        author = author?.let { VcsUserImpl("Alice", it) },
        committer = null
    )

    private fun tableWith(entries: List<LogEntry>): JujutsuLogTable {
        val table = JujutsuLogTable(project.get())
        table.setEntries(entries)
        return table
    }

    @Test
    fun `filter change preserves selection when the selected entry stays visible`() {
        val a = entry("aaa111")
        val b = entry("bbb222")
        val table = tableWith(listOf(a, b))
        table.setRowSelectionInterval(0, 0)
        table.selectedEntry?.id shouldBe a.id

        // A filter that both entries still match.
        table.logModel.setAuthorFilter(setOf("alice@example.com"))

        table.selectedEntry?.id shouldBe a.id
    }

    @Test
    fun `reported scenario - clearing the bookmark filter keeps the current selection`() {
        val a = entry("aaa111")
        val b = entry("bbb222")
        val table = tableWith(listOf(a, b))
        table.setRowSelectionInterval(0, 0)

        table.logModel.setBookmarkFilter(setOf(a.id))
        table.selectedEntry?.id shouldBe a.id

        table.logModel.setBookmarkFilter(emptySet())
        table.selectedEntry?.id shouldBe a.id
    }

    @Test
    fun `filtering out the selected entry deselects it, not the next row's entry`() {
        val a = entry("aaa111", author = "alice@example.com")
        val b = entry("bbb222", author = "bob@example.com")
        val table = tableWith(listOf(a, b))
        table.setRowSelectionInterval(0, 0)

        table.logModel.setAuthorFilter(setOf("bob@example.com"))

        // Without identity-based reselection, the surviving row still occupies index 0 (the
        // previously-selected row's index), so a naive fix would leave b silently selected instead
        // of correctly clearing the selection.
        table.selectedEntry shouldBe null
    }

    @Test
    fun `a filter change that preserves selection never surfaces a transient empty selection to deferred listeners`() {
        val a = entry("aaa111")
        val b = entry("bbb222")
        val table = tableWith(listOf(a, b))
        table.setRowSelectionInterval(0, 0)

        // Mirrors CommitTablePanel's real selection listener (JujutsuCommitDetailsPanel is driven
        // this way): defer the read via runLater rather than sampling synchronously, so it only
        // ever observes the settled post-reselect state, not any transient selection-model churn
        // the clear+reselect causes along the way.
        val observed = mutableListOf<List<ChangeId>>()
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) runLater { observed.add(table.selectedEntries.map { it.id }) }
        }

        table.logModel.setAuthorFilter(setOf("alice@example.com"))
        UIUtil.dispatchAllInvocationEvents()

        observed shouldNotContain emptyList<ChangeId>()
        observed.last() shouldBe listOf(a.id)
    }
}
