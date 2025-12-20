package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.table.JBTable
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * Custom table for displaying Jujutsu commit log.
 *
 * Built from scratch using JTable - no dependency on IntelliJ's VcsLogGraphTable.
 * This gives us complete control over rendering and behavior.
 */
class JujutsuLogTable : JBTable(JujutsuLogTableModel()) {

    // Graph nodes for rendering (populated when data is loaded)
    var graphNodes: Map<ChangeId, GraphNode> = emptyMap()
        private set

    init {
        // Single selection mode for now
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Enable column reordering
        tableHeader.reorderingAllowed = true

        // Auto-resize mode
        autoResizeMode = AUTO_RESIZE_SUBSEQUENT_COLUMNS

        // Row height for better readability
        rowHeight = 22

        // Striped rows for better readability
        setStriped(true)
    }

    /**
     * Get the table model cast to our custom type.
     */
    val logModel: JujutsuLogTableModel
        get() = model as JujutsuLogTableModel

    /**
     * Get the currently selected log entry, if any.
     */
    val selectedEntry: LogEntry?
        get() {
            val row = selectedRow
            return if (row >= 0) logModel.getEntry(row) else null
        }

    /**
     * Update graph nodes and refresh graph column.
     * Called after data is loaded.
     */
    fun updateGraph(nodes: Map<ChangeId, GraphNode>) {
        graphNodes = nodes
        // Refresh graph column rendering
        if (columnModel.columnCount > 0) {
            columnModel.getColumn(JujutsuLogTableModel.COLUMN_GRAPH).cellRenderer =
                JujutsuGraphCellRenderer(graphNodes)
        }
        repaint()
    }
}

/**
 * Table model for Jujutsu commit log.
 *
 * Columns:
 * 1. Graph - Visual commit graph (placeholder for Phase 2)
 * 2. Status - Conflict/empty indicators
 * 3. Change ID - Formatted change ID
 * 4. Description - Commit description with refs
 * 5. Author - Author name
 * 6. Date - Commit timestamp
 */
class JujutsuLogTableModel : AbstractTableModel() {

    private val entries = mutableListOf<LogEntry>()

    companion object {
        const val COLUMN_GRAPH = 0
        const val COLUMN_STATUS = 1
        const val COLUMN_CHANGE_ID = 2
        const val COLUMN_DESCRIPTION = 3
        const val COLUMN_AUTHOR = 4
        const val COLUMN_DATE = 5

        private val COLUMN_NAMES = arrayOf(
            "Graph",
            "Status",
            "Change ID",
            "Description",
            "Author",
            "Date"
        )
    }

    override fun getRowCount() = entries.size

    override fun getColumnCount() = COLUMN_NAMES.size

    override fun getColumnName(column: Int) = COLUMN_NAMES[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex < 0 || rowIndex >= entries.size) return null

        val entry = entries[rowIndex]

        return when (columnIndex) {
            COLUMN_GRAPH -> "" // Placeholder for graph rendering
            COLUMN_STATUS -> entry // Return full entry for status renderer
            COLUMN_CHANGE_ID -> entry.changeId
            COLUMN_DESCRIPTION -> entry // Return full entry for description renderer
            COLUMN_AUTHOR -> entry.author?.name ?: ""
            COLUMN_DATE -> entry.authorTimestamp ?: entry.committerTimestamp
            else -> null
        }
    }

    /**
     * Get the log entry at the given row.
     */
    fun getEntry(row: Int): LogEntry? =
        if (row in entries.indices) entries[row] else null

    /**
     * Update the table with new log entries.
     * Called on EDT after background loading.
     */
    fun setEntries(newEntries: List<LogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        fireTableDataChanged()
    }

    /**
     * Append more entries (for pagination/incremental loading).
     */
    fun appendEntries(moreEntries: List<LogEntry>) {
        val oldSize = entries.size
        entries.addAll(moreEntries)
        fireTableRowsInserted(oldSize, entries.size - 1)
    }

    /**
     * Clear all entries.
     */
    fun clear() {
        val oldSize = entries.size
        entries.clear()
        if (oldSize > 0) {
            fireTableRowsDeleted(0, oldSize - 1)
        }
    }
}
