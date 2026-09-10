package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.ScrollPaneFactory
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
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
 * Platform coverage for jj-idea-sc8m's long-edge hover/click wiring in [JujutsuLogTable]: the pure
 * hit-test logic itself is covered by [GraphEdgeIndexTest]; this drives real
 * [java.awt.event.MouseEvent]s against a real table, mirroring
 * [JujutsuLogTableBookmarkClickTest]'s fixture. The table is deliberately not wrapped in a
 * [javax.swing.JScrollPane]: `setSize` alone still bounds `visibleRect` to less than the full
 * content, which is what makes a distant edge "long" per [JujutsuLogTable.graphEdgeHoverAt].
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableGraphEdgeHoverTest {
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

    private fun entry(id: String, parentIds: List<String> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId(id, id, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "commit $id",
        parentIds = parentIds.map { ChangeId(it, it, null) }
    )

    private fun tableWith(entries: List<LogEntry>): JujutsuLogTable {
        val table = JujutsuLogTable(project.get())
        this.table = table
        Disposer.register(project.get(), table)
        table.setEntries(entries)
        // Short enough that only the first ~18 of many rows are within visibleRect - see class doc.
        table.setSize(2000, 400)
        table.doLayout()
        table.updateGraph(CommitGraphBuilder().buildGraph(entries))
        return table
    }

    /** A point on [lane] in [row]'s cell, at the row's vertical center - direction no longer
     * depends on where within the row you point (jj-idea-sc8m round 3), so a single point per row
     * suffices. */
    private fun edgePoint(table: JujutsuLogTable, row: Int, lane: Int): Point {
        val col = table.convertColumnIndexToView(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION)
        val cellRect = table.getCellRect(row, col, false)
        val laneX = JujutsuGraphAndDescriptionRenderer.laneX(
            lane,
            JujutsuGraphAndDescriptionRenderer.HORIZONTAL_PADDING.get(),
            JujutsuGraphAndDescriptionRenderer.LANE_WIDTH.get()
        )
        return Point(cellRect.x + laneX, cellRect.y + cellRect.height / 2)
    }

    private fun moveMouseTo(table: JujutsuLogTable, point: Point) {
        table.dispatchEvent(
            MouseEvent(table, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, point.x, point.y, 0, false)
        )
    }

    private fun clickAt(table: JujutsuLogTable, point: Point) {
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

    /** A long linear chain, plus one non-adjacent edge from row 0 straight down to the last row -
     * both ends never fit inside a 400px-tall unscrolled table, so it's always "long". */
    private fun chainWithLongEdge(n: Int = 40): List<LogEntry> {
        val middle = (1 until n - 1).map { i -> entry("e$i", listOf("e${i + 1}")) }
        val last = entry("e${n - 1}")
        val first = entry("e0", listOf("e1", "e${n - 1}")) // merge: adjacent e1 + distant e(n-1)
        return listOf(first) + middle + listOf(last)
    }

    @Test
    fun `hovering a long edge whose child is visible shows the directional down cursor`() {
        val entries = chainWithLongEdge()
        val table = tableWith(entries)
        val node = table.graphNodes.getValue(ChangeKey(repo, ChangeId("e0", "e0", null)))
        val longLane = node.passthroughLanes.getValue(ChangeKey(repo, ChangeId("e${entries.size - 1}", null, null)))

        moveMouseTo(table, edgePoint(table, 0, longLane))

        // Row 0 (e0, the child) is visible - direction always points away from it, down.
        table.cursor shouldBe Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
        table.hoveredEdge.shouldNotBeNull()
        table.hoveredEdge!!.direction shouldBe EdgeDirection.DOWN
        table.hoveredEdge!!.navigable shouldBe true
    }

    @Test
    fun `clicking a long edge navigates to its target`() {
        val entries = chainWithLongEdge()
        val table = tableWith(entries)
        val node = table.graphNodes.getValue(ChangeKey(repo, ChangeId("e0", "e0", null)))
        val targetKey = ChangeKey(repo, ChangeId("e${entries.size - 1}", null, null))
        val longLane = node.passthroughLanes.getValue(targetKey)
        val point = edgePoint(table, 0, longLane)
        moveMouseTo(table, point)

        clickAt(table, point)

        table.selectedEntries.map { it.key } shouldBe listOf(targetKey)
    }

    @Test
    fun `clicking a not-loaded parent stub triggers expansion instead of a no-op`() {
        val entries = listOf(entry("a", listOf("ghost")))
        val table = tableWith(entries)
        var requestedExpansion: ChangeKey? = null
        table.onSelectionExpansionNeeded = { requestedExpansion = it }
        val point = edgePoint(table, 0, 0)
        moveMouseTo(table, point)
        table.hoveredEdge!!.navigable shouldBe true

        clickAt(table, point)

        requestedExpansion shouldBe ChangeKey(repo, ChangeId("ghost", "ghost", null))
    }

    @Test
    fun `when both ends are off-screen, direction pivots once at the viewport's middle row`() {
        // e0's long edge to e59 (chainWithLongEdge) passes through every row via a passthrough
        // lane. Viewed through a real scrolled JScrollPane (an unparented table's visibleRect is
        // just its own bounds, always starting at row 0 - not enough to put a *child* off-screen,
        // only a parent), scroll so both e0 (child) and e59 (parent) are off-screen in opposite
        // directions - the row nearest the top of the viewport should point up (towards e0), the
        // row nearest the bottom should point down (towards e59), one stable transition point
        // rather than a per-row/per-pixel split (jj-idea-sc8m round 3).
        val entries = chainWithLongEdge(n = 60)
        val table = tableWith(entries)
        // Wrapping in a real scroll pane requires the table to already have real column widths
        // (from tableWith's own setSize/doLayout) before it's added as the view, and the
        // viewport's own ViewportLayout pass (not just the outer scroll pane's) to size the table
        // to its full natural height - otherwise the view's preferred size resolves to 0x0 and
        // visibleRect stays empty regardless of the requested scroll position.
        val scrollPane = ScrollPaneFactory.createScrollPane(table)
        scrollPane.setSize(2000, 400)
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        val rowHeight = table.getCellRect(0, 0, true).height
        scrollPane.viewport.viewPosition = Point(0, 30 * rowHeight)

        val childKey = ChangeKey(repo, ChangeId("e0", "e0", null))
        val parentKey = ChangeKey(repo, ChangeId("e${entries.size - 1}", null, null))
        val longLane = table.graphNodes.getValue(childKey).passthroughLanes.getValue(parentKey)
        val topVisibleRow = table.rowAtPoint(Point(0, table.visibleRect.y))
        val bottomVisibleRow = table.rowAtPoint(Point(0, table.visibleRect.y + table.visibleRect.height - 1))

        moveMouseTo(table, edgePoint(table, topVisibleRow, longLane))
        table.hoveredEdge.shouldNotBeNull()
        table.hoveredEdge!!.direction shouldBe EdgeDirection.UP
        table.hoveredEdge!!.targetKey shouldBe childKey
        table.cursor shouldBe Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)

        moveMouseTo(table, edgePoint(table, bottomVisibleRow, longLane))
        table.hoveredEdge.shouldNotBeNull()
        table.hoveredEdge!!.direction shouldBe EdgeDirection.DOWN
        table.hoveredEdge!!.targetKey shouldBe parentKey
        table.cursor shouldBe Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
    }

    @Test
    fun `hovering plain graph space with no long edge leaves the default cursor`() {
        val entries = listOf(entry("a", listOf("b")), entry("b"))
        val table = tableWith(entries)

        moveMouseTo(table, edgePoint(table, 0, 0))

        table.cursor shouldBe Cursor.getDefaultCursor()
        table.hoveredEdge.shouldBeNull()
    }
}
