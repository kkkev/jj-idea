package `in`.kkkev.jjidea.ui.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Tests the testable core of [JujutsuLogTable.processMouseMotionEvent]'s drag-selection fix
 * (jj-idea-6jvh follow-up) directly against a plain [JTable]'s selection model - not via a
 * dispatched `MOUSE_DRAGGED` event, since that goes through `BasicTableUI.Handler.mouseDragged`,
 * which unconditionally calls `BasicGraphicsUtils.isMenuShortcutKeyDown` and throws
 * `HeadlessException` in this project's platform test environment.
 */
class RestoreSelectionIfChangedTest {
    private fun tableWithRows(count: Int): JTable {
        val model = DefaultTableModel(count, 1)
        val table = JTable(model)
        table.selectionModel.selectionMode = javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        return table
    }

    @Test
    fun `does nothing when the selection already matches the snapshot`() {
        val table = tableWithRows(5)
        table.setRowSelectionInterval(2, 2)

        restoreSelectionIfChanged(table, listOf(2))

        table.selectedRows.toList() shouldBe listOf(2)
    }

    @Test
    fun `restores a single-row selection that drifted to a different row`() {
        val table = tableWithRows(5)
        table.setRowSelectionInterval(1, 1)
        // Simulate BasicTableUI's own drag-to-select fallback moving selection to row 3.
        table.setRowSelectionInterval(3, 3)

        restoreSelectionIfChanged(table, listOf(1))

        table.selectedRows.toList() shouldBe listOf(1)
    }

    @Test
    fun `restores a multi-row selection collapsed by a drag back to its original rows`() {
        val table = tableWithRows(6)
        table.setRowSelectionInterval(0, 0)
        table.addRowSelectionInterval(2, 2)
        table.addRowSelectionInterval(4, 4)
        val snapshot = table.selectedRows.toList()
        // Drag-to-select collapses a multi-selection down to a single row under the pointer.
        table.setRowSelectionInterval(5, 5)

        restoreSelectionIfChanged(table, snapshot)

        table.selectedRows.toList() shouldBe snapshot
    }

    @Test
    fun `restores an empty snapshot by clearing a selection that appeared mid-drag`() {
        val table = tableWithRows(4)

        table.setRowSelectionInterval(1, 1)
        restoreSelectionIfChanged(table, emptyList())

        table.selectedRows.toList() shouldBe emptyList()
    }
}
