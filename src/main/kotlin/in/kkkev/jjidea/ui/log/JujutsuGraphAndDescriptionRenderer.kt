package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBValue
import com.intellij.util.ui.UIUtil
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
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
        private val EDGE_HOVER_STROKE_WIDTH = JBValue.Float(2.6f)

        // Lane colors - the single source of truth GraphNode.color derives from (jj-idea-a0wp).
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

        /** The x-coordinate of [lane]'s centerline, given the graph's [startX] and [laneWidth] -
         * the one formula every paint site and the long-edge hit test (jj-idea-sc8m) share. */
        internal fun laneX(lane: Int, startX: Int, laneWidth: Int) = startX + laneWidth / 2 + lane * laneWidth
    }

    /**
     * Lazily built once per renderer instance (a fresh renderer is installed on every graph
     * update - see [JujutsuLogTable.updateGraph] - so this needs no invalidation logic, same
     * lifetime the old `rowPassthroughCache` had). Replaces that field plus
     * [in.kkkev.jjidea.ui.log.graphTextStartX]'s separate uncached pass with one shared index
     * (jj-idea-sc8m) - also the seam the long-edge hover/click hit test reads from
     * ([JujutsuLogTable.graphEdgeHoverAt]).
     */
    private var edgeIndexCache: GraphEdgeIndex? = null

    internal fun edgeIndex(model: JujutsuLogTableModel): GraphEdgeIndex {
        edgeIndexCache?.let { return it }
        val index = GraphEdgeIndex.build(model.getFilteredEntries(), graphNodes)
        edgeIndexCache = index
        return index
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
        private val isHovered: Boolean,
        private val mousePos: Point?
    ) : JPanel(null) {
        private val entry = (table.model as? JujutsuLogTableModel)?.getEntry(row)
        private val graphNode = entry?.let { graphNodes[it.key] }

        /**
         * The long edge currently under the pointer anywhere in the table (jj-idea-sc8m) - null
         * for the Split/Squash/Rebase/Duplicate picker tables, which aren't [JujutsuLogTable] and
         * so never set this. Deliberately *not* gated to this row being the hovered one (unlike
         * [hoveredLinkTarget]'s single-cell text hover): a long edge's thickened span covers many
         * rows, so every row's panel needs the same shared reference and does its own `row in
         * span` check - gating this to `isHovered` (an earlier bug) meant only the exact row under
         * the mouse ever saw it, so thickening never covered more than one row.
         */
        private val hoveredEdge: HoveredEdge? = (table as? JujutsuLogTable)?.hoveredEdge

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
                toolTipText = hoveredEdgeTooltip() ?: buildTooltip(e)
                configureTextPanel(e)
            }
        }

        /**
         * Tooltip for [hoveredEdge], replacing the row's usual [buildTooltip] while a long edge is
         * hovered (jj-idea-sc8m): names the target for an already-loaded parent/child, explains
         * "not loaded yet - click to load" for a [ParentState.NOT_LOADED] stub, or "hidden by the
         * current filter" (no click hint - [HoveredEdge.navigable] is false) for [ParentState.HIDDEN].
         * Gated to [isHovered] (unlike [hoveredEdge] itself, which every row's panel shares) since a
         * tooltip is a single-cell affordance - only the row actually under the mouse should show one.
         */
        private fun hoveredEdgeTooltip(): String? {
            val hovered = hoveredEdge?.takeIf { isHovered } ?: return null
            val model = table.model as? JujutsuLogTableModel ?: return null
            return htmlString(linkifier = linkifier) {
                when (hovered.edge.state) {
                    ParentState.NOT_LOADED -> append("Parent not loaded yet — click to load")
                    ParentState.HIDDEN -> {
                        append("Hidden by the current filter")
                        model.entryFor(hovered.edge.parent)?.let { target ->
                            append("\n")
                            appendSummary(target.description)
                        }
                    }
                    null -> {
                        val target = model.entryFor(hovered.targetKey) ?: return@htmlString
                        val targetRow = edgeIndex(model).rowOf(hovered.targetKey)
                        appendSummary(target.description)
                        val way = if (hovered.direction == EdgeDirection.DOWN) "down" else "up"
                        targetRow?.let { append("\n${kotlin.math.abs(it - row)} rows $way") }
                    }
                }
            }
        }

        /**
         * This dangling-head [entry]'s nearest ancestor bookmark distance, for
         * [appendSummaryAndStatuses]'s status tag (jj-idea-uyu9 follow-up) - `null` for every
         * non-dangling-head row, the working copy (already has its own status tag), a picker
         * table with no [JujutsuLogTable]/`Project` to ask, or a dangling head beyond the bounded
         * `danglingHeads` list's cap.
         */
        private fun danglingHeadClosest(entry: LogEntry): ClosestBookmarks? {
            if (!entry.isDanglingHead || entry.isWorkingCopy) return null
            val project = (table as? JujutsuLogTable)?.project ?: return null
            return project.stateModel.danglingHeads.value[entry.repo]?.firstOrNull { it.id == entry.id }?.closest
        }

        private fun buildTooltip(entry: LogEntry) = htmlString(linkifier = linkifier) {
            if (entry.pending) {
                // No repo/author/id to show - appendSummaryAndStatuses already covers the whole
                // tooltip content for a pending entry.
                appendSummaryAndStatuses(entry)
                return@htmlString
            }

            appendSummaryAndStatuses(entry, danglingHeadClosest(entry))
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
            val model = table.model as? JujutsuLogTableModel
                ?: return HORIZONTAL_PADDING.get() + (graphNode.lane + 1) * LANE_WIDTH.get()
            return graphTextStartX(row, model, graphNodes, edgeIndex(model))
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
            model.getEntry(row) ?: return

            drawPassThroughLines(g2d, startX, laneWidth)

            val commitX = laneX(node.lane, startX, laneWidth)
            val commitY = height / 2

            drawLinesToParents(g2d, commitX, commitY, row, startX, laneWidth)
            edgeIndex(model).stubEdgeAt(row)?.let { (stubLane, stubEdge) ->
                val stubX = laneX(stubLane, startX, laneWidth)
                val state = stubEdge.state ?: return@let
                drawElidedParentStub(g2d, colorForLane(stubLane), stubX, commitY, state, isHoveredEdge(stubEdge))
            }
            drawCommitCircle(g2d, node, commitX, commitY)
        }

        /**
         * Whether [edge] (the specific connector/passthrough segment about to be drawn, not just a
         * lane number) is the one currently hovered - comparing by edge identity, not by lane,
         * matters right at a hovered edge's endpoint row: a lane is freed and often immediately
         * reused there by a *different*, unrelated edge (e.g. the dot's own separate connector to
         * its own parent), and comparing lanes alone would bleed the thickened/emphasized
         * treatment into that unrelated segment past the circle where the hovered edge actually
         * ends (jj-idea-sc8m round 3).
         */
        private fun isHoveredEdge(edge: GraphEdge?) = edge != null && edge == hoveredEdge?.edge

        /** Whether [edge] should paint with the thickened hover stroke: it's the hovered edge, and
         * this row is on [HoveredEdge.emphasized]'s (the [HoveredEdge.direction]) side of the pivot.
         * The other side paints as plain, full-opacity, normal-width - not a dimmed/translucent
         * overlay, which visually muddied the line instead of reading as a clean de-emphasis. */
        private fun isEmphasizedEdge(edge: GraphEdge?) = isHoveredEdge(edge) && hoveredEdge?.emphasized(row) == true

        private fun strokeForEdge(edge: GraphEdge?): Stroke =
            if (isEmphasizedEdge(edge)) BasicStroke(EDGE_HOVER_STROKE_WIDTH.getFloat()) else BasicStroke(1f)

        /** Draws one connector/passthrough segment with [strokeForEdge] applied and restored, so
         * callers don't each hand-roll the save/apply/restore dance. [edge] is the specific
         * [GraphEdge] this segment belongs to (see [isHoveredEdge] for why that matters, not just
         * the lane it's drawn in). */
        private fun drawLaneLine(g2d: Graphics2D, edge: GraphEdge?, color: Color, x1: Int, y1: Int, x2: Int, y2: Int) {
            val originalStroke = g2d.stroke
            g2d.color = color
            g2d.stroke = strokeForEdge(edge)
            g2d.drawLine(x1, y1, x2, y2)
            g2d.stroke = originalStroke
        }

        /**
         * Draws an unresolved-parent stub from just below the commit circle to the row's bottom
         * edge: a continuous wiggle for [ParentState.HIDDEN], echoing the `~` jj uses for
         * elision in `jj log` (jj-idea-2c8k); a faded straight line for [ParentState.NOT_LOADED]
         * - "the line continues, we just haven't drawn the rest" rather than a deliberate skip
         * (jj-idea-xi58). [stubX] is [node]'s own lane unless a mixed merge needed a free lane
         * of its own ([GraphNode.stubLane], jj-idea-1pgy). [thickened] applies the long-edge hover
         * stroke (jj-idea-sc8m) when this stub is the one currently hovered.
         */
        private fun drawElidedParentStub(
            g2d: Graphics2D,
            color: Color,
            stubX: Int,
            commitY: Int,
            state: ParentState,
            thickened: Boolean
        ) {
            val commitRadius = COMMIT_RADIUS.get()
            val startY = commitY + commitRadius
            val originalStroke = g2d.stroke
            if (thickened) g2d.stroke = BasicStroke(EDGE_HOVER_STROKE_WIDTH.getFloat())

            if (state == ParentState.NOT_LOADED) {
                val composite = g2d.composite
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
                g2d.color = color
                g2d.drawLine(stubX, startY, stubX, height)
                g2d.composite = composite
                g2d.stroke = originalStroke
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
            g2d.stroke = originalStroke
        }

        private fun drawPassThroughLines(g2d: Graphics2D, graphStartX: Int, laneWidth: Int) {
            val model = table.model as? JujutsuLogTableModel ?: return
            val index = edgeIndex(model)
            val rowPT = index.passthroughLanes(row)

            for (lane in rowPT) {
                val passX = laneX(lane, graphStartX, laneWidth)
                drawLaneLine(g2d, index.edgeAt(row, lane), colorForLane(lane), passX, 0, passX, height)
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

        /**
         * Draws every connector touching [currentRow] as an endpoint - incoming from a child row
         * above (top edge to this row's commit circle) and outgoing to a parent row below (commit
         * circle to bottom edge) - reading both straight off [edgeIndex], which already computed
         * this exact lane geometry once in [GraphEdgeIndex.build]. Previously rescanned every row
         * from 0 to `currentRow` on every paint to find incoming edges (O(row index) per row,
         * O(visible rows × total rows) per repaint) - jj-idea-a0wp.
         */
        private fun drawLinesToParents(
            g2d: Graphics2D,
            commitX: Int,
            commitY: Int,
            currentRow: Int,
            graphStartX: Int,
            laneWidth: Int
        ) {
            val model = table.model as? JujutsuLogTableModel ?: return
            val index = edgeIndex(model)

            for ((connectionLane, edge) in index.incomingEdges(currentRow)) {
                val connectionX = laneX(connectionLane, graphStartX, laneWidth)
                drawLaneLine(g2d, edge, colorForLane(connectionLane), connectionX, 0, commitX, commitY)
            }

            for ((targetLane, edge) in index.outgoingEdges(currentRow)) {
                val targetX = laneX(targetLane, graphStartX, laneWidth)
                drawLaneLine(g2d, edge, colorForLane(targetLane), commitX, commitY, targetX, height)
            }
        }

        override fun getPreferredSize() =
            Dimension(table.columnModel.getColumn(column).width, ROW_HEIGHT.get())
    }
}
