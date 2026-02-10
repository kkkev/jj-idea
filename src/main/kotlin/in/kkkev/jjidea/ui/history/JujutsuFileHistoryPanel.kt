package `in`.kkkev.jjidea.ui.history

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.FieldInplaceActionButtonLook
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.SearchFieldWithExtension
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.log.*
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableColumn

/**
 * Panel for displaying file history with the same styling as the custom log view.
 *
 * Reuses components from the log panel:
 * - JujutsuLogTable (with showGraphColumn = false)
 * - JujutsuLogTableModel
 * - JujutsuCommitDetailsPanel
 * - All standard renderers
 *
 * Simplified toolbar: refresh, search (no paths/root filters since this is single-file).
 */
class JujutsuFileHistoryPanel(project: Project, filePath: FilePath, repo: JujutsuRepository) :
    JPanel(BorderLayout()), Disposable {
    private val log = Logger.getInstance(javaClass)

    // Column manager configured for file history (no graph column)
    private val columnManager = JujutsuColumnManager().apply {
        showGraphColumn = false
        // Show change ID and description as separate columns since there's no graph
        showChangeIdColumn = true
        showDescriptionColumn = true
    }

    // Table showing commits (reuses JujutsuLogTable)
    private val logTable = JujutsuLogTable(project, columnManager)

    // Details panel showing selected commit info (reuses JujutsuCommitDetailsPanel)
    private val detailsPanel = JujutsuCommitDetailsPanel(project)

    // Splitter for table and details panel
    private var splitter: OnePixelSplitter

    // Details panel position (true = right, false = bottom)
    private var detailsOnRight = true

    // Data loader for file history
    private val dataLoader = JujutsuFileHistoryDataLoader(repo, filePath, logTable.logModel)

    // Filter options state
    private var useRegex = false
    private var matchCase = false
    private var matchWholeWords = false

    // Base search field with history
    private val searchTextField = SearchTextField(true).apply {
        textEditor.emptyText.text = JujutsuBundle.message("log.filter.text.placeholder")
        toolTipText = JujutsuBundle.message("log.filter.text.tooltip")

        // Listen for text changes
        textEditor.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent?) = applyFilter()
                override fun removeUpdate(e: DocumentEvent?) = applyFilter()
                override fun changedUpdate(e: DocumentEvent?) = applyFilter()
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

    // Filter panel using SearchFieldWithExtension
    private val filterField: SearchFieldWithExtension

    init {
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

        // Wire table selection to details panel
        logTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                detailsPanel.showCommit(logTable.selectedEntry)
            }
        }

        // Load initial data
        dataLoader.loadHistory()

        log.info("JujutsuFileHistoryPanel initialized for file: ${filePath.name}")
    }

    /**
     * Create a panel containing the table with its toolbar.
     */
    private fun createTablePanel() = JPanel(BorderLayout()).apply {
        // Add toolbar at the top
        add(createToolbar(), BorderLayout.NORTH)

        // Add table scroll pane in the center
        add(ScrollPaneFactory.createScrollPane(logTable), BorderLayout.CENTER)
    }

    /**
     * Create a splitter with the given orientation.
     */
    private fun createSplitter(tablePanel: JPanel, horizontal: Boolean) =
        OnePixelSplitter(!horizontal, if (horizontal) 0.7f else 0.7f).apply {
            firstComponent = tablePanel
            secondComponent = detailsPanel
        }

    private fun createToolbar() =
        JPanel(BorderLayout()).apply {
            // Create left-side panel with text filter
            val leftPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(filterField)
                add(Box.createHorizontalStrut(5))
                add(createFilterComponents())
            }

            // Create main toolbar with actions on the right
            val toolbar = ActionManager.getInstance().createActionToolbar(
                "JujutsuFileHistoryToolbar",
                createActionGroup(),
                true
            )
            toolbar.targetComponent = this@JujutsuFileHistoryPanel

            add(leftPanel, BorderLayout.WEST)
            add(toolbar.component, BorderLayout.EAST)
        }

    /**
     * Create the filter components panel (Reference, Author, Date).
     * Simplified compared to log panel - no root/paths filters.
     */
    private fun createFilterComponents() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)

        // Reference filter (bookmarks, tags, @)
        add(
            JujutsuReferenceFilterComponent(logTable.logModel).apply {
                initUi()
                initialize()
            }
        )
        add(Box.createHorizontalStrut(5))

        // Author filter
        add(
            JujutsuAuthorFilterComponent(logTable.logModel).apply {
                initUi()
                initialize()
            }
        )
        add(Box.createHorizontalStrut(5))

        // Date filter
        add(
            JujutsuDateFilterComponent(logTable.logModel).apply {
                initUi()
                initialize()
            }
        )
    }

    /**
     * Create the filter field using SearchFieldWithExtension pattern.
     */
    private fun createFilterField(): SearchFieldWithExtension {
        // Create action group with toggle buttons
        val filterActionsGroup = DefaultActionGroup().apply {
            add(RegexFilterAction())
            add(MatchCaseAction())
            add(MatchWholeWordsAction())
        }

        // Create custom toolbar that uses toggle-aware action buttons
        val toolbar = object : ActionToolbarImpl("JujutsuFileHistoryFilter", filterActionsGroup, true, false, true) {
            override fun createToolbarButton(
                action: AnAction,
                look: ActionButtonLook?,
                place: String,
                presentation: Presentation,
                minimumSize: java.util.function.Supplier<out Dimension?>
            ): ActionButton {
                val button = ToggleAwareActionButton(action, presentation)
                button.isFocusable = true
                applyToolbarLook(look, presentation, button)
                return button
            }
        }

        toolbar.apply {
            targetComponent = searchTextField.textEditor
            setCustomButtonLook(FieldInplaceActionButtonLook())
            isReservePlaceAutoPopupIcon = false
        }

        return SearchFieldWithExtension(toolbar.component, searchTextField)
    }

    private fun createActionGroup() = DefaultActionGroup().apply {
        add(RefreshAction())
        add(ColumnsAction())
        add(DetailsPositionAction())
    }

    /**
     * Refresh action - reload file history.
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
    private inner class ColumnsAction : DefaultActionGroup(JujutsuBundle.message("log.action.columns"), true) {
        init {
            templatePresentation.icon = AllIcons.Actions.Show
            add(createColumnsActionGroup())
        }
    }

    /**
     * Details position sub-menu - toggle between right and bottom.
     */
    private inner class DetailsPositionAction :
        DefaultActionGroup(JujutsuBundle.message("log.action.details.position"), true) {
        init {
            templatePresentation.icon = AllIcons.Actions.SplitHorizontally
            add(DetailsOnRightAction())
            add(DetailsOnBottomAction())
        }
    }

    private inner class DetailsOnRightAction : ToggleAction(JujutsuBundle.message("log.action.details.right")) {
        override fun isSelected(e: AnActionEvent) = detailsOnRight

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && !detailsOnRight) {
                toggleDetailsPosition()
            }
        }
    }

    private inner class DetailsOnBottomAction : ToggleAction(JujutsuBundle.message("log.action.details.bottom")) {
        override fun isSelected(e: AnActionEvent) = !detailsOnRight

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state && detailsOnRight) {
                toggleDetailsPosition()
            }
        }
    }

    private inner class RegexFilterAction : ToggleAction(
        JujutsuBundle.message("log.filter.regex"),
        JujutsuBundle.message("log.filter.regex.tooltip"),
        AllIcons.Actions.RegexHovered
    ) {
        override fun isSelected(e: AnActionEvent) = useRegex

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            useRegex = state
            applyFilter()
        }
    }

    private inner class MatchCaseAction : ToggleAction(
        JujutsuBundle.message("log.filter.matchcase"),
        JujutsuBundle.message("log.filter.matchcase.tooltip"),
        AllIcons.Actions.MatchCase
    ) {
        override fun isSelected(e: AnActionEvent) = matchCase

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            matchCase = state
            applyFilter()
        }
    }

    private inner class MatchWholeWordsAction : ToggleAction(
        JujutsuBundle.message("log.filter.words"),
        JujutsuBundle.message("log.filter.words.tooltip"),
        AllIcons.Actions.Words
    ) {
        override fun isSelected(e: AnActionEvent) = matchWholeWords

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            matchWholeWords = state
            applyFilter()
        }
    }

    private fun createColumnsActionGroup() = DefaultActionGroup().apply {
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.status"),
                getter = { columnManager.showStatusColumn },
                setter = { columnManager.showStatusColumn = it }
            )
        )
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.changeid"),
                getter = { columnManager.showChangeIdColumn },
                setter = { columnManager.showChangeIdColumn = it }
            )
        )
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.description"),
                getter = { columnManager.showDescriptionColumn },
                setter = { columnManager.showDescriptionColumn = it }
            )
        )
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.decorations"),
                getter = { columnManager.showDecorationsColumn },
                setter = { columnManager.showDecorationsColumn = it }
            )
        )
        addSeparator()
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.author"),
                getter = { columnManager.showAuthorColumn },
                setter = { columnManager.showAuthorColumn = it }
            )
        )
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.committer"),
                getter = { columnManager.showCommitterColumn },
                setter = { columnManager.showCommitterColumn = it }
            )
        )
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.date"),
                getter = { columnManager.showDateColumn },
                setter = { columnManager.showDateColumn = it }
            )
        )
    }

    private inner class ToggleColumnAction(
        columnName: String,
        private val getter: () -> Boolean,
        private val setter: (Boolean) -> Unit
    ) : ToggleAction(columnName) {
        override fun isSelected(e: AnActionEvent) = getter()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            setter(state)
            updateColumnVisibility()
        }
    }

    /**
     * Update table column visibility based on column manager settings.
     */
    private fun updateColumnVisibility() {
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

        log.info("Details panel position toggled to ${if (detailsOnRight) "right" else "bottom"}")
    }

    private fun applyFilter() {
        val filterText = searchTextField.text
        log.info("Applying filter: '$filterText' (regex=$useRegex, matchCase=$matchCase, wholeWords=$matchWholeWords)")
        logTable.logModel.setFilter(filterText, useRegex, matchCase, matchWholeWords)
    }

    /**
     * Refresh the file history.
     */
    fun refresh() {
        log.info("Refreshing file history panel")
        dataLoader.refresh()
    }

    override fun dispose() {
        log.info("JujutsuFileHistoryPanel disposed")
        detailsPanel.dispose()
    }
}
