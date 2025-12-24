package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.JujutsuColors
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Combined renderer for graph and description column.
 *
 * Layout (left to right):
 * 1. Commit graph (colored circles and lines)
 * 2. Status indicators (conflict, empty)
 * 3. Change ID (short form, bold) - optional
 * 4. Description text (aligned with rightmost lane) - optional
 * 5. Decorations on right (bookmarks, tags, working copy indicator) - optional
 *
 * This maximizes horizontal space by combining elements that Git plugin keeps separate.
 */
class JujutsuGraphAndDescriptionRenderer(
    private val graphNodes: Map<ChangeId, GraphNode>,
    private val columnManager: JujutsuColumnManager = JujutsuColumnManager.DEFAULT
) : TableCellRenderer {

    companion object {
        private const val LANE_WIDTH = 16 // Pixels between lanes
        private const val ROW_HEIGHT = 22 // Match table row height
        private const val COMMIT_RADIUS = 4 // Radius of commit circle
        private const val HORIZONTAL_PADDING = 4 // Padding between elements
    }

    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val mousePos = table.mousePosition
        val isHovered = mousePos != null && table.rowAtPoint(mousePos) == row
        return GraphAndDescriptionPanel(table, row, isSelected, isHovered)
    }

    private inner class GraphAndDescriptionPanel(
        private val table: JTable,
        private val row: Int,
        private val isSelected: Boolean,
        private val isHovered: Boolean
    ) : JPanel() {

        private var entry: LogEntry? = null
        private var graphNode: GraphNode? = null

        init {
            isOpaque = true

            // Get entry and graph node for this row
            val model = table.model as? JujutsuLogTableModel
            entry = model?.getEntry(row)
            graphNode = entry?.let { graphNodes[it.changeId] }

            // Set background based on selection/hover state
            background = when {
                isSelected -> table.selectionBackground
                isHovered -> UIUtil.getListBackground(true, false)
                else -> table.background
            }

            // Set tooltip to full description with HTML formatting
            entry?.let { e ->
                if (!e.description.empty) {
                    toolTipText = formatDescriptionTooltip(e.description.actual)
                }
            }
        }

        private fun formatDescriptionTooltip(description: String): String {
            // Convert to HTML and replace newlines with <br>
            val htmlEscaped = description
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>")

            return "<html>$htmlEscaped</html>"
        }

        override fun paintComponent(g: Graphics) {
            val g2d = g as Graphics2D

            // Paint background explicitly
            g2d.color = background
            g2d.fillRect(0, 0, width, height)

            val entry = this.entry ?: return
            val graphNode = this.graphNode ?: return

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val graphStartX = HORIZONTAL_PADDING

            // 1. Draw graph (pass-through lines, commit circle, lines to parents)
            val activeLanesInRow = drawGraph(g2d, graphNode, graphStartX)

            // Calculate rightmost active lane for THIS ROW for text alignment
            val rightmostActiveLane = activeLanesInRow.maxOrNull() ?: graphNode.lane
            val rightmostLaneX = graphStartX + LANE_WIDTH / 2 + rightmostActiveLane * LANE_WIDTH
            var x = rightmostLaneX + LANE_WIDTH / 2 + HORIZONTAL_PADDING

            // 2. Draw status indicators (always shown)
            x = drawStatusIndicators(g2d, entry, x)

            // 3. Draw change ID (optional)
            if (columnManager.showChangeId) {
                x = drawChangeId(g2d, entry.changeId, x)
            }

            // 4. Draw description (optional)
            if (columnManager.showDescription) {
                x = drawDescription(g2d, entry, x)
            }

            // 5. Draw decorations on the right (bookmarks, tags, working copy) - optional
            if (columnManager.showDecorations) {
                drawDecorations(g2d, entry, width - HORIZONTAL_PADDING)
            }
        }

        private fun drawGraph(g2d: Graphics2D, node: GraphNode, startX: Int): Set<Int> {
            val model = table.model as? JujutsuLogTableModel ?: return emptySet()
            val entry = model.getEntry(row) ?: return emptySet()

            // Track which lanes are active in this row
            val activeLanes = mutableSetOf<Int>()

            // Draw pass-through lines first (behind commit)
            val passThroughLanes = drawPassThroughLines(g2d, node, startX)
            activeLanes.addAll(passThroughLanes)

            // This commit's lane is active
            activeLanes.add(node.lane)

            // Calculate commit position - align with text vertically
            val commitX = startX + LANE_WIDTH / 2 + node.lane * LANE_WIDTH
            val fontMetrics = g2d.fontMetrics
            val textCenterY = height / 2 + fontMetrics.ascent / 4 // Align with text center
            val commitY = textCenterY

            // Draw lines to parents (in next row)
            drawLinesToParents(g2d, node, commitX, commitY, row, startX)

            // Draw commit circle
            drawCommitCircle(g2d, node, commitX, commitY)

            return activeLanes
        }

        private fun drawPassThroughLines(g2d: Graphics2D, node: GraphNode, graphStartX: Int): Set<Int> {
            val model = table.model as? JujutsuLogTableModel ?: return emptySet()
            val currentEntry = model.getEntry(row) ?: return emptySet()
            val passThroughLanes = mutableSetOf<Int>()

            for (prevRow in 0 until row) {
                val prevEntry = model.getEntry(prevRow) ?: continue
                val prevNode = graphNodes[prevEntry.changeId] ?: continue

                for (parentId in prevEntry.parentIds) {
                    var parentRow = -1
                    for (r in row + 1 until model.rowCount) {
                        if (model.getEntry(r)?.changeId == parentId) {
                            parentRow = r
                            break
                        }
                    }

                    if (parentRow > row) {
                        val childLane = prevNode.lane
                        val childX = graphStartX + LANE_WIDTH / 2 + childLane * LANE_WIDTH
                        g2d.color = prevNode.color
                        g2d.drawLine(childX, 0, childX, height)
                        passThroughLanes.add(childLane)
                        break
                    }
                }
            }

            return passThroughLanes
        }

        private fun drawCommitCircle(g2d: Graphics2D, node: GraphNode, x: Int, y: Int) {
            // Fill circle with branch color
            g2d.color = node.color
            g2d.fillOval(x - COMMIT_RADIUS, y - COMMIT_RADIUS, COMMIT_RADIUS * 2, COMMIT_RADIUS * 2)

            // Draw border
            if (isSelected) {
                g2d.color = table.selectionForeground
                g2d.drawOval(x - COMMIT_RADIUS, y - COMMIT_RADIUS, COMMIT_RADIUS * 2, COMMIT_RADIUS * 2)
            }
        }

        private fun drawLinesToParents(
            g2d: Graphics2D,
            node: GraphNode,
            commitX: Int,
            commitY: Int,
            currentRow: Int,
            graphStartX: Int
        ) {
            val model = table.model as? JujutsuLogTableModel ?: return
            val currentEntry = model.getEntry(currentRow) ?: return

            // Draw incoming lines from children
            for (prevRow in 0 until currentRow) {
                val prevEntry = model.getEntry(prevRow) ?: continue
                val prevNode = graphNodes[prevEntry.changeId] ?: continue

                if (prevEntry.parentIds.contains(currentEntry.changeId)) {
                    val childLane = prevNode.lane
                    val childX = graphStartX + LANE_WIDTH / 2 + childLane * LANE_WIDTH
                    g2d.color = prevNode.color
                    g2d.drawLine(childX, 0, commitX, commitY)
                }
            }

            // Draw outgoing lines to parents
            if (node.parentLanes.isNotEmpty()) {
                g2d.color = node.color
                g2d.drawLine(commitX, commitY, commitX, height)
            }
        }

        private fun drawStatusIndicators(g2d: Graphics2D, entry: LogEntry, startX: Int): Int {
            var x = startX
            val centerY = height / 2

            // Conflict indicator
            if (entry.hasConflict) {
                AllIcons.General.Warning.paintIcon(this, g2d, x, centerY - AllIcons.General.Warning.iconHeight / 2)
                x += AllIcons.General.Warning.iconWidth + HORIZONTAL_PADDING
            }

            // Empty indicator
            if (entry.isEmpty) {
                AllIcons.General.BalloonInformation.paintIcon(
                    this,
                    g2d,
                    x,
                    centerY - AllIcons.General.BalloonInformation.iconHeight / 2
                )
                x += AllIcons.General.BalloonInformation.iconWidth + HORIZONTAL_PADDING
            }

            return x
        }

        private fun drawChangeId(g2d: Graphics2D, changeId: ChangeId, startX: Int): Int {
            val centerY = height / 2
            val fontMetrics = g2d.fontMetrics

            // Draw short prefix in bold
            val boldFont = fontMetrics.font.deriveFont(java.awt.Font.BOLD)
            g2d.font = boldFont
            g2d.color = if (isSelected) table.selectionForeground else table.foreground

            var x = startX
            g2d.drawString(changeId.short, x, centerY + fontMetrics.ascent / 2)
            x += fontMetrics.stringWidth(changeId.short)

            // Draw remainder in gray/small if present
            if (changeId.displayRemainder.isNotEmpty()) {
                val regularFont = fontMetrics.font.deriveFont(java.awt.Font.PLAIN)
                val smallFont = regularFont.deriveFont(regularFont.size2D * 0.85f)
                g2d.font = smallFont
                g2d.color = if (isSelected) table.selectionForeground else JBColor.GRAY

                g2d.drawString(changeId.displayRemainder, x, centerY + fontMetrics.ascent / 2)
                x += g2d.fontMetrics.stringWidth(changeId.displayRemainder)
            }

            // Reset font
            g2d.font = fontMetrics.font

            return x + HORIZONTAL_PADDING * 2
        }

        private fun drawDescription(g2d: Graphics2D, entry: LogEntry, startX: Int): Int {
            val centerY = height / 2
            val fontMetrics = g2d.fontMetrics

            // Use italic for empty descriptions
            if (entry.description.empty) {
                val italicFont = fontMetrics.font.deriveFont(java.awt.Font.ITALIC)
                g2d.font = italicFont
                g2d.color = if (isSelected) table.selectionForeground else JBColor.GRAY
            } else {
                g2d.color = if (isSelected) table.selectionForeground else table.foreground
            }

            val text = entry.description.summary
            g2d.drawString(text, startX, centerY + fontMetrics.ascent / 2)

            // Reset font
            if (entry.description.empty) {
                g2d.font = fontMetrics.font
            }

            return startX + fontMetrics.stringWidth(text)
        }

        private fun drawDecorations(g2d: Graphics2D, entry: LogEntry, rightX: Int) {
            if (entry.bookmarks.isEmpty()) return

            val centerY = height / 2
            val fontMetrics = g2d.fontMetrics
            val boldFont = fontMetrics.font.deriveFont(java.awt.Font.BOLD)

            var x = rightX

            // Draw bookmarks from right to left (icon first, then text)
            for (bookmark in entry.bookmarks.reversed()) {
                // Draw bookmark name
                g2d.font = boldFont
                g2d.color = if (isSelected) table.selectionForeground else JujutsuColors.BOOKMARK
                val nameWidth = fontMetrics.stringWidth(bookmark.name)
                x -= nameWidth
                g2d.drawString(bookmark.name, x, centerY + fontMetrics.ascent / 2)
                x -= HORIZONTAL_PADDING

                // Draw bookmark icon to the left of the text
                val icon = AllIcons.Vcs.Branch
                x -= icon.iconWidth
                icon.paintIcon(this, g2d, x, centerY - icon.iconHeight / 2)
                x -= HORIZONTAL_PADDING * 2
            }

            // Reset font
            g2d.font = fontMetrics.font
        }

        override fun getPreferredSize() = super.getPreferredSize().apply {
            // Calculate width based on graph + content
            val maxLane = graphNodes.values.maxOfOrNull { it.lane } ?: 0
            val graphWidth = (maxLane + 1) * LANE_WIDTH + LANE_WIDTH

            // Minimum width for description area
            val contentWidth = 400

            width = HORIZONTAL_PADDING + graphWidth + HORIZONTAL_PADDING * 2 + contentWidth
            height = ROW_HEIGHT
        }
    }
}
