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

            // Draw pass-through lines first (behind commit)
            drawPassThroughLines(g2d, graphNode)

            // Calculate commit position
            val commitX = LANE_WIDTH / 2 + graphNode.lane * LANE_WIDTH
            val commitY = height / 2

            // Draw lines to parents (in next row)
            drawLinesToParents(g2d, graphNode, commitX, commitY, row)

            // Draw commit circle
            drawCommitCircle(g2d, graphNode, commitX, commitY)
        }

        private fun drawPassThroughLines(g2d: Graphics2D, node: GraphNode) {
            // Draw vertical lines for child lanes passing through this row
            // Look at all previous commits (children) that have parents in rows after this one
            val model = table.model as? JujutsuLogTableModel ?: return
            val currentEntry = model.getEntry(row) ?: return

            for (prevRow in 0 until row) {
                val prevEntry = model.getEntry(prevRow) ?: continue
                val prevNode = graphNodes[prevEntry.changeId] ?: continue

                // Check if this child has a parent that comes after this row
                for (parentId in prevEntry.parentIds) {
                    // Find parent's row
                    var parentRow = -1
                    for (r in row + 1 until model.rowCount) {
                        if (model.getEntry(r)?.changeId == parentId) {
                            parentRow = r
                            break
                        }
                    }

                    if (parentRow > row) {
                        // This child's line passes through this row in the child's lane
                        val childLane = prevNode.lane
                        val childX = LANE_WIDTH / 2 + childLane * LANE_WIDTH
                        g2d.color = prevNode.color
                        g2d.drawLine(childX, 0, childX, height)
                        break // Only draw once per child
                    }
                }
            }
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
            val currentEntry = model.getEntry(currentRow) ?: return

            // Draw incoming lines from children (from their lanes at top of this row to this commit)
            // Look through ALL previous rows to find children that have this commit as a parent
            for (prevRow in 0 until currentRow) {
                val prevEntry = model.getEntry(prevRow) ?: continue
                val prevNode = graphNodes[prevEntry.changeId] ?: continue

                // Check if this previous commit has current commit as a parent
                if (prevEntry.parentIds.contains(currentEntry.changeId)) {
                    // Draw from CHILD's lane at TOP of THIS row to THIS commit's center
                    // The line travels in the child's lane through pass-through rows,
                    // then diagonals (or stays vertical) to reach this commit
                    val childLane = prevNode.lane
                    val childX = LANE_WIDTH / 2 + childLane * LANE_WIDTH
                    g2d.color = prevNode.color
                    g2d.drawLine(childX, 0, commitX, commitY)
                }
            }

            // Draw outgoing lines to parents
            // Always draw vertical in THIS commit's lane - the line continues through pass-through
            // until it reaches the parent, where it diagonals to the parent's position
            if (node.parentLanes.isNotEmpty()) {
                // Draw vertical line from commit center to bottom of cell (in THIS commit's lane)
                g2d.color = node.color
                g2d.drawLine(commitX, commitY, commitX, height)
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
