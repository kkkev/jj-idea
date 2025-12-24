package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ScrollPaneFactory
import `in`.kkkev.jjidea.JujutsuBundle
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Main panel for Jujutsu commit log UI.
 *
 * Layout:
 * - NORTH: Toolbar (refresh, filters - Phase 4)
 * - CENTER: Log table with commits
 * - SOUTH: Details pane (Phase 4)
 *
 * Built from scratch - no dependency on IntelliJ's VCS log UI.
 */
class JujutsuLogPanel(
    private val project: Project,
    private val root: VirtualFile
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(JujutsuLogPanel::class.java)

    // Column manager for controlling column visibility
    private val columnManager = JujutsuColumnManager()

    // Table showing commits
    private val logTable = JujutsuLogTable(columnManager)

    // Data loader for background loading
    private val dataLoader = JujutsuLogDataLoader(project, root, logTable.logModel, logTable)

    init {
        // Install custom renderers
        logTable.installRenderers()

        // Set up initial column visibility
        updateColumnVisibility()

        // Add table in scroll pane
        val scrollPane = ScrollPaneFactory.createScrollPane(logTable)
        add(scrollPane, BorderLayout.CENTER)

        // Add toolbar
        add(createToolbar(), BorderLayout.NORTH)

        // Load initial data
        dataLoader.loadCommits()

        log.info("JujutsuLogPanel initialized for root: ${root.path}")
    }

    private fun createToolbar(): JPanel {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "JujutsuLogToolbar",
            createActionGroup(),
            true
        )

        toolbar.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.CENTER)
        }
    }

    private fun createActionGroup() = DefaultActionGroup().apply {
        add(RefreshAction())
        add(ColumnsAction())
        // Phase 4: Add filter, search, etc.
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

    private fun createColumnsActionGroup() = DefaultActionGroup().apply {
        add(ToggleColumnAction(JujutsuBundle.message("log.column.toggle.changeid"),
            getter = { columnManager.showChangeIdColumn },
            setter = { columnManager.showChangeIdColumn = it }
        ))
        add(ToggleColumnAction(JujutsuBundle.message("log.column.toggle.description"),
            getter = { columnManager.showDescriptionColumn },
            setter = { columnManager.showDescriptionColumn = it }
        ))
        add(ToggleColumnAction(JujutsuBundle.message("log.column.toggle.decorations"),
            getter = { columnManager.showDecorationsColumn },
            setter = { columnManager.showDecorationsColumn = it }
        ))
        addSeparator()
        add(ToggleColumnAction(JujutsuBundle.message("log.column.toggle.author"),
            getter = { columnManager.showAuthorColumn },
            setter = { columnManager.showAuthorColumn = it }
        ))
        add(ToggleColumnAction(JujutsuBundle.message("log.column.toggle.date"),
            getter = { columnManager.showDateColumn },
            setter = { columnManager.showDateColumn = it }
        ))
    }

    /**
     * Toggle action for a single column.
     */
    private inner class ToggleColumnAction(
        private val columnName: String,
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
        val existingColumns = mutableListOf<javax.swing.table.TableColumn>()
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
                    ?: javax.swing.table.TableColumn(idx)

                columnModel.addColumn(column)
            }
        }

        // Re-install renderers
        logTable.installRenderers()
    }

    override fun dispose() {
        log.info("JujutsuLogPanel disposed")
        // Cleanup will happen automatically
    }

    companion object {
        /**
         * Create a new log panel for the given project and root.
         */
        fun create(project: Project, root: VirtualFile) =
            JujutsuLogPanel(project, root)
    }
}
