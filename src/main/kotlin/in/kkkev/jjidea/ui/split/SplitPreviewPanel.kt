package `in`.kkkev.jjidea.ui.split

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.LogEntry
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
 * Preview panel showing a simulated post-split commit graph.
 *
 * Parent is highlighted green (SOURCE_HIGHLIGHT), child blue (DESTINATION_HIGHLIGHT).
 * Below the graph, a legend shows which color means what.
 *
 * Driven entirely by [setEntries] and [update] calls.
 */
class SplitPreviewPanel : JPanel(BorderLayout()) {
    private val tableModel = JujutsuLogTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        tableHeader.isVisible = false
        rowHeight = JBUI.scale(22)
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        isEnabled = false
    }
    private val legendLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
    }

    private var graphNodes: Map<ChangeKey, GraphNode> = emptyMap()
    private var allEntries: List<LogEntry> = emptyList()

    init {
        val scrollPane = JBScrollPane(table).apply { border = JBUI.Borders.empty() }
        add(scrollPane, BorderLayout.CENTER)
        add(legendLabel, BorderLayout.SOUTH)
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
     * Update the preview by simulating the split and rebuilding the graph.
     */
    fun update(
        sourceEntry: LogEntry,
        parallel: Boolean,
        parentLabel: String,
        childLabel: String,
        parentDescription: String? = null,
        childDescription: String? = null
    ) {
        val simulation = SplitSimulator.simulate(
            allEntries,
            sourceEntry,
            parallel,
            parentDescription,
            childDescription
        )

        tableModel.setEntries(simulation.entries)

        val baseNodes = CommitGraphBuilder().buildGraph(simulation.entries)
        graphNodes = baseNodes.mapValues { (key, node) ->
            when (key.revision) {
                simulation.parentId -> node.copy(highlightColor = JujutsuColors.SOURCE_HIGHLIGHT)
                simulation.childId -> node.copy(highlightColor = JujutsuColors.DESTINATION_HIGHLIGHT)
                else -> node
            }
        }

        updateRenderer()
        updateLegend(parentLabel, childLabel)
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

    private fun updateLegend(parentLabel: String, childLabel: String) {
        legendLabel.text = buildString {
            append("<html>")
            append("<font color='${colorHex(JujutsuColors.SOURCE_HIGHLIGHT)}'>&#9632;</font> $parentLabel &nbsp; ")
            append("<font color='${colorHex(JujutsuColors.DESTINATION_HIGHLIGHT)}'>&#9632;</font> $childLabel")
            append("</html>")
        }
    }

    private fun colorHex(color: Color) = String.format("#%06x", color.rgb and 0xFFFFFF)

    private fun hideExtraColumns() {
        val columnsToRemove = (table.columnCount - 1 downTo 0)
            .filter { it != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }

        for (colIndex in columnsToRemove) {
            if (colIndex < table.columnModel.columnCount) {
                table.removeColumn(table.columnModel.getColumn(colIndex))
            }
        }
    }
}
