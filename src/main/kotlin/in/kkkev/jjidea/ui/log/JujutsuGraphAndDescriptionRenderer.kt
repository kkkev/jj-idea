package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBValue
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.*
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import java.awt.*
import java.awt.geom.Path2D
import java.net.URI
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
 *
 * [linkifier] linkifies issue-tracker references (e.g. `JIRA-123`) in the description (jj-idea-91qf)
 * - left at [Linkifier.None] for the Split/Squash/Rebase/Duplicate picker tables, which construct
 * this renderer without one.
 */
class JujutsuGraphAndDescriptionRenderer(
    private val graphNodes: Map<ChangeKey, GraphNode>,
    private val columnManager: JujutsuColumnManager = JujutsuColumnManager.DEFAULT,
    private val linkifier: Linkifier = Linkifier.None
) : TableCellRenderer {
    companion object {
        // HiDPI-aware dimensions using JBValue for proper scaling. LANE_WIDTH/HORIZONTAL_PADDING
        // are internal so JujutsuLogTableRenderers.graphTextStartX can reproduce the graph indent
        // for click resolution (jj-idea-91qf).
        internal val LANE_WIDTH = JBValue.UIInteger("Jujutsu.Graph.laneWidth", 16)
        private val ROW_HEIGHT = JBValue.UIInteger("Jujutsu.Graph.rowHeight", 22)
        private val COMMIT_RADIUS = JBValue.UIInteger("Jujutsu.Graph.commitRadius", 4)
        internal val HORIZONTAL_PADDING = JBValue.UIInteger("Jujutsu.Graph.horizontalPadding", 4)
        private val ELIDED_WAVE_AMPLITUDE = JBValue.UIInteger("Jujutsu.Graph.elidedWaveAmplitude", 2)
        private val ELIDED_WAVE_LENGTH = JBValue.UIInteger("Jujutsu.Graph.elidedWaveLength", 6)

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

        /**
         * The state to paint a stub for, or null when [node] has no unresolved parent at all.
         * [ParentState.NOT_LOADED] (a paged-window boundary, or beyond a non-paged limit) gets a
         * faded straight stub; [ParentState.HIDDEN] (genuinely elided/filtered, jj's `~`) keeps
         * the wiggle. When a row mixes both (rare - e.g. a merge with two unresolved parents of
         * different states), NOT_LOADED wins: it's the actionable one (click to load), where
         * HIDDEN's target needs the filter cleared first (jj-idea-hlu3).
         */
        internal fun stubStateToDraw(node: GraphNode): ParentState? {
            val states = node.unresolvedParents.values
            return when {
                states.isEmpty() -> null
                ParentState.NOT_LOADED in states -> ParentState.NOT_LOADED
                else -> ParentState.HIDDEN
            }
        }
    }

    /** Lazily computed per-row passthrough lanes derived from entries' passthroughLanes */
    private var rowPassthroughCache: Map<Int, Set<Int>>? = null

    private fun getRowPassthroughs(model: JujutsuLogTableModel): Map<Int, Set<Int>> {
        rowPassthroughCache?.let { return it }

        val rowByKey = mutableMapOf<ChangeKey, Int>()
        for (row in 0 until model.rowCount) {
            val entry = model.getEntry(row) ?: continue
            rowByKey[entry.key] = row
        }

        val result = mutableMapOf<Int, MutableSet<Int>>()
        for (row in 0 until model.rowCount) {
            val entry = model.getEntry(row) ?: continue
            val node = graphNodes[entry.key] ?: continue
            for ((parentKey, lane) in node.passthroughLanes) {
                val parentRow = rowByKey[parentKey] ?: continue
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
        return GraphAndDescriptionPanel(table, row, column, isSelected, isHovered, mousePos)
    }

    private inner class GraphAndDescriptionPanel(
        private val table: JTable,
        private val row: Int,
        private val column: Int,
        private val isSelected: Boolean,
        isHovered: Boolean,
        private val mousePos: Point?
    ) : JPanel(null) {
        private val entry = (table.model as? JujutsuLogTableModel)?.getEntry(row)
        private val graphNode = entry?.let { graphNodes[it.key] }
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

        private fun buildTooltip(entry: LogEntry) = htmlString(linkifier = linkifier) {
            if (entry.pending) {
                // No repo/author/id to show - appendSummaryAndStatuses already covers the whole
                // tooltip content for a pending entry.
                appendSummaryAndStatuses(entry)
                return@htmlString
            }

            appendSummaryAndStatuses(entry)
            entry.author?.let { author ->
                append(author)
                entry.authorTimestamp?.let { ts ->
                    append(" \u00b7 ")
                    append(DateTimeFormatter.formatAbsolute(ts))
                }
                append("\n")
            }
            control("<pre style='white-space: pre-wrap;'>", "</pre>") {
                appendSummary(entry.description)
            }
        }

        /**
         * Build this row's [LaidOutCell] once and paint from it, applying the two hover cues its
         * content can carry: [HoverCue.ISSUE_LINK_UNDERLINE] underlines the hovered fragment
         * (jj-idea-91qf, jj-idea-vrmv - matching author/committer names, jj-idea-iesq);
         * [HoverCue.REF_BACKGROUND] paints a highlight behind the hovered bookmark/tag chip instead
         * (jj-idea-a52h), since those have no left-click action of their own (jj-idea-wkcz). The two
         * are mutually exclusive - only one fragment/chip is ever hovered at a time.
         */
        private fun configureTextPanel(entry: LogEntry) {
            val fg = if (isSelected) table.selectionForeground else table.foreground
            val columnWidth = table.columnModel.getColumn(column).width
            val frc = table.getFontMetrics(table.font).fontRenderContext
            val textStart = textStartX()
            val laidOut = LaidOutCell.forRow(
                entry,
                columnWidth,
                textStart,
                columnManager,
                linkifier,
                fg,
                table.font,
                frc
            )

            val hovered = hoveredLinkTarget(laidOut)
            val hoveredCue = hovered?.let { LogClickTarget.resolve(it, project = null, listOf(entry))?.hoverCue }
            val underlineTarget = hovered.takeIf { hoveredCue == HoverCue.ISSUE_LINK_UNDERLINE }

            textPanel.configure(
                leftCanvas = FragmentRecordingCanvas(laidOut.leftFragments.underlining(underlineTarget)),
                rightCanvas = FragmentRecordingCanvas(laidOut.rightFragments.underlining(underlineTarget)),
                cellWidth = columnWidth - textStart,
                background = background,
                rightHighlightTarget = hovered.takeIf { hoveredCue == HoverCue.REF_BACKGROUND }
            )
        }

        /** The link target under [mousePos] within this cell, if any. */
        private fun hoveredLinkTarget(laidOut: LaidOutCell): URI? {
            val point = mousePos ?: return null
            val cellRect = table.getCellRect(row, column, false)
            if (!cellRect.contains(point)) return null
            return laidOut.linkTargetAt(point.x - cellRect.x)
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
                    val prevNode = graphNodes[prevEntry.key] ?: continue
                    if (prevEntry.parentKeys.contains(entry.key)) activeLanes.add(prevNode.lane)
                }
                for (parentLane in graphNode.parentLanes) {
                    if (parentLane != graphNode.lane) activeLanes.add(parentLane)
                }
                graphNode.stubLane?.let { activeLanes.add(it) }
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
            stubStateToDraw(node)?.let { state ->
                val stubLane = node.stubLane ?: node.lane
                val stubX = startX + laneWidth / 2 + stubLane * laneWidth
                drawElidedParentStub(g2d, colorForLane(stubLane), stubX, commitY, state)
            }
            drawCommitCircle(g2d, node, commitX, commitY)
        }

        /**
         * Draws an unresolved-parent stub from just below the commit circle to the row's bottom
         * edge: a continuous wiggle for [ParentState.HIDDEN], echoing the `~` jj uses for
         * elision in `jj log` (jj-idea-2c8k); a faded straight line for [ParentState.NOT_LOADED]
         * - "the line continues, we just haven't drawn the rest" rather than a deliberate skip
         * (jj-idea-xi58). [stubX] is [node]'s own lane unless a mixed merge needed a free lane
         * of its own ([GraphNode.stubLane], jj-idea-1pgy).
         */
        private fun drawElidedParentStub(g2d: Graphics2D, color: Color, stubX: Int, commitY: Int, state: ParentState) {
            val commitRadius = COMMIT_RADIUS.get()
            val startY = commitY + commitRadius

            if (state == ParentState.NOT_LOADED) {
                val composite = g2d.composite
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
                g2d.color = color
                g2d.drawLine(stubX, startY, stubX, height)
                g2d.composite = composite
                return
            }

            val amplitude = ELIDED_WAVE_AMPLITUDE.get().toFloat()
            val wavelength = ELIDED_WAVE_LENGTH.get().toFloat()

            val path = Path2D.Float()
            path.moveTo(stubX.toDouble(), startY.toDouble())
            var y = startY
            while (y <= height) {
                val x = stubX + amplitude * kotlin.math.sin(2 * Math.PI.toFloat() * (y - startY) / wavelength)
                path.lineTo(x.toDouble(), y.toDouble())
                y++
            }

            g2d.color = color
            g2d.draw(path)
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
                val prevNode = graphNodes[prevEntry.key] ?: continue

                val parentIndex = prevEntry.parentKeys.indexOf(currentEntry.key)
                if (parentIndex >= 0) {
                    val childLane = prevNode.lane
                    val childHasMultipleParents = prevNode.parentLanes.size > 1

                    val passThroughLane = prevNode.passthroughLanes[currentEntry.key]
                    val connectionLane = passThroughLane
                        ?: if (childHasMultipleParents) node.lane else childLane
                    val connectionX = graphStartX + laneWidth / 2 + connectionLane * laneWidth
                    g2d.color = colorForLane(connectionLane)
                    g2d.drawLine(connectionX, 0, commitX, commitY)
                }
            }

            val childHasMultipleParents = node.parentLanes.size > 1

            for (parentKey in currentEntry.parentKeys) {
                val parentLane = graphNodes[parentKey]?.lane ?: continue

                val passThroughLane = node.passthroughLanes[parentKey]
                val targetLane = passThroughLane
                    ?: if (childHasMultipleParents && parentLane != node.lane) parentLane else node.lane
                val targetX = graphStartX + laneWidth / 2 + targetLane * laneWidth
                g2d.color = if (targetLane == node.lane) node.color else colorForLane(targetLane)
                g2d.drawLine(commitX, commitY, targetX, height)
            }
        }

        override fun getPreferredSize() =
            Dimension(table.columnModel.getColumn(column).width, ROW_HEIGHT.get())
    }
}
