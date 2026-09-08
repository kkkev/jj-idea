package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import javax.swing.JScrollPane

/**
 * Regression tests for jj-idea-2c8k round 3: [JujutsuLogTable.setEntries] used to always scroll
 * to the (unchanged) selection after rebuilding the model, which yanked the viewport back up to
 * `@` every time [in.kkkev.jjidea.ui.log.UnifiedJujutsuLogDataLoader.loadMore] appended a new
 * page below the current scroll position. The fix: only scroll when the pending selection is
 * *explicit* ([JujutsuLogTable.requestSelection]) - a merely carried-over selection during a data
 * refresh no longer forces the viewport back to it.
 *
 * Platform-tagged: needs a real Swing [JujutsuLogTable] inside a real [JScrollPane] to observe
 * viewport movement, which needs IJPGP's full platform classpath.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableScrollPreservationTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>()

    private fun entry(changeId: String) = LogEntry(
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
        author = VcsUserImpl("Alice", "alice@example.com"),
        committer = null
    )

    /** Many rows so row 0 sits well above a short viewport once scrolled to the bottom. */
    private fun manyEntries(count: Int) = (0 until count).map { entry("e%03d".format(it)) }

    private fun tableInScrollPane(entries: List<LogEntry>): Pair<JujutsuLogTable, JScrollPane> {
        val table = JujutsuLogTable(project.get())
        Disposer.register(project.get(), table)
        table.setEntries(entries)
        val scrollPane = JScrollPane(table)
        scrollPane.setSize(400, 100)
        scrollPane.viewport.setSize(400, 100)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        return table to scrollPane
    }

    @Test
    fun `carrying an unchanged selection through setEntries does not scroll back to it`() {
        val entries = manyEntries(50)
        val (table, scrollPane) = tableInScrollPane(entries)

        // Select row 0 (top), then scroll the viewport down to the bottom so row 0 is off-screen.
        table.setRowSelectionInterval(0, 0)
        val bottomRect = table.getCellRect(49, 0, true)
        scrollPane.viewport.viewPosition = java.awt.Point(0, bottomRect.y)
        val scrolledPosition = scrollPane.viewport.viewPosition

        // A refresh that just re-passes the same entries: setEntries() will carry the existing
        // selection (row 0) forward implicitly - pendingSelectionIsExplicit is false here since
        // no requestSelection() call was made.
        table.setEntries(entries)

        scrollPane.viewport.viewPosition shouldBe scrolledPosition
    }

    @Test
    fun `explicit requestSelection still scrolls to make the target visible`() {
        val entries = manyEntries(50)
        val (table, scrollPane) = tableInScrollPane(entries)

        // Start scrolled to the bottom, viewing the last row.
        val bottomRect = table.getCellRect(49, 0, true)
        scrollPane.viewport.viewPosition = java.awt.Point(0, bottomRect.y)
        val scrolledPosition = scrollPane.viewport.viewPosition

        // Explicitly navigate to row 0's entry, which is off-screen at the top.
        table.requestSelection(ChangeKey(repo, entries[0].id))

        scrollPane.viewport.viewPosition shouldNotBe scrolledPosition
    }
}
