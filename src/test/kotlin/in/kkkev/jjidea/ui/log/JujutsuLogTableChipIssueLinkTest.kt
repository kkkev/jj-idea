package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Platform tests for jj-idea-vrmv: a linkified issue-tracker reference (e.g. `JIRA-123`) inside a
 * bookmark chip's own name gets a hand cursor while hovered, distinct from the rest of the chip
 * (which stays cursor-less per jj-idea-wkcz - see [JujutsuLogTableBookmarkClickTest]). Left-click
 * itself calls `BrowserUtil.browse`, which isn't exercised here to avoid actually invoking a
 * browser launcher in tests, matching [JujutsuLogTableIssueLinkTest]'s restraint.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableChipIssueLinkTest {
    private val project = projectFixture()

    // A relaxed mock's VirtualFile.path defaults to "", which collapses the "jjref://<host>?..."
    // authority to nothing and breaks LogClickTarget.REF_URL_PARSER's `([^?]+)` host group - stub
    // a real path so a chip's jjref URI actually resolves (see LogClickTargetTest).
    private val repo = mockk<JujutsuRepository>(relaxed = true).also { every { it.directory.path } returns "/repo" }
    private var table: JujutsuLogTable? = null

    @AfterEach
    fun cleanUp() {
        table?.let {
            it.dispatchEvent(MouseEvent(it, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false))
        }
        drainBackgroundLoads()
    }

    private fun entry(bookmarks: List<Bookmark>) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = bookmarks
    )

    private fun tableWith(entries: List<LogEntry>): JujutsuLogTable {
        IssueNavigationConfiguration.getInstance(project.get()).links =
            listOf(IssueNavigationLink("[A-Z]+-\\d+", "https://tracker/\$0"))
        val table = JujutsuLogTable(project.get())
        this.table = table
        Disposer.register(project.get(), table)
        table.setEntries(entries)
        table.setSize(2000, 400)
        table.doLayout()
        table.updateGraph(emptyMap())
        return table
    }

    private fun cellRect(table: JujutsuLogTable, row: Int) =
        table.getCellRect(row, table.convertColumnIndexToView(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION), false)

    /** The x-offset (relative to the cell) of the linkified "JIRA-123" reference in row 0's sole bookmark chip. */
    private fun issueLinkLocalX(table: JujutsuLogTable, row: Int): Int {
        val cellRect = cellRect(table, row)
        val entry = table.logModel.getEntry(row)!!
        val frc = table.getFontMetrics(table.font).fontRenderContext
        val issueLinks = IssueNavigationConfiguration.getInstance(table.project)
        val hitX = (0 until cellRect.width).first { x ->
            val uri = findInlinedRefUri(entry, x, cellRect.width, table.font, frc, true, issueLinks)
            uri != null && LogClickTarget.resolve(uri, table.project, listOf(entry)) is IssueLinkClick
        }
        return hitX
    }

    /** The x-offset of a point elsewhere in the chip (the icon), which should resolve to the bookmark, not the issue link. */
    private fun chipIconLocalX(table: JujutsuLogTable, row: Int): Int {
        val cellRect = cellRect(table, row)
        val entry = table.logModel.getEntry(row)!!
        val frc = table.getFontMetrics(table.font).fontRenderContext
        val issueLinks = IssueNavigationConfiguration.getInstance(table.project)
        val hitX = (0 until cellRect.width).first { x ->
            val uri = findInlinedRefUri(entry, x, cellRect.width, table.font, frc, true, issueLinks)
            uri != null && LogClickTarget.resolve(uri, table.project, listOf(entry)) is BookmarkClick
        }
        return hitX
    }

    private fun moveMouseTo(table: JujutsuLogTable, point: Point) {
        table.dispatchEvent(
            MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false)
        )
    }

    @Test
    fun `hovering the linkified issue reference inside a bookmark chip shows the hand cursor`() {
        val table = tableWith(listOf(entry(listOf(Bookmark("JIRA-123-fix-thing")))))
        val cellRect = cellRect(table, 0)
        val point = Point(cellRect.x + issueLinkLocalX(table, 0), cellRect.y + cellRect.height / 2)

        moveMouseTo(table, point)

        table.cursor shouldBe Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    @Test
    fun `hovering the rest of the same chip does not show the hand cursor`() {
        val table = tableWith(listOf(entry(listOf(Bookmark("JIRA-123-fix-thing")))))
        val cellRect = cellRect(table, 0)
        val point = Point(cellRect.x + chipIconLocalX(table, 0), cellRect.y + cellRect.height / 2)

        moveMouseTo(table, point)

        table.cursor shouldBe Cursor.getDefaultCursor()
    }
}
