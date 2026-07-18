package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.IdeTooltip
import com.intellij.ide.IdeTooltipManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScreenUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.Wrapper
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.components.IconAwareHtmlPane
import `in`.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.clickActionGroup
import kotlinx.datetime.Instant
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.*
import javax.swing.JComponent
import javax.swing.JViewport
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
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

            val screen = ScreenUtil.getScreenRectangle(this@JujutsuLogTable)
            val maxWidth = minOf(JBUI.scale(500), screen.width - JBUI.scale(40))
            val maxHeight = screen.height - JBUI.scale(40)

            point = mousePos
            tipComponent = Wrapper(tooltipComponent(pane, maxWidth, maxHeight))
            return true
        }

        override fun canBeDismissedOnTimeout() = false
    }

    // Root gutter state: true = expanded (shows repo name), false = collapsed (just colored strip).
    // Defaults to expanded for discoverability (GitHub #10); restored/persisted per-tab via
    // LogWindowConfig.rootGutterExpanded in UnifiedJujutsuLogPanel.
    var isRootGutterExpanded: Boolean = true

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

    // Set by requestSelection(), kept alive until clearNavigation() so loadCommits overwrites can
    // trigger re-expansion from the cache.
    private var pendingSelectionIsExplicit = false

    // True while an expansion background task is in flight; prevents duplicate concurrent loads.
    private var expansionPending = false

    var onSelectionExpansionNeeded: ((ChangeKey) -> Unit)? = null

    fun requestSelection(changeKey: ChangeKey) {
        // Try immediate selection from current model data (e.g., annotation click with no pending refresh).
        // Store as pending regardless so setEntries() can apply it if a refresh is in flight.
        pendingSelection = changeKey
        pendingSelectionIsExplicit = true
        expansionPending = false
        if (!selectEntry(changeKey.repo, changeKey.revision)) {
            log.info("requestSelection: entry not in current model, triggering expansion")
            expansionPending = true
            onSelectionExpansionNeeded?.invoke(changeKey)
        }
    }

    /**
     * Clear navigation state. Call when the user explicitly refreshes so the log returns to the
     * configured revset rather than the expansion view.
     */
    fun clearNavigation() {
        pendingSelection = null
        pendingSelectionIsExplicit = false
        expansionPending = false
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
                    // Show hand cursor when hovering over a ref chip (bookmark or tag)
                    cursor = if (clickTargetAt(e) != null) {
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
                    val resizing = tableHeader.resizingColumn ?: return
                    // The user's dragged size becomes the new desired width (preferredWidth),
                    // distinct from the possibly-squeezed displayed width (see applyColumnWidthPolicy).
                    resizing.preferredWidth = resizing.width
                    saveColumnWidths()
                    // Rebalance the flex description column against the newly-desired fixed width.
                    val resizedFixedColumn =
                        resizing.modelIndex != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION
                    if (columnManager.fitColumnsToWidth && resizedFixedColumn) {
                        applyColumnWidthPolicy()
                    }
                }

                override fun columnAdded(e: TableColumnModelEvent) {}

                override fun columnRemoved(e: TableColumnModelEvent) {}

                override fun columnMoved(e: TableColumnModelEvent) {}

                override fun columnSelectionChanged(e: ListSelectionEvent) {}
            }
        )

        // Handle left-click on ref chips (bookmarks, tags) for navigation, or the "+N more"
        // overflow chip (jj-idea-w61m) to show the hidden refs in a popup.
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                    val target = clickTargetAt(e) ?: return
                    if (target is MoreRefsClick) {
                        showMoreRefsPopup(e.component, e.x, e.y, target)
                    } else {
                        project.stateModel.changeSelection.notify(ChangeKey(target.repo, target.entry.id))
                    }
                    e.consume()
                }
            }
        )

        // Add context menu support (checks ref chips before falling through to row menu)
        addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(comp: Component, x: Int, y: Int) {
                    val syntheticEvent = MouseEvent(this@JujutsuLogTable, 0, 0, 0, x, y, 1, false)
                    when (val target = clickTargetAt(syntheticEvent)) {
                        null -> showContextMenu(comp, x, y)
                        is MoreRefsClick -> showMoreRefsPopup(comp, x, y, target)
                        else -> {
                            val group = clickActionGroup(project, target)
                            ActionManager.getInstance()
                                .createActionPopupMenu(ActionPlaces.UNKNOWN, group)
                                .component.show(comp, x, y)
                        }
                    }
                }
            }
        )

        // Add component resize listener for dynamic column width adjustment. Backup for
        // viewportResizeListener below - the table itself only resizes when its own preferred
        // width changes (e.g. gutter toggle), since AUTO_RESIZE_OFF means the table doesn't
        // track the viewport's width.
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    applyColumnWidthPolicy()
                }
            }
        )
    }

    /**
     * Listens for the enclosing [JViewport] resizing (e.g. the tool window narrowing, or the
     * details pane docking beside the table). With `autoResizeMode = AUTO_RESIZE_OFF` the table
     * itself never shrinks below the sum of its column widths, so `componentResized` on the table
     * never fires for this - only the viewport observes the actual available width.
     */
    private val viewportResizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent?) {
            applyColumnWidthPolicy()
        }
    }

    override fun addNotify() {
        super.addNotify()
        (parent as? JViewport)?.addComponentListener(viewportResizeListener)
        applyColumnWidthPolicy()
    }

    override fun removeNotify() {
        (parent as? JViewport)?.removeComponentListener(viewportResizeListener)
        super.removeNotify()
    }

    /**
     * Return the [LogClickTarget] under [e] (a bookmark or tag chip, or a "+N more" overflow chip —
     * jj-idea-w61m), or null if the event is not over a clickable ref chip.
     * Handles both the Decorations column (SCC-based) and the graph+description column (fragment canvas).
     */
    private fun clickTargetAt(e: MouseEvent): LogClickTarget? {
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
                findInlinedRefUri(entry, localX, cellRect.width, font, frc, columnManager.showDecorations)
            }
            else -> null
        } ?: return null
        if (uri.toString().contains("&kind=overflow&")) {
            val frc = getFontMetrics(font).fontRenderContext
            val budget = cellRect.width * DECORATION_WIDTH_FRACTION
            val hidden = cappedDecorations(entry, Color.BLACK, budget, font, frc).hidden
            return MoreRefsClick(entry.repo, entry, hidden)
        }
        return LogClickTarget.resolve(uri, entry)
    }

    /** Show a popup listing the refs collapsed behind a "+N more" chip; each opens its usual ref action menu. */
    private fun showMoreRefsPopup(component: Component, x: Int, y: Int, target: MoreRefsClick) {
        val group = DefaultActionGroup().apply {
            target.hidden.forEach { hiddenTarget ->
                val label = when (hiddenTarget) {
                    is BookmarkClick -> hiddenTarget.bookmark.name.name
                    is TagClick -> hiddenTarget.tag.name
                    is MoreRefsClick -> return@forEach
                }
                add(DefaultActionGroup(label, true).apply { addAll(clickActionGroup(project, hiddenTarget)) })
            }
        }
        ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.UNKNOWN, group)
            .component.show(component, x, y)
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
                expansionPending = false
                // pendingSelectionIsExplicit stays true: if a concurrent loadCommits later
                // overwrites this expansion result, the next setEntries() will re-fire from cache.
            } else if (pendingSelectionIsExplicit && !expansionPending) {
                expansionPending = true
                onSelectionExpansionNeeded?.invoke(it)
            }
        }
    }

    /**
     * Keep horizontal scroll position stable when the selection changes via mouse click or
     * keyboard navigation. The base [javax.swing.JTable.changeSelection] scrolls the clicked/
     * navigated cell fully into view, which snaps the viewport back to the left edge for the wide
     * graph+description column (view column 0, x≈0). Re-assert the prior horizontal offset while
     * keeping the vertical scroll `super` already performed.
     */
    override fun changeSelection(rowIndex: Int, columnIndex: Int, toggle: Boolean, extend: Boolean) {
        val restoreX = visibleRect.x
        super.changeSelection(rowIndex, columnIndex, toggle, extend)
        val v = visibleRect
        if (v.x != restoreX) scrollRectToVisible(Rectangle(restoreX, v.y, v.width, v.height))
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
        scrollRectToVisible(rowRectPreservingHorizontalScroll(getCellRect(rowIndex, 0, true), visibleRect))
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
     * When set, column widths are read from / written to this map instead of global settings.
     * Set by [in.kkkev.jjidea.ui.log.UnifiedJujutsuLogPanel] to point at the per-window config map.
     */
    var columnWidthsStorage: MutableMap<String, Int>? = null

    /**
     * Called after a per-window column-width save so the panel can persist the whole config.
     * Only invoked when [columnWidthsStorage] is set.
     */
    var onColumnWidthsSaved: (() -> Unit)? = null

    /**
     * Save current column widths.
     *
     * Saves [TableColumn.getPreferredWidth], the user's *desired* size, not
     * [TableColumn.getWidth] which may be transiently squeezed by [applyColumnWidthPolicy] on a
     * narrow window - so a saved width always reflects what the user actually chose (jj-idea-lzq7).
     *
     * If [columnWidthsStorage] is set, writes there and calls [onColumnWidthsSaved].
     * Otherwise falls back to the global [JujutsuSettings.state.columnWidths].
     */
    private fun saveColumnWidths() {
        val storage = columnWidthsStorage
        if (storage != null) {
            for (i in 0 until columnModel.columnCount) {
                val column = columnModel.getColumn(i)
                JujutsuLogTableModel.columnKey(column.modelIndex)?.let { key -> storage[key] = column.preferredWidth }
            }
            onColumnWidthsSaved?.invoke()
        } else {
            val settings = JujutsuSettings.getInstance(project)
            val widths = settings.state.columnWidths.toMutableMap()
            for (i in 0 until columnModel.columnCount) {
                val column = columnModel.getColumn(i)
                JujutsuLogTableModel.columnKey(column.modelIndex)?.let { key -> widths[key] = column.preferredWidth }
            }
            settings.state.columnWidths = widths
        }
    }

    /**
     * Load saved column widths and apply them to the current columns.
     *
     * Reads from [columnWidthsStorage] when set, otherwise from [JujutsuSettings.state.columnWidths].
     * Should be called after columns are set up.
     */
    fun loadColumnWidths() {
        val savedWidths = columnWidthsStorage ?: JujutsuSettings.getInstance(project).state.columnWidths
        if (savedWidths.isNotEmpty()) {
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
        // Re-fit against the restored desired widths in case this runs before the first
        // viewport resize event (e.g. tab restore, column-visibility change).
        applyColumnWidthPolicy()
    }

    // Reentrancy guard: applying computed widths mutates the column model, which can re-fire
    // the resize listeners that call back into this method.
    private var adjustingColumnWidths = false

    /**
     * Apply the current column-width policy: fit columns to the viewport (shrinking the
     * description, then the fixed columns) when [JujutsuColumnManager.fitColumnsToWidth] is on,
     * or restore each column to its desired ([TableColumn.getPreferredWidth]) size when off.
     * Runs on viewport/table resize, column-visibility changes, and width restore.
     */
    fun applyColumnWidthPolicy() {
        if (adjustingColumnWidths || columnModel.columnCount == 0) return
        adjustingColumnWidths = true
        try {
            if (columnManager.fitColumnsToWidth) fitColumnsToViewport() else restoreDesiredColumnWidths()
        } finally {
            adjustingColumnWidths = false
        }
    }

    /** OFF mode: show every column at its desired width (today's manual-scroll behavior). */
    private fun restoreDesiredColumnWidths() {
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            column.width = column.preferredWidth
        }
        resizeAndRepaint()
    }

    /**
     * ON mode: the graph+description column fills the leftover viewport width (shrinking the
     * fixed columns toward their minimums first, per [fitColumnWidths]) instead of the table
     * overflowing into a horizontal scrollbar. With `autoResizeMode = AUTO_RESIZE_OFF` the table
     * always sizes itself to the sum of its column widths, never to the viewport, so the viewport
     * (not `width`) is the source of truth for available space.
     */
    private fun fitColumnsToViewport() {
        val viewportWidth = (parent as? JViewport)?.width ?: width
        if (viewportWidth <= 0) return

        val columns = (0 until columnModel.columnCount).map { columnModel.getColumn(it) }
        val descColumn = columns.firstOrNull { it.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }
            ?: return
        val pinnedWidth = columns
            .filter { it.modelIndex == JujutsuLogTableModel.COLUMN_ROOT_GUTTER }
            .sumOf { it.width }
        val fixedColumns = columns.filter {
            it != descColumn && it.modelIndex != JujutsuLogTableModel.COLUMN_ROOT_GUTTER
        }

        val layout = fitColumnWidths(
            available = viewportWidth - pinnedWidth,
            descMin = descColumn.minWidth,
            fixed = fixedColumns.map { FixedColumn(desired = it.preferredWidth, min = it.minWidth) }
        )
        // Also update preferredWidth so a subsequent save (or a switch to manual mode) captures
        // the fitted size rather than a stale default.
        descColumn.preferredWidth = layout.desc
        descColumn.width = layout.desc
        fixedColumns.zip(layout.fixed).forEach { (column, w) -> column.width = w }
        resizeAndRepaint()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        selectedEntry?.let { sink[JujutsuDataKeys.LOG_ENTRY] = it }
        selectedEntries.takeIf { it.isNotEmpty() }?.let { sink[JujutsuDataKeys.LOG_ENTRIES] = it }
    }

    override fun dispose() {
    }
}

/**
 * Returns [rowRect] adjusted to keep the current horizontal viewport ([currentVisible]), so
 * scrolling a selected row into view only moves vertically and does not reset the user's
 * horizontal scroll position.
 */
internal fun rowRectPreservingHorizontalScroll(rowRect: Rectangle, currentVisible: Rectangle): Rectangle =
    Rectangle(currentVisible.x, rowRect.y, currentVisible.width, rowRect.height)

/** A shrinkable fixed-width column (author/committer/date) as input to [fitColumnWidths]. */
internal data class FixedColumn(val desired: Int, val min: Int)

/** Computed widths from [fitColumnWidths]: the flex graph+description column and each fixed column, in order. */
internal data class ColumnLayout(val desc: Int, val fixed: List<Int>)

/**
 * Distribute [available] px (viewport width minus any pinned columns, e.g. the root gutter)
 * across the flex graph+description column and [fixed] columns (author/committer/date).
 *
 * The description column fills whatever is left over after the fixed columns take their
 * [FixedColumn.desired] width, but never drops below [descMin]. When it would, the fixed columns
 * give back space by shrinking proportionally from their desired width toward their
 * [FixedColumn.min] (each column's share of the shortfall is proportional to its own shrinkable
 * range, so a column with more room to give gives more). Only when every fixed column is already
 * at its minimum and the description is still at its floor does the total exceed [available] -
 * horizontal scroll is the last-resort fallback in that case (jj-idea-lzq7).
 */
internal fun fitColumnWidths(available: Int, descMin: Int, fixed: List<FixedColumn>): ColumnLayout {
    val descFill = available - fixed.sumOf { it.desired }
    if (descFill >= descMin) return ColumnLayout(descFill, fixed.map { it.desired })

    val shortfall = descMin - descFill
    val shrinkable = fixed.sumOf { it.desired - it.min }
    if (shrinkable <= 0) return ColumnLayout(descMin, fixed.map { it.min })

    val reclaim = minOf(shortfall, shrinkable)
    return ColumnLayout(descMin, fixed.map { it.desired - reclaim * (it.desired - it.min) / shrinkable })
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

    /**
     * Invoked on the EDT after [applyFilter] rebuilds [filteredEntries], except when called
     * from [setEntries] (which is followed immediately by an explicit graph update by the
     * caller). Used by the panel to rebuild the displayed graph for the visible subset.
     */
    var onFilterApplied: (() -> Unit)? = null

    /** Set to true inside [setEntries] to suppress the [onFilterApplied] callback. */
    private var suppressFilterCallback = false

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
     * Returns a snapshot of the currently-visible (filtered) entries, in their current order.
     * Used by the panel to recompute the graph layout for the visible subset.
     */
    fun getFilteredEntries(): List<LogEntry> = filteredEntries.toList()

    /**
     * Update the table with new log entries.
     * Called on EDT after background loading.
     */
    fun setEntries(newEntries: List<LogEntry>) {
        entries.clear()
        entries.addAll(newEntries)
        suppressFilterCallback = true
        try {
            applyFilter()
        } finally {
            suppressFilterCallback = false
        }
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

        if (!suppressFilterCallback) onFilterApplied?.invoke()
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

/**
 * Bounds [pane] to [maxWidth] so its HTML reflows (e.g. bookmark chips wrap across lines)
 * instead of laying out as one oversized line. If the resulting preferred height still
 * exceeds [maxHeight], wraps it in a vertically scrollable pane instead of letting the
 * tooltip get clipped by the screen (jj-idea-szn8).
 */
internal fun tooltipComponent(pane: JComponent, maxWidth: Int, maxHeight: Int): JComponent {
    pane.setSize(maxWidth, Int.MAX_VALUE)
    val pref = pane.preferredSize
    val boundedWidth = minOf(pref.width, maxWidth)
    pane.preferredSize = Dimension(boundedWidth, pref.height)

    if (pref.height <= maxHeight) return pane

    return JBScrollPane(pane).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(boundedWidth, maxHeight)
    }
}
