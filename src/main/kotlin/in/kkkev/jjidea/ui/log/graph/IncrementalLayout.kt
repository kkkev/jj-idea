package `in`.kkkev.jjidea.ui.log.graph

private data class ChildInfo<I : Any>(val id: I, val lane: Int)

private data class Passthrough<I : Any>(
    val lane: Int, // The lane this passthrough blocks
    val targetParentId: I,
    /** The row whose step 2 removes this passthrough (the lane is free for that row's own node). */
    val endRow: Int,
    /** Set on a long edge's bottom cap: the child it belongs to, recorded on the parent's row when removed. */
    val capChildId: I? = null,
    /**
     * Set on a long edge's top cap. Its arrow occupies only the upper half of its last row
     * ([endRow] - 1), so from that row's own downward connectors the lane is already free - only
     * the row's node circle (and bottom caps) must still avoid it.
     */
    val topCap: Boolean = false
)

/** A long edge's bottom cap, to be allocated a lane when row `parent - LONG_EDGE_PART_ROWS` is processed. */
private data class PendingCap<I : Any>(val childId: I, val parentId: I, val preferredLane: Int)

/**
 * Cross-row forward-pass state as of just before processing row [rowIndex] - enough to
 * resume [IncrementalLayout]'s single forward pass from that point without replaying
 * everything from row 0. See [IncrementalLayout.append].
 */
private class Checkpoint<I : Any>(
    val rowIndex: Int,
    val passthroughs: Set<Passthrough<I>>,
    val reservedLanes: Map<I, Int>,
    val pendingCaps: Map<Int, List<PendingCap<I>>>
)

/**
 * Resumable engine behind [LayoutCalculatorImpl.calculate] (jj-idea-jnqi). A full
 * [calculate] is `reset()` + one [append] of the whole set - a pure refactor, unchanged
 * for every existing caller. The value add is [append] on its own: a caller with a
 * strictly-appended delta (paged `loadMore()` in `UnifiedJujutsuLogDataLoader`) can
 * extend a previously computed layout instead of recomputing it from scratch, so a scroll
 * to page N costs O(total rows), not O(total rows x N).
 *
 * ### Algorithm
 * Mirrors [LayoutCalculatorImpl]'s original single forward pass ([processRow]) plus a
 * second pass ([buildRow]) that fills in each row's `parentLanes` once its parent's lane
 * is known (parents are processed *after* their children, since entries are in
 * "children before parents" order) - see docs/LOG_GRAPH_ALGORITHM.md.
 *
 * [append] resumes that pass rather than restarting it, with two wrinkles a plain resume
 * can't ignore:
 *
 * 1. **A newly-appended row can resolve an earlier row's [ParentState.NOT_LOADED]
 *    parent.** That earlier row's passthrough/lane bookkeeping was computed assuming the
 *    parent would never appear, so it - and everything after it, since passthroughs and
 *    reserved lanes are threaded forward - must be recomputed. [append] finds the
 *    earliest such row ([unresolvedParentRow]) and reprocesses from there.
 * 2. **A reprocessed row can land on a different lane than before, retroactively
 *    invalidating an already-built earlier row's cached `parentLanes`** (a child's
 *    `parentLanes` reads its *parent's* lane, and parents are processed later - see
 *    above). [append]'s final step detects any such lane changes and patches just the
 *    affected children's `RowLayout`s ([buildRow] is cheap and side-effect-free, so a
 *    single-row rebuild is fine) - this can't cascade further, since only `lane` (not
 *    `parentLanes`) feeds into any other row's computation.
 *
 * Resuming exactly at the earliest affected row would need per-row snapshots (O(total
 * rows) memory). Instead, [checkpoints] snapshot the forward-pass state every
 * [CHECKPOINT_INTERVAL] rows, so a resume replays at most one checkpoint window of
 * already-correct rows - bounded, independent of total row count.
 *
 * Every caller of [append] with a delta must supply it in a form where the *combined*
 * `previously appended + delta` sequence stays in valid topological order (children
 * before parents) - this class does not re-derive or verify that; see
 * `UnifiedJujutsuLogDataLoader`'s append guard. [allIds]/`deltaAllIds` is expected to
 * track [entries]'s own ids exactly (the two-arg [LayoutCalculator.calculate] filtered
 * case, where `allIds` is a superset of a *different* visible subset, is only ever used
 * via a fresh `reset()` + one full [append] - never mixed with a resumed one).
 */
class IncrementalLayout<I : Any>(private val longEdgeRows: Int = LONG_EDGE_ROWS) {
    init {
        require(longEdgeRows > 2 * LONG_EDGE_PART_ROWS + 1) { "longEdgeRows too small for two caps: $longEdgeRows" }
    }

    private companion object {
        /**
         * Rows between two checkpoints. A resume lands on the latest checkpoint at or
         * before the target row, then replays forward - so it costs at most this many
         * extra already-correct rows beyond the ones that actually changed. The
         * checkpoint list itself is O(total rows / interval), negligible at any scale.
         */
        const val CHECKPOINT_INTERVAL = 256
    }

    /**
     * Work-count of per-row bookkeeping in the last [append] call - see
     * [LayoutCalculatorImpl.operationCount], which this backs.
     */
    var operationCount: Long = 0
        private set

    // Accumulated input, in the same order as [rows] ("children before parents").
    private val entries = ArrayList<GraphEntry<I>>()
    private val rowByChangeId = HashMap<I, Int>()
    private val allIds = HashSet<I>()

    // Per-entry layout state. Entries for rows >= a resume point are discarded and
    // recomputed by rewindTo()/the forward pass in append().
    private val childrenByParent = HashMap<I, MutableSet<ChildInfo<I>>>()
    private val lanes = HashMap<I, Int>()
    private val passthroughLanesByEntry = HashMap<I, Map<I, Int>>()
    private val unresolvedParentsByEntry = HashMap<I, MutableMap<I, ParentState>>()
    private val stubLaneByEntry = HashMap<I, Int>()
    private val capLanesByEntry = HashMap<I, MutableMap<I, Int>>()

    // Cross-row state threaded through the forward pass; snapshotted into [checkpoints].
    private var passthroughs = HashSet<Passthrough<I>>()
    private var reservedLanes = HashMap<I, Int>()

    // Long edges' bottom caps awaiting allocation, keyed by the row that allocates them.
    private var pendingCaps = HashMap<Int, MutableList<PendingCap<I>>>()

    // For each parent id currently referenced but not loaded, the lowest row index that
    // references it - the earliest row an append resolving that id must reprocess from.
    private val unresolvedParentRow = HashMap<I, Int>()

    private val checkpoints = ArrayList<Checkpoint<I>>()
    private val rows = ArrayList<RowLayout<I>>()

    val layout: GraphLayout<I> get() = GraphLayout(rows)

    /** Discards all accumulated state - used by [LayoutCalculatorImpl.calculate] for a from-scratch call. */
    fun reset() {
        entries.clear()
        rowByChangeId.clear()
        allIds.clear()
        childrenByParent.clear()
        lanes.clear()
        passthroughLanesByEntry.clear()
        unresolvedParentsByEntry.clear()
        stubLaneByEntry.clear()
        capLanesByEntry.clear()
        passthroughs = HashSet()
        reservedLanes = HashMap()
        pendingCaps = HashMap()
        unresolvedParentRow.clear()
        checkpoints.clear()
        rows.clear()
        operationCount = 0
    }

    /**
     * Appends [delta] to the previously laid-out entries and returns the updated
     * [GraphLayout] for the whole accumulated set. See the class doc for the ordering
     * contract on [delta] and the two correctness wrinkles a resume has to handle.
     */
    fun append(
        delta: List<GraphEntry<I>>,
        deltaAllIds: Set<I> = delta.mapTo(HashSet()) { it.current }
    ): GraphLayout<I> {
        operationCount = 0
        if (delta.isEmpty()) return layout

        allIds.addAll(deltaAllIds)

        var restart = entries.size
        for (e in delta) {
            unresolvedParentRow[e.current]?.let { r -> if (r < restart) restart = r }
        }

        val (resumeFrom, oldLanes) = rewindTo(restart)

        for (e in delta) {
            rowByChangeId[e.current] = entries.size
            entries.add(e)
        }
        val newSize = entries.size

        for (rowIndex in resumeFrom until newSize) {
            if (rowIndex % CHECKPOINT_INTERVAL == 0) {
                checkpoints.add(
                    Checkpoint(rowIndex, HashSet(passthroughs), HashMap(reservedLanes), copyOf(pendingCaps))
                )
            }
            processRow(rowIndex)
        }
        for (rowIndex in resumeFrom until newSize) {
            rows.add(buildRow(rowIndex))
        }

        // Wrinkle 2 (see class doc): a row below resumeFrom may have cached a now-stale
        // parentLanes entry for a parent whose lane just changed above. Only its direct
        // children need patching, and only the ones below resumeFrom (children at/above
        // resumeFrom were just rebuilt above already) - doesn't cascade further, since
        // `lane` (not `parentLanes`) is the only thing any other row's computation reads.
        for ((id, oldLane) in oldLanes) {
            val newLane = lanes[id] ?: continue
            if (newLane == oldLane) continue
            val children = childrenByParent[id] ?: continue
            operationCount += children.size
            for (child in children) {
                val childRow = rowByChangeId.getValue(child.id)
                if (childRow < resumeFrom) rows[childRow] = buildRow(childRow)
            }
        }

        return layout
    }

    /**
     * Discards computed layout state for rows from the latest checkpoint at or before
     * [restart] onward, restoring cross-row state ([passthroughs]/[reservedLanes]) from
     * that checkpoint. [entries]/[rowByChangeId] are untouched - under the append-only
     * contract existing rows never move, so their index stays valid.
     *
     * @return the row index to resume the forward pass from, and the pre-discard lane of
     *   every entry in the discarded range (for the wrinkle-2 fixup in [append]).
     */
    private fun rewindTo(restart: Int): Pair<Int, Map<I, Int>> {
        if (restart >= entries.size) return entries.size to emptyMap()

        val checkpointIndex = checkpoints.indexOfLast { it.rowIndex <= restart }
        val resumeFrom = if (checkpointIndex >= 0) checkpoints[checkpointIndex].rowIndex else 0

        val oldLanes = HashMap<I, Int>(entries.size - resumeFrom)
        for (rowIndex in resumeFrom until entries.size) {
            val e = entries[rowIndex]
            val lane = lanes.remove(e.current)
            if (lane != null) {
                oldLanes[e.current] = lane
                for (parentId in e.parents) {
                    childrenByParent[parentId]?.let { children ->
                        children.remove(ChildInfo(e.current, lane))
                        if (children.isEmpty()) childrenByParent.remove(parentId)
                    }
                }
            }
            unresolvedParentsByEntry.remove(e.current)?.let { unresolved ->
                for (parentId in unresolved.keys) {
                    if (unresolvedParentRow[parentId] == rowIndex) unresolvedParentRow.remove(parentId)
                }
            }
            passthroughLanesByEntry.remove(e.current)
            stubLaneByEntry.remove(e.current)
            capLanesByEntry.remove(e.current)
        }

        rows.subList(resumeFrom, rows.size).clear()

        passthroughs = if (checkpointIndex >= 0) HashSet(checkpoints[checkpointIndex].passthroughs) else HashSet()
        reservedLanes = if (checkpointIndex >= 0) HashMap(checkpoints[checkpointIndex].reservedLanes) else HashMap()
        pendingCaps = if (checkpointIndex >= 0) copyOf(checkpoints[checkpointIndex].pendingCaps) else HashMap()
        while (checkpoints.isNotEmpty() && checkpoints.last().rowIndex >= resumeFrom) {
            checkpoints.removeAt(checkpoints.size - 1)
        }

        return resumeFrom to oldLanes
    }

    private fun copyOf(caps: Map<Int, List<PendingCap<I>>>) =
        caps.mapValuesTo(HashMap()) { (_, list) -> list.toMutableList() }

    /** One row of the forward pass - identical shape to the original [LayoutCalculatorImpl] loop body. */
    private fun processRow(rowIndex: Int) {
        val entry = entries[rowIndex]

        // Step 2: Terminate passthroughs that end at this entry
        operationCount += passthroughs.size
        passthroughs.removeAll { pt ->
            (pt.endRow == rowIndex).also { ended ->
                if (ended && pt.capChildId != null) {
                    capLanesByEntry.getOrPut(entry.current) { mutableMapOf() }[pt.capChildId] = pt.lane
                }
            }
        }

        // Step 1: Determine lane (after terminating passthroughs so lane may become available)
        operationCount += passthroughs.size
        val currentPassthroughLanes = passthroughs.mapTo(HashSet()) { it.lane }
        val lane = laneFor(entry, currentPassthroughLanes, reservedLanes, childrenByParent)

        reservedLanes.remove(entry.current)

        // Step 3: Register this entry as a child of each of its parents
        for (parentId in entry.parents) {
            childrenByParent.getOrPut(parentId) { mutableSetOf() }.add(ChildInfo(entry.current, lane))
        }

        // Step 4: Classify each parent and, for non-adjacent ones, create a passthrough.
        // Lanes held only by a top cap whose arrow ends on this row are free for this row's downward
        // connectors; they stay off-limits to the node (laneFor above) and to bottom caps (below).
        operationCount += passthroughs.size + reservedLanes.size
        val heldLanes = HashSet<Int>()
        val endingTopCapLanes = HashSet<Int>()
        for (pt in passthroughs) {
            if (pt.topCap && pt.endRow == rowIndex + 1) endingTopCapLanes.add(pt.lane) else heldLanes.add(pt.lane)
        }
        endingTopCapLanes.removeAll(heldLanes) // a sibling passthrough sharing the stem lane keeps it held
        val usedLanes = HashSet(heldLanes)
        usedLanes.add(lane)
        usedLanes.addAll(reservedLanes.values)

        val newPassthroughs = mutableSetOf<Passthrough<I>>()
        val childHasMultipleParents = entry.parents.size > 1
        var childLaneUsed = false
        // A merge whose loaded parents are all long edges has no short connector wanting its own lane, so
        // its first long edge may leave straight down on it (an octopus merge fans out without a gap).
        val onlyLongEdges = childHasMultipleParents &&
            entry.parents.all { id ->
                rowByChangeId[id]?.let { it - rowIndex >= longEdgeRows } != false
            }

        operationCount += entry.parents.size
        for (parentId in entry.parents) {
            val parentRow = rowByChangeId[parentId] ?: run {
                val state = if (parentId in allIds) ParentState.HIDDEN else ParentState.NOT_LOADED
                unresolvedParentsByEntry.getOrPut(entry.current) { mutableMapOf() }[parentId] = state
                unresolvedParentRow.merge(parentId, rowIndex) { old, new -> minOf(old, new) }
                continue
            }
            val isAdjacent = parentRow == rowIndex + 1
            val isLong = parentRow - rowIndex >= longEdgeRows

            // A long edge's lane is only held for its top cap; its bottom cap is allocated at
            // parentRow - LONG_EDGE_PART_ROWS (jj-idea-66rr).
            fun openPassthrough(passLane: Int) {
                if (isLong) {
                    newPassthroughs.add(
                        Passthrough(passLane, parentId, endRow = rowIndex + LONG_EDGE_PART_ROWS + 1, topCap = true)
                    )
                    pendingCaps.getOrPut(parentRow - LONG_EDGE_PART_ROWS) { mutableListOf() }
                        .add(PendingCap(entry.current, parentId, passLane))
                } else {
                    newPassthroughs.add(Passthrough(passLane, parentId, endRow = parentRow))
                }
            }

            operationCount += childrenByParent[parentId]?.size ?: 0
            val parentHasOtherChildren = childrenByParent[parentId]?.any { it.id != entry.current } == true

            if (isLong && childHasMultipleParents && onlyLongEdges && !childLaneUsed) {
                childLaneUsed = true
                openPassthrough(lane)
            } else if (isLong && childHasMultipleParents) {
                // A merge's long edge never shares the child's lane with a sibling connector: its
                // arrow would sit on the sibling's line, and edgeAt/hover resolve that lane to the
                // longer-spanning sibling, leaving the arrow unclickable. Fresh lane, unreserved.
                val capLane = firstFreeLane(usedLanes)
                usedLanes.add(capLane)
                openPassthrough(capLane)
            } else if (!childHasMultipleParents || parentHasOtherChildren) {
                childLaneUsed = true
                if (!isAdjacent) openPassthrough(lane)
            } else if (!childLaneUsed) {
                childLaneUsed = true
                if (!isAdjacent) openPassthrough(lane)
            } else {
                val newLane = firstFreeLane(usedLanes)
                usedLanes.add(newLane)
                // No reservation for a long edge: holding the lane (and forcing the parent onto it)
                // for the whole span would defeat releasing it.
                if (!isLong) reservedLanes[parentId] = newLane
                if (!isAdjacent) openPassthrough(newLane)
            }
        }

        val unresolvedHere = unresolvedParentsByEntry[entry.current]
        if (!unresolvedHere.isNullOrEmpty() && entry.parents.any { rowByChangeId.containsKey(it) }) {
            operationCount += usedLanes.size
            val stubLane = firstFreeLane(usedLanes)
            stubLaneByEntry[entry.current] = stubLane
            usedLanes.add(stubLane)
        }

        // Allocate the bottom caps of long edges whose parent is LONG_EDGE_PART_ROWS below. Done
        // last so every lane this row already needs (node, connectors, stub) takes precedence.
        usedLanes.addAll(endingTopCapLanes)
        val newCaps = mutableListOf<Passthrough<I>>()
        pendingCaps.remove(rowIndex)?.let { caps ->
            operationCount += caps.size
            // The cap row is LONG_EDGE_PART_ROWS (1) above the parent, so the parent is the next row and
            // its lane is already determined: prefer it so the up arrow lines up with the node below.
            // Caps end at the parent's row, so only the other passthroughs still block it there.
            val parentRow = rowIndex + LONG_EDGE_PART_ROWS
            val predictedParentLane = run {
                operationCount += passthroughs.size + newPassthroughs.size
                val surviving = HashSet<Int>()
                passthroughs.forEach { if (it.endRow != parentRow) surviving.add(it.lane) }
                newPassthroughs.forEach { if (it.endRow != parentRow) surviving.add(it.lane) }
                laneFor(entries[parentRow], surviving, reservedLanes, childrenByParent)
            }
            for (cap in caps) {
                val capLane = when {
                    predictedParentLane !in usedLanes -> predictedParentLane
                    cap.preferredLane !in usedLanes -> cap.preferredLane
                    else -> firstFreeLane(usedLanes)
                }
                usedLanes.add(capLane)
                newCaps.add(
                    Passthrough(
                        capLane,
                        cap.parentId,
                        endRow = rowByChangeId.getValue(cap.parentId),
                        capChildId = cap.childId
                    )
                )
            }
        }

        lanes[entry.current] = lane
        passthroughs.addAll(newPassthroughs)
        passthroughs.addAll(newCaps)
        if (newPassthroughs.isNotEmpty()) {
            passthroughLanesByEntry[entry.current] = newPassthroughs.associate { it.targetParentId to it.lane }
        }
    }

    /** Builds one row's [RowLayout] - identical shape to the original second pass. */
    private fun buildRow(rowIndex: Int): RowLayout<I> {
        val entry = entries[rowIndex]
        val lane = lanes[entry.current] ?: 0
        val childLanes = childrenByParent[entry.current]?.map { it.lane } ?: emptyList()
        val parentLanes = entry.parents.mapNotNull { lanes[it] }
        val entryPassthroughLanes = passthroughLanesByEntry[entry.current] ?: emptyMap()
        val unresolvedParents = unresolvedParentsByEntry[entry.current] ?: emptyMap()
        val stubLane = stubLaneByEntry[entry.current]
        val capLanes = capLanesByEntry[entry.current] ?: emptyMap()

        return RowLayout(
            entry.current,
            lane,
            childLanes,
            parentLanes,
            entryPassthroughLanes,
            unresolvedParents,
            stubLane,
            capLanes
        )
    }

    /** Pick lowest available lane not occupied by a passthrough or reservation. */
    private fun laneFor(
        entry: GraphEntry<I>,
        currentPassthroughLanes: Set<Int>,
        reservedLanes: Map<I, Int>,
        childrenByParent: Map<I, Set<ChildInfo<I>>>
    ): Int {
        reservedLanes[entry.current]?.let { return it }

        val children = childrenByParent[entry.current]
        return if (children.isNullOrEmpty()) {
            firstFreeLane(currentPassthroughLanes)
        } else {
            firstFreeLanePreferring(children.minOf { it.lane }, currentPassthroughLanes)
        }
    }

    /** Finds the lowest non-negative integer not in [occupied]. */
    private fun firstFreeLane(occupied: Set<Int>): Int {
        var lane = 0
        while (lane in occupied) {
            operationCount++
            lane++
        }
        return lane
    }

    /** Returns [preference] if it is not in [occupied], otherwise the lowest free lane. */
    private fun firstFreeLanePreferring(preference: Int, occupied: Set<Int>) =
        if (preference in occupied) firstFreeLane(occupied) else preference
}
