package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBValue
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.*
import java.awt.*
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * Combined renderer for graph and description column.
 *
 * Layout (left to right):
 * 1. Commit graph (colored circles and lines) — painted via Graphics2D
 * 2. Text area using [TruncatingLeftRightLayout]:
 *    - Left: status indicators, optional change ID, description (truncatable)
 *    - Right: optional decorations (bookmarks, working copy indicator)
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
        return GraphAndDescriptionPanel(table, row, column, isSelected, isHovered)
    }

    private inner class GraphAndDescriptionPanel(
        private val table: JTable,
        private val row: Int,
        private val column: Int,
        private val isSelected: Boolean,
        isHovered: Boolean
    ) : JPanel(null) {
        private val entry = (table.model as? JujutsuLogTableModel)?.getEntry(row)
        private val graphNode = entry?.let { graphNodes[it.id] }
        private val textPanel = TruncatingLeftRightLayout().apply {
            isOpaque = false
        }

        init {
            isOpaque = true

            background = when {
                isSelected -> table.selectionBackground
                isHovered -> UIUtil.getListBackground(true, false)
                else -> table.background
            }

            add(textPanel)

            entry?.let { e ->
                toolTipText = buildTooltip(e)
                configureTextPanel(e)
            }
        }

        private fun buildTooltip(entry: LogEntry) = htmlString {
            appendSummaryAndStatuses(entry)
            control("<pre style='white-space: pre-wrap;'>", "</pre>") {
                appendSummary(entry.description)
            }
        }

        private fun configureTextPanel(entry: LogEntry) {
            val fg = if (isSelected) table.selectionForeground else table.foreground

            val leftCanvas = entryCanvas(entry, fg) {
                appendStatusIndicators(entry)
                if (columnManager.showChangeId) {
                    append(entry.id)
                    append(" ")
                }
                appendDescriptionAndEmptyIndicator(entry)
            }

            val rightCanvas = if (columnManager.showDecorations) {
                entryCanvas(entry, fg) { appendDecorations(entry) }
            } else {
                FragmentRecordingCanvas()
            }

            textPanel.configure(
                leftCanvas = leftCanvas,
                rightCanvas = rightCanvas,
                cellWidth = table.columnModel.getColumn(column).width - textStartX(),
                background = background
            )
        }

        private fun textStartX(): Int {
            val graphNode = this.graphNode ?: return HORIZONTAL_PADDING.get()
            val laneWidth = LANE_WIDTH.get()
            val horizontalPadding = HORIZONTAL_PADDING.get()

            // Compute rightmost active lane to position text after the graph
            val model = table.model as? JujutsuLogTableModel
            val activeLanes = mutableSetOf(graphNode.lane)
            model?.let { m ->
                val entry = m.getEntry(row) ?: return@let
                getRowPassthroughs(m)[row]?.let { activeLanes.addAll(it) }
                for (prevRow in 0 until row) {
                    val prevEntry = m.getEntry(prevRow) ?: continue
                    val prevNode = graphNodes[prevEntry.id] ?: continue
                    if (prevEntry.parentIds.contains(entry.id)) activeLanes.add(prevNode.lane)
                }
                for (parentLane in graphNode.parentLanes) {
                    if (parentLane != graphNode.lane) activeLanes.add(parentLane)
                }
            }

            val rightmostLane = activeLanes.maxOrNull() ?: graphNode.lane
            return horizontalPadding + (rightmostLane + 1) * laneWidth
        }

        override fun doLayout() {
            val x = textStartX()
            textPanel.setBounds(x, 0, width - x, height)
        }

        override fun paintComponent(g: Graphics) {
            val g2d = g as Graphics2D

            g2d.color = background
            g2d.fillRect(0, 0, width, height)

            val graphNode = this.graphNode ?: return

            // Paint highlight stripe if set (e.g., rebase preview source/destination)
            graphNode.highlightColor?.let { highlight ->
                val composite = g2d.composite
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.15f)
                g2d.color = highlight
                g2d.fillRect(0, 0, width, height)
                g2d.composite = composite
            }

            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val laneWidth = LANE_WIDTH.get()
            val graphStartX = HORIZONTAL_PADDING.get()

            drawGraph(g2d, graphNode, graphStartX, laneWidth)
        }

        private fun drawGraph(g2d: Graphics2D, node: GraphNode, startX: Int, laneWidth: Int) {
            val model = table.model as? JujutsuLogTableModel ?: return
            val entry = model.getEntry(row) ?: return

            drawPassThroughLines(g2d, startX, laneWidth)

            val commitX = startX + laneWidth / 2 + node.lane * laneWidth
            val commitY = height / 2

            drawLinesToParents(g2d, node, commitX, commitY, row, startX, laneWidth)
            drawCommitCircle(g2d, node, commitX, commitY)
        }

        private fun drawPassThroughLines(g2d: Graphics2D, graphStartX: Int, laneWidth: Int) {
            val model = table.model as? JujutsuLogTableModel ?: return
            val rowPT = getRowPassthroughs(model)[row] ?: return

            for (lane in rowPT) {
                val passX = graphStartX + laneWidth / 2 + lane * laneWidth
                g2d.color = colorForLane(lane)
                g2d.drawLine(passX, 0, passX, height)
            }
        }

        private fun drawCommitCircle(g2d: Graphics2D, node: GraphNode, x: Int, y: Int) {
            val commitRadius = COMMIT_RADIUS.get()

            g2d.color = node.color
            g2d.fillOval(x - commitRadius, y - commitRadius, commitRadius * 2, commitRadius * 2)

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

            for (prevRow in 0 until currentRow) {
                val prevEntry = model.getEntry(prevRow) ?: continue
                val prevNode = graphNodes[prevEntry.id] ?: continue

                val parentIndex = prevEntry.parentIds.indexOf(currentEntry.id)
                if (parentIndex >= 0) {
                    val childLane = prevNode.lane
                    val childHasMultipleParents = prevNode.parentLanes.size > 1

                    val passThroughLane = prevNode.passthroughLanes[currentEntry.id]
                    val connectionLane = passThroughLane
                        ?: if (childHasMultipleParents) node.lane else childLane
                    val connectionX = graphStartX + laneWidth / 2 + connectionLane * laneWidth
                    g2d.color = colorForLane(connectionLane)
                    g2d.drawLine(connectionX, 0, commitX, commitY)
                }
            }

            val childHasMultipleParents = node.parentLanes.size > 1

            for ((parentIndex, parentId) in currentEntry.parentIds.withIndex()) {
                val parentLane = node.parentLanes.getOrNull(parentIndex) ?: continue

                val passThroughLane = node.passthroughLanes[parentId]
                val targetLane = passThroughLane
                    ?: if (childHasMultipleParents && parentLane != node.lane) parentLane else node.lane
                val targetX = graphStartX + laneWidth / 2 + targetLane * laneWidth
                g2d.color = if (targetLane == node.lane) node.color else colorForLane(targetLane)
                g2d.drawLine(commitX, commitY, targetX, height)
            }
        }

        override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
            val laneWidth = LANE_WIDTH.get()
            val horizontalPadding = HORIZONTAL_PADDING.get()

            val maxLane = graphNodes.values.maxOfOrNull { it.lane } ?: 0
            val graphWidth = (maxLane + 1) * laneWidth + laneWidth
            val contentWidth = 400

            width = horizontalPadding + graphWidth + horizontalPadding * 2 + contentWidth
            height = ROW_HEIGHT.get()
        }
    }
}
