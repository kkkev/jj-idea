package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.ui.log.graph.LONG_EDGE_PART_ROWS
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

/** One connector's endpoint at a specific row/lane - [edge] plus which [lane] it occupies there,
 * as returned by [GraphEdgeIndex.incomingEdges]/[GraphEdgeIndex.outgoingEdges]. */
data class RowEdge(val lane: Int, val edge: GraphEdge)

/**
 * The arrow glyph a collapsed long edge's cap paints at a row (jj-idea-66rr): [direction] [DOWN] on
 * the top cap (the line continues towards the parent), [UP] on the bottom cap (towards the child).
 */
data class CapArrow(val lane: Int, val edge: GraphEdge, val direction: EdgeDirection)

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
 * One connector occupying [lane] over rows [start]..[end] (inclusive, `start <= end`). [seq] is the
 * order it was recorded in, so equal-length spans on a (row, lane) tie-break to the earliest.
 */
internal class LaneSpan(val start: Int, val end: Int, val edge: GraphEdge, val seq: Int) {
    val length: Int get() = end - start
}

/** One lane's spans, sorted by [LaneSpan.start], with the running max of [LaneSpan.end] so a
 * stabbing query can stop as soon as no earlier span can still reach the queried row. */
internal class LaneSpans(sortedByStart: List<LaneSpan>) {
    private val spans = sortedByStart
    private val prefixMaxEnd = IntArray(spans.size).also {
        var max = Int.MIN_VALUE
        for (i in spans.indices) {
            max = maxOf(max, spans[i].end)
            it[i] = max
        }
    }

    /**
     * The span occupying [row]: the longest covering one, the earliest-recorded among equals (what
     * the old per-cell `place()` kept). O(log k + spans overlapping [row]) - lanes only overlap at
     * endpoint rows, so effectively O(log k).
     */
    fun at(row: Int): LaneSpan? {
        // Last span with start <= row.
        var lo = 0
        var hi = spans.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (spans[mid].start <= row) lo = mid + 1 else hi = mid
        }
        var best: LaneSpan? = null
        var i = lo - 1
        while (i >= 0 && prefixMaxEnd[i] >= row) {
            val span = spans[i]
            if (span.end >= row &&
                (best == null || span.length > best.length || (span.length == best.length && span.seq < best.seq))
            ) {
                best = span
            }
            i--
        }
        return best
    }
}

/** Per-row answers derived from the lane spans - see [GraphEdgeIndex.passthroughLanes]/[GraphEdgeIndex.rightmostLane]. */
private class RowSummary(val passthroughLanes: Set<Int>, val rightmostLane: Int)

/**
 * Pure, font-free index over one graph layout's rows, replacing three separate O(rows)-per-call
 * passes that used to live in [JujutsuGraphAndDescriptionRenderer] (`getRowPassthroughs`) and
 * [graphTextStartX] with one shared pass, built once per graph update (jj-idea-sc8m). Also the
 * seam for long-edge hover/click hit-testing: [edgeAt] resolves a lane click to the edge occupying
 * it, [hoveredEdgeAt] additionally classifies it as long-or-not against a viewport.
 *
 * [incomingEdges]/[outgoingEdges]/[stubEdgeAt] additionally let a row's paint (jj-idea-a0wp) read
 * every edge touching that row directly off the index instead of rescanning the whole graph above
 * it - see their docs.
 *
 * Each edge is stored once, as a row interval on its lane (jj-idea-2570.6), not once per row it
 * spans: the old per-(row, lane) cells made [build] and the heap O(sum of edge spans) = rows x
 * active lanes (~557 cells/row on git/git, 5-6 s EDT freezes at ~47k rows). [build] is now
 * O(rows + edges + sort), and per-row answers are computed on demand for the rows actually painted.
 *
 * Built once via [build] and reused for the lifetime of one rendered graph (no invalidation logic
 * needed - a fresh index is built alongside every fresh [GraphNode] map, exactly like the
 * renderer's old per-instance cache).
 */
class GraphEdgeIndex private constructor(
    private val rowOfKey: Map<ChangeKey, Int>,
    private val spansByLane: List<LaneSpans?>,
    private val incomingByRow: Map<Int, List<RowEdge>>,
    private val outgoingByRow: Map<Int, List<RowEdge>>,
    private val stubByRow: Map<Int, RowEdge>,
    private val ownLaneByRow: Map<Int, Int>,
    private val arrowsByRow: Map<Int, List<CapArrow>>,
    private val collapsedEdges: Set<GraphEdge>,
    val operationCount: Long
) {
    private val rowSummaries = java.util.concurrent.ConcurrentHashMap<Int, RowSummary>()

    private fun spanAt(row: Int, lane: Int): LaneSpan? = spansByLane.getOrNull(lane)?.at(row)

    private fun summaryOf(row: Int): RowSummary {
        rowSummaries[row]?.let { return it }
        var rightmost = -1
        val passthrough = HashSet<Int>()
        for (lane in spansByLane.indices) {
            val span = spanAt(row, lane) ?: continue
            rightmost = lane // ascending, so the last hit is the rightmost
            // Strictly between the endpoints, and a real edge (a stub is span 0, never strictly inside).
            if (span.edge.state == null && span.start != row && span.end != row) passthrough += lane
        }
        val summary = RowSummary(
            passthrough,
            if (rightmost >= 0) maxOf(rightmost, ownLaneByRow[row] ?: -1) else (ownLaneByRow[row] ?: 0)
        )
        if (rowSummaries.size >= MAX_MEMOISED_ROWS) rowSummaries.clear()
        rowSummaries[row] = summary
        return summary
    }

    fun rowOf(key: ChangeKey): Int? = rowOfKey[key]

    /** The [GraphEdge] occupying [lane] at [row] (a real connector or a stub), or null. */
    fun edgeAt(row: Int, lane: Int): GraphEdge? = spanAt(row, lane)?.edge

    /**
     * Every real edge arriving at [row] from a child row above it (`prevRow -> row`) - i.e. [row]
     * is the *parent* end. Lets a row's paint draw its incoming connectors in O(incident edges)
     * instead of [JujutsuGraphAndDescriptionRenderer]'s old `drawLinesToParents`, which rescanned
     * every row above the current one on every paint (jj-idea-a0wp) purely to find these.
     */
    fun incomingEdges(row: Int): List<RowEdge> = incomingByRow[row] ?: emptyList()

    /** Every real edge leaving [row] towards one of its own parents (`row -> parentRow`) - i.e.
     * [row] is the *child* end. The outgoing counterpart to [incomingEdges]. */
    fun outgoingEdges(row: Int): List<RowEdge> = outgoingByRow[row] ?: emptyList()

    /** The long-edge cap arrows to paint at [row] (jj-idea-66rr) - empty for nearly every row. */
    fun capArrows(row: Int): List<CapArrow> = arrowsByRow[row] ?: emptyList()

    /** The unresolved-parent stub at [row] ([GraphNode.stubLane] or the row's own lane), or null
     * when [row] has no unresolved parent - the paint-time equivalent of [stubTargetFor]. */
    fun stubEdgeAt(row: Int): RowEdge? = stubByRow[row]

    /**
     * Lanes with a plain vertical passthrough line drawn through [row] - i.e. [row] is strictly
     * between the edge's child and parent rows, not one of its own endpoints. An endpoint row
     * paints a diagonal instead (`drawLinesToParents`), never a plain vertical - excluding it here
     * matters for a merge commit whose connector lane differs from its own lane
     * ([GraphNode.parentLanes].size > 1, a later pure-merge sibling): filtering only `!= ownLane`
     * (the pre-jj-idea-sc8m check) missed this case and painted a spurious extra vertical line
     * alongside the real diagonal at the merge row.
     */
    fun passthroughLanes(row: Int): Set<Int> = summaryOf(row).passthroughLanes

    /** The rightmost lane active at [row] - used to place the text column after the graph. */
    fun rightmostLane(row: Int): Int = summaryOf(row).rightmostLane

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
        if (edge in collapsedEdges) {
            // A collapsed long edge (jj-idea-66rr): its arrows are the affordance, so it's
            // navigable even with both ends on screen. The cap under the pointer picks the
            // direction; the pivot sits at the span's far end so both caps are emphasized.
            val capSpan = maxOf(childRow, visibleRows.first)..minOf(parentRow, visibleRows.last)
            val onTopCap = row <= childRow + LONG_EDGE_PART_ROWS
            return HoveredEdge(
                edge = edge,
                lane = lane,
                direction = if (onTopCap) EdgeDirection.DOWN else EdgeDirection.UP,
                span = capSpan,
                pivotRow = if (onTopCap) capSpan.first else capSpan.last,
                navigable = true
            )
        }
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
         *
         * Complexity (jj-idea-2570.6): O(rows + edges) work plus an O(k log k) sort per lane,
         * independent of how many lanes are active at once - each edge is one interval, never one
         * entry per row it spans. [operationCount] counts rows, edges and stubs, plus spans sorted.
         */
        fun build(entries: List<GraphableEntry>, nodes: Map<ChangeKey, GraphNode>): GraphEdgeIndex {
            var operationCount = 0L
            val rowOfKey = HashMap<ChangeKey, Int>(entries.size * 2)
            entries.forEachIndexed { row, entry -> rowOfKey[entry.key] = row }

            val ownLaneByRow = HashMap<Int, Int>(entries.size * 2)
            val incomingByRow = HashMap<Int, MutableList<RowEdge>>()
            val outgoingByRow = HashMap<Int, MutableList<RowEdge>>()
            val stubByRow = HashMap<Int, RowEdge>()
            val arrowsByRow = HashMap<Int, MutableList<CapArrow>>()
            val collapsedEdges = HashSet<GraphEdge>()
            val spansByLane = ArrayList<MutableList<LaneSpan>?>()
            var seq = 0

            fun record(lane: Int, start: Int, end: Int, edge: GraphEdge) {
                while (spansByLane.size <= lane) spansByLane.add(null)
                val list = spansByLane[lane] ?: mutableListOf<LaneSpan>().also { spansByLane[lane] = it }
                list.add(LaneSpan(start, end, edge, seq++))
            }

            for ((row, entry) in entries.withIndex()) {
                val key = entry.key
                val node = nodes[key] ?: continue
                operationCount++
                ownLaneByRow[row] = node.lane

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
                    // Recorded once per edge, not per row it spans - what a row's own paint
                    // (jj-idea-a0wp) needs is "which edges touch my row as an endpoint"; the
                    // passthrough extent is one interval on the lane (jj-idea-2570.6).
                    // A long edge (jj-idea-66rr) is two caps on two lanes, with no lane held between:
                    // the top cap on the child's passthrough lane, the bottom cap on the lane the
                    // layout allocated at the parent row.
                    val capLane = if (passThroughLane != null) parentNode.longEdgeCapLanes[key] else null
                    outgoingByRow.getOrPut(row) { mutableListOf() }.add(RowEdge(lane, edge))
                    incomingByRow.getOrPut(parentRow) { mutableListOf() }.add(RowEdge(capLane ?: lane, edge))
                    if (capLane != null) {
                        operationCount += 2
                        collapsedEdges += edge
                        val topArrowRow = row + LONG_EDGE_PART_ROWS
                        val bottomArrowRow = parentRow - LONG_EDGE_PART_ROWS
                        record(lane, row, topArrowRow, edge)
                        record(capLane, bottomArrowRow, parentRow, edge)
                        arrowsByRow.getOrPut(topArrowRow) {
                            mutableListOf()
                        }.add(CapArrow(lane, edge, EdgeDirection.DOWN))
                        arrowsByRow.getOrPut(bottomArrowRow) {
                            mutableListOf()
                        }.add(CapArrow(capLane, edge, EdgeDirection.UP))
                    } else if (parentRow >= row) {
                        record(lane, row, parentRow, edge)
                    }
                }

                // Stub for unresolved parents - one row, `stubLane` or the row's own lane.
                stubTargetFor(node)?.let { (parentKey, state) ->
                    operationCount++
                    val stubLane = node.stubLane ?: node.lane
                    val stubEdge = GraphEdge(child = key, parent = parentKey, state = state)
                    stubByRow[row] = RowEdge(stubLane, stubEdge)
                    record(stubLane, row, row, stubEdge)
                }
            }

            // When two edges would occupy the same (row, lane) - only possible at an endpoint row,
            // e.g. a linear chain reusing lane 0 - the longest-spanning edge wins (earliest on a
            // tie): LaneSpans.at(). Sorting is the only super-linear step: O(k log k) per lane.
            val sorted = spansByLane.map { list ->
                list?.let {
                    operationCount += it.size
                    LaneSpans(it.sortedBy { span -> span.start }) // stable: keeps seq order within a start
                }
            }

            return GraphEdgeIndex(
                rowOfKey = rowOfKey,
                spansByLane = sorted,
                incomingByRow = incomingByRow,
                outgoingByRow = outgoingByRow,
                stubByRow = stubByRow,
                ownLaneByRow = ownLaneByRow,
                arrowsByRow = arrowsByRow,
                collapsedEdges = collapsedEdges,
                operationCount = operationCount
            )
        }

        /** Per-index cap on memoised row summaries - paint touches only the visible window. */
        private const val MAX_MEMOISED_ROWS = 512
    }
}

/**
 * The unresolved parent a row should draw a stub for, paired with its [ParentState], or null when
 * [node] has no unresolved parent at all. [ParentState.NOT_LOADED] (a paged-window boundary, or
 * beyond a non-paged limit) gets a faded straight stub; [ParentState.HIDDEN] (genuinely
 * elided/filtered, jj's `~`) keeps the wiggle. When a row mixes both (rare - e.g. a merge with two
 * unresolved parents of different states), NOT_LOADED wins: it's the actionable one (click to
 * load), where HIDDEN's target needs the filter cleared first (jj-idea-hlu3).
 */
internal fun stubTargetFor(node: GraphNode): Pair<ChangeKey, ParentState>? {
    val notLoaded = node.unresolvedParents.entries.firstOrNull { it.value == ParentState.NOT_LOADED }
    if (notLoaded != null) return notLoaded.key to notLoaded.value
    return node.unresolvedParents.entries.firstOrNull()?.let { it.key to it.value }
}
