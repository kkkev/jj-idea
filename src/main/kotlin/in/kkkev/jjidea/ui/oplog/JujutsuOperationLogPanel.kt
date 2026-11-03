package `in`.kkkev.jjidea.ui.oplog

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.ManagedActions
import `in`.kkkev.jjidea.actions.add
import `in`.kkkev.jjidea.actions.undo.RedoAction
import `in`.kkkev.jjidea.actions.undo.UndoAction
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter
import `in`.kkkev.jjidea.ui.log.JujutsuRootFilterComponent
import `in`.kkkev.jjidea.ui.log.JujutsuRootGutterRenderer
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
    Disposable,
    UiDataProvider,
    OperationLogView {
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
                1 -> shownRow.entry.command()
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
        PopupHandler.installPopupMenu(table, buildPopupActions(), "JujutsuOperationLogPopup")
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
            add(ManagedActions["Jujutsu.Oplog.Refresh"])
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
            add(ManagedActions["Jujutsu.Oplog.GoToCurrent"])
            add(Separator.getInstance())
            // jj-idea-x30x: repository-wide Undo/Redo of the head operation, ahead of the per-row actions.
            // Acts on the selected row's repository, else the only repository being shown.
            val shownRepo = { selected()?.repo ?: perRepo.keys.singleOrNull() }
            add(UndoAction(shownRepo))
            add(RedoAction(shownRepo))
            add(Separator.getInstance())
            add(revertRestoreActions())
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
    private fun revertRestoreActions() = DefaultActionGroup().apply {
        add(ManagedActions["Jujutsu.Oplog.Revert"])
        add(Separator.getInstance())
        add(ManagedActions["Jujutsu.Oplog.Restore"])
    }

    /** The popup: the toolbar's row actions, then the copy actions, then navigation. */
    private fun buildPopupActions() = DefaultActionGroup().apply {
        add(revertRestoreActions())
        add(Separator.getInstance())
        add(ManagedActions["Jujutsu.Oplog.CopyId"])
        add(ManagedActions["Jujutsu.Oplog.CopyDescription"])
        add(ManagedActions["Jujutsu.Oplog.CopyCommand"])
        add(Separator.getInstance())
        add(ManagedActions["Jujutsu.Oplog.GoToCurrent"])
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[JujutsuDataKeys.OPERATION_LOG] = this
        selected()?.let { sink[JujutsuDataKeys.OPERATION_ROW] = it }
    }

    override fun head(repo: JujutsuRepository) = perRepo[repo]?.firstOrNull()

    override fun laterCount(row: OperationRow) =
        perRepo[row.repo].orEmpty().indexOfFirst { it.id == row.entry.id }.coerceAtLeast(0)

    override fun goToHeadState() = goToHeadState(shown, selected())

    override fun goToHead() {
        val index = headRow(shown, selected())?.let(shown::indexOf)?.takeIf { it >= 0 } ?: return
        table.setRowSelectionInterval(index, index)
        table.scrollRectToVisible(table.getCellRect(index, 0, true))
    }

    override fun refresh() {
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
