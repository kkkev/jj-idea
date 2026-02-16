package `in`.kkkev.jjidea.ui.rebase

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.log.CommitGraphBuilder
import `in`.kkkev.jjidea.ui.log.GraphNode
import `in`.kkkev.jjidea.ui.log.JujutsuGraphAndDescriptionRenderer
import `in`.kkkev.jjidea.ui.log.JujutsuLogTableModel
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Preview panel showing a simulated post-rebase commit graph.
 *
 * Source commits are highlighted green, destination commits blue.
 * Below the graph, a text summary describes the operation.
 *
 * Driven entirely by [setEntries] and [update] calls — no data in constructor.
 */
class RebasePreviewPanel : JPanel(BorderLayout()) {
    private val tableModel = JujutsuLogTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        isEnabled = false // Read-only preview
    }
    private val descriptionLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
    }

    private var graphNodes: Map<ChangeId, GraphNode> = emptyMap()
    private var allEntries: List<LogEntry> = emptyList()

    init {
        val scrollPane = JBScrollPane(table).apply { border = JBUI.Borders.empty() }
        add(scrollPane, BorderLayout.CENTER)
        add(descriptionLabel, BorderLayout.SOUTH)
        // Hide extra columns once — setEntries/fireTableDataChanged don't recreate them
        hideExtraColumns()
    }

    /**
     * Set the full list of entries available for simulation.
     * Called once from the dialog when entries are loaded.
     */
    fun setEntries(entries: List<LogEntry>) {
        allEntries = entries
    }

    /**
     * Update the preview by simulating the rebase and rebuilding the graph.
     */
    fun update(
        sourceEntries: List<LogEntry>,
        destinationIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode
    ) {
        val simulation = RebaseSimulator.simulate(
            allEntries,
            sourceEntries,
            destinationIds,
            sourceMode,
            destinationMode
        )

        tableModel.setEntries(simulation.entries)

        val baseNodes = CommitGraphBuilder().buildGraph(simulation.entries)
        graphNodes = baseNodes.mapValues { (id, node) ->
            when (id) {
                in simulation.sourceIds -> node.copy(highlightColor = JujutsuColors.SOURCE_HIGHLIGHT)
                in simulation.destinationIds -> node.copy(highlightColor = JujutsuColors.DESTINATION_HIGHLIGHT)
                else -> node
            }
        }

        updateRenderer()
        updateDescription(simulation.sourceIds, destinationIds, sourceMode, destinationMode)
    }

    private fun updateRenderer() {
        for (i in 0 until table.columnModel.columnCount) {
            val column = table.columnModel.getColumn(i)
            if (column.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) {
                column.cellRenderer = JujutsuGraphAndDescriptionRenderer(graphNodes)
                break
            }
        }
    }

    private fun updateDescription(
        sourceIds: Set<ChangeId>,
        destinationIds: Set<ChangeId>,
        sourceMode: RebaseSourceMode,
        destinationMode: RebaseDestinationMode
    ) {
        if (sourceIds.isEmpty() || destinationIds.isEmpty()) {
            descriptionLabel.text = ""
            return
        }

        val sourceText = if (sourceIds.size == 1) {
            sourceIds.first().short
        } else {
            "${sourceIds.size} changes"
        }

        val modeText = when (sourceMode) {
            RebaseSourceMode.REVISION -> ""
            RebaseSourceMode.SOURCE -> " and descendants"
            RebaseSourceMode.BRANCH -> " (whole branch)"
        }

        val destText = destinationIds.joinToString(", ") { findDisplayName(it) }

        val operationText = when (destinationMode) {
            RebaseDestinationMode.ONTO -> "onto $destText"
            RebaseDestinationMode.INSERT_AFTER -> "insert after $destText"
            RebaseDestinationMode.INSERT_BEFORE -> "insert before $destText"
        }

        descriptionLabel.text = buildString {
            append("<html>")
            append("<font color='${colorHex(JujutsuColors.SOURCE_HIGHLIGHT)}'>&#9632;</font> source &nbsp; ")
            append(
                "<font color='${colorHex(
                    JujutsuColors.DESTINATION_HIGHLIGHT
                )}'>&#9632;</font> destination &nbsp;&nbsp; "
            )
            append("$sourceText$modeText → $operationText")
            append("</html>")
        }
    }

    private fun findDisplayName(id: ChangeId): String {
        val entry = allEntries.find { it.id == id }
        return if (entry != null && entry.bookmarks.isNotEmpty()) {
            entry.bookmarks.first().name
        } else {
            id.short
        }
    }

    private fun colorHex(color: Color) = String.format("#%06x", color.rgb and 0xFFFFFF)

    private fun hideExtraColumns() {
        // Remove columns in reverse order to preserve indices
        val columnsToRemove = (table.columnCount - 1 downTo 0)
            .filter { it != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }

        for (colIndex in columnsToRemove) {
            if (colIndex < table.columnModel.columnCount) {
                table.removeColumn(table.columnModel.getColumn(colIndex))
            }
        }
    }
}
