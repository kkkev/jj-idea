package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.table.JBTable
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.settings.JujutsuSettings
import kotlinx.datetime.Instant
import java.awt.Component
import java.awt.event.ComponentAdapter
import javax.swing.ListSelectionModel
import javax.swing.event.ChangeEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener
import javax.swing.table.AbstractTableModel

/**
 * Custom table for displaying Jujutsu commit log.
 *
 * Built from scratch using JTable - no dependency on IntelliJ's VcsLogGraphTable.
 * This gives us complete control over rendering and behavior.
 */
class JujutsuLogTable(
    private val project: Project,
    val columnManager: JujutsuColumnManager = JujutsuColumnManager.DEFAULT
) : JBTable(JujutsuLogTableModel()) {
    // Graph nodes for rendering (populated when data is loaded)
    var graphNodes: Map<ChangeId, GraphNode> = emptyMap()
        private set

    init {
        // Single selection mode for now
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

        // Enable column reordering and resizing
        tableHeader.reorderingAllowed = true
        tableHeader.resizingAllowed = true

        // Enable column sorting
        autoCreateRowSorter = true

        // Ensure header is visible even with empty column names
        tableHeader.preferredSize = java.awt.Dimension(tableHeader.preferredSize.width, 24)

        // Disable auto-resize to allow manual column sizing
        autoResizeMode = AUTO_RESIZE_OFF

        // Row height for better readability
        rowHeight = 22

        // Striped rows for better readability
        setStriped(true)

        // Enable hover effect - repaint on mouse movement
        addMouseMotionListener(
            object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    repaint()
                }
            }
        )

        // Add column model listener to persist column widths
        columnModel.addColumnModelListener(
            object : TableColumnModelListener {
                override fun columnMarginChanged(e: ChangeEvent) {
                    saveColumnWidths()
                }

                override fun columnAdded(e: TableColumnModelEvent) {}

                override fun columnRemoved(e: TableColumnModelEvent) {}

                override fun columnMoved(e: TableColumnModelEvent) {}

                override fun columnSelectionChanged(e: ListSelectionEvent) {}
            }
        )

        // Add context menu support
        addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(
                    comp: Component,
                    x: Int,
                    y: Int
                ) {
                    showContextMenu(comp, x, y)
                }
            }
        )

        // Add component resize listener for dynamic column width adjustment
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent?) {
                    adjustDescriptionColumnWidth()
                }
            }
        )
    }

    /**
     * Show context menu at the given location.
     * Called when user right-clicks on the table.
     */
    private fun showContextMenu(
        component: Component,
        x: Int,
        y: Int
    ) {
        val actionGroup = JujutsuLogContextMenuActions.createActionGroup(project, selectedEntries)
        val popupMenu =
            ActionManager
                .getInstance()
                .createActionPopupMenu(ActionPlaces.UNKNOWN, actionGroup)
        popupMenu.component.show(component, x, y)
    }

    /**
     * Get the table model cast to our custom type.
     */
    val logModel: JujutsuLogTableModel
        get() = model as JujutsuLogTableModel

    /**
     * Get the currently selected log entry, if there is exactly one.
     * Handles row sorting by converting view index to model index.
     */
    val selectedEntry get() = selectedRows.singleOrNull()?.let(::convertRowIndexToModel)?.let(logModel::getEntry)

    /**
     * Get the currently selected log entries.
     * Handles row sorting by converting view index to model index.
     */
    val selectedEntries get() = selectedRows.map(::convertRowIndexToModel).mapNotNull(logModel::getEntry)

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

    /**
     * Save current column widths to settings.
     */
    private fun saveColumnWidths() {
        val settings = JujutsuSettings.getInstance(project)
        val widths = mutableMapOf<Int, Int>()

        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            widths[column.modelIndex] = column.width
        }

        settings.state.customLogColumnWidths = widths
    }

    /**
     * Load saved column widths from settings and apply them to columns.
     * Should be called after columns are set up.
     */
    fun loadColumnWidths() {
        val settings = JujutsuSettings.getInstance(project)
        val savedWidths = settings.state.customLogColumnWidths

        if (savedWidths.isEmpty()) {
            return // No saved widths, use defaults
        }

        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            val savedWidth = savedWidths[column.modelIndex]
            if (savedWidth != null && savedWidth > 0) {
                // Set both to override defaults but still allow resizing
                column.preferredWidth = savedWidth
                column.width = savedWidth
            }
        }
    }

    /**
     * Dynamically adjust description column to fill available width.
     * Runs on component resize.
     */
    private fun adjustDescriptionColumnWidth() {
        // Skip if no columns
        if (columnModel.columnCount == 0) return

        // Find the description column (either combined or separate)
        val descColumnIndex =
            if (columnManager.showDescriptionColumn) {
                // Using separate description column
                JujutsuLogTableModel.COLUMN_DESCRIPTION
            } else {
                // Using combined graph+description column
                JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION
            }

        // Find the actual column in the column model
        val descColumn =
            (0 until columnModel.columnCount)
                .map { columnModel.getColumn(it) }
                .firstOrNull { it.modelIndex == descColumnIndex }
                ?: return

        // Calculate total width of other columns
        val otherColumnsWidth =
            (0 until columnModel.columnCount)
                .map { columnModel.getColumn(it) }
                .filter { it != descColumn }
                .sumOf { it.width }

        // Calculate available width for description
        val availableWidth = width - otherColumnsWidth

        // Set description column width (respect minimum)
        if (availableWidth > descColumn.minWidth) {
            descColumn.preferredWidth = availableWidth
            descColumn.width = availableWidth
        }
    }
}

/**
 * Table model for Jujutsu commit log.
 *
 * Columns:
 * 0. Graph+Description - Combined column (optional elements controlled by column manager)
 * 1. Status - Conflict/empty indicators (optional)
 * 2. Change ID - Separate column (optional)
 * 3. Description - Separate column (optional)
 * 4. Decorations - Separate column for bookmarks/tags (optional)
 * 5. Author - Author name
 * 6. Committer - Committer name (optional)
 * 7. Date - Commit timestamp
 */
class JujutsuLogTableModel : AbstractTableModel() {
    private val entries = mutableListOf<LogEntry>()
    private val filteredEntries = mutableListOf<LogEntry>()
    private var filterText: String = ""
    private var useRegex: Boolean = false
    private var matchCase: Boolean = false
    private var matchWholeWords: Boolean = false
    private var authorFilter: Set<String> = emptySet() // Filter by author email
    private var bookmarkFilter: Set<ChangeId> = emptySet() // Filter by bookmark change IDs (includes ancestors)
    private var dateFilterCutoff: Instant? = null // Filter by date (show commits after cutoff)
    private var pathsFilter: Set<String> = emptySet() // Filter by paths

    companion object {
        const val COLUMN_GRAPH_AND_DESCRIPTION = 0
        const val COLUMN_STATUS = 1
        const val COLUMN_CHANGE_ID = 2
        const val COLUMN_DESCRIPTION = 3
        const val COLUMN_DECORATIONS = 4
        const val COLUMN_AUTHOR = 5
        const val COLUMN_COMMITTER = 6
        const val COLUMN_DATE = 7

        const val NUM_COLUMNS = 8
    }

    override fun getRowCount() = filteredEntries.size

    override fun getColumnCount() = NUM_COLUMNS

    // No column headings - matches Git plugin
    override fun getColumnName(column: Int) = ""

    override fun getValueAt(
        rowIndex: Int,
        columnIndex: Int
    ): Any? {
        if (rowIndex < 0 || rowIndex >= filteredEntries.size) return null

        val entry = filteredEntries[rowIndex]

        return when (columnIndex) {
            COLUMN_GRAPH_AND_DESCRIPTION -> entry // Return full entry for combined renderer
            COLUMN_STATUS -> if (entry.hasConflict || entry.isEmpty || entry.immutable) entry else null
            COLUMN_CHANGE_ID -> entry.changeId
            COLUMN_DESCRIPTION -> entry.description
            COLUMN_DECORATIONS -> entry // Return full entry to access both isWorkingCopy and bookmarks
            COLUMN_AUTHOR -> entry.author
            COLUMN_COMMITTER -> entry.committer ?: entry.author
            COLUMN_DATE -> entry.authorTimestamp ?: entry.committerTimestamp
            else -> null
        }
    }

    /**
     * Get the log entry at the given row.
     */
    fun getEntry(row: Int): LogEntry? = if (row in filteredEntries.indices) filteredEntries[row] else null

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
    fun setFilter(
        text: String,
        regex: Boolean = false,
        caseSensitive: Boolean = false,
        wholeWords: Boolean = false
    ) {
        filterText = text
        useRegex = regex
        matchCase = caseSensitive
        matchWholeWords = wholeWords
        applyFilter()
    }

    /**
     * Set the author filter (by author email).
     * Empty set means no author filtering.
     */
    fun setAuthorFilter(authors: Set<String>) {
        authorFilter = authors
        applyFilter()
    }

    /**
     * Set the bookmark filter (by change IDs that should be included).
     * Empty set means no bookmark filtering.
     * The set should include all ancestors of the selected bookmark.
     */
    fun setBookmarkFilter(changeIds: Set<ChangeId>) {
        bookmarkFilter = changeIds
        applyFilter()
    }

    /**
     * Set the date filter (commits after the given instant).
     * Null means no date filtering.
     */
    fun setDateFilter(cutoff: Instant?) {
        dateFilterCutoff = cutoff
        applyFilter()
    }

    /**
     * Set the paths filter.
     * Empty set means no path filtering.
     */
    fun setPathsFilter(paths: Set<String>) {
        pathsFilter = paths
        applyFilter()
    }

    /**
     * Get all unique authors in the current entries (for filter UI).
     */
    fun getAllAuthors(): List<String> = entries.mapNotNull { it.author?.email }.distinct().sorted()

    /**
     * Get all unique bookmarks in the current entries (for filter UI).
     */
    fun getAllBookmarks(): List<String> =
        entries.flatMap { it.bookmarks.map { bookmark -> bookmark.name } }.distinct().sorted()

    /**
     * Get all entries (unfiltered) for computing ancestors.
     */
    fun getAllEntries(): List<LogEntry> = entries

    /**
     * Apply the current filter to the entries.
     */
    private fun applyFilter() {
        filteredEntries.clear()

        // Build text matcher if text filter is active
        val textMatcher: ((String) -> Boolean)? =
            if (filterText.isNotBlank()) {
                if (useRegex) {
                    // Regex mode
                    try {
                        val pattern =
                            if (matchCase) {
                                filterText.toRegex()
                            } else {
                                filterText.toRegex(RegexOption.IGNORE_CASE)
                            }
                        { text: String -> pattern.containsMatchIn(text) }
                    } catch (_: Exception) {
                        // Invalid regex - fall back to literal search
                        createLiteralMatcher(filterText, matchCase, matchWholeWords)
                    }
                } else {
                    // Literal search
                    createLiteralMatcher(filterText, matchCase, matchWholeWords)
                }
            } else {
                null
            }

        // Filter entries by all active filters
        filteredEntries.addAll(
            entries.filter { entry ->
                // Text filter (if active)
                val matchesText =
                    textMatcher?.let { matcher ->
                        matcher(entry.description.summary) ||
                            matcher(entry.changeId.toString()) ||
                            entry.author?.name?.let(matcher) == true ||
                            entry.author?.email?.let(matcher) == true
                    } ?: true

                // Author filter (if active)
                val matchesAuthor =
                    if (authorFilter.isNotEmpty()) {
                        entry.author?.email?.let { authorFilter.contains(it) } == true
                    } else {
                        true
                    }

                // Bookmark filter (if active) - filter by change ID
                val matchesBookmark =
                    if (bookmarkFilter.isNotEmpty()) {
                        bookmarkFilter.contains(entry.changeId)
                    } else {
                        true
                    }

                // Date filter (if active)
                val matchesDate =
                    dateFilterCutoff?.let { cutoff ->
                        val timestamp = entry.authorTimestamp ?: entry.committerTimestamp
                        timestamp != null && timestamp >= cutoff
                    } ?: true

                // Paths filter (if active) - placeholder for now
                // TODO: Implement path filtering once file changes are available in LogEntry
                val matchesPaths = pathsFilter.isEmpty()

                matchesText && matchesAuthor && matchesBookmark && matchesDate && matchesPaths
            }
        )

        fireTableDataChanged()
    }

    /**
     * Create a literal string matcher with case sensitivity and whole word options.
     */
    private fun createLiteralMatcher(
        filter: String,
        caseSensitive: Boolean,
        wholeWords: Boolean
    ): (String) -> Boolean {
        if (wholeWords) {
            // Match whole words only
            val pattern =
                if (caseSensitive) {
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
