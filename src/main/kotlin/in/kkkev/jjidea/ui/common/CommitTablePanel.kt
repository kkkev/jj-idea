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
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.SearchFieldWithExtension
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.ui.log.*
import `in`.kkkev.jjidea.util.runLater
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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

    // Details panel position (true = right, false = bottom)
    var detailsOnRight = initialDetailsOnRight

    // Filter panel using SearchFieldWithExtension
    private val filterField: SearchFieldWithExtension

    // Base search field with history
    protected val searchTextField = SearchTextField(true).apply {
        textEditor.emptyText.text = JujutsuBundle.message("log.filter.text.placeholder")
        toolTipText = JujutsuBundle.message("log.filter.text.tooltip")

        // Listen for text changes
        textEditor.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) {
                    applyFilter()
                    onConfigChanged()
                }

                override fun removeUpdate(e: DocumentEvent?) {
                    applyFilter()
                    onConfigChanged()
                }

                override fun changedUpdate(e: DocumentEvent?) {
                    applyFilter()
                    onConfigChanged()
                }
            }
        )

        // Add Enter key listener to save search to history
        textEditor.addActionListener {
            val text = text?.trim()
            if (!text.isNullOrEmpty()) {
                addCurrentTextToHistory()
            }
        }
    }

    // Filter options state
    var useRegex = false
    var matchCase = false
    var matchWholeWords = false

    // Status bar for truncation indicator
    private val statusBar = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isVisible = false
        border = JBUI.Borders.empty(2, 6)
    }

    init {
        Disposer.register(this, detailsPanel)

        // Create filter field with extension toolbar
        filterField = createFilterField()

        // Install custom renderers
        logTable.installRenderers()

        // Set up initial column visibility
        updateColumnVisibility()

        // Create table panel with toolbar
        val tablePanel = createTablePanel()

        // Create splitter with table panel and details panel
        splitter = createSplitter(tablePanel, detailsOnRight)

        add(splitter, BorderLayout.CENTER)

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
     * Create the filter field using SearchFieldWithExtension pattern.
     */
    private fun createFilterField(): SearchFieldWithExtension {
        val filterActionsGroup = BackgroundActionGroup(
            FilterToggleAction("regex", AllIcons.Actions.RegexHovered, CommitTablePanel<D>::useRegex),
            FilterToggleAction("matchcase", AllIcons.Actions.MatchCase, CommitTablePanel<D>::matchCase),
            FilterToggleAction("words", AllIcons.Actions.Words, CommitTablePanel<D>::matchWholeWords)
        )

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "JujutsuFileHistoryFilter",
            filterActionsGroup,
            true
        )
        toolbar.targetComponent = searchTextField.textEditor

        return SearchFieldWithExtension(toolbar.component, searchTextField).apply {
            // Allow the search field to shrink under width pressure, but not below a
            // sensible floor (matches IntelliJ's own VCS Log text filter field), so it
            // degrades gracefully instead of forcing the action toolbar off-screen.
            minimumSize = Dimension(JBUI.scale(150), preferredSize.height)
        }
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
            add(filterField)
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
        authorFilterComponent = JujutsuAuthorFilterComponent(logTable.logModel).apply {
            initUi()
            initialize()
        }
        filters += authorFilterComponent

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
            ActionManager.getInstance().getAction("Jujutsu.GitFetch"),
            ActionManager.getInstance().getAction("Jujutsu.GitPush"),
            Separator.create(),
            ColumnsAction(),
            DetailsPositionAction()
        )
    }

    /**
     * Hook called whenever any persisted UI state changes (column visibility, details position,
     * filter options, or search text). Subclasses override to persist to their [LogWindowConfig].
     */
    open fun onConfigChanged() {}

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
     * Columns sub-menu - show column visibility options.
     */
    private inner class ColumnsAction : PopupActionGroup("log.action.columns", createColumnsActionGroup()) {
        init {
            templatePresentation.icon = AllIcons.Actions.Show
        }
    }

    /**
     * Details position sub-menu - toggle between right and bottom.
     */
    private inner class DetailsPositionAction : PopupActionGroup(
        "log.action.details.position",
        DetailsOnRightAction(),
        DetailsOnBottomAction()
    ) {
        init {
            templatePresentation.icon = AllIcons.Actions.SplitHorizontally
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

    private inner class FilterToggleAction(
        messageKeySuffix: String, icon: Icon, private val property: KMutableProperty1<CommitTablePanel<D>, Boolean>
    ) : ToggleAction(
            JujutsuBundle.message("log.filter.$messageKeySuffix"),
            JujutsuBundle.message("log.filter.$messageKeySuffix.tooltip"),
            icon
        ) {
        override fun isSelected(e: AnActionEvent) = property.get(this@CommitTablePanel)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            property.set(this@CommitTablePanel, state)
            applyFilter()
            onConfigChanged()
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

    fun createColumnsActionGroup() = DefaultActionGroup().apply {
        addAction(ToggleColumnAction("status", JujutsuColumnManager::showStatus))
        addAction(ToggleColumnAction("changeid", JujutsuColumnManager::showChangeId))
        addAction(ToggleColumnAction("description", JujutsuColumnManager::showDescription))
        addAction(ToggleColumnAction("decorations", JujutsuColumnManager::showDecorations))
        addSeparator()
        addAction(ToggleColumnAction("author", JujutsuColumnManager::showAuthorColumn))
        addAction(ToggleColumnAction("committer", JujutsuColumnManager::showCommitterColumn))
        addAction(ToggleColumnAction("date", JujutsuColumnManager::showDateColumn))
        addSeparator()
        addAction(FitColumnsToWidthAction())
    }

    /**
     * Apply the current filter text to the table.
     */
    private fun applyFilter() {
        val filterText = searchTextField.text
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
     * Toggle the details panel position between right and bottom.
     */
    private fun toggleDetailsPosition() {
        detailsOnRight = !detailsOnRight

        // Remove old splitter
        remove(splitter)

        // Create new splitter with new orientation
        val tablePanel = createTablePanel()
        splitter = createSplitter(tablePanel, detailsOnRight)

        // Add new splitter
        add(splitter, BorderLayout.CENTER)

        // Refresh UI
        revalidate()
        repaint()

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
