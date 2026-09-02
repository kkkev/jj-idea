package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.dnd.DropTarget
import `in`.kkkev.jjidea.ui.dnd.DropZone
import `in`.kkkev.jjidea.ui.dnd.ZoneHysteresis
import `in`.kkkev.jjidea.util.drainBackgroundLoads
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseEvent

/**
 * Platform-level coverage for [dropTargetAt] against a real, laid-out [JujutsuLogTable] (jj-idea-6jvh):
 * real pixel geometry (row height, `getCellRect`) rather than the pure [in.kkkev.jjidea.ui.dnd.DropZonesTest]
 * math, plus design section 2's per-row-not-per-gap correctness trap. Also checks installing DnD
 * doesn't disturb the table's existing mouse-driven behaviour, mirroring [JujutsuLogTableBookmarkClickTest].
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableDnDTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private var table: JujutsuLogTable? = null

    // Same teardown as JujutsuLogTableBookmarkClickTest, to avoid a flaky LeakHunter retained
    // -Project report (jj-idea-q49j): drain stateModel's fire-and-forget loaders and clear the
    // static ToolTipManager registration installIconAwareTooltip left behind.
    @AfterEach
    fun cleanUp() {
        table?.let {
            it.dispatchEvent(MouseEvent(it, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false))
        }
        drainBackgroundLoads()
    }

    private fun entry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("commit-$id"),
        underlyingDescription = "desc $id"
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

    private fun pointInRow(table: JujutsuLogTable, row: Int, dy: Int): Point {
        val rowRect = table.getCellRect(row, 0, true)
        return Point(rowRect.x + 10, rowRect.y + dy)
    }

    @Test
    fun `the top band of a row resolves to a Gap INSERT_BEFORE`() {
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb")
        val table = tableWith(listOf(a, b))

        val (row, target) = table.dropTargetAt(pointInRow(table, 0, 0), ZoneHysteresis())!!

        row shouldBe 0
        target.shouldNotBeNull()
        target as DropTarget.Gap
        target.entry shouldBe a
        target.edge shouldBe DropZone.INSERT_BEFORE
    }

    @Test
    fun `the row centre resolves to a CommitRow ONTO`() {
        val a = entry("aaaaaaaa")
        val table = tableWith(listOf(a))

        val (_, target) = table.dropTargetAt(pointInRow(table, 0, table.rowHeight / 2), ZoneHysteresis())!!

        target.shouldNotBeNull()
        target as DropTarget.CommitRow
        target.entry shouldBe a
    }

    @Test
    fun `the bottom band of a row resolves to a Gap INSERT_AFTER`() {
        val a = entry("aaaaaaaa")
        val table = tableWith(listOf(a))

        val (_, target) = table.dropTargetAt(pointInRow(table, 0, table.rowHeight - 1), ZoneHysteresis())!!

        target.shouldNotBeNull()
        target as DropTarget.Gap
        target.entry shouldBe a
        target.edge shouldBe DropZone.INSERT_AFTER
    }

    @Test
    fun `the top band of row N is bound to row N, not the bottom band of row N-1 (per-row, not per-gap)`() {
        // Design section 2's correctness trap: the log is a linearised DAG view, so
        // table-adjacent rows are not necessarily DAG-adjacent. The boundary between row 0 and
        // row 1 must resolve to Gap(row1, INSERT_BEFORE) from row 1's own top band, never
        // Gap(row0, INSERT_AFTER) from row 0's bottom band, even though both bands sit at the
        // same visual boundary.
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb")
        val table = tableWith(listOf(a, b))

        val (row, target) = table.dropTargetAt(pointInRow(table, 1, 0), ZoneHysteresis())!!

        row shouldBe 1
        target.shouldNotBeNull()
        target as DropTarget.Gap
        target.entry shouldBe b
        target.edge shouldBe DropZone.INSERT_BEFORE
    }

    @Test
    fun `a point below the last row resolves to no target`() {
        val a = entry("aaaaaaaa")
        val table = tableWith(listOf(a))

        table.dropTargetAt(Point(10, 5000), ZoneHysteresis()).shouldBeNull()
    }

    @Test
    fun `installing drag-and-drop leaves plain single-click handling on a row unaffected`() {
        // Real mouse-press-driven selection goes through BasicTableUI, which needs a real
        // display (HeadlessException in CI) - so this drives the table's own MOUSE_CLICKED
        // listener instead (as JujutsuLogTableBookmarkClickTest does), which is the code path
        // DnD installation could plausibly have disturbed by registering its own listeners.
        val a = entry("aaaaaaaa")
        val b = entry("bbbbbbbb")
        val table = tableWith(listOf(a, b))
        table.setRowSelectionInterval(1, 1)
        val point = pointInRow(table, 1, table.rowHeight / 2)

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

        table.selectedEntry shouldBe b
    }

    @Test
    fun `installing drag-and-drop does not change the default cursor away from a plain row`() {
        val a = entry("aaaaaaaa")
        val table = tableWith(listOf(a))

        table.dispatchEvent(
            MouseEvent(
                table,
                MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(),
                0,
                pointInRow(table, 0, table.rowHeight / 2).x,
                pointInRow(table, 0, table.rowHeight / 2).y,
                0,
                false
            )
        )

        table.cursor shouldBe Cursor.getDefaultCursor()
    }
}
