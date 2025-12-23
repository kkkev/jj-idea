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
class JujutsuLogTable(
    val columnManager: JujutsuColumnManager = JujutsuColumnManager.DEFAULT
) : JBTable(JujutsuLogTableModel()) {

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

        // Enable hover effect - repaint on mouse movement
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                repaint()
            }
        })
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
     * Update graph nodes and refresh graph+description column.
     * Called after data is loaded.
     */
    fun updateGraph(nodes: Map<ChangeId, GraphNode>) {
        graphNodes = nodes
        // Refresh combined graph+description column rendering with column manager
        if (columnModel.columnCount > 0) {
            columnModel.getColumn(JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION).cellRenderer =
                JujutsuGraphAndDescriptionRenderer(graphNodes, columnManager)
        }
        repaint()
    }
}

/**
 * Table model for Jujutsu commit log.
 *
 * Columns:
 * 0. Graph+Description - Combined column (optional elements controlled by column manager)
 * 1. Change ID - Separate column (optional)
 * 2. Description - Separate column (optional)
 * 3. Decorations - Separate column for bookmarks/tags (optional)
 * 4. Author - Author name
 * 5. Date - Commit timestamp
 */
class JujutsuLogTableModel : AbstractTableModel() {

    private val entries = mutableListOf<LogEntry>()

    companion object {
        const val COLUMN_GRAPH_AND_DESCRIPTION = 0
        const val COLUMN_CHANGE_ID = 1
        const val COLUMN_DESCRIPTION = 2
        const val COLUMN_DECORATIONS = 3
        const val COLUMN_AUTHOR = 4
        const val COLUMN_DATE = 5

        private val COLUMN_NAMES = arrayOf(
            "", // No column headings - matches Git plugin
            "",
            "",
            "",
            "",
            ""
        )
    }

    override fun getRowCount() = entries.size

    override fun getColumnCount() = COLUMN_NAMES.size

    override fun getColumnName(column: Int) = COLUMN_NAMES[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex < 0 || rowIndex >= entries.size) return null

        val entry = entries[rowIndex]

        return when (columnIndex) {
            COLUMN_GRAPH_AND_DESCRIPTION -> entry // Return full entry for combined renderer
            COLUMN_CHANGE_ID -> entry.changeId
            COLUMN_DESCRIPTION -> entry.description
            COLUMN_DECORATIONS -> entry.bookmarks
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
