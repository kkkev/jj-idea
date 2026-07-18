package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.awt.Rectangle

/**
 * Tests for column functionality in JujutsuLogTable.
 *
 * ## Column Visibility
 * - Thoroughly tested in JujutsuColumnManagerTest.kt
 * - Tests cover enabling/disabling individual columns
 * - Tests cover getVisibleColumns() logic
 *
 * ## Column Resizing
 * - Implementation: JujutsuLogTable.saveColumnWidths() and loadColumnWidths()
 * - Column widths persisted to JujutsuSettings.columnWidths (string-keyed)
 * - Resizing enabled via tableHeader.resizingAllowed = true
 * - Manual testing required (needs Swing environment)
 *
 * ## Column Sorting
 * - Implementation: JujutsuLogTable.autoCreateRowSorter = true
 * - Uses standard JTable row sorter with automatic comparators
 * - All columns sortable by default (click column header to sort)
 * - Selected entry handling updated to convert view row to model row
 * - Manual testing required (needs Swing environment and user interaction)
 *
 * This test file covers table model functionality that can be tested in headless mode.
 */
class JujutsuLogTableColumnTest {
    @Test
    fun `table model provides correct row count`() {
        val model = JujutsuLogTableModel()

        model.rowCount shouldBe 0

        model.setEntries(listOf(createTestEntry("abc123")))
        model.rowCount shouldBe 1

        model.setEntries(
            listOf(
                createTestEntry("abc123"),
                createTestEntry("def456")
            )
        )
        model.rowCount shouldBe 2
    }

    @Test
    fun `table model provides correct column count`() {
        val model = JujutsuLogTableModel()

        // Always 5 columns in the model (visibility controlled separately)
        // Columns: Root Gutter, Graph+Desc, Author, Committer, Date
        model.columnCount shouldBe 5
    }

    @Test
    fun `table model returns correct values for graph column`() {
        val model = JujutsuLogTableModel()
        val entry = createTestEntry("abc123")
        model.setEntries(listOf(entry))

        val value = model.getValueAt(0, JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION)
        value shouldBe entry
    }

    @Test
    fun `table model returns correct values for author column`() {
        val model = JujutsuLogTableModel()
        val author = VcsUserImpl("Test User", "test@example.com")
        val entry = createTestEntry("abc123", author = author)
        model.setEntries(listOf(entry))

        val value = model.getValueAt(0, JujutsuLogTableModel.COLUMN_AUTHOR)
        value shouldBe author
    }

    @Test
    fun `table model returns correct values for date column`() {
        val model = JujutsuLogTableModel()
        val timestamp = Instant.fromEpochMilliseconds(1000000000L)
        val entry = createTestEntry("abc123", timestamp = timestamp)
        model.setEntries(listOf(entry))

        val value = model.getValueAt(0, JujutsuLogTableModel.COLUMN_DATE)
        value shouldBe timestamp
    }

    // Note: Sorting tests are not included as they require Swing components
    // which don't work well in headless test environment.
    // Column sorting is implemented using standard JTable.setAutoCreateRowSorter()
    // and is tested manually during development.

    @Test
    fun `table model getEntry returns correct entry at row`() {
        val model = JujutsuLogTableModel()
        val entry1 = createTestEntry("abc123")
        val entry2 = createTestEntry("def456")
        model.setEntries(listOf(entry1, entry2))

        model.getEntry(0) shouldBe entry1
        model.getEntry(1) shouldBe entry2
        model.getEntry(2) shouldBe null
        model.getEntry(-1) shouldBe null
    }

    @Test
    fun `table model clear removes all entries`() {
        val model = JujutsuLogTableModel()
        model.setEntries(
            listOf(
                createTestEntry("abc123"),
                createTestEntry("def456")
            )
        )

        model.rowCount shouldBe 2

        model.clear()
        model.rowCount shouldBe 0
    }

    @Test
    fun `column manager controls column visibility correctly`() {
        val manager = JujutsuColumnManager()

        // Graph is always visible
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) shouldBe true

        // Toggle author column
        manager.showAuthorColumn shouldBe true
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_AUTHOR) shouldBe true

        manager.showAuthorColumn = false
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_AUTHOR) shouldBe false

        // Toggle date column
        manager.showDateColumn shouldBe true
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_DATE) shouldBe true

        manager.showDateColumn = false
        manager.isColumnVisible(JujutsuLogTableModel.COLUMN_DATE) shouldBe false
    }

    @Test
    fun `column manager getVisibleColumns returns only visible column indices`() {
        val manager = JujutsuColumnManager()

        // Default: graph, author, date
        var visible = manager.getVisibleColumns()
        visible shouldContainExactly
            listOf(
                JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION,
                JujutsuLogTableModel.COLUMN_AUTHOR,
                JujutsuLogTableModel.COLUMN_DATE
            )

        // Hide author
        manager.showAuthorColumn = false
        visible = manager.getVisibleColumns()
        visible shouldContainExactly
            listOf(
                JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION,
                JujutsuLogTableModel.COLUMN_DATE
            )

        // Show committer column
        manager.showCommitterColumn = true
        visible = manager.getVisibleColumns()
        visible shouldContainExactly
            listOf(
                JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION,
                JujutsuLogTableModel.COLUMN_COMMITTER,
                JujutsuLogTableModel.COLUMN_DATE
            )
    }

    @Test
    fun `table column names are empty strings`() {
        val model = JujutsuLogTableModel()

        // All columns should have empty names to match Git plugin style
        for (i in 0 until model.columnCount) {
            model.getColumnName(i) shouldBe ""
        }
    }

    @Test
    fun `table model handles null author gracefully`() {
        val model = JujutsuLogTableModel()
        val entry = createTestEntry("abc123", author = null)
        model.setEntries(listOf(entry))

        val value = model.getValueAt(0, JujutsuLogTableModel.COLUMN_AUTHOR)
        value shouldBe null
    }

    @Test
    fun `table model handles null timestamp gracefully`() {
        val model = JujutsuLogTableModel()
        val entry = createTestEntry("abc123", timestamp = null)
        model.setEntries(listOf(entry))

        val value = model.getValueAt(0, JujutsuLogTableModel.COLUMN_DATE)
        value shouldBe null
    }

    @Test
    fun `rowRectPreservingHorizontalScroll keeps horizontal viewport when scrolled right`() {
        val currentVisible = Rectangle(300, 0, 200, 400)
        val rowRect = Rectangle(0, 180, 120, 20)

        val result = rowRectPreservingHorizontalScroll(rowRect, currentVisible)

        result shouldBe Rectangle(300, 180, 200, 20)
    }

    @Test
    fun `rowRectPreservingHorizontalScroll is a no-op when not scrolled horizontally`() {
        val currentVisible = Rectangle(0, 0, 200, 400)
        val rowRect = Rectangle(0, 180, 120, 20)

        val result = rowRectPreservingHorizontalScroll(rowRect, currentVisible)

        result shouldBe Rectangle(0, 180, 200, 20)
    }

    // jj-idea-lzq7: responsive column sizing. fixed columns below model author(100)/committer(100)/date(120).

    @Test
    fun `fitColumnWidths gives all leftover space to description on a wide viewport`() {
        val fixed = listOf(FixedColumn(desired = 100, min = 55), FixedColumn(desired = 120, min = 60))

        val layout = fitColumnWidths(available = 1000, descMin = 180, fixed = fixed)

        layout.desc shouldBe 1000 - 100 - 120
        layout.fixed shouldBe listOf(100, 120)
    }

    @Test
    fun `fitColumnWidths gives description exactly its floor when space is an exact fit`() {
        val fixed = listOf(FixedColumn(desired = 100, min = 55), FixedColumn(desired = 120, min = 60))

        val layout = fitColumnWidths(available = 180 + 100 + 120, descMin = 180, fixed = fixed)

        layout.desc shouldBe 180
        layout.fixed shouldBe listOf(100, 120)
    }

    @Test
    fun `fitColumnWidths shrinks fixed columns proportionally toward their minimums when narrow`() {
        // descMin=180, fixed desired 100+120=220, total desired = 400. Available = 350, so
        // fixed columns must give back 50px total; author has 45 shrinkable (100-55), date has
        // 60 shrinkable (120-60), total shrinkable = 105 - both have room, so both shrink.
        val fixed = listOf(FixedColumn(desired = 100, min = 55), FixedColumn(desired = 120, min = 60))

        val layout = fitColumnWidths(available = 350, descMin = 180, fixed = fixed)

        layout.desc shouldBe 180
        // author: 100 - 50*45/105 = 100 - 21 = 79 (integer division truncates); date: 120 - 50*60/105 = 120 - 28 = 92
        layout.fixed shouldBe listOf(79, 92)
        // Total lands within a few px of available - integer-division truncation on each fixed
        // column's share means the reclaimed total can undershoot the exact shortfall slightly.
        (layout.desc + layout.fixed.sum()) shouldBe 351
    }

    @Test
    fun `fitColumnWidths floors everything and allows overflow when even minimums do not fit`() {
        val fixed = listOf(FixedColumn(desired = 100, min = 55), FixedColumn(desired = 120, min = 60))

        // Available is less than descMin + sum(min) = 180 + 55 + 60 = 295: no room left to shrink.
        val layout = fitColumnWidths(available = 200, descMin = 180, fixed = fixed)

        layout.desc shouldBe 180
        layout.fixed shouldBe listOf(55, 60)
        // Total exceeds available - this is the accepted horizontal-scroll fallback.
        (layout.desc + layout.fixed.sum()) shouldBe 295
    }

    @Test
    fun `fitColumnWidths with no fixed columns gives everything to description`() {
        val layout = fitColumnWidths(available = 500, descMin = 180, fixed = emptyList())

        layout.desc shouldBe 500
        layout.fixed shouldBe emptyList()
    }

    // Helper function to create test log entries
    private fun createTestEntry(
        changeId: String,
        description: String = "Test commit",
        author: com.intellij.vcs.log.VcsUser? = VcsUserImpl("Test User", "test@example.com"),
        timestamp: Instant? = Instant.fromEpochMilliseconds(1000000000L),
        hasConflict: Boolean = false,
        isEmpty: Boolean = false
    ) = LogEntry(
        repo = mockk<JujutsuRepository>(),
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = description,
        bookmarks = emptyList(),
        parentIdentifiers = emptyList(),
        isWorkingCopy = false,
        hasConflict = hasConflict,
        isEmpty = isEmpty,
        authorTimestamp = timestamp,
        committerTimestamp = null,
        author = author,
        committer = null
    )
}
