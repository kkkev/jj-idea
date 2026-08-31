package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vcs.IssueNavigationConfiguration
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.components.IssueLinkifier
import `in`.kkkev.jjidea.ui.components.installIconAwareTableTooltip
import `in`.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.clickActionGroup
import kotlinx.datetime.Instant
import org.apache.commons.lang3.ArrayUtils.addAll
import java.awt.*
import java.awt.event.*
import javax.swing.JViewport
import javax.swing.KeyStroke
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
    // internal, not private: JujutsuLogTableRenderers.installRenderers (a same-package extension
    // function) needs it to fetch IssueNavigationConfiguration for the graph+description renderer
    // (jj-idea-91qf).
    internal val project: Project,
    val columnManager: JujutsuColumnManager = JujutsuColumnManager.DEFAULT
) : JBTable(JujutsuLogTableModel()), Disposable, UiDataProvider {
    private val log = Logger.getInstance(javaClass)

    // Graph nodes for rendering (populated when data is loaded)
    var graphNodes: Map<ChangeKey, GraphNode> = emptyMap()
        private set

    // Currently hovered row for targeted repaint
    var hoveredRow: Int = -1
        private set

    // (View) row/column of the currently-hovered clickable link (jj-idea-iesq) - i.e. the exact
    // cell clickTargetAt(e) last resolved non-null for, or -1/-1 when the pointer isn't over one.
    // Read by UserCellRenderer (via TextTableCellRenderer) to underline only that cell's link,
    // matching the "colored always, underlined only on hover" convention (SimpleTextAttributes
    // .LINK_PLAIN_ATTRIBUTES vs .LINK_ATTRIBUTES) used elsewhere in the platform, e.g.
    // VcsLogGraphTable's empty-state links.
    var hoveredLinkRow: Int = -1
        private set
    var hoveredLinkCol: Int = -1
        private set

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
        tableHeader.preferredSize = Dimension(tableHeader.preferredSize.width, 24)

        // Disable auto-resize to allow manual column sizing
        autoResizeMode = AUTO_RESIZE_OFF

        // Row height for better readability
        rowHeight = 22

        // Striped rows for better readability (jj-idea-eyf1: off-switch in settings).
        setStriped(JujutsuSettings.getInstance(project).state.stripedLogRows)
        project.stateModel.logRefresh.connect(this) {
            setStriped(JujutsuSettings.getInstance(project).state.stripedLogRows)
        }

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
                    // Show hand cursor over a clickable element (author/committer name, or the
                    // "+N more" overflow chip), and underline it (only it - jj-idea-iesq) while
                    // the pointer is over it. Computed once and reused for both, rather than
                    // calling clickTargetAt twice per move.
                    //
                    // Bookmark/tag chips deliberately do NOT get a hover cue (jj-idea-wkcz):
                    // clickTargetAt still resolves them (right-click still works, via
                    // clickActionGroup), but they have no left-click action, so hinting
                    // "clickable" here would be misleading - and it'd clash visually once a chip
                    // can itself contain a linkified issue reference (jj-idea-vrmv), where only
                    // that inner fragment should look interactive.
                    val newCol = columnAtPoint(e.point)
                    val target = clickTargetAt(e)
                    val showsHoverCue = target?.hasHoverCue == true
                    cursor = if (showsHoverCue) {
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
                    val newHoveredLinkRow = if (showsHoverCue) newRow else -1
                    val newHoveredLinkCol = if (showsHoverCue) newCol else -1
                    if (newHoveredLinkRow != hoveredLinkRow || newHoveredLinkCol != hoveredLinkCol) {
                        val oldLinkRow = hoveredLinkRow
                        hoveredLinkRow = newHoveredLinkRow
                        hoveredLinkCol = newHoveredLinkCol
                        if (oldLinkRow >= 0) repaintRow(oldLinkRow)
                        if (newHoveredLinkRow >= 0) repaintRow(newHoveredLinkRow)
                    }
                }
            }
        )

        // Register custom tooltip that renders all tooltips via IconAwareHtmlPane, so chip
        // <img> tags render correctly and all tooltips have consistent styling.
        // jj-idea-tknb: off-switch, read live (not cached) so it applies without restart.
        installIconAwareTableTooltip(
            this,
            project,
            isEnabled = { JujutsuSettings.getInstance(project).state.showLogHoverTooltip }
        )

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

        // Handle left-click on author/committer names (open the OS mail client), or the "+N more"
        // overflow chip (jj-idea-w61m) to show the hidden refs in a popup. Bookmark/tag chips have
        // no left-click action (jj-idea-wkcz) - only the right-click menu built by clickActionGroup
        // (jj-idea-iesq) reaches them, mirroring each other element's default action.
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button != MouseEvent.BUTTON1 || e.clickCount != 1) return
                    when (val target = clickTargetAt(e) ?: return) {
                        is MoreRefsClick -> showMoreRefsPopup(e.component, e.x, e.y, target)
                        else -> target.performDefaultAction(project)
                    }
                    e.consume()
                }
            }
        )

        // Route double-click through whichever action is bound to Enter (default: Show Diff,
        // jj-idea-th9h). Mirrors IntelliJ's own EditorTabPreview pattern: Enter and double-click
        // invoke the same action rather than a bespoke double-click setting, so rebinding Enter
        // in Keymap settings changes double-click behaviour too. Ref chips and the root gutter
        // keep their existing single-click behaviour instead of triggering the Enter action.
        object : DoubleClickListener() {
            override fun onDoubleClick(e: MouseEvent): Boolean {
                if (clickTargetAt(e) != null) return false
                val viewColumn = columnAtPoint(e.point)
                if (viewColumn >= 0 &&
                    convertColumnIndexToModel(viewColumn) == JujutsuLogTableModel.COLUMN_ROOT_GUTTER
                ) {
                    return false
                }
                val row = rowAtPoint(e.point)
                if (row < 0) return false
                if (!isRowSelected(row)) setRowSelectionInterval(row, row)
                return invokeEnterBoundAction()
            }
        }.installOn(this)

        // Add context menu support (checks ref chips before falling through to row menu)
        addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(comp: Component, x: Int, y: Int) {
                    val syntheticEvent = MouseEvent(this@JujutsuLogTable, 0, 0, 0, x, y, 1, false)
                    when (val target = clickTargetAt(syntheticEvent)) {
                        null -> showContextMenu(comp, x, y)
                        is MoreRefsClick -> showMoreRefsPopup(comp, x, y, target)
                        else -> {
                            // Highlighted-default ListPopup (same idiom as JujutsuFilterComponent's
                            // toolbar popups) instead of a plain JPopupMenu, so the action mirroring
                            // this element's left-click default is pre-selected (jj-idea-iesq).
                            val group = clickActionGroup(project, target)
                            JBPopupFactory.getInstance()
                                .createActionGroupPopup(
                                    null,
                                    group,
                                    DataManager.getInstance().getDataContext(comp),
                                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                                    true,
                                    null,
                                    -1,
                                    Condition { it is DefaultClickAction },
                                    null
                                )
                                .show(RelativePoint(comp, Point(x, y)))
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

        // Preserve the current selection across filter changes when the selected entry stays
        // visible (jj-idea-yje9). rebuild() (fireTableDataChanged()) leaves the JTable selection
        // tracking a raw row index rather than the entry that was there before, so without this a
        // filter tweak (author/date/reference/text/root/paths) can silently point the selection at
        // the wrong row - or, if the old index is no longer valid, drop it - even though the
        // originally-selected commit is still visible in the filtered list.
        //
        // Any selection churn this causes (clear, then reselect-by-identity) is absorbed by
        // CommitTablePanel's deferred, dedup'd selection listener rather than being fought here -
        // see the comment on the listener in CommitTablePanel.kt.
        logModel.withSelectionPreserved = { rebuild ->
            val key = selectedEntry?.let { ChangeKey(it.repo, it.id) }
            rebuild()
            if (key != null && !selectEntry(key.repo, key.revision)) {
                clearSelection()
            }
        }
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
     * Return the [LogClickTarget] under [e] (a bookmark/tag chip, a "+N more" overflow chip —
     * jj-idea-w61m, or an author/committer name — jj-idea-iesq), or null if the event is not over
     * a clickable element. Handles the Decorations column (SCC-based), the graph+description
     * column (fragment canvas), and the Author/Committer columns (name-width hit-test).
     */
    private fun clickTargetAt(e: MouseEvent): LogClickTarget? {
        val row = rowAtPoint(e.point).takeIf { it >= 0 } ?: return null
        val col = columnAtPoint(e.point).takeIf { it >= 0 } ?: return null
        val modelRow = convertRowIndexToModel(row)
        val entry = logModel.getEntry(modelRow) ?: return null
        val cellRect = getCellRect(row, col, false)
        val localX = e.x - cellRect.x
        val modelCol = convertColumnIndexToModel(col)
        if (modelCol == JujutsuLogTableModel.COLUMN_AUTHOR || modelCol == JujutsuLogTableModel.COLUMN_COMMITTER) {
            val frc = getFontMetrics(font).fontRenderContext
            return findPersonClickTarget(entry, modelCol, localX, font, frc)
        }
        if (modelCol != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) return null
        val frc = getFontMetrics(font).fontRenderContext
        val linkifier = IssueLinkifier(IssueNavigationConfiguration.getInstance(project))
        val textStart = graphTextStartX(modelRow, logModel, graphNodes)
        val laidOut = LaidOutCell.forRow(
            entry,
            cellRect.width,
            textStart,
            columnManager,
            linkifier,
            JBColor.BLACK,
            font,
            frc
        )
        val uri = laidOut.linkTargetAt(localX) ?: return null
        if (uri.toString().contains("&kind=overflow&")) {
            return MoreRefsClick(entry.repo, entry, laidOut.hidden)
        }
        return LogClickTarget.resolve(uri, project, listOf(entry))
    }

    /**
     * Show a popup listing the refs collapsed behind a "+N more" chip; each opens its usual ref
     * action menu, labelled with the same coloured bookmark/tag glyph its own chip would show
     * (jj-idea-lm3o).
     *
     * `addAll` takes the `.toList()` of [clickActionGroup]'s children rather than the raw
     * `AnAction[]` - passing the array directly resolves to a Kotlin/Java vararg overload that
     * silently adds nothing. `isDisableGroupIfEmpty = false` is defensive: these submenus are never
     * legitimately empty.
     */
    private fun showMoreRefsPopup(component: Component, x: Int, y: Int, target: MoreRefsClick) {
        val group = BackgroundActionGroup().apply {
            target.hidden.forEach { hiddenTarget ->
                add(
                    DefaultActionGroup(hiddenTarget.displayName, null, hiddenTarget.displayIcon?.get())
                        .apply {
                            isPopup = true
                            templatePresentation.isDisableGroupIfEmpty = false
                            addAll(clickActionGroup(project, hiddenTarget).childActionsOrStubs.toList())
                        }
                )
            }
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                null,
                group,
                DataManager.getInstance().getDataContext(component),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
                null,
                -1,
                null,
                null
            )
            .show(RelativePoint(component, Point(x, y)))
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
     * Run the first enabled action bound to the Enter keystroke in the active keymap (default:
     * Show Diff, `Jujutsu.ShowChangesDiff`), using this table as the context component so the
     * action sees the current selection. Backs the double-click handler above — jj-idea-th9h.
     *
     * Uses [ActionManager.tryToExecute] — the same entry point the real Enter keypress goes
     * through — rather than hand-building an [com.intellij.openapi.actionSystem.AnActionEvent],
     * so update() (including background-thread update actions like Show Diff) and enablement
     * checks run exactly as they would for a real keystroke. Tries the next Enter-bound action
     * (if any) when one is disabled, since [ActionManager.tryToExecute] only checks the single
     * action it's given.
     */
    private fun invokeEnterBoundAction(actionIds: List<String> = enterBoundActionIds()): Boolean {
        val actionId = actionIds.firstOrNull() ?: return false
        val action = ActionManager.getInstance().getAction(actionId)
        if (action == null) {
            return invokeEnterBoundAction(actionIds.drop(1))
        }
        val keyEvent = KeyEvent(this, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\r')
        ActionManager.getInstance()
            .tryToExecute(action, keyEvent, this, ActionPlaces.KEYBOARD_SHORTCUT, true)
            .doWhenRejected(Runnable { invokeEnterBoundAction(actionIds.drop(1)) })
        return true
    }

    private fun enterBoundActionIds(): List<String> {
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        return KeymapManager.getInstance().activeKeymap.getActionIds(enter).toList()
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

    fun updateGraph(nodes: Map<ChangeKey, GraphNode>) {
        graphNodes = nodes
        // Refresh combined graph+description column rendering with column manager
        // Find the column by model index, not view index
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            if (column.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) {
                column.cellRenderer = JujutsuGraphAndDescriptionRenderer(
                    graphNodes,
                    columnManager,
                    IssueLinkifier(IssueNavigationConfiguration.getInstance(project))
                )
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
     * Saves [javax.swing.table.TableColumn.getPreferredWidth], the user's *desired* size, not
     * [javax.swing.table.TableColumn.getWidth] which may be transiently squeezed by [applyColumnWidthPolicy] on a
     * narrow window - so a saved width always reflects what the user actually chose (jj-idea-lzq7).
     *
     * If [columnWidthsStorage] is set, writes there and calls [onColumnWidthsSaved].
     * Otherwise, falls back to the global `JujutsuSettings.state.columnWidths`.
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
     * Reads from [columnWidthsStorage] when set, otherwise from `JujutsuSettings.state.columnWidths`.
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
     * or restore each column to its desired ([javax.swing.table.TableColumn.getPreferredWidth]) size when off.
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
    private var bookmarkFilter: Set<ChangeKey> =
        emptySet() // Filter by repo-scoped bookmark change keys (includes ancestors)
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

    /**
     * Installed by the view so filter-driven rebuilds preserve the current selection when the
     * selected entry stays visible (jj-idea-yje9). [applyFilter] calls this around the whole
     * [rebuildFilteredEntries] (not just the final [fireTableDataChanged]) so the callback can read
     * the *pre-filter* selection before [filteredEntries] is mutated - reading it after the swap
     * would pick up whichever entry lands at the old row index in the new filtered list, not the
     * entry that was actually selected. Not used during [setEntries] (see [suppressFilterCallback]),
     * which is followed by the view's own richer selection/expansion-aware preservation.
     */
    var withSelectionPreserved: ((rebuild: () -> Unit) -> Unit)? = null

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
     * Set the bookmark filter (by repo-scoped change keys that should be included).
     * Empty set means no bookmark filtering.
     * The set should include all ancestors of the selected bookmark.
     * Keyed by [ChangeKey] rather than a bare [ChangeId] so entries from different repos that
     * happen to share an id (e.g. the synthetic root commit id) can't slip through the filter for
     * the wrong repo (jj-idea-1ra9).
     */
    fun setBookmarkFilter(keys: Set<ChangeKey>) {
        bookmarkFilter = keys
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
     *
     * The actual recompute-and-swap is deferred into [rebuildFilteredEntries] so that
     * [withSelectionPreserved], when installed, gets a chance to read the *pre-filter* selection
     * (via the view's `selectedEntry`, which reads [filteredEntries]) before [filteredEntries] is
     * mutated - see jj-idea-yje9. Capturing the selection after the swap would read whatever entry
     * ended up at the old row index in the *new* filtered list, not the entry that was actually
     * selected.
     */
    private fun applyFilter() {
        val preserve = withSelectionPreserved
        if (suppressFilterCallback || preserve == null) {
            rebuildFilteredEntries()
        } else {
            preserve { rebuildFilteredEntries() }
        }
    }

    /** Recomputes [filteredEntries] from the current filter state and fires the table update. */
    private fun rebuildFilteredEntries() {
        filteredEntries.clear()

        val matcher = LogFilterMatcher.create(filterText, useRegex, matchCase, matchWholeWords)

        // Filter entries by all active filters
        filteredEntries.addAll(
            entries.filter { entry ->
                // Text filter (if active)
                val matchesText = matcher?.matches(entry) ?: true

                // Author filter (if active)
                val matchesAuthor = if (authorFilter.isNotEmpty()) {
                    entry.author?.email?.let { authorFilter.contains(it) } == true
                } else {
                    true
                }

                // Bookmark filter (if active) - filter by repo-scoped change key
                val matchesBookmark = if (bookmarkFilter.isNotEmpty()) {
                    bookmarkFilter.contains(entry.key)
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
     * Clear all entries.
     */
    fun clear() {
        entries.clear()
        filteredEntries.clear()
        fireTableDataChanged()
    }
}
