package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.table.JBTable
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import kotlinx.datetime.Instant
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
    private var authorFilter: Set<String> = emptySet() // Filter by author email
    private var bookmarkFilter: Set<ChangeId> = emptySet() // Filter by bookmark change IDs (includes ancestors)
    private var dateFilterCutoff: Instant? = null // Filter by date (show commits after cutoff)
    private var pathsFilter: Set<String> = emptySet() // Filter by paths

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
    fun getAllAuthors(): List<String> =
        entries.mapNotNull { it.author?.email }.distinct().sorted()

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
        val textMatcher: ((String) -> Boolean)? = if (filterText.isNotBlank()) {
            if (useRegex) {
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
        } else {
            null
        }

        // Filter entries by all active filters
        filteredEntries.addAll(entries.filter { entry ->
            // Text filter (if active)
            val matchesText = textMatcher?.let { matcher ->
                matcher(entry.description.summary) ||
                matcher(entry.changeId.toString()) ||
                entry.author?.name?.let(matcher) == true ||
                entry.author?.email?.let(matcher) == true
            } ?: true

            // Author filter (if active)
            val matchesAuthor = if (authorFilter.isNotEmpty()) {
                entry.author?.email?.let { authorFilter.contains(it) } == true
            } else {
                true
            }

            // Bookmark filter (if active) - filter by change ID
            val matchesBookmark = if (bookmarkFilter.isNotEmpty()) {
                bookmarkFilter.contains(entry.changeId)
            } else {
                true
            }

            // Date filter (if active)
            val matchesDate = dateFilterCutoff?.let { cutoff ->
                val timestamp = entry.authorTimestamp ?: entry.committerTimestamp
                timestamp != null && timestamp >= cutoff
            } ?: true

            // Paths filter (if active) - placeholder for now
            // TODO: Implement path filtering once file changes are available in LogEntry
            val matchesPaths = pathsFilter.isEmpty()

            matchesText && matchesAuthor && matchesBookmark && matchesDate && matchesPaths
        })

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
