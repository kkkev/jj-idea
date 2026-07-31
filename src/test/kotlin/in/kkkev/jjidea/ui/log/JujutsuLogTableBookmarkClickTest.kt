package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.event.MouseEvent

/**
 * Regression tests for jj-idea-wkcz: bookmark/tag chips in the log table used to be left-click
 * hyperlinks (filtering the log to that reference) with a hand-cursor hover cue. Both are now gone
 * - the right-click context menu (`clickActionGroup`) is the only way to reach that action - so a
 * bookmark chip must behave like plain cell content on hover/left-click.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableBookmarkClickTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private var table: JujutsuLogTable? = null

    // stateModel.init fires fire-and-forget pooled-thread loaders that capture this fixture's
    // project (see PlatformTestSupport.drainBackgroundLoads); drain them before projectFixture
    // disposes the project, to avoid a flaky LeakHunter retained-Project report (jj-idea-q49j).
    //
    // A dispatched MOUSE_MOVED also leaves the table registered as ToolTipManager's static
    // insideComponent (installIconAwareTooltip registers with it) - a real MOUSE_EXITED clears
    // that, same reasoning as the stateModel drain above.
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
        val table = JujutsuLogTable(project.get())
        this.table = table
        Disposer.register(project.get(), table)
        table.setEntries(entries)
        table.setSize(2000, 400)
        table.doLayout()
        return table
    }

    /** A point over the graph+description cell, right at its right edge — where a lone bookmark
     * chip renders (jj-idea-w61m: decorations are right-aligned within the cell). */
    private fun bookmarkChipPoint(table: JujutsuLogTable, row: Int): java.awt.Point {
        val col = table.convertColumnIndexToView(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION)
        val cellRect = table.getCellRect(row, col, false)
        return java.awt.Point(cellRect.x + cellRect.width - 5, cellRect.y + cellRect.height / 2)
    }

    private fun moveMouseTo(table: JujutsuLogTable, point: java.awt.Point) {
        table.dispatchEvent(
            MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false)
        )
    }

    private fun clickAt(table: JujutsuLogTable, point: java.awt.Point) {
        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1
            )
        )
    }

    @Test
    fun `hovering a bookmark chip does not show the hand cursor`() {
        val table = tableWith(listOf(entry(listOf(Bookmark("main")))))

        moveMouseTo(table, bookmarkChipPoint(table, 0))

        table.cursor shouldBe Cursor.getDefaultCursor()
    }

    @Test
    fun `left-clicking a bookmark chip does not filter the log to that reference`() {
        val table = tableWith(listOf(entry(listOf(Bookmark("main")))))
        var notified: String? = null
        project.get().stateModel.filterToReference.connect(table) { notified = it }

        clickAt(table, bookmarkChipPoint(table, 0))

        notified shouldBe null
    }
}
