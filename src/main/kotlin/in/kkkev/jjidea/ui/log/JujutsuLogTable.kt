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
    private val filteredEntries = mutableListOf<LogEntry>()
    private var filterText: String = ""
    private var useRegex: Boolean = false
    private var matchCase: Boolean = false
    private var matchWholeWords: Boolean = false

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

    override fun getRowCount() = filteredEntries.size

    override fun getColumnCount() = COLUMN_NAMES.size

    override fun getColumnName(column: Int) = COLUMN_NAMES[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex < 0 || rowIndex >= filteredEntries.size) return null

        val entry = filteredEntries[rowIndex]

        return when (columnIndex) {
            COLUMN_GRAPH_AND_DESCRIPTION -> entry // Return full entry for combined renderer
            COLUMN_CHANGE_ID -> entry.changeId
            COLUMN_DESCRIPTION -> entry.description
            COLUMN_DECORATIONS -> entry // Return full entry to access both isWorkingCopy and bookmarks
            COLUMN_AUTHOR -> entry.author
            COLUMN_DATE -> entry.authorTimestamp ?: entry.committerTimestamp
            else -> null
        }
    }

    /**
     * Get the log entry at the given row.
     */
    fun getEntry(row: Int): LogEntry? =
        if (row in filteredEntries.indices) filteredEntries[row] else null

    /**
     * Update the table with new log entries.
     * Called on EDT after background loading.
     */
    fun setEntries(newEntries: List<LogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        applyFilter()
    }

    /**
     * Set the filter text and options, then update the filtered entries.
     */
    fun setFilter(text: String, regex: Boolean = false, caseSensitive: Boolean = false, wholeWords: Boolean = false) {
        filterText = text
        useRegex = regex
        matchCase = caseSensitive
        matchWholeWords = wholeWords
        applyFilter()
    }

    /**
     * Apply the current filter to the entries.
     */
    private fun applyFilter() {
        filteredEntries.clear()

        if (filterText.isBlank()) {
            // No filter - show all entries
            filteredEntries.addAll(entries)
        } else {
            // Build the matcher function based on options
            val matcher: (String) -> Boolean = if (useRegex) {
                // Regex mode
                try {
                    val pattern = if (matchCase) {
                        filterText.toRegex()
                    } else {
                        filterText.toRegex(RegexOption.IGNORE_CASE)
                    }
                    { text: String -> pattern.containsMatchIn(text) }
                } catch (e: Exception) {
                    // Invalid regex - fall back to literal search
                    createLiteralMatcher(filterText, matchCase, matchWholeWords)
                }
            } else {
                // Literal search
                createLiteralMatcher(filterText, matchCase, matchWholeWords)
            }

            // Filter entries
            filteredEntries.addAll(entries.filter { entry ->
                matcher(entry.description.summary) ||
                matcher(entry.changeId.toString()) ||
                entry.author?.name?.let(matcher) == true ||
                entry.author?.email?.let(matcher) == true
            })
        }

        fireTableDataChanged()
    }

    /**
     * Create a literal string matcher with case sensitivity and whole word options.
     */
    private fun createLiteralMatcher(filter: String, caseSensitive: Boolean, wholeWords: Boolean): (String) -> Boolean {
        if (wholeWords) {
            // Match whole words only
            val pattern = if (caseSensitive) {
                "\\b${Regex.escape(filter)}\\b".toRegex()
            } else {
                "\\b${Regex.escape(filter)}\\b".toRegex(RegexOption.IGNORE_CASE)
            }
            return { text: String -> pattern.containsMatchIn(text) }
        }

        // Simple substring match
        if (caseSensitive) {
            return { text: String -> text.contains(filter) }
        }

        val lowerFilter = filter.lowercase()
        return { text: String -> text.lowercase().contains(lowerFilter) }
    }

    /**
     * Append more entries (for pagination/incremental loading).
     */
    fun appendEntries(moreEntries: List<LogEntry>) {
        entries.addAll(moreEntries)
        applyFilter()
    }

    /**
     * Clear all entries.
     */
    fun clear() {
        entries.clear()
        filteredEntries.clear()
        fireTableDataChanged()
    }
}
