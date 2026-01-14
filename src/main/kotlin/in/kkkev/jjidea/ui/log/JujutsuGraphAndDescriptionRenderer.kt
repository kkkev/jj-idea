package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.JujutsuColors
import java.awt.Component
import java.awt.Font
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
                // Calculate space available for description (accounting for decorations on the right)
                val decorationsWidth = if (columnManager.showDecorations) {
                    calculateDecorationsWidth(g2d, entry)
                } else {
                    0
                }
                val maxDescriptionX = width - HORIZONTAL_PADDING - decorationsWidth
                x = drawDescription(g2d, entry, x, maxDescriptionX)
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
                val icon = AllIcons.General.Warning
                val iconY = centerY - icon.iconHeight / 2
                icon.paintIcon(this, g2d, x, iconY)
                x += icon.iconWidth + HORIZONTAL_PADDING
            }

            // Immutable indicator
            if (entry.immutable) {
                val icon = AllIcons.Nodes.Locked
                val iconY = centerY - icon.iconHeight / 2
                icon.paintIcon(this, g2d, x, iconY)
                x += icon.iconWidth + HORIZONTAL_PADDING
            }

            // Note: Empty indicator is shown as "(empty)" text inline with description
            // No icon needed here to avoid redundancy

            return x
        }

        private fun drawChangeId(g2d: Graphics2D, changeId: ChangeId, startX: Int): Int {
            val centerY = height / 2
            val fontMetrics = g2d.fontMetrics

            // Draw short prefix in bold
            val boldFont = fontMetrics.font.deriveFont(Font.BOLD)
            g2d.font = boldFont
            g2d.color = if (isSelected) table.selectionForeground else table.foreground

            var x = startX
            g2d.drawString(changeId.short, x, centerY + fontMetrics.ascent / 2)
            x += fontMetrics.stringWidth(changeId.short)

            // Draw remainder in gray/small if present
            if (changeId.displayRemainder.isNotEmpty()) {
                val regularFont = fontMetrics.font.deriveFont(Font.PLAIN)
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

        private fun drawDescription(g2d: Graphics2D, entry: LogEntry, startX: Int, maxX: Int): Int {
            val centerY = height / 2
            val baseFontMetrics = g2d.fontMetrics

            // Use shared style logic for font and color
            val fontStyle = DescriptionRenderingStyle.getFontStyle(entry)
            g2d.font = baseFontMetrics.font.deriveFont(fontStyle)
            val fontMetrics = g2d.fontMetrics

            // Calculate width needed for "(empty)" indicator if applicable
            val emptyText = " (empty)"
            val emptyIndicatorWidth = if (entry.isEmpty) {
                val emptyStyle = DescriptionRenderingStyle.getEmptyIndicatorFontStyle(entry.isWorkingCopy)
                g2d.getFontMetrics(baseFontMetrics.font.deriveFont(emptyStyle)).stringWidth(emptyText)
            } else {
                0
            }

            // Reduce available width for description to reserve space for "(empty)"
            val availableWidthForDescription = maxX - startX - emptyIndicatorWidth

            // Set color using shared logic
            g2d.color = DescriptionRenderingStyle.getTextColor(
                entry.description,
                isSelected,
                table.selectionForeground,
                table.foreground
            )

            val text = entry.description.summary

            // Truncate text if it doesn't fit
            val displayText = truncateText(text, fontMetrics, availableWidthForDescription)

            var x = startX
            if (displayText.isNotEmpty()) {
                g2d.drawString(displayText, x, centerY + fontMetrics.ascent / 2)
                x += fontMetrics.stringWidth(displayText)
            }

            // Draw "(empty)" indicator if entry is empty
            if (entry.isEmpty) {
                val emptyStyle = DescriptionRenderingStyle.getEmptyIndicatorFontStyle(entry.isWorkingCopy)
                g2d.font = baseFontMetrics.font.deriveFont(emptyStyle)
                g2d.color = if (isSelected) table.selectionForeground else JBColor.GRAY
                g2d.drawString(emptyText, x, centerY + g2d.fontMetrics.ascent / 2)
                x += g2d.fontMetrics.stringWidth(emptyText)
            }

            // Reset font
            g2d.font = baseFontMetrics.font

            return x
        }

        private fun truncateText(text: String, fontMetrics: java.awt.FontMetrics, availableWidth: Int): String {
            if (fontMetrics.stringWidth(text) <= availableWidth) return text

            // Binary search for the right length
            var truncated = text
            while (truncated.isNotEmpty() && fontMetrics.stringWidth(truncated + "...") > availableWidth) {
                truncated = truncated.dropLast(1)
            }
            return if (truncated.isEmpty()) "" else truncated + "..."
        }

        private fun calculateDecorationsWidth(g2d: Graphics2D, entry: LogEntry): Int {
            if (entry.bookmarks.isEmpty() && !entry.isWorkingCopy) return 0

            val fontMetrics = g2d.fontMetrics
            val boldFont = fontMetrics.font.deriveFont(Font.BOLD)
            val boldFontMetrics = g2d.getFontMetrics(boldFont)
            var width = 0

            // Calculate bookmark widths using platform-style painter
            if (entry.bookmarks.isNotEmpty()) {
                val painter = JujutsuLabelPainter(this, compact = false)
                width += painter.calculateWidth(entry.bookmarks, fontMetrics)
            }

            // Calculate @ symbol width
            if (entry.isWorkingCopy) {
                width += boldFontMetrics.stringWidth("@")
                width += HORIZONTAL_PADDING
            }

            return width
        }

        private fun drawDecorations(g2d: Graphics2D, entry: LogEntry, rightX: Int) {
            if (entry.bookmarks.isEmpty() && !entry.isWorkingCopy) return

            val centerY = height / 2
            val fontMetrics = g2d.fontMetrics
            val boldFont = fontMetrics.font.deriveFont(Font.BOLD)

            var x = rightX

            // Draw bookmarks using platform-style painter
            if (entry.bookmarks.isNotEmpty()) {
                val painter = JujutsuLabelPainter(this, compact = false)
                val background = when {
                    isSelected -> table.selectionBackground
                    isHovered -> UIUtil.getListBackground(true, false)
                    else -> table.background
                }
                // Use grey text color (icon is orange, text is grey)
                val foreground = if (isSelected) table.selectionForeground else JBColor.GRAY

                x = painter.paintRightAligned(
                    g2d,
                    x,
                    0,
                    height,
                    entry.bookmarks,
                    background,
                    foreground,
                    isSelected
                )
            }

            // Draw @ symbol for working copy
            if (entry.isWorkingCopy) {
                g2d.font = boldFont
                g2d.color = if (isSelected) table.selectionForeground else JujutsuColors.WORKING_COPY
                val atWidth = fontMetrics.stringWidth("@")
                x -= atWidth
                g2d.drawString("@", x, centerY + fontMetrics.ascent / 2)
                x -= HORIZONTAL_PADDING
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
