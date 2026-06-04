package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.IdeTooltip
import com.intellij.ide.IdeTooltipManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.components.IconAwareHtmlPane
import `in`.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.resolveBookmarkClick
import kotlinx.datetime.Instant
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.event.*
import javax.swing.JComponent
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
) : JBTable(JujutsuLogTableModel()), Disposable, UiDataProvider {
    private val log = Logger.getInstance(javaClass)

    // Graph nodes for rendering (populated when data is loaded)
    var graphNodes: Map<ChangeId, GraphNode> = emptyMap()
        private set

    // Currently hovered row for targeted repaint
    var hoveredRow: Int = -1
        private set

    // Tooltip cell tracking — hideCurrentNow() on cell change forces IdeTooltipManager
    // to call beforeShow() again with fresh content for the new cell
    private var tooltipRow = -1
    private var tooltipCol = -1

    private val customTooltip = object : IdeTooltip(this, Point(0, 0), null) {
        override fun beforeShow(): Boolean {
            val mousePos = this@JujutsuLogTable.mousePosition ?: return false
            val row = rowAtPoint(mousePos)
            val col = columnAtPoint(mousePos)
            if (row < 0 || col < 0) return false

            val renderer = getCellRenderer(row, col)
            val component = prepareRenderer(renderer, row, col) as? JComponent ?: return false
            val text = component.toolTipText
            if (text.isNullOrBlank()) return false

            val pane = IconAwareHtmlPane(project)
            pane.foreground = UIUtil.getToolTipForeground()
            pane.text = text

            point = mousePos
            tipComponent = Wrapper(pane)
            return true
        }

        override fun canBeDismissedOnTimeout() = false
    }

    // Root gutter state: true = expanded (shows repo name), false = collapsed (just colored strip)
    var isRootGutterExpanded: Boolean = false
        private set

    // Callback when gutter expansion changes (for column width adjustment)
    var onGutterExpansionChanged: (() -> Unit)? = null

    /**
     * Width of the gutter strip when collapsed.
     */
    val gutterCollapsedWidth: Int get() = JBUI.scale(8)

    /**
     * Width of the gutter when expanded (includes repo name).
     * Calculated based on longest repo name.
     */
    val gutterExpandedWidth: Int
        get() {
            val fm = getFontMetrics(font)
            val maxWidth = logModel.getAllRoots().maxOfOrNull { fm.stringWidth(it.displayName) } ?: 0
            return maxWidth + 16 // padding
        }

    @Volatile
    private var pendingSelection: ChangeKey? = null

    fun requestSelection(changeKey: ChangeKey) {
        // Try immediate selection from current model data (e.g., annotation click with no pending refresh).
        // Store as pending regardless so setEntries() can apply it if a refresh is in flight.
        pendingSelection = changeKey
        if (!selectEntry(changeKey.repo, changeKey.revision)) {
            log.info("requestSelection: entry not in current model, will apply on next setEntries()")
        }
    }

    /**
     * Toggle the root gutter expansion state.
     */
    fun toggleRootGutter() {
        isRootGutterExpanded = !isRootGutterExpanded
        updateGutterColumnWidth()
        onGutterExpansionChanged?.invoke()
    }

    /**
     * Update the gutter column width based on current expansion state.
     */
    private fun updateGutterColumnWidth() {
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            if (column.modelIndex == JujutsuLogTableModel.COLUMN_ROOT_GUTTER) {
                val newWidth = if (isRootGutterExpanded) gutterExpandedWidth else gutterCollapsedWidth
                column.minWidth = newWidth
                column.maxWidth = newWidth
                column.preferredWidth = newWidth
                column.width = newWidth
                break
            }
        }
        // Force table to recalculate layout
        revalidate()
        repaint()
    }

    init {
        // Single selection mode for now
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

        // Enable column reordering and resizing
        tableHeader.reorderingAllowed = true
        tableHeader.resizingAllowed = true

        // Ensure header is visible even with empty column names
        tableHeader.preferredSize = java.awt.Dimension(tableHeader.preferredSize.width, 24)

        // Disable auto-resize to allow manual column sizing
        autoResizeMode = AUTO_RESIZE_OFF

        // Row height for better readability
        rowHeight = 22

        // Striped rows for better readability
        setStriped(true)

        // Disable expandable items — we handle text truncation via TruncatingLeftRightLayout.
        // Without this, JBTable's ExpandableItemsHandler wraps our renderer in
        // ExpandedItemRendererComponentWrapper when getPreferredSize() exceeds column width,
        // causing graph+description to overpaint adjacent columns on hover.
        setExpandableItemsEnabled(false)

        // Enable hover effect and tooltip cell tracking
        addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val newRow = rowAtPoint(e.point)
                    if (newRow != hoveredRow) {
                        val oldRow = hoveredRow
                        hoveredRow = newRow
                        if (oldRow >= 0) repaintRow(oldRow)
                        if (newRow >= 0) repaintRow(newRow)
                    }
                    // Force tooltip re-evaluation when the cell changes
                    val newCol = columnAtPoint(e.point)
                    if (newRow != tooltipRow || newCol != tooltipCol) {
                        tooltipRow = newRow
                        tooltipCol = newCol
                        IdeTooltipManager.getInstance().hideCurrentNow(false)
                    }
                    // Show hand cursor when hovering over a bookmark chip
                    cursor = if (bookmarkClickAt(e) != null) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                }
            }
        )

        // Register custom tooltip that renders all tooltips via IconAwareHtmlPane.
        // This ensures <icon> tags render correctly and all tooltips have consistent styling.
        // The identity check (currentTooltip === tooltip) in IdeTooltipManager keeps the
        // tooltip stable during mouse movement within the same cell; hideCurrentNow() in
        // the MouseMotionListener above forces re-evaluation on cell changes.
        IdeTooltipManager.getInstance().setCustomTooltip(this, customTooltip)

        // Handle clicks on the gutter column to toggle expansion
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    // Check if click is on the gutter column
                    val viewColumn = columnAtPoint(e.point)
                    if (viewColumn >= 0) {
                        val modelColumn = convertColumnIndexToModel(viewColumn)
                        if (modelColumn == JujutsuLogTableModel.COLUMN_ROOT_GUTTER) {
                            toggleRootGutter()
                            e.consume()
                        }
                    }
                }
            }
        )

        // Add column model listener to persist column widths
        columnModel.addColumnModelListener(
            object : TableColumnModelListener {
                override fun columnMarginChanged(e: ChangeEvent) {
                    // Only persist user-initiated resizes — tableHeader.resizingColumn is null
                    // for programmatic changes (installRenderers, loadColumnWidths, etc.).
                    // Saving during init would clobber user-saved widths with defaults before
                    // they can be restored. Same idiom as VcsLogGraphTable.MyTableColumnModel.
                    if (tableHeader.resizingColumn == null) return
                    saveColumnWidths()
                }

                override fun columnAdded(e: TableColumnModelEvent) {}

                override fun columnRemoved(e: TableColumnModelEvent) {}

                override fun columnMoved(e: TableColumnModelEvent) {}

                override fun columnSelectionChanged(e: ListSelectionEvent) {}
            }
        )

        // Handle left-click on bookmark chips for navigation
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                    val click = bookmarkClickAt(e) ?: return
                    project.stateModel.changeSelection.notify(ChangeKey(click.repo, click.entry.id))
                    e.consume()
                }
            }
        )

        // Add context menu support (checks bookmark chips before falling through to row menu)
        addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(comp: Component, x: Int, y: Int) {
                    val syntheticEvent = MouseEvent(this@JujutsuLogTable, 0, 0, 0, x, y, 1, false)
                    val click = bookmarkClickAt(syntheticEvent)
                    if (click != null) {
                        val group = JujutsuLogContextMenuActions.createBookmarkActionGroup(project, click)
                        ActionManager.getInstance()
                            .createActionPopupMenu(ActionPlaces.UNKNOWN, group)
                            .component.show(comp, x, y)
                    } else {
                        showContextMenu(comp, x, y)
                    }
                }
            }
        )

        // Add component resize listener for dynamic column width adjustment
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    adjustDescriptionColumnWidth()
                }
            }
        )
    }

    /**
     * Return the [BookmarkClick] under [e], or null if the event is not over a bookmark chip.
     * Handles both the Decorations column (SCC-based) and the graph+description column (fragment canvas).
     */
    private fun bookmarkClickAt(e: MouseEvent): BookmarkClick? {
        val row = rowAtPoint(e.point).takeIf { it >= 0 } ?: return null
        val col = columnAtPoint(e.point).takeIf { it >= 0 } ?: return null
        val modelRow = convertRowIndexToModel(row)
        val entry = logModel.getEntry(modelRow) ?: return null
        val cellRect = getCellRect(row, col, false)
        val localX = e.x - cellRect.x
        val modelCol = convertColumnIndexToModel(col)
        val uri = when (modelCol) {
            JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION -> {
                val frc = getFontMetrics(font).fontRenderContext
                findInlinedBookmarkUri(entry, localX, cellRect.width, font, frc, columnManager.showDecorations)
            }
            else -> null
        } ?: return null
        return resolveBookmarkClick(uri, entry)
    }

    /**
     * Show context menu at the given location.
     * Called when user right-clicks on the table.
     */
    private fun showContextMenu(component: Component, x: Int, y: Int) {
        val actionGroup = JujutsuLogContextMenuActions.createActionGroup(project, selectedEntries)
        val popupMenu = ActionManager.getInstance().createActionPopupMenu("Jujutsu.LogTable", actionGroup)
        popupMenu.setTargetComponent(this)
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

    fun setEntries(entries: List<LogEntry>) {
        // Capture current selection for re-selection after model update,
        // but only if no explicit selection was requested (e.g., via changeSelection after edit/abandon).
        if (pendingSelection == null) {
            selectedEntry?.let {
                pendingSelection = ChangeKey(it.repo, it.id)
            }
        }
        logModel.setEntries(entries)
        pendingSelection?.let {
            if (selectEntry(it.repo, it.revision)) {
                pendingSelection = null
            }
        }
    }

    /**
     * Select an entry in the table by repo and revision, scrolling it into view.
     * Matches by repo to ensure correct selection in multi-root.
     *
     * @param repo The repository containing the entry
     * @param revision The revision to select ([ChangeId] or [WorkingCopy])
     */
    private fun selectEntry(repo: JujutsuRepository, revision: Revision): Boolean {
        val rowIndex = when (revision) {
            is ChangeId -> (0 until logModel.rowCount).firstOrNull { row ->
                val entry = logModel.getEntry(row)
                entry?.repo == repo && entry.id == revision
            }

            WorkingCopy -> (0 until logModel.rowCount).firstOrNull { row ->
                val entry = logModel.getEntry(row)
                entry?.repo == repo && entry.isWorkingCopy
            }

            else -> {
                log.warn("Unsupported revision type for selection: $revision")
                null
            }
        } ?: return false

        setRowSelectionInterval(rowIndex, rowIndex)
        scrollRectToVisible(getCellRect(rowIndex, 0, true))
        log.info("Selected entry at row $rowIndex ($repo:$revision)")
        return true
    }

    /**
     * Update graph nodes and refresh graph+description column.
     * Called after data is loaded.
     */
    private fun repaintRow(row: Int) {
        val rect = getCellRect(row, 0, true)
        rect.width = width
        repaint(rect)
    }

    fun updateGraph(nodes: Map<ChangeId, GraphNode>) {
        graphNodes = nodes
        // Refresh combined graph+description column rendering with column manager
        // Find the column by model index, not view index
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            if (column.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) {
                column.cellRenderer = JujutsuGraphAndDescriptionRenderer(graphNodes, columnManager)
                break
            }
        }
        repaint()
    }

    /**
     * Save current column widths to settings.
     */
    private fun saveColumnWidths() {
        val settings = JujutsuSettings.getInstance(project)
        val widths = settings.state.columnWidths.toMutableMap()
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            JujutsuLogTableModel.columnKey(column.modelIndex)?.let { key ->
                widths[key] = column.width
            }
        }
        settings.state.columnWidths = widths
    }

    /**
     * Load saved column widths from settings and apply them to columns.
     * Should be called after columns are set up.
     */
    fun loadColumnWidths() {
        val settings = JujutsuSettings.getInstance(project)
        val savedWidths = settings.state.columnWidths
        if (savedWidths.isEmpty()) return
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            val key = JujutsuLogTableModel.columnKey(column.modelIndex) ?: continue
            val savedWidth = savedWidths[key]
            if (savedWidth != null && savedWidth > 0) {
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
        if (columnModel.columnCount == 0) return
        val descColumn = (0 until columnModel.columnCount)
            .map { columnModel.getColumn(it) }
            .firstOrNull { it.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }
            ?: return
        val otherColumnsWidth = (0 until columnModel.columnCount)
            .map { columnModel.getColumn(it) }
            .filter { it != descColumn }
            .sumOf { it.width }
        val availableWidth = width - otherColumnsWidth
        if (availableWidth > descColumn.minWidth) {
            descColumn.preferredWidth = availableWidth
            descColumn.width = availableWidth
        }
    }

    override fun uiDataSnapshot(sink: DataSink) {
        selectedEntry?.let { sink[JujutsuDataKeys.LOG_ENTRY] = it }
    }

    override fun dispose() {
    }
}

/**
 * Table model for Jujutsu commit log.
 *
 * Columns:
 * 0. Root Gutter - Colored strip showing repository (only visible with multiple roots)
 * 1. Graph+Description - Combined column (status, change ID, description, decorations controlled by column manager)
 * 2. Author - Author name
 * 3. Committer - Committer name (optional)
 * 4. Date - Commit timestamp
 */
class JujutsuLogTableModel : AbstractTableModel() {
    private val entries = mutableListOf<LogEntry>()
    private val filteredEntries = mutableListOf<LogEntry>()
    private var filterText: String = ""
    private var useRegex: Boolean = false
    private var matchCase: Boolean = false
    private var matchWholeWords: Boolean = false
    private var authorFilter: Set<String> = emptySet() // Filter by author email
    private var bookmarkFilter: Set<ChangeId> =
        emptySet() // Filter by bookmark change IDs (includes ancestors)
    private var dateFilterCutoff: Instant? = null // Filter by date (show commits after cutoff)
    private var pathsFilter: Set<String> = emptySet() // Filter by paths
    private var rootFilter: Set<JujutsuRepository> = emptySet() // Filter by repository root

    companion object {
        const val COLUMN_ROOT_GUTTER = 0
        const val COLUMN_GRAPH_AND_DESCRIPTION = 1
        const val COLUMN_AUTHOR = 2
        const val COLUMN_COMMITTER = 3
        const val COLUMN_DATE = 4

        const val NUM_COLUMNS = 5

        const val KEY_ROOT_GUTTER = "rootGutter"
        const val KEY_GRAPH_AND_DESCRIPTION = "graph"
        const val KEY_AUTHOR = "author"
        const val KEY_COMMITTER = "committer"
        const val KEY_DATE = "date"

        fun columnKey(modelIndex: Int) = when (modelIndex) {
            COLUMN_ROOT_GUTTER -> KEY_ROOT_GUTTER
            COLUMN_GRAPH_AND_DESCRIPTION -> KEY_GRAPH_AND_DESCRIPTION
            COLUMN_AUTHOR -> KEY_AUTHOR
            COLUMN_COMMITTER -> KEY_COMMITTER
            COLUMN_DATE -> KEY_DATE
            else -> null
        }
    }

    override fun getRowCount() = filteredEntries.size

    override fun getColumnCount() = NUM_COLUMNS

    // No column headings - matches Git plugin
    override fun getColumnName(column: Int) = ""

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
        if (rowIndex < 0 || rowIndex >= filteredEntries.size) return null
        val entry = filteredEntries[rowIndex]
        return when (columnIndex) {
            COLUMN_ROOT_GUTTER -> entry
            COLUMN_GRAPH_AND_DESCRIPTION -> entry
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
    fun setBookmarkFilter(ids: Set<ChangeId>) {
        bookmarkFilter = ids
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
     * Set the root filter (by repository).
     * Empty set means no root filtering (show all roots).
     */
    fun setRootFilter(roots: Set<JujutsuRepository>) {
        rootFilter = roots
        applyFilter()
    }

    /**
     * Get all unique roots in the current entries (for filter UI).
     */
    fun getAllRoots(): List<JujutsuRepository> = entries.map { it.repo }.distinct()

    /**
     * Get all unique authors in the current entries (for filter UI).
     */
    fun getAllAuthors(): List<String> = entries.mapNotNull {
        it.author?.email?.takeIf(String::isNotBlank)
    }.distinct().sorted()

    /**
     * Get all unique bookmarks in the current entries (for filter UI).
     */
    fun getAllBookmarks(): List<String> =
        entries.flatMap { it.bookmarks.map { bookmark -> bookmark.name.name } }.distinct().sorted()

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
                val matchesText = textMatcher?.let { matcher ->
                    matcher(entry.description.summary) ||
                        matcher(entry.id.short) ||
                        matcher(entry.id.full) ||
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
                    bookmarkFilter.contains(entry.id)
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

                // Root filter (if active)
                val matchesRoot = rootFilter.isEmpty() || rootFilter.contains(entry.repo)

                matchesText && matchesAuthor && matchesBookmark && matchesDate && matchesPaths && matchesRoot
            }
        )

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
     * Clear all entries.
     */
    fun clear() {
        entries.clear()
        filteredEntries.clear()
        fireTableDataChanged()
    }
}
