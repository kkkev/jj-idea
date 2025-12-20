package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import `in`.kkkev.jjidea.jj.ChangeId
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Renders the commit graph column with circles and connecting lines.
 *
 * Draws:
 * - Circle for each commit (filled with branch color)
 * - Lines connecting commits to their parents
 * - Handles merges (multiple parents) and branches (multiple children)
 */
class JujutsuGraphCellRenderer(
    private val graphNodes: Map<ChangeId, GraphNode>
) : TableCellRenderer {

    companion object {
        private const val LANE_WIDTH = 16 // Pixels between lanes
        private const val ROW_HEIGHT = 22 // Match table row height
        private const val COMMIT_RADIUS = 4 // Radius of commit circle
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        return GraphPanel(table, row, isSelected)
    }

    private inner class GraphPanel(
        private val table: JTable,
        private val row: Int,
        private val isSelected: Boolean
    ) : JPanel() {

        init {
            isOpaque = true
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)

            val g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Get the log entry for this row
            val model = table.model as? JujutsuLogTableModel ?: return
            val entry = model.getEntry(row) ?: return
            val graphNode = graphNodes[entry.changeId] ?: return

            // Set background
            background = if (isSelected) {
                table.selectionBackground
            } else {
                table.background
            }

            // Calculate commit position
            val commitX = LANE_WIDTH / 2 + graphNode.lane * LANE_WIDTH
            val commitY = height / 2

            // Draw lines to parents (in next row)
            drawLinesToParents(g2d, graphNode, commitX, commitY, row)

            // Draw commit circle
            drawCommitCircle(g2d, graphNode, commitX, commitY)
        }

        private fun drawCommitCircle(g2d: Graphics2D, node: GraphNode, x: Int, y: Int) {
            // Fill circle with branch color
            g2d.color = node.color
            g2d.fillOval(x - COMMIT_RADIUS, y - COMMIT_RADIUS, COMMIT_RADIUS * 2, COMMIT_RADIUS * 2)

            // Draw border
            if (isSelected) {
                g2d.color = if (isSelected) {
                    table.selectionForeground
                } else {
                    JBColor.GRAY
                }
                g2d.drawOval(x - COMMIT_RADIUS, y - COMMIT_RADIUS, COMMIT_RADIUS * 2, COMMIT_RADIUS * 2)
            }
        }

        private fun drawLinesToParents(g2d: Graphics2D, node: GraphNode, commitX: Int, commitY: Int, currentRow: Int) {
            val model = table.model as? JujutsuLogTableModel ?: return

            // Draw incoming lines from previous row (child commits)
            // Check if there are commits in the previous row that connect to this one
            if (currentRow > 0) {
                val prevEntry = model.getEntry(currentRow - 1)
                if (prevEntry != null) {
                    val prevNode = graphNodes[prevEntry.changeId]
                    if (prevNode != null) {
                        // Check if previous commit has this commit as a parent
                        val parentIndex = prevEntry.parentIds.indexOf(model.getEntry(currentRow)?.changeId)
                        if (parentIndex >= 0 && parentIndex < prevNode.parentLanes.size) {
                            val prevLane = prevNode.lane
                            val prevX = LANE_WIDTH / 2 + prevLane * LANE_WIDTH

                            val prevLineEndX = commitX + (prevX - commitX) / 2

                            // Draw line from top of this cell to current commit
                            g2d.color = prevNode.color
                            g2d.drawLine(prevLineEndX, 0, commitX, commitY /*- COMMIT_RADIUS*/)
                        }
                    }
                }
            }

            // Draw outgoing lines to parents (in next row)
            for ((index, parentLane) in node.parentLanes.withIndex()) {
                val parentX = LANE_WIDTH / 2 + parentLane * LANE_WIDTH

                val parentLineEndX = commitX + (parentX - commitX) / 2

                // Draw line from commit to bottom of cell
                g2d.color = node.color
                g2d.drawLine(commitX, commitY /*+ COMMIT_RADIUS*/, parentLineEndX, height)
            }
        }

        override fun getPreferredSize() = super.getPreferredSize().apply {
            // Calculate width based on maximum lane used
            val maxLane = graphNodes.values.maxOfOrNull { it.lane } ?: 0
            width = (maxLane + 1) * LANE_WIDTH + LANE_WIDTH // Extra padding
            height = ROW_HEIGHT
        }
    }
}
