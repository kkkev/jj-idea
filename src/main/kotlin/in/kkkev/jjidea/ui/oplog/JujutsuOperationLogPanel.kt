package `in`.kkkev.jjidea.ui.oplog

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter
import `in`.kkkev.jjidea.ui.log.JujutsuRootFilterComponent
import `in`.kkkev.jjidea.ui.log.JujutsuRootGutterRenderer
import `in`.kkkev.jjidea.ui.services.performUndo
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import kotlinx.datetime.Instant
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableColumn

/**
 * The "Operations" tab: a browsable `jj op log` for the repositories picked by the roots filter, merged
 * newest first (jj-idea-aii0, jj-idea-qz9d),
 * modelled on IntelliJ's Local History. The primary row action is **Revert** (`jj op revert`),
 * which undoes exactly one operation and composes safely with later ones; **Restore** (`jj op
 * restore`) is a reset, so it lives in its own menu section behind a confirmation. See
 * docs/design/undo-support-roadmap.md for why.
 *
 * Refreshes on the state model's `logRefresh` (the same funnel the log uses); [OperationLogLoader]
 * turns a refresh with an unchanged head into a single `-n1` probe.
 */
internal class JujutsuOperationLogPanel(private val project: Project, initialRepo: JujutsuRepository) :
    JPanel(BorderLayout()),
    Disposable {
    private fun repos() = project.stateModel.initialisedRepositories.value.values.toList()
    private val loaders = mutableMapOf<JujutsuRepository, OperationLogLoader>()

    private var perRepo: Map<JujutsuRepository, List<OperationEntry>> = emptyMap()
    private var showSnapshots = false
    private var shown: List<OperationRow> = emptyList()

    /** Root gutter state, like the log's: expanded shows the repo name, collapsed a thin coloured strip. */
    private var gutterExpanded = true
    private lateinit var gutterColumn: TableColumn
    private var gutterVisible = false

    private val rootFilter = JujutsuRootFilterComponent(::repos)

    private val model = object : AbstractTableModel() {
        private val columns = listOf("description", "command", "time", "gutter")

        override fun getRowCount() = shown.size

        override fun getColumnCount() = columns.size

        override fun getColumnName(column: Int) =
            if (column == GUTTER_MODEL_COLUMN) "" else JujutsuBundle.message("oplog.column.${columns[column]}")

        override fun getValueAt(row: Int, column: Int): Any {
            val shownRow = shown[row]
            return when (column) {
                0 -> shownRow.entry.title()
                GUTTER_MODEL_COLUMN -> shownRow
                1 -> shownRow.entry.args.removePrefix("args: ")
                else -> shownRow.entry.time
            }
        }
    }

    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setShowGrid(false)
        emptyText.text = JujutsuBundle.message("oplog.empty")
        // Time is last, formatted exactly like the log's Date column (Today/Yesterday/locale date).
        columnModel.getColumn(TIME_COLUMN).apply {
            cellRenderer = TimeCellRenderer()
            preferredWidth = JBUI.scale(TIME_COLUMN_WIDTH)
            maxWidth = JBUI.scale(TIME_COLUMN_WIDTH * 2)
        }
    }

    private fun selected(): OperationRow? = table.selectedRow.takeIf { it >= 0 }?.let { shown.getOrNull(it) }

    init {
        installGutter()
        add(buildNorth(), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        PopupHandler.installPopupMenu(table, buildRowActions(), "JujutsuOperationLogPopup")
        project.stateModel.logRefresh.connect(this) { refresh() }
        // Start focused on the repo the tab was opened from; any later change reloads what is newly shown.
        rootFilter.initialize {
            applyFilter()
            refresh()
        }
        if (repos().size > 1) rootFilter.select(setOf(initialRepo)) else refresh()
    }

    private fun buildNorth(): JPanel {
        val north = JPanel(BorderLayout())
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction(
                JujutsuBundle.message("oplog.action.refresh"),
                null,
                AllIcons.Actions.Refresh
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT

                override fun actionPerformed(e: AnActionEvent) = refresh()
            })
            add(object : ToggleAction(
                JujutsuBundle.message("oplog.action.snapshots"),
                null,
                AllIcons.Actions.ToggleVisibility
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT

                override fun isSelected(e: AnActionEvent) = showSnapshots

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showSnapshots = state
                    applyFilter()
                }
            })
            add(Separator.getInstance())
            add(buildRowActions())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("JujutsuOperationLogToolbar", group, true)
        toolbar.targetComponent = table
        north.add(toolbar.component, BorderLayout.CENTER)
        rootFilter.initUi()
        rootFilter.isVisible = repos().size > 1
        north.add(rootFilter, BorderLayout.EAST)
        north.border = JBUI.Borders.empty()
        return north
    }

    /** Revert (primary), then - separated, labelled as a reset - Restore. Shared by toolbar and popup. */
    private fun buildRowActions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction(
            JujutsuBundle.message("oplog.action.revert"),
            JujutsuBundle.message("oplog.action.revert.description"),
            AllIcons.Actions.Rollback
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) = applyState(e, revertState(selected()?.entry))

            override fun actionPerformed(e: AnActionEvent) {
                val row = selected() ?: return
                performUndo(project, row.repo, row.entry.id)
            }
        })
        add(Separator.getInstance())
        add(object : DumbAwareAction(
            JujutsuBundle.message("oplog.action.restore"),
            JujutsuBundle.message("oplog.action.restore.description"),
            null
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) = applyState(
                e,
                selected()?.let { restoreState(it.entry, perRepo[it.repo]?.firstOrNull()) }
                    ?: restoreState(null, null)
            )

            override fun actionPerformed(e: AnActionEvent) {
                val row = selected() ?: return
                restore(row)
            }
        })
    }

    private fun applyState(e: AnActionEvent, state: RowActionState) {
        e.presentation.isEnabled = state.enabled
        state.disabledReason?.let { e.presentation.description = it }
    }

    private fun restore(row: OperationRow) {
        val later = perRepo[row.repo].orEmpty().indexOfFirst { it.id == row.entry.id }.coerceAtLeast(0)
        val choice = Messages.showYesNoDialog(
            project,
            restoreConfirmMessage(row.entry, later),
            JujutsuBundle.message("oplog.restore.confirm.title"),
            JujutsuBundle.message("oplog.action.restore.confirm"),
            Messages.getCancelButton(),
            Messages.getWarningIcon()
        )
        if (choice != Messages.YES) return
        restoreOperation(row.repo, row.entry.id)
    }

    private fun restoreOperation(target: JujutsuRepository, id: OperationId) {
        target.createCommand { opRestore(id) }
            .onSuccess { invalidate(select = WorkingCopy, vfsChanged = true) }
            .onFailure { tellUser("notification.oplog.restore.error") }
            .executeAsync()
    }

    private fun refresh() {
        val selection = rootFilter.selection()
        val all = repos()
        runInBackground {
            val results = loadShown(all, selection) { loaders.getOrPut(it) { OperationLogLoader(it.commandExecutor) } }
            runLater {
                // A newer filter change supersedes this load; its own refresh will land.
                if (selection != rootFilter.selection()) return@runLater
                var changed = perRepo.keys != results.keys
                val next = results.mapNotNull { (repo, result) ->
                    when (result) {
                        is OperationLogLoader.Result.Loaded -> {
                            changed = changed || result.changed
                            repo to result.entries
                        }
                        is OperationLogLoader.Result.Failed -> {
                            if (perRepo.isEmpty()) table.emptyText.text = result.message
                            null
                        }
                    }
                }.toMap()
                updateGutterVisibility()
                if (changed) {
                    perRepo = next
                    applyFilter()
                }
            }
        }
    }

    /** Re-merges the loaded repos under the current filters, keeping the selected operation when still visible. */
    private fun applyFilter() {
        val keep = selected()?.let { it.repo to it.entry.id }
        shown = mergeOperations(perRepo, rootFilter.selection(), showSnapshots)
        model.fireTableDataChanged()
        keep?.let { (repo, id) -> shown.indexOfFirst { it.repo == repo && it.entry.id == id }.takeIf { it >= 0 } }
            ?.let { table.setRowSelectionInterval(it, it) }
    }

    override fun dispose() = Unit

    private fun rowRepo(row: Int) = shown.getOrNull(row)?.repo

    /** Same width rule as the log's gutter: a thin strip collapsed, or the longest repo name expanded. */
    private fun gutterWidth(): Int {
        if (!gutterExpanded) return JBUI.scale(GUTTER_COLLAPSED_WIDTH)
        val fm = table.getFontMetrics(table.font)
        return (repos().maxOfOrNull { fm.stringWidth(it.displayName) } ?: 0) + GUTTER_EXPANDED_PADDING
    }

    private fun applyGutterWidth() {
        val width = gutterWidth()
        gutterColumn.apply {
            minWidth = width
            maxWidth = width
            preferredWidth = width
            this.width = width
        }
        table.revalidate()
        table.repaint()
    }

    /** Makes the gutter the leftmost column; it is dropped again unless the project has several roots (like the log). */
    private fun installGutter() {
        gutterColumn = table.columnModel.getColumn(GUTTER_MODEL_COLUMN).apply {
            cellRenderer = JujutsuRootGutterRenderer(
                repoAt = { _, row -> rowRepo(row) },
                isExpanded = { gutterExpanded }
            )
            resizable = false
        }
        table.moveColumn(GUTTER_MODEL_COLUMN, 0)
        gutterVisible = true
        applyGutterWidth()
        updateGutterVisibility()
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val viewColumn = table.columnAtPoint(e.point)
                if (viewColumn >= 0 && table.convertColumnIndexToModel(viewColumn) == GUTTER_MODEL_COLUMN) {
                    gutterExpanded = !gutterExpanded
                    applyGutterWidth()
                    e.consume()
                }
            }
        })
    }

    private fun updateGutterVisibility() {
        val wanted = repos().size > 1
        if (wanted == gutterVisible) return
        if (wanted) {
            table.addColumn(gutterColumn)
            table.moveColumn(table.columnCount - 1, 0)
            applyGutterWidth()
        } else {
            table.removeColumn(gutterColumn)
        }
        gutterVisible = wanted
    }

    private class TimeCellRenderer : DefaultTableCellRenderer() {
        override fun setValue(value: Any?) {
            val instant = value as? Instant
            text = instant?.let(DateTimeFormatter::formatRelative).orEmpty()
            toolTipText = instant?.let(DateTimeFormatter::formatAbsolute)
        }
    }

    private companion object {
        const val TIME_COLUMN = 2
        const val GUTTER_MODEL_COLUMN = 3
        const val GUTTER_COLLAPSED_WIDTH = 8
        const val GUTTER_EXPANDED_PADDING = 16
        const val TIME_COLUMN_WIDTH = 140
    }
}
