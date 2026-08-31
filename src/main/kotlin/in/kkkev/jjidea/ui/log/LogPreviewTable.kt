package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeKey
import javax.swing.JTable

/**
 * Shared setup for the single-column, graph-only log tables used by the Rebase/Squash
 * Into…/Duplicate Onto… destination pickers and the Rebase/New Change preview
 * ([in.kkkev.jjidea.ui.rebase.RebasePreviewPanel]) - each shows only the combined
 * graph+description column, with its own [GraphNode] map recomputed as selections change.
 */

/** Removes every column but [JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION]. */
internal fun JTable.hideAllButGraphColumn() {
    val toRemove = (columnCount - 1 downTo 0).filter { it != JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION }
    for (col in toRemove) {
        if (col < columnModel.columnCount) {
            removeColumn(columnModel.getColumn(col))
        }
    }
}

/** (Re)installs [JujutsuGraphAndDescriptionRenderer] for [graphNodes] on the graph column. */
internal fun JTable.setGraphRenderer(graphNodes: Map<ChangeKey, GraphNode>) {
    for (i in 0 until columnModel.columnCount) {
        val column = columnModel.getColumn(i)
        if (column.modelIndex == JujutsuLogTableModel.COLUMN_GRAPH_AND_DESCRIPTION) {
            column.cellRenderer = JujutsuGraphAndDescriptionRenderer(graphNodes)
            break
        }
    }
}
