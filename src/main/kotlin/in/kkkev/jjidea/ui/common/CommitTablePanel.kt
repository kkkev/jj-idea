package `in`.kkkev.jjidea.ui.common

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.LazyActionById
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.components.LogSearchField
import `in`.kkkev.jjidea.ui.log.*
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.TableColumn
import kotlin.reflect.KMutableProperty1

abstract class CommitTablePanel<D>(
    protected val project: Project,
    private val toolbarPlace: String,
    dataLoaderFactory: (CommitTablePanel<D>) -> DataLoader,
    initialDetailsOnRight: Boolean = true,
    configureColumnManager: (JujutsuColumnManager) -> Unit = {}
) : JPanel(BorderLayout()), Disposable, UiDataProvider {
    private val log = Logger.getInstance(javaClass)

    val columnManager = JujutsuColumnManager().also(configureColumnManager)

    val logTable = JujutsuLogTable(project, columnManager)

    val dataLoader = dataLoaderFactory.invoke(this)

    lateinit var referenceFilterComponent: JujutsuReferenceFilterComponent
    protected lateinit var authorFilterComponent: JujutsuAuthorFilterComponent
    protected lateinit var dateFilterComponent: JujutsuDateFilterComponent

    // Details panel showing selected commit info
    val detailsPanel = JujutsuCommitDetailsPanel(project)

    // Splitter for table and details panel
    var splitter: OnePixelSplitter

    // Always holds [splitter] alone; exists so [installLeftComponent] can wrap this whole
    // table+details area in an outer splitter without [toggleDetailsPosition]'s remove/re-add of
    // [splitter] needing to know whether such a wrapper is present.
    private val centerContainer = JPanel(BorderLayout())

    // Details panel position (true = right, false = bottom)
    var detailsOnRight = initialDetailsOnRight

    // Search field with regex/match-case/whole-words toggles, and history (jj-idea-lpbv).
    protected val searchField: LogSearchField = LogSearchField(
        placeholder = JujutsuBundle.message("log.filter.text.placeholder"),
        tooltip = JujutsuBundle.message("log.filter.text.tooltip"),
        withHistory = true,
        onFilterChanged = {
            applyFilter()
            onConfigChanged()
        },
        onSubmitted = { text -> onSearchSubmitted(text) }
    )

    // Filter options state — delegate to [searchField] so persistence (LogWindowConfig) and
    // status-bar code can keep reading/writing these as plain properties.
    var useRegex: Boolean
        get() = searchField.useRegex
        set(value) {
            searchField.useRegex = value
        }
    var matchCase: Boolean
        get() = searchField.matchCase
        set(value) {
            searchField.matchCase = value
        }
    var matchWholeWords: Boolean
        get() = searchField.matchWholeWords
        set(value) {
            searchField.matchWholeWords = value
        }

    // Status bar for truncation indicator
    private val statusBar = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isVisible = false
        border = JBUI.Borders.empty(2, 6)
    }

    init {
        Disposer.register(this, detailsPanel)
        // jj-idea-eyf1: logTable now subscribes to logRefresh (to pick up striping changes live),
        // so it must be disposed with the panel or that subscription leaks the panel/project.
        Disposer.register(this, logTable)

        // Install custom renderers
        logTable.installRenderers()

        // Set up initial column visibility
        updateColumnVisibility()

        // Create table panel with toolbar
        val tablePanel = createTablePanel()

        // Create splitter with table panel and details panel
        splitter = createSplitter(tablePanel, detailsOnRight)

        centerContainer.add(splitter, BorderLayout.CENTER)
        add(centerContainer, BorderLayout.CENTER)

        // Wire table selection to details panel. Deferred via runLater (rather than reading
        // logTable.selectedEntries synchronously in the listener) so that a filter change which
        // clears and re-selects the current row (jj-idea-yje9, see
        // JujutsuLogTableModel.withSelectionPreserved) coalesces into a single update: any
        // intermediate selection-changed events this produces are all deferred to the same EDT
        // tick, and by the time the first deferred callback runs, the synchronous
        // clear-then-reselect has already finished - so every deferred callback reads the same
        // final selection. Combined with showCommits()'s no-op-on-unchanged-selection guard, this
        // means the details panel never flashes empty or reloads the same commit's diff twice.
        logTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) runLater { detailsPanel.showCommits(logTable.selectedEntries) }
        }

        dataLoader.load()
    }

    /**
     * Create a splitter with the given orientation.
     */
    private fun createSplitter(tablePanel: JPanel, horizontal: Boolean) =
        OnePixelSplitter(!horizontal, if (horizontal) 0.7f else 0.7f).apply {
            firstComponent = tablePanel
            secondComponent = detailsPanel
        }

    /**
     * Create a panel containing the table with its toolbar.
     */
    private fun createTablePanel() = JPanel(BorderLayout()).apply {
        // Add toolbar at the top
        add(createToolbar(), BorderLayout.NORTH)

        // Add table scroll pane in the center
        add(ScrollPaneFactory.createScrollPane(logTable), BorderLayout.CENTER)

        // Add status bar at the bottom (hidden by default)
        add(statusBar, BorderLayout.SOUTH)
    }

    private fun createToolbar() = JPanel(BorderLayout()).apply {
        // Create left-side panel with text filter
        val leftPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(searchField)
            add(Box.createHorizontalStrut(5))
            add(createFilterComponents())
        }

        // Create main toolbar with actions on the right
        val toolbar = ActionManager.getInstance().createActionToolbar(
            toolbarPlace,
            createActionGroup(),
            true
        )
        toolbar.targetComponent = this@CommitTablePanel

        // Filters go in CENTER (not WEST) so the action toolbar in EAST always claims its
        // full preferred width first; the filter cluster absorbs whatever width remains and
        // shrinks/clips under pressure instead of pushing New/Edit/Fetch/Push off-screen.
        add(leftPanel, BorderLayout.CENTER)
        add(toolbar.component, BorderLayout.EAST)
    }

    open fun createOtherFilterComponents(): List<JujutsuFilterComponent> = emptyList()

    /**
     * Create the filter action toolbar (Root, Reference, Author, Date, Paths). Backed by an
     * [ActionToolbar] using [FilterPriorityLayoutStrategy] so filters that no longer fit the
     * available width collapse into the standard "»" overflow popup — hiding unapplied filters
     * before applied ones, without reordering whatever stays visible — rather than being pushed
     * off-screen and becoming completely unreachable.
     */
    private fun createFilterComponents(): JComponent {
        val filters = mutableListOf<JujutsuFilterComponent>()
        filters += createOtherFilterComponents()

        // Reference filter (bookmarks, tags, @)
        referenceFilterComponent =
            JujutsuReferenceFilterComponent(logTable.logModel, project, this@CommitTablePanel).apply {
                initUi()
                initialize()
            }
        Disposer.register(this@CommitTablePanel, referenceFilterComponent)
        filters += referenceFilterComponent

        // Author filter
        authorFilterComponent = JujutsuAuthorFilterComponent(logTable.logModel, project).apply {
            initUi()
            initialize()
        }
        filters += authorFilterComponent

        // Wire the log row's own click actions (jj-idea-iesq) to these filter components: a
        // clicked bookmark/tag chip's "Filter log to this reference" and an author name's
        // "Filter log by this author" both go through project.stateModel rather than a direct
        // reference, since the actions are built statically in JujutsuLogContextMenuActions
        // without a handle to this panel. Both toggle: triggering the same reference/author again
        // while it's already the active filter clears it instead of re-applying it, so repeatedly
        // clicking the same bookmark chip or menu item acts as an on/off switch.
        project.stateModel.filterToReference.connect(this@CommitTablePanel) { name ->
            if (referenceFilterComponent.getSelectedReferenceName() == name) {
                referenceFilterComponent.clearReference()
            } else {
                referenceFilterComponent.selectReference(name)
            }
        }
        project.stateModel.filterByAuthor.connect(this@CommitTablePanel) { email ->
            if (authorFilterComponent.getSelectedAuthors() == setOf(email)) {
                authorFilterComponent.setSelectedAuthors(emptySet())
            } else {
                authorFilterComponent.setSelectedAuthors(setOf(email))
            }
        }

        // Date filter
        dateFilterComponent = JujutsuDateFilterComponent(logTable.logModel).apply {
            initUi()
            initialize()
        }
        filters += dateFilterComponent
        // Note: Paths filter is omitted in unified mode as it requires a single root

        val group = BackgroundActionGroup(*filters.map { FilterComponentAction(it) }.toTypedArray())
        val toolbar = ActionManager.getInstance().createActionToolbar("JujutsuLogFilters", group, true).apply {
            targetComponent = this@CommitTablePanel
            layoutStrategy = FilterPriorityLayoutStrategy
        }
        return toolbar.component
    }

    /**
     * Wraps a [JujutsuFilterComponent] as a toolbar action so it can participate in
     * [FilterPriorityLayoutStrategy] overflow: clicking it (whether shown inline or selected from
     * the "»" popup once collapsed) opens the filter's own selection popup.
     */
    private class FilterComponentAction(private val component: JujutsuFilterComponent) :
        AnAction(),
        CustomComponentAction {
        override fun createCustomComponent(presentation: Presentation, place: String): JComponent = component

        override fun updateCustomComponent(component: JComponent, presentation: Presentation) {
            component.isEnabled = presentation.isEnabled
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = component.isVisible
        }

        override fun actionPerformed(e: AnActionEvent) {
            component.openPopup()
        }
    }

    /**
     * Extra actions shown at the left of the toolbar, ahead of Refresh. Empty by default;
     * subclasses override to add surface-specific one-click actions (e.g. New/Edit in the
     * main log toolbar). Not shown in the file history toolbar.
     */
    protected open fun primaryActions(): List<AnAction> = emptyList()

    private fun createActionGroup(): BackgroundActionGroup {
        val primary = primaryActions()
        return BackgroundActionGroup(
            *primary.toTypedArray(),
            *(if (primary.isEmpty()) emptyArray() else arrayOf(Separator.create())),
            RefreshAction(),
            Separator.create(),
            LazyActionById("Jujutsu.GitFetch"),
            LazyActionById("Jujutsu.GitPush"),
            Separator.create(),
            ViewOptionsAction()
        )
    }

    /**
     * Hook called whenever any persisted UI state changes (column visibility, details position,
     * filter options, or search text). Subclasses override to persist to their [LogWindowConfig].
     */
    open fun onConfigChanged() {}

    /**
     * Hook called when Enter is pressed in the search field with non-blank [text]. Default no-op;
     * [UnifiedJujutsuLogPanel] overrides this to run a whole-repo search (jj-idea-lpbv) so results
     * outside the loaded log window are found. File history does not opt in — its results are
     * already the complete history of the file, so there is no "outside the window" to search.
     */
    protected open fun onSearchSubmitted(text: String) {}

    /**
     * Refresh action - reload commits from all repositories.
     */
    private inner class RefreshAction : AnAction(
        JujutsuBundle.message("log.action.refresh"),
        JujutsuBundle.message("log.action.refresh.tooltip"),
        AllIcons.Actions.Refresh
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            log.info("Refresh action triggered")
            dataLoader.refresh()
        }
    }

    /**
     * View options popup - column visibility, details position, and other per-table display
     * toggles, grouped under labeled separators rather than nested submenus (jj-idea-lgo4: fewer
     * clicks than the old separate Columns / Details Position submenus).
     */
    private inner class ViewOptionsAction : PopupActionGroup(
        "log.action.view.options",
        createViewOptionsActionGroup()
    ) {
        init {
            templatePresentation.icon = AllIcons.Actions.Show
            templatePresentation.description = JujutsuBundle.message("log.action.view.options.tooltip")
        }
    }

    /**
     * Action to position details on the right.
     */
    private inner class DetailsOnRightAction : ToggleAction(JujutsuBundle.message("log.action.details.right")) {
        override fun isSelected(e: AnActionEvent) = detailsOnRight

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && !detailsOnRight) {
                toggleDetailsPosition()
            }
        }
    }

    /**
     * Action to position details on the bottom.
     */
    private inner class DetailsOnBottomAction : ToggleAction(JujutsuBundle.message("log.action.details.bottom")) {
        override fun isSelected(e: AnActionEvent) = !detailsOnRight

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && detailsOnRight) {
                toggleDetailsPosition()
            }
        }
    }

    /**
     * Update table column visibility based on column manager settings.
     */
    open fun updateColumnVisibility() {
        val columnModel = logTable.columnModel
        val tableModel = logTable.logModel

        // Store current columns
        val existingColumns = mutableListOf<TableColumn>()
        for (i in 0 until columnModel.columnCount) {
            existingColumns.add(columnModel.getColumn(i))
        }

        // Remove all columns
        while (columnModel.columnCount > 0) {
            columnModel.removeColumn(columnModel.getColumn(0))
        }

        // Add back only visible columns
        for (idx in 0 until tableModel.columnCount) {
            if (columnManager.isColumnVisible(idx)) {
                // Try to reuse existing column or create new one
                val column =
                    existingColumns.find { it.modelIndex == idx }
                        ?: TableColumn(idx)

                columnModel.addColumn(column)
            }
        }

        // Re-install renderers
        logTable.installRenderers()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[DiffDataKeys.EDITOR_TAB_DIFF_PREVIEW] = detailsPanel.diffPreview
        // The toolbar's targetComponent is this panel, not logTable, and DataManager only
        // walks up from a target component - so log-selection keys must be forwarded here for
        // toolbar actions (e.g. New/Edit) to see the current selection.
        logTable.selectedEntry?.let { sink[JujutsuDataKeys.LOG_ENTRY] = it }
        logTable.selectedEntries.takeIf { it.isNotEmpty() }?.let { sink[JujutsuDataKeys.LOG_ENTRIES] = it }
    }

    abstract fun onDataLoaded(newData: D)

    abstract fun updateTableStuff()

    inner class ToggleColumnAction(
        resourceKeySuffix: String,
        private val property: KMutableProperty1<JujutsuColumnManager, Boolean>
    ) : ToggleAction(JujutsuBundle.message("log.column.toggle.$resourceKeySuffix")) {
        override fun isSelected(e: AnActionEvent) = property.getter.invoke(columnManager)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            property.setter.invoke(columnManager, state)
            updateTableStuff()
            onConfigChanged()
        }
    }

    /**
     * Toggle for "Fit columns to window width" (jj-idea-lzq7): flexes the graph+description
     * column and squeezes the fixed columns to avoid horizontal scroll, vs. honoring each
     * column's exact persisted width (today's manual-scroll behavior). Unlike [ToggleColumnAction]
     * this doesn't rebuild the column model - it only needs to re-run the width policy.
     */
    private inner class FitColumnsToWidthAction :
        ToggleAction(
            JujutsuBundle.message("log.column.toggle.fitwidth"),
            JujutsuBundle.message("log.column.toggle.fitwidth.tooltip"),
            null
        ) {
        override fun isSelected(e: AnActionEvent) = columnManager.fitColumnsToWidth

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            columnManager.fitColumnsToWidth = state
            logTable.applyColumnWidthPolicy()
            onConfigChanged()
        }
    }

    open fun createViewOptionsActionGroup() = DefaultActionGroup().apply {
        addSeparator(JujutsuBundle.message("log.view.group.columns"))
        addAction(ToggleColumnAction("status", JujutsuColumnManager::showStatus))
        addAction(ToggleColumnAction("changeid", JujutsuColumnManager::showChangeId))
        addAction(ToggleColumnAction("description", JujutsuColumnManager::showDescription))
        addAction(ToggleColumnAction("decorations", JujutsuColumnManager::showDecorations))
        addAction(ToggleColumnAction("author", JujutsuColumnManager::showAuthorColumn))
        addAction(ToggleColumnAction("committer", JujutsuColumnManager::showCommitterColumn))
        addAction(ToggleColumnAction("date", JujutsuColumnManager::showDateColumn))
        addSeparator()
        addAction(FitColumnsToWidthAction())
        addSeparator(JujutsuBundle.message("log.view.group.details"))
        addAction(DetailsOnRightAction())
        addAction(DetailsOnBottomAction())
        addSeparator()
        addAction(StripedRowsAction())
        addAction(CommitTooltipsAction())
    }

    /**
     * Toggle for alternating row background colors (jj-idea-n22a: moved here from a global
     * Settings checkbox, same as [CommitTooltipsAction]). Unlike the tooltip toggle, striping is
     * applied eagerly rather than read live, so this still needs to broadcast [stateModel]'s
     * logRefresh - [JujutsuLogTable]'s existing subscription re-applies it to every open log and
     * history table immediately, including this one.
     */
    private inner class StripedRowsAction : ToggleAction(
        JujutsuBundle.message("log.action.striped.rows"),
        JujutsuBundle.message("log.action.striped.rows.tooltip"),
        null
    ) {
        override fun isSelected(e: AnActionEvent) =
            JujutsuSettings.getInstance(project).state.stripedLogRows

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            JujutsuSettings.getInstance(project).state.stripedLogRows = state
            project.stateModel.logRefresh.notify(Unit)
        }
    }

    /**
     * Toggle for showing the commit-details tooltip when hovering a log row (jj-idea-lgo4: moved
     * here from a global Settings checkbox). The underlying state stays project-global -
     * [JujutsuLogTable]'s hover-tooltip callback reads it live - but the control now lives on
     * each table's toolbar, next to the other display toggles it affects.
     */
    private inner class CommitTooltipsAction : ToggleAction(
        JujutsuBundle.message("log.action.tooltips"),
        JujutsuBundle.message("log.action.tooltips.tooltip"),
        null
    ) {
        override fun isSelected(e: AnActionEvent) =
            JujutsuSettings.getInstance(project).state.showLogHoverTooltip

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            JujutsuSettings.getInstance(project).state.showLogHoverTooltip = state
        }
    }

    /**
     * Apply the current filter text to the table.
     */
    private fun applyFilter() {
        val filterText = searchField.text
        log.info("Applying filter: '$filterText' (regex=$useRegex, matchCase=$matchCase, wholeWords=$matchWholeWords)")
        logTable.logModel.setFilter(filterText, useRegex, matchCase, matchWholeWords)
    }

    /**
     * Update the status bar to indicate when the log is truncated by the limit.
     */
    protected fun updateStatusBar(entryCount: Int, limit: Int) {
        if (entryCount < limit) {
            statusBar.isVisible = false
            return
        }
        statusBar.removeAll()
        statusBar.add(
            JBLabel(JujutsuBundle.message("log.status.truncated", entryCount, limit))
        )
        statusBar.add(
            HyperlinkLabel(JujutsuBundle.message("log.status.truncated.link")).apply {
                addHyperlinkListener {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, JujutsuBundle.message("settings.title"))
                }
            }
        )
        statusBar.isVisible = true
    }

    /**
     * Shows a plain one-line message in the status bar strip below the table, replacing whatever
     * was there (e.g. the truncation notice). Used by [UnifiedJujutsuLogPanel] to report the
     * outcome of a whole-repo search (jj-idea-lpbv).
     */
    protected fun showStatusMessage(text: String) {
        statusBar.removeAll()
        statusBar.add(JBLabel(text))
        statusBar.isVisible = true
    }

    /**
     * Hides the status bar strip. Called before a whole-repo search's result message would
     * otherwise linger.
     */
    protected fun clearStatusMessage() {
        statusBar.removeAll()
        statusBar.isVisible = false
    }

    /**
     * Wraps the whole table+details area in an outer horizontal splitter with [component] as its
     * first (left) child, initially shown or hidden per [initiallyVisible], with [gutter] pinned
     * outside the splitter (west of it) and always visible regardless of that state. Used by
     * [in.kkkev.jjidea.ui.log.UnifiedJujutsuLogPanel] to host the bookmarks panel (jj-idea-b2ae)
     * to the left of the log table, git4idea-Branches-dashboard style — [gutter] is the
     * discoverable click target that survives collapsing [component], analogous to the multi-repo
     * root gutter's always-visible strip ([in.kkkev.jjidea.ui.log.JujutsuRootGutterRenderer]).
     *
     * [proportionKey] is a global (not per-window) [com.intellij.ide.util.PropertiesComponent] key for the splitter
     * width, the same scheme git4idea's Branches dashboard uses for its own splitter.
     *
     * Must be called after this panel's `init` has run (so [centerContainer] already holds
     * [splitter]) — i.e. from a subclass's own `init` block, never from this class's constructor.
     * Returns the outer splitter so the caller can toggle [OnePixelSplitter.firstComponent]
     * between `null` and [component] later.
     */
    protected fun installLeftComponent(
        component: JComponent,
        gutter: JComponent,
        proportionKey: String,
        initiallyVisible: Boolean
    ): OnePixelSplitter {
        remove(centerContainer)
        val splitter = OnePixelSplitter(false, proportionKey, 0.25f).apply {
            firstComponent = if (initiallyVisible) component else null
            secondComponent = centerContainer
        }
        val wrapper = JPanel(BorderLayout()).apply {
            add(gutter, BorderLayout.WEST)
            add(splitter, BorderLayout.CENTER)
        }
        add(wrapper, BorderLayout.CENTER)
        return splitter
    }

    /**
     * Toggle the details panel position between right and bottom.
     */
    private fun toggleDetailsPosition() {
        detailsOnRight = !detailsOnRight

        // Remove old splitter
        centerContainer.remove(splitter)

        // Create new splitter with new orientation
        val tablePanel = createTablePanel()
        splitter = createSplitter(tablePanel, detailsOnRight)

        // Add new splitter
        centerContainer.add(splitter, BorderLayout.CENTER)

        // Refresh UI
        centerContainer.revalidate()
        centerContainer.repaint()

        onConfigChanged()

        log.info("Details panel position toggled to ${if (detailsOnRight) "right" else "bottom"}")
    }

    /**
     * Refresh the file history. Clears any active navigation-expansion so the log returns
     * to the configured revset/limit view.
     */
    fun refresh() {
        log.info("Refreshing log entries")
        logTable.clearNavigation()
        dataLoader.clearExpansions()
        dataLoader.refresh()
    }
}

@Suppress("ComponentNotRegistered")
open class PopupActionGroup(shortNameResourceKey: String, vararg actions: AnAction) :
    DefaultActionGroup(JujutsuBundle.message(shortNameResourceKey), actions.toList()),
    ActionUpdateThreadAware.Recursive {
    init {
        getTemplatePresentation().isPopupGroup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
