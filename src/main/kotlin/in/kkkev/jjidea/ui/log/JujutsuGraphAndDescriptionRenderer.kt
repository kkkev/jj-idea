package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent.getTextBaseLine
import com.intellij.util.ui.JBValue
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.components.GraphicsTextCanvas
import `in`.kkkev.jjidea.ui.components.append
import `in`.kkkev.jjidea.ui.components.drawStringCentredVertically
import java.awt.*
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
        // HiDPI-aware dimensions using JBValue for proper scaling
        private val LANE_WIDTH = JBValue.UIInteger("Jujutsu.Graph.laneWidth", 16)
        private val ROW_HEIGHT = JBValue.UIInteger("Jujutsu.Graph.rowHeight", 22)
        private val COMMIT_RADIUS = JBValue.UIInteger("Jujutsu.Graph.commitRadius", 4)
        private val HORIZONTAL_PADDING = JBValue.UIInteger("Jujutsu.Graph.horizontalPadding", 4)

        // Tooltip text for status indicators
        private const val CONFLICT_TOOLTIP =
            "<html><b>Conflict</b><br>This change has unresolved merge conflicts</html>"
        private const val IMMUTABLE_TOOLTIP =
            "<html><b>Immutable</b><br>This change cannot be modified (protected commit)</html>"

        // Lane colors - must match CommitGraphBuilder colors for consistent coloring
        private val LANE_COLORS =
            listOf(
                JBColor(0x4285F4, 0x6AA1FF), // Blue
                JBColor(0xEA4335, 0xFF6B5E), // Red
                JBColor(0xC99700, 0xE0B800), // Yellow
                JBColor(0x34A853, 0x5DCD73), // Green
                JBColor(0xFF6D00, 0xFF8A3D), // Orange
                JBColor(0x9C27B0, 0xC25ED0), // Purple
                JBColor(0x00ACC1, 0x4DD0E1), // Cyan
                JBColor(0x689F38, 0x8BC34A) // Light green
            )

        /** Get the color for a specific lane */
        fun colorForLane(lane: Int) = LANE_COLORS[lane % LANE_COLORS.size]
    }

    /** Lazily computed per-row passthrough lanes derived from entries' passthroughLanes */
    private var rowPassthroughCache: Map<Int, Set<Int>>? = null

    private fun getRowPassthroughs(model: JujutsuLogTableModel): Map<Int, Set<Int>> {
        rowPassthroughCache?.let { return it }

        val rowByChangeId = mutableMapOf<ChangeId, Int>()
        for (row in 0 until model.rowCount) {
            val entry = model.getEntry(row) ?: continue
            rowByChangeId[entry.id] = row
        }

        val result = mutableMapOf<Int, MutableSet<Int>>()
        for (row in 0 until model.rowCount) {
            val entry = model.getEntry(row) ?: continue
            val node = graphNodes[entry.id] ?: continue
            for ((parentId, lane) in node.passthroughLanes) {
                val parentRow = rowByChangeId[parentId] ?: continue
                for (r in (row + 1) until parentRow) {
                    result.getOrPut(r) { mutableSetOf() }.add(lane)
                }
            }
        }

        rowPassthroughCache = result
        return result
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
            graphNode = entry?.let { graphNodes[it.id] }

            // Set background based on selection/hover state
            background = when {
                isSelected -> table.selectionBackground
                isHovered -> UIUtil.getListBackground(true, false)
                else -> table.background
            }

            // Set tooltip with description and status indicators
            entry?.let { e ->
                toolTipText = buildTooltip(e)
            }
        }

        private fun buildTooltip(entry: LogEntry): String? {
            val parts = mutableListOf<String>()

            // Add root indicator for multi-root projects
            val rootName = entry.repo.displayName
            if (rootName.isNotEmpty()) {
                parts.add("<b>📁 $rootName</b>")
            }

            // Add status indicators
            if (entry.hasConflict) {
                parts.add("<b>⚠ Conflict</b> - This change has unresolved merge conflicts")
            }
            if (entry.immutable) {
                parts.add("<b>🔒 Immutable</b> - This change cannot be modified (protected)")
            }
            if (entry.isEmpty) {
                parts.add("<b>Empty</b> - This change has no file modifications")
            }

            // Add description if present
            if (!entry.description.empty) {
                if (parts.isNotEmpty()) {
                    parts.add("<hr>")
                }
                val htmlEscaped = entry.description.actual
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>")
                parts.add(htmlEscaped)
            }

            return if (parts.isEmpty()) null else "<html>${parts.joinToString("<br>")}</html>"
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

            val laneWidth = LANE_WIDTH.get()
            val horizontalPadding = HORIZONTAL_PADDING.get()
            val graphStartX = horizontalPadding

            // 1. Draw graph (pass-through lines, commit circle, lines to parents)
            val activeLanesInRow = drawGraph(g2d, graphNode, graphStartX, laneWidth)

            // Calculate rightmost active lane for THIS ROW for text alignment
            val rightmostActiveLane = activeLanesInRow.maxOrNull() ?: graphNode.lane
            var x = graphStartX + (rightmostActiveLane + 1) * laneWidth

            // 2. Draw status indicators (always shown)
            x = drawStatusIndicators(g2d, entry, x)

            // 3. Draw change ID (optional)
            if (columnManager.showChangeId) {
                x = drawQualifiedChangeId(g2d, entry.id, x)
            }

            // 4. Draw description (optional)
            if (columnManager.showDescription) {
                // Calculate space available for description (accounting for decorations on the right)
                val decorationsWidth =
                    if (columnManager.showDecorations) {
                        calculateDecorationsWidth(g2d, entry, horizontalPadding)
                    } else {
                        0
                    }
                val maxDescriptionX = width - horizontalPadding - decorationsWidth
                x = drawDescription(g2d, entry, x, maxDescriptionX)
            }

            // 5. Draw decorations on the right (bookmarks, tags, working copy) - optional
            if (columnManager.showDecorations) {
                drawDecorations(g2d, entry, width - horizontalPadding, horizontalPadding)
            }
        }

        private fun drawGraph(g2d: Graphics2D, node: GraphNode, startX: Int, laneWidth: Int): Set<Int> {
            val model = table.model as? JujutsuLogTableModel ?: return emptySet()
            val entry = model.getEntry(row) ?: return emptySet()

            // Track which lanes are active in this row
            val activeLanes = mutableSetOf<Int>()

            // Draw pass-through lines first (behind commit)
            val passThroughLanes = drawPassThroughLines(g2d, startX, laneWidth)
            activeLanes.addAll(passThroughLanes)

            // This commit's lane is active
            activeLanes.add(node.lane)

            // Include lanes from children above (jj-idea-cjy)
            // Incoming diagonal lines take visual space even without passthroughs
            for (prevRow in 0 until row) {
                val prevEntry = model.getEntry(prevRow) ?: continue
                val prevNode = graphNodes[prevEntry.id] ?: continue
                if (prevEntry.parentIds.contains(entry.id)) {
                    activeLanes.add(prevNode.lane)
                }
            }

            // Include lanes from merge parents (diagonal lines going down from this row)
            // For merges, the cross-lane parent lines take visual space
            for (parentLane in node.parentLanes) {
                if (parentLane != node.lane) {
                    activeLanes.add(parentLane)
                }
            }

            // Calculate commit position - center vertically for symmetric diagonals
            val commitX = startX + laneWidth / 2 + node.lane * laneWidth
            val commitY = height / 2

            // Draw lines to parents (in next row)
            drawLinesToParents(g2d, node, commitX, commitY, row, startX, laneWidth)

            // Draw commit circle
            drawCommitCircle(g2d, node, commitX, commitY)

            return activeLanes
        }

        private fun drawPassThroughLines(g2d: Graphics2D, graphStartX: Int, laneWidth: Int): Set<Int> {
            val model = table.model as? JujutsuLogTableModel ?: return emptySet()
            val rowPT = getRowPassthroughs(model)[row] ?: return emptySet()

            for (lane in rowPT) {
                val passX = graphStartX + laneWidth / 2 + lane * laneWidth
                g2d.color = colorForLane(lane)
                g2d.drawLine(passX, 0, passX, height)
            }

            return rowPT
        }

        private fun drawCommitCircle(g2d: Graphics2D, node: GraphNode, x: Int, y: Int) {
            val commitRadius = COMMIT_RADIUS.get()

            // Fill circle with branch color
            g2d.color = node.color
            g2d.fillOval(x - commitRadius, y - commitRadius, commitRadius * 2, commitRadius * 2)

            // Draw border
            if (isSelected) {
                g2d.color = table.selectionForeground
                g2d.drawOval(x - commitRadius, y - commitRadius, commitRadius * 2, commitRadius * 2)
            }
        }

        private fun drawLinesToParents(
            g2d: Graphics2D,
            node: GraphNode,
            commitX: Int,
            commitY: Int,
            currentRow: Int,
            graphStartX: Int,
            laneWidth: Int
        ) {
            val model = table.model as? JujutsuLogTableModel ?: return
            val currentEntry = model.getEntry(currentRow) ?: return

            // Draw incoming lines from children
            // Uses the actual passthrough lane from the algorithm to determine connection path.
            // For adjacent children, falls back to heuristic (merge → parent's lane, else → child's lane).
            for (prevRow in 0 until currentRow) {
                val prevEntry = model.getEntry(prevRow) ?: continue
                val prevNode = graphNodes[prevEntry.id] ?: continue

                val parentIndex = prevEntry.parentIds.indexOf(currentEntry.id)
                if (parentIndex >= 0) {
                    val childLane = prevNode.lane
                    val childHasMultipleParents = prevNode.parentLanes.size > 1

                    // Use actual passthrough lane if available (non-adjacent), else heuristic (adjacent)
                    val passThroughLane = prevNode.passthroughLanes[currentEntry.id]
                    val connectionLane = passThroughLane
                        ?: if (childHasMultipleParents) node.lane else childLane
                    val connectionX = graphStartX + laneWidth / 2 + connectionLane * laneWidth
                    g2d.color = colorForLane(connectionLane)
                    g2d.drawLine(connectionX, 0, commitX, commitY)
                }
            }

            // Draw outgoing lines to parents
            // Uses the actual passthrough lane to determine where the connection exits this row.
            // For adjacent parents, falls back to heuristic (merge → parent's lane, else → vertical).
            val childHasMultipleParents = node.parentLanes.size > 1

            for ((parentIndex, parentId) in currentEntry.parentIds.withIndex()) {
                val parentLane = node.parentLanes.getOrNull(parentIndex) ?: continue

                // Use actual passthrough lane if available (non-adjacent), else heuristic (adjacent)
                val passThroughLane = node.passthroughLanes[parentId]
                val targetLane = passThroughLane
                    ?: if (childHasMultipleParents && parentLane != node.lane) parentLane else node.lane
                val targetX = graphStartX + laneWidth / 2 + targetLane * laneWidth
                g2d.color = if (targetLane == node.lane) node.color else colorForLane(targetLane)
                g2d.drawLine(commitX, commitY, targetX, height)
            }
        }

        private fun drawStatusIndicators(g2d: Graphics2D, entry: LogEntry, startX: Int): Int {
            var x = startX
            val centerY = height / 2
            val horizontalPadding = HORIZONTAL_PADDING.get()

            // Immutable indicator - always reserve space for alignment consistency
            // The lock icon is intended to annotate other icons - so it is painted only in the bottom-right corner.
            val icon = if (entry.immutable) AllIcons.Nodes.Private else AllIcons.Nodes.Public
            val iconY = centerY - icon.iconHeight / 2

            icon.paintIcon(this, g2d, x, iconY)
            // Always advance by icon width (placeholder for mutable commits)
            x += icon.iconWidth + horizontalPadding

            // Conflict indicator
            if (entry.hasConflict) {
                val icon = AllIcons.General.Warning
                val iconY = centerY - icon.iconHeight / 2
                icon.paintIcon(this, g2d, x, iconY)
                x += icon.iconWidth + horizontalPadding
            }

            // Note: Empty indicator is shown as "(empty)" text inline with description
            // No icon needed here to avoid redundancy

            return x
        }

        private fun drawQualifiedChangeId(g2d: Graphics2D, id: ChangeId, startX: Int): Int {
            // TODO Need to handle selected better
            g2d.color = if (isSelected) table.selectionForeground else table.foreground

            val textCanvas = GraphicsTextCanvas(g2d, startX, getTextBaseLine(g2d.fontMetrics, height))
            textCanvas.append(id)

            return textCanvas.cursor.x + HORIZONTAL_PADDING.get()
        }

        private fun drawDescription(g2d: Graphics2D, entry: LogEntry, startX: Int, maxX: Int): Int {
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
                g2d.drawStringCentredVertically(displayText, x, height)
                x += fontMetrics.stringWidth(displayText)
            }

            // Draw "(empty)" indicator if entry is empty
            if (entry.isEmpty) {
                val emptyStyle = DescriptionRenderingStyle.getEmptyIndicatorFontStyle(entry.isWorkingCopy)
                g2d.font = baseFontMetrics.font.deriveFont(emptyStyle)
                g2d.color = if (isSelected) table.selectionForeground else JBColor.GRAY
                g2d.drawStringCentredVertically(emptyText, x, height)
                x += g2d.fontMetrics.stringWidth(emptyText)
            }

            // Reset font
            g2d.font = baseFontMetrics.font

            return x
        }

        private fun truncateText(text: String, fontMetrics: FontMetrics, availableWidth: Int): String {
            if (fontMetrics.stringWidth(text) <= availableWidth) return text

            // Binary search for the right length
            var truncated = text
            while (truncated.isNotEmpty() && fontMetrics.stringWidth("$truncated...") > availableWidth) {
                truncated = truncated.dropLast(1)
            }
            return if (truncated.isEmpty()) "" else "$truncated..."
        }

        private fun calculateDecorationsWidth(g2d: Graphics2D, entry: LogEntry, horizontalPadding: Int): Int {
            if (entry.bookmarks.isEmpty() && !entry.isWorkingCopy) return 0

            val fontMetrics = g2d.fontMetrics
            val boldFont = fontMetrics.font.deriveFont(Font.BOLD)
            val boldFontMetrics = g2d.getFontMetrics(boldFont)
            var width = 0

            // Calculate bookmark widths using platform-style painter
            if (entry.bookmarks.isNotEmpty()) {
                val painter = JujutsuLabelPainter(this, compact = false)
                width += painter.calculateWidth(entry.bookmarks)
            }

            // Calculate @ symbol width
            if (entry.isWorkingCopy) {
                width += boldFontMetrics.stringWidth("@")
                width += horizontalPadding
            }

            return width
        }

        private fun drawDecorations(g2d: Graphics2D, entry: LogEntry, rightX: Int, horizontalPadding: Int) {
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
                x -= horizontalPadding
            }

            // Reset font
            g2d.font = fontMetrics.font
        }

        override fun getPreferredSize() = super.getPreferredSize().apply {
            val laneWidth = LANE_WIDTH.get()
            val horizontalPadding = HORIZONTAL_PADDING.get()

            // Calculate width based on graph + content
            val maxLane = graphNodes.values.maxOfOrNull { it.lane } ?: 0
            val graphWidth = (maxLane + 1) * laneWidth + laneWidth

            // Minimum width for description area
            val contentWidth = 400

            width = horizontalPadding + graphWidth + horizontalPadding * 2 + contentWidth
            height = ROW_HEIGHT.get()
        }
    }
}
