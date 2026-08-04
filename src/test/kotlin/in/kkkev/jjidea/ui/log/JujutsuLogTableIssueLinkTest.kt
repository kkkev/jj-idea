package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.openapi.vcs.IssueNavigationLink
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.IssueLinkifier
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Platform tests for jj-idea-91qf: a linkified issue-tracker reference (e.g. `JIRA-123`) in the
 * graph+description column's description text gets a hand cursor while hovered, matching the
 * "colored always, hand cursor + underline on hover" convention used for author/committer names
 * (jj-idea-iesq). Not covered here (both require headless-unsafe rendering paths, matching the
 * restraint [JujutsuLogTableBookmarkClickTest] already takes on the mailto/bookmark paths):
 * - Left-click itself calls `BrowserUtil.browse`, which would actually invoke a browser launcher.
 * - The tooltip's `<a href>` anchor requires `JTable.prepareRenderer`, which calls the headless-
 *   unsafe `Component.getMousePosition()` inside [JujutsuGraphAndDescriptionRenderer]; the
 *   underlying linkify-to-HTML mechanism it reuses is covered by `IssueLinkRenderingTest` instead.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableIssueLinkTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private var table: JujutsuLogTable? = null

    @AfterEach
    fun cleanUp() {
        table?.let {
            it.dispatchEvent(MouseEvent(it, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false))
        }
        drainBackgroundLoads()
    }

    private fun entry(description: String) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = description
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
        // Force graphNodes/renderer install with issueLinks threaded (jj-idea-91qf) - setEntries
        // alone installs the renderer without graph data.
        table.updateGraph(emptyMap())
        return table
    }

    private fun cellRect(table: JujutsuLogTable, row: Int) =
        table.getCellRect(row, table.convertColumnIndexToView(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION), false)

    /** The x-offset (relative to the cell) of the linkified "JIRA-123" reference in row 0's description. */
    private fun issueLinkLocalX(table: JujutsuLogTable, row: Int): Int {
        val cellRect = cellRect(table, row)
        val entry = table.logModel.getEntry(row)!!
        val frc = table.getFontMetrics(table.font).fontRenderContext
        val textStart = graphTextStartX(row, table.logModel, table.graphNodes)
        val linkifier = IssueLinkifier(IssueNavigationConfiguration.getInstance(table.project))
        val availableWidth = cellRect.width - textStart
        val hitX = (0 until availableWidth).first { x ->
            findDescriptionLinkUri(entry, x, availableWidth, table.columnManager, linkifier, table.font, frc) != null
        }
        return textStart + hitX
    }

    private fun moveMouseTo(table: JujutsuLogTable, point: Point) {
        table.dispatchEvent(
            MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false)
        )
    }

    @Test
    fun `hovering the linkified issue reference shows the hand cursor`() {
        val table = tableWith(listOf(entry("Fixes JIRA-123 now")))
        val cellRect = cellRect(table, 0)
        val point = Point(cellRect.x + issueLinkLocalX(table, 0), cellRect.y + cellRect.height / 2)

        moveMouseTo(table, point)

        table.cursor shouldBe Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    @Test
    fun `hovering plain description text before the reference shows the default cursor`() {
        val table = tableWith(listOf(entry("Fixes JIRA-123 now")))
        val cellRect = cellRect(table, 0)
        val textStart = graphTextStartX(0, table.logModel, table.graphNodes)
        val point = Point(cellRect.x + textStart + 2, cellRect.y + cellRect.height / 2)

        moveMouseTo(table, point)

        table.cursor shouldBe Cursor.getDefaultCursor()
    }
}
