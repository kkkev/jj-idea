package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.ui.log.graph.ParentState

/**
 * One graph connector: a real edge between two laid-out rows ([state] null), or an
 * unresolved-parent stub with no laid-out [parent] at all ([state] non-null - see [ParentState]).
 */
data class GraphEdge(val child: ChangeKey, val parent: ChangeKey, val state: ParentState?)

/** Which way navigation goes: [DOWN] towards history (older, further down the table, i.e. the
 * parent) or [UP] towards a child (newer, further up) - see [GraphEdgeIndex.hoveredEdgeAt] for how
 * it's chosen. */
enum class EdgeDirection { UP, DOWN }

/**
 * A [GraphEdge] currently under the pointer - see [GraphEdgeIndex.hoveredEdgeAt] for how
 * [direction] is decided. [span] is the edge's full visible extent clamped to the viewport - the
 * whole span paints thickened, but [emphasized] additionally marks which side of [pivotRow] (the
 * [direction] side) gets full-strength color, the other side dimmed, as a direction cue alongside
 * the directional cursor: when one end is already visible, [pivotRow] sits at that visible end so
 * the *entire* span is emphasized, right up to its circle; when both ends are off-screen,
 * [pivotRow] is the viewport's middle row (the same row the direction choice pivots on).
 * [navigable] is false only for a [ParentState.HIDDEN] stub - its target can't be shown without
 * clearing the filter first (jj-idea-hlu3's job), so it gets a tooltip but no click action.
 */
data class HoveredEdge(
    val edge: GraphEdge,
    val lane: Int,
    val direction: EdgeDirection,
    val span: IntRange,
    val pivotRow: Int,
    val navigable: Boolean
) {
    val targetKey: ChangeKey get() = if (direction == EdgeDirection.DOWN) edge.parent else edge.child

    /** Whether [row] is on the [direction] side of [pivotRow] - the "lit up" side of the line. */
    fun emphasized(row: Int) = if (direction == EdgeDirection.DOWN) row >= pivotRow else row <= pivotRow
}

/**
 * Pure, font-free index over one graph layout's rows, replacing three separate O(rows)-per-call
 * passes that used to live in [JujutsuGraphAndDescriptionRenderer] (`getRowPassthroughs`) and
 * [graphTextStartX] with one shared pass, built once per graph update (jj-idea-sc8m). Also the
 * seam for long-edge hover/click hit-testing: [edgeAt] resolves a lane click to the edge occupying
 * it, [hoveredEdgeAt] additionally classifies it as long-or-not against a viewport.
 *
 * Built once via [build] and reused for the lifetime of one rendered graph (no invalidation logic
 * needed - a fresh index is built alongside every fresh [GraphNode] map, exactly like the
 * renderer's old per-instance cache).
 */
class GraphEdgeIndex private constructor(
    private val rowOfKey: Map<ChangeKey, Int>,
    private val edgesByRow: Map<Int, Map<Int, GraphEdge>>,
    private val rightmostLaneByRow: Map<Int, Int>,
    private val ownLaneByRow: Map<Int, Int>,
    val operationCount: Long
) {
    fun rowOf(key: ChangeKey): Int? = rowOfKey[key]

    /** The [GraphEdge] occupying [lane] at [row] (a real connector or a stub), or null. */
    fun edgeAt(row: Int, lane: Int): GraphEdge? = edgesByRow[row]?.get(lane)

    /**
     * Lanes with a plain vertical passthrough line drawn through [row] - i.e. [row] is strictly
     * between the edge's child and parent rows, not one of its own endpoints. An endpoint row
     * paints a diagonal instead (`drawLinesToParents`), never a plain vertical - excluding it here
     * matters for a merge commit whose connector lane differs from its own lane
     * ([GraphNode.parentLanes].size > 1, a later pure-merge sibling): filtering only `!= ownLane`
     * (the pre-jj-idea-sc8m check) missed this case and painted a spurious extra vertical line
     * alongside the real diagonal at the merge row.
     */
    fun passthroughLanes(row: Int): Set<Int> {
        val edges = edgesByRow[row] ?: return emptySet()
        return edges.filterValues { edge ->
            edge.state == null && rowOfKey[edge.child] != row && rowOfKey[edge.parent] != row
        }.keys
    }

    /** The rightmost lane active at [row] - used to place the text column after the graph. */
    fun rightmostLane(row: Int): Int = rightmostLaneByRow[row] ?: (ownLaneByRow[row] ?: 0)

    /**
     * Map [localX] to a lane index (lanes are evenly spaced [laneWidth] apart, starting at
     * [startX]), or null if [localX] is left of the graph entirely.
     */
    fun laneAt(localX: Int, startX: Int, laneWidth: Int): Int? {
        if (localX < startX) return null
        return (localX - startX) / laneWidth
    }

    /**
     * The long-edge hover state at ([row], [lane]) given the currently visible row range
     * [visibleRows], or null when there's no edge there or it isn't "long" (both ends already
     * visible).
     *
     * Direction is *not* chosen by where within the row the pointer sits (jj-idea-sc8m round 3
     * replaces round 2's per-row upper/lower split, which produced a new, independent decision on
     * every row - moving the pointer one pixel across a row boundary could flip the direction back
     * and forth with no stable transition point, and it fought the "always point away from a
     * visible end" rule below). Instead:
     * - If exactly one end is already visible, direction always points *away* from it, at every
     *   row along the whole span - there is nothing to choose, since that end doesn't need
     *   navigating to.
     * - If both ends are off-screen, direction is decided once per hover by which half of the
     *   *viewport* [row] falls in (not the row's own pixel bounds): the viewport's middle row is
     *   the single transition point, so it only changes when you scroll, not as you move within a
     *   row or between adjacent rows on the same side of that midpoint.
     */
    fun hoveredEdgeAt(row: Int, lane: Int, visibleRows: IntRange): HoveredEdge? {
        val edge = edgeAt(row, lane) ?: return null
        if (edge.state != null) {
            // A stub has no child-ward direction to offer - it's this row's own commit, already
            // visible/selected. Only "down towards the missing parent" is meaningful.
            return HoveredEdge(
                edge = edge,
                lane = lane,
                direction = EdgeDirection.DOWN,
                span = row..row,
                pivotRow = row,
                navigable = edge.state != ParentState.HIDDEN
            )
        }
        val childRow = rowOfKey[edge.child] ?: return null
        val parentRow = rowOfKey[edge.parent] ?: return null
        val childVisible = childRow in visibleRows
        val parentVisible = parentRow in visibleRows
        if (childVisible && parentVisible) return null
        val span = maxOf(childRow, visibleRows.first)..minOf(parentRow, visibleRows.last)
        val (direction, pivotRow) = when {
            childVisible -> EdgeDirection.DOWN to span.first
            parentVisible -> EdgeDirection.UP to span.last
            else -> {
                val viewportMiddle = (visibleRows.first + visibleRows.last) / 2
                (if (row <= viewportMiddle) EdgeDirection.UP else EdgeDirection.DOWN) to viewportMiddle
            }
        }
        return HoveredEdge(
            edge = edge,
            lane = lane,
            direction = direction,
            span = span,
            pivotRow = pivotRow,
            navigable = true
        )
    }

    companion object {
        /**
         * Build the index from [entries] (row order, as displayed - the same list
         * [CommitGraphBuilder.buildGraph] was called with) and [nodes] (the resulting layout,
         * keyed the same way [JujutsuLogTable.graphNodes] is). Mirrors the connector geometry
         * [JujutsuGraphAndDescriptionRenderer.drawLinesToParents]/`drawPassThroughLines` already
         * paint, so a lane clicked here is the same lane a line is drawn in. [entries] (not just
         * their keys) is needed because [GraphNode] itself only records non-adjacent parents
         * ([GraphNode.passthroughLanes]) and unresolved ones ([GraphNode.unresolvedParents]) - an
         * adjacent loaded parent has no record on the node at all, only on the entry.
         *
         * When two edges would occupy the same (row, lane) - only possible at an endpoint row,
         * e.g. a linear chain reusing lane 0 - the longest-spanning edge wins: it's the one the
         * long-edge interaction is about.
         */
        fun build(entries: List<GraphableEntry>, nodes: Map<ChangeKey, GraphNode>): GraphEdgeIndex {
            var operationCount = 0L
            val rowOfKey = HashMap<ChangeKey, Int>(entries.size * 2)
            entries.forEachIndexed { row, entry -> rowOfKey[entry.key] = row }

            val edgesByRow = HashMap<Int, MutableMap<Int, GraphEdge>>()
            val spanByRowLane = HashMap<Int, MutableMap<Int, Int>>() // row -> lane -> span length of edge occupying it
            val ownLaneByRow = HashMap<Int, Int>(entries.size * 2)
            val rightmostLaneByRow = HashMap<Int, Int>(entries.size * 2)

            fun place(row: Int, lane: Int, edge: GraphEdge, span: Int) {
                val existingSpan = spanByRowLane.getOrPut(row) { mutableMapOf() }[lane]
                if (existingSpan != null && existingSpan >= span) return
                spanByRowLane[row]!![lane] = span
                edgesByRow.getOrPut(row) { mutableMapOf() }[lane] = edge
            }

            fun markActive(row: Int, lane: Int) {
                rightmostLaneByRow[row] = maxOf(rightmostLaneByRow[row] ?: -1, lane)
            }

            for ((row, entry) in entries.withIndex()) {
                val key = entry.key
                val node = nodes[key] ?: continue
                operationCount++
                ownLaneByRow[row] = node.lane
                markActive(row, node.lane)

                // Real edges to loaded parents - same lane choice as
                // JujutsuGraphAndDescriptionRenderer.drawLinesToParents.
                val childHasMultipleParents = node.parentLanes.size > 1
                for (parentKey in entry.parentKeys) {
                    val parentNode = nodes[parentKey] ?: continue // unresolved - handled by the stub below
                    val parentRow = rowOfKey[parentKey] ?: continue
                    operationCount++
                    val passThroughLane = node.passthroughLanes[parentKey]
                    val lane = passThroughLane
                        ?: if (childHasMultipleParents && parentNode.lane != node.lane) parentNode.lane else node.lane
                    val edge = GraphEdge(child = key, parent = parentKey, state = null)
                    val span = parentRow - row
                    for (r in row..parentRow) {
                        // The actual scale-sensitive work: one increment per row this edge's span touches.
                        operationCount++
                        place(r, lane, edge, span)
                        markActive(r, lane)
                    }
                }

                // Stub for unresolved parents - one row, `stubLane` or the row's own lane.
                stubTargetFor(node)?.let { (parentKey, state) ->
                    operationCount++
                    val stubLane = node.stubLane ?: node.lane
                    place(row, stubLane, GraphEdge(child = key, parent = parentKey, state = state), span = 0)
                    markActive(row, stubLane)
                }
            }

            return GraphEdgeIndex(
                rowOfKey = rowOfKey,
                edgesByRow = edgesByRow,
                rightmostLaneByRow = rightmostLaneByRow,
                ownLaneByRow = ownLaneByRow,
                operationCount = operationCount
            )
        }
    }
}

/**
 * The unresolved parent [stubTargetFor] should draw a stub for, paired with its [ParentState]
 * (matching [JujutsuGraphAndDescriptionRenderer.stubStateToDraw]'s NOT_LOADED-wins precedence),
 * or null when [node] has no unresolved parent at all.
 */
internal fun stubTargetFor(node: GraphNode): Pair<ChangeKey, ParentState>? {
    val notLoaded = node.unresolvedParents.entries.firstOrNull { it.value == ParentState.NOT_LOADED }
    if (notLoaded != null) return notLoaded.key to notLoaded.value
    return node.unresolvedParents.entries.firstOrNull()?.let { it.key to it.value }
}
