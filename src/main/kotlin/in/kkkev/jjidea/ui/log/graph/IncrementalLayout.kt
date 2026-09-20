package `in`.kkkev.jjidea.ui.log.graph

private data class ChildInfo<I : Any>(val id: I, val lane: Int)

private data class Passthrough<I : Any>(
    val lane: Int, // The lane this passthrough blocks
    val targetParentId: I
)

/**
 * Cross-row forward-pass state as of just before processing row [rowIndex] - enough to
 * resume [IncrementalLayout]'s single forward pass from that point without replaying
 * everything from row 0. See [IncrementalLayout.append].
 */
private class Checkpoint<I : Any>(
    val rowIndex: Int,
    val passthroughs: Set<Passthrough<I>>,
    val reservedLanes: Map<I, Int>
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
class IncrementalLayout<I : Any> {
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

    // Cross-row state threaded through the forward pass; snapshotted into [checkpoints].
    private var passthroughs = HashSet<Passthrough<I>>()
    private var reservedLanes = HashMap<I, Int>()

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
        passthroughs = HashSet()
        reservedLanes = HashMap()
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
                checkpoints.add(Checkpoint(rowIndex, HashSet(passthroughs), HashMap(reservedLanes)))
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
        }

        rows.subList(resumeFrom, rows.size).clear()

        passthroughs = if (checkpointIndex >= 0) HashSet(checkpoints[checkpointIndex].passthroughs) else HashSet()
        reservedLanes = if (checkpointIndex >= 0) HashMap(checkpoints[checkpointIndex].reservedLanes) else HashMap()
        while (checkpoints.isNotEmpty() && checkpoints.last().rowIndex >= resumeFrom) {
            checkpoints.removeAt(checkpoints.size - 1)
        }

        return resumeFrom to oldLanes
    }

    /** One row of the forward pass - identical shape to the original [LayoutCalculatorImpl] loop body. */
    private fun processRow(rowIndex: Int) {
        val entry = entries[rowIndex]

        // Step 2: Terminate passthroughs that end at this entry
        operationCount += passthroughs.size
        passthroughs.removeAll { it.targetParentId == entry.current }

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
        operationCount += currentPassthroughLanes.size + reservedLanes.size
        val usedLanes = HashSet(currentPassthroughLanes)
        usedLanes.add(lane)
        usedLanes.addAll(reservedLanes.values)

        val newPassthroughs = mutableSetOf<Passthrough<I>>()
        val childHasMultipleParents = entry.parents.size > 1
        var childLaneUsed = false

        operationCount += entry.parents.size
        for (parentId in entry.parents) {
            val parentRow = rowByChangeId[parentId] ?: run {
                val state = if (parentId in allIds) ParentState.HIDDEN else ParentState.NOT_LOADED
                unresolvedParentsByEntry.getOrPut(entry.current) { mutableMapOf() }[parentId] = state
                unresolvedParentRow.merge(parentId, rowIndex) { old, new -> minOf(old, new) }
                continue
            }
            val isAdjacent = parentRow == rowIndex + 1

            operationCount += childrenByParent[parentId]?.size ?: 0
            val parentHasOtherChildren = childrenByParent[parentId]?.any { it.id != entry.current } == true

            if (!childHasMultipleParents || parentHasOtherChildren) {
                childLaneUsed = true
                if (!isAdjacent) newPassthroughs.add(Passthrough(lane = lane, targetParentId = parentId))
            } else if (!childLaneUsed) {
                childLaneUsed = true
                if (!isAdjacent) newPassthroughs.add(Passthrough(lane = lane, targetParentId = parentId))
            } else {
                val newLane = firstFreeLane(usedLanes)
                usedLanes.add(newLane)
                reservedLanes[parentId] = newLane
                if (!isAdjacent) newPassthroughs.add(Passthrough(lane = newLane, targetParentId = parentId))
            }
        }

        val unresolvedHere = unresolvedParentsByEntry[entry.current]
        if (!unresolvedHere.isNullOrEmpty() && entry.parents.any { rowByChangeId.containsKey(it) }) {
            operationCount += usedLanes.size
            stubLaneByEntry[entry.current] = firstFreeLane(usedLanes)
        }

        lanes[entry.current] = lane
        passthroughs.addAll(newPassthroughs)
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

        return RowLayout(
            entry.current,
            lane,
            childLanes,
            parentLanes,
            entryPassthroughLanes,
            unresolvedParents,
            stubLane
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
