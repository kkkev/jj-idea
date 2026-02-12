package `in`.kkkev.jjidea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionButtonLook
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl
import com.intellij.openapi.actionSystem.impl.FieldInplaceActionButtonLook
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.SearchFieldWithExtension
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.ui.log.*
import `in`.kkkev.jjidea.vcs.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.vcs.actions.PopupActionGroup
import java.awt.BorderLayout
import java.awt.Dimension
import java.util.function.Supplier
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableColumn
import kotlin.reflect.KMutableProperty1

abstract class CommitTablePanel<D>(
    project: Project,
    private val toolbarPlace: String,
    dataLoaderFactory: (CommitTablePanel<D>) -> DataLoader,
    columnManagerConfig: JujutsuColumnManager.() -> Unit = {}
) : JPanel(BorderLayout()), Disposable {
    private val log = Logger.getInstance(javaClass)

    val columnManager = JujutsuColumnManager()

    val logTable = JujutsuLogTable(project, columnManager)

    val dataLoader = dataLoaderFactory.invoke(this)

    // Details panel showing selected commit info
    val detailsPanel = JujutsuCommitDetailsPanel(project)

    // Splitter for table and details panel
    var splitter: OnePixelSplitter

    // Details panel position (true = right, false = bottom)
    var detailsOnRight = true

    // Filter panel using SearchFieldWithExtension
    private val filterField: SearchFieldWithExtension

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

    // Filter options state
    private var useRegex = false
    private var matchCase = false
    private var matchWholeWords = false

    init {
        // Create filter field with extension toolbar
        filterField = createFilterField()

        // Apply column manager configuration before installing renderers
        columnManager.apply(columnManagerConfig)

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
        // Create action group with toggle buttons
        val filterActionsGroup = BackgroundActionGroup(
            BinaryAction("regex", AllIcons.Actions.RegexHovered, CommitTablePanel<D>::useRegex),
            BinaryAction("matchcase", AllIcons.Actions.MatchCase, CommitTablePanel<D>::matchCase),
            BinaryAction("words", AllIcons.Actions.Words, CommitTablePanel<D>::matchWholeWords)
        )

        // Create custom toolbar that uses toggle-aware action buttons
        val toolbar = object : ActionToolbarImpl("JujutsuFileHistoryFilter", filterActionsGroup, true, false, true) {
            override fun createToolbarButton(
                action: AnAction,
                look: ActionButtonLook?,
                place: String,
                presentation: Presentation,
                minimumSize: Supplier<out Dimension?>
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

    /**
     * Create a panel containing the table with its toolbar.
     */
    private fun createTablePanel() = JPanel(BorderLayout()).apply {
        // Add toolbar at the top
        add(createToolbar(), BorderLayout.NORTH)

        // Add table scroll pane in the center
        add(ScrollPaneFactory.createScrollPane(logTable), BorderLayout.CENTER)
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

        add(leftPanel, BorderLayout.WEST)
        add(toolbar.component, BorderLayout.EAST)
    }

    open fun createOtherFilterComponents(filterPanel: JPanel) {}

    /**
     * Create the filter components panel (Root, Reference, Author, Date, Paths).
     */
    private fun createFilterComponents() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)

        createOtherFilterComponents(this)

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
        // Note: Paths filter is omitted in unified mode as it requires a single root
    }

    private fun createActionGroup() = BackgroundActionGroup(RefreshAction(), ColumnsAction(), DetailsPositionAction())

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

    private inner class BinaryAction(
        messageKeySuffix: String, icon: Icon, private val property: KMutableProperty1<CommitTablePanel<D>, Boolean>
    ) : ToggleAction(
        JujutsuBundle.message("log.filter.$messageKeySuffix"),
        JujutsuBundle.message("log.filter.$messageKeySuffix.tooltip"),
        icon
    ) {
        override fun isSelected(e: AnActionEvent) = property.getter.invoke(this@CommitTablePanel)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            property.setter.invoke(this@CommitTablePanel, state)
            applyFilter()
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

    abstract fun onDataLoaded(newData: D)

    abstract fun updateTableStuff()

    inner class ToggleColumnAction(
        resourceKeySuffix: String,
        private val property: KMutableProperty1<JujutsuColumnManager, Boolean>,
    ) : ToggleAction(JujutsuBundle.message("log.column.toggle.$resourceKeySuffix")) {
        override fun isSelected(e: AnActionEvent) = property.getter.invoke(columnManager)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            property.setter.invoke(columnManager, state)
            updateTableStuff()
        }
    }

    fun createColumnsActionGroup() = DefaultActionGroup().apply {
        addAction(ToggleColumnAction("status", JujutsuColumnManager::showStatusColumn))
        addAction(ToggleColumnAction("changeid", JujutsuColumnManager::showChangeIdColumn))
        addAction(ToggleColumnAction("description", JujutsuColumnManager::showDescriptionColumn))
        addAction(ToggleColumnAction("decorations", JujutsuColumnManager::showDecorationsColumn))
        addSeparator()
        addAction(ToggleColumnAction("author", JujutsuColumnManager::showAuthorColumn))
        addAction(ToggleColumnAction("committer", JujutsuColumnManager::showCommitterColumn))
        addAction(ToggleColumnAction("date", JujutsuColumnManager::showDateColumn))
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
     * Refresh the file history.
     */
    fun refresh() {
        log.info("Refreshing log entries")
        dataLoader.refresh()
    }
}
