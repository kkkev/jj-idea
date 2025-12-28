package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.FieldInplaceActionButtonLook
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.SearchFieldWithExtension
import `in`.kkkev.jjidea.JujutsuBundle
import java.awt.*
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableColumn

/**
 * Main panel for Jujutsu commit log UI.
 *
 * Layout:
 * - NORTH: Toolbar (refresh, filters)
 * - CENTER: Splitter with log table (top) and details panel (bottom)
 *
 * Built from scratch - no dependency on IntelliJ's VCS log UI.
 */
class JujutsuLogPanel(project: Project, root: VirtualFile) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(JujutsuLogPanel::class.java)

    // Column manager for controlling column visibility
    private val columnManager = JujutsuColumnManager()

    // Table showing commits
    private val logTable = JujutsuLogTable(columnManager)

    // Details panel showing selected commit info
    private val detailsPanel = JujutsuCommitDetailsPanel(project, root)

    // Splitter for table and details panel
    private var splitter: OnePixelSplitter

    // Details panel position (true = right, false = bottom)
    private var detailsOnRight = true

    // Data loader for background loading
    private val dataLoader = JujutsuLogDataLoader(project, root, logTable.logModel, logTable)

    // Filter options state
    private var useRegex = false
    private var matchCase = false
    private var matchWholeWords = false

    // Base search field with history
    private val searchTextField = SearchTextField(true).apply {
        textEditor.emptyText.text = JujutsuBundle.message("log.filter.text.placeholder")
        toolTipText = JujutsuBundle.message("log.filter.text.tooltip")

        // Listen for text changes
        textEditor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })

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
        dataLoader.loadCommits()

        log.info("JujutsuLogPanel initialized for root: ${root.path}")
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
     * @param tablePanel The panel containing the table and toolbar
     * @param horizontal True to position details on right, false for bottom
     */
    private fun createSplitter(tablePanel: JPanel, horizontal: Boolean) =
        OnePixelSplitter(!horizontal, if (horizontal) 0.7f else 0.7f).apply {
            firstComponent = tablePanel
            secondComponent = detailsPanel
        }

    private fun createToolbar() = JPanel(BorderLayout()).apply {
        // Create main toolbar with actions on the right
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "JujutsuLogToolbar",
            createActionGroup(),
            true
        )
        toolbar.targetComponent = this@JujutsuLogPanel

        add(filterField, BorderLayout.WEST)
        add(toolbar.component, BorderLayout.EAST)
    }

    /**
     * Create the filter field using SearchFieldWithExtension pattern.
     * Following IntelliJ's VCS log approach with ActionToolbar inside the search field.
     */
    private fun createFilterField(): SearchFieldWithExtension {
        // Create action group with toggle buttons
        val filterActionsGroup = DefaultActionGroup().apply {
            add(RegexFilterAction())
            add(MatchCaseAction())
            add(MatchWholeWordsAction())
        }

        // Create custom toolbar that uses toggle-aware action buttons
        val toolbar = object : ActionToolbarImpl(
            "JujutsuLogFilter",
            filterActionsGroup,
            true // horizontal
        ) {
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
     * Refresh action - reload commits.
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
     * Toggle regex filter mode.
     */
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

    /**
     * Toggle match case in filter.
     */
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

    /**
     * Toggle match whole words in filter.
     */
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
                JujutsuBundle.message("log.column.toggle.changeid"),
                getter = { columnManager.showChangeIdColumn },
                setter = { columnManager.showChangeIdColumn = it }
            ))
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.description"),
                getter = { columnManager.showDescriptionColumn },
                setter = { columnManager.showDescriptionColumn = it }
            ))
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.decorations"),
                getter = { columnManager.showDecorationsColumn },
                setter = { columnManager.showDecorationsColumn = it }
            ))
        addSeparator()
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.author"),
                getter = { columnManager.showAuthorColumn },
                setter = { columnManager.showAuthorColumn = it }
            ))
        add(
            ToggleColumnAction(
                JujutsuBundle.message("log.column.toggle.date"),
                getter = { columnManager.showDateColumn },
                setter = { columnManager.showDateColumn = it }
            ))
    }

    /**
     * Toggle action for a single column.
     */
    private inner class ToggleColumnAction(
        columnName: String,
        private val getter: () -> Boolean,
        private val setter: (Boolean) -> Unit
    ) : ToggleAction(columnName) {

        override fun isSelected(e: AnActionEvent) = getter()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            setter(state)
            updateColumnVisibility()
            logTable.updateGraph(logTable.graphNodes) // Refresh rendering
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
                val column = existingColumns.find { it.modelIndex == idx }
                    ?: TableColumn(idx)

                columnModel.addColumn(column)
            }
        }

        // Re-install renderers
        logTable.installRenderers()
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

        log.info("Details panel position toggled to ${if (detailsOnRight) "right" else "bottom"}")
    }

    /**
     * Apply the current filter text to the table.
     */
    private fun applyFilter() {
        val filterText = searchTextField.text
        log.info("Applying filter: '$filterText' (regex=$useRegex, matchCase=$matchCase, wholeWords=$matchWholeWords)")
        logTable.logModel.setFilter(filterText, useRegex, matchCase, matchWholeWords)
    }

    override fun dispose() {
        log.info("JujutsuLogPanel disposed")
        detailsPanel.dispose()
        // Other cleanup will happen automatically
    }

    /**
     * Custom ActionButton that shows visual feedback (different background) when selected.
     * Based on IntelliJ's VCS log implementation.
     */
    private class ToggleAwareActionButton(action: AnAction, presentation: Presentation) :
        ActionButton(action, presentation, "JujutsuLogFilter", ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {

        init {
            updateIcon()
        }

        override fun getPopState(): Int =
            if (isSelected) SELECTED else super.getPopState()

        override fun getIcon(): Icon {
            if (isEnabled && isSelected) {
                val selectedIcon = myPresentation.selectedIcon
                if (selectedIcon != null) return selectedIcon
            }
            return super.getIcon()
        }
    }

    companion object {
        /**
         * Create a new log panel for the given project and root.
         */
        fun create(project: Project, root: VirtualFile) =
            JujutsuLogPanel(project, root)
    }
}
