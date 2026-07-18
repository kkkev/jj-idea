package `in`.kkkev.jjidea.ui.log.graph

/**
 * Calculates the layout for a commit graph.
 *
 * @see <a href="../../../../../../../docs/LOG_GRAPH_ALGORITHM.md">Log Graph Algorithm</a>
 */
interface LayoutCalculator<I : Any> {
    fun calculate(entries: List<GraphEntry<I>>): GraphLayout<I>
}

private data class ChildInfo<I : Any>(val id: I, val lane: Int)

private data class Passthrough<I : Any>(
    val lane: Int, // The lane this passthrough blocks
    val targetParentId: I
)

class LayoutCalculatorImpl<I : Any> : LayoutCalculator<I> {
    /**
     * Work-count of per-row passthrough/lane bookkeeping in the last [calculate] call. Reset at
     * the start of each call; a single mutable field is safe because `calculate` is not
     * reentrant/concurrent (one calculator instance lays out one graph at a time — see
     * [CommitGraphBuilder]). Exposed for `report.count("operations", …)` and for scale tests
     * asserting this stays linear (not quadratic) in entry count — see GraphLayoutScaleTest.
     */
    var operationCount: Long = 0
        private set

    override fun calculate(entries: List<GraphEntry<I>>): GraphLayout<I> {
        operationCount = 0

        // Pre-compute row index for each entry (needed to determine if parent is adjacent)
        val rowByChangeId = HashMap<I, Int>(entries.size * 2)
        entries.forEachIndexed { index, entry -> rowByChangeId[entry.current] = index }

        // Mutable state for the first pass
        val childrenByParent = HashMap<I, MutableSet<ChildInfo<I>>>()
        val lanes = HashMap<I, Int>(entries.size * 2)
        val passthroughs = HashSet<Passthrough<I>>()
        val passthroughLanesByEntry = HashMap<I, Map<I, Int>>(entries.size * 2)
        val reservedLanes = HashMap<I, Int>()

        // First pass: assign lanes, track children, manage passthroughs
        for ((rowIndex, entry) in entries.withIndex()) {
            // Step 2: Terminate passthroughs that end at this entry
            operationCount += passthroughs.size
            passthroughs.removeAll { it.targetParentId == entry.current }

            // Step 1: Determine lane (after terminating passthroughs so lane may become available)
            operationCount += passthroughs.size
            val currentPassthroughLanes = passthroughs.mapTo(HashSet()) { it.lane }
            val lane = laneFor(entry, currentPassthroughLanes, reservedLanes, childrenByParent)

            // If this entry used a reserved lane, remove the reservation
            val usedReservedLane = reservedLanes.remove(entry.current)

            // Step 3: Register this entry as a child of each of its parents
            for (parentId in entry.parents) {
                childrenByParent.getOrPut(parentId) { mutableSetOf() }
                    .add(ChildInfo(entry.current, lane))
            }

            // Step 4: Classify each parent and, for non-adjacent ones, create a passthrough.
            // Walk ALL parents (not just non-adjacent ones) in order: a Pure Merge parent
            // other than the first must get an explicit, order-increasing lane reservation
            // even when it happens to be adjacent — otherwise its own row's natural
            // "lowest free lane" placement can land it anywhere, out of sequence with its
            // non-adjacent siblings (see LOG_GRAPH_ALGORITHM.md).
            operationCount += currentPassthroughLanes.size + reservedLanes.size
            val usedLanes = HashSet(currentPassthroughLanes)
            usedLanes.add(lane)
            usedLanes.addAll(reservedLanes.values)

            val newPassthroughs = mutableSetOf<Passthrough<I>>()

            val childHasMultipleParents = entry.parents.size > 1
            // Tracks whether some parent has already claimed the child's lane. Iteration is
            // in parents-list order, so the first eligible (Simple/Fork/Fork+Merge, or the
            // first Pure Merge parent) parent to reach a branch below wins — for a normal
            // merge that's parents[0], preserving the "mainline stays put" convention; if
            // parents[0] is filtered out of the loaded entries (see the continue below), the
            // first still-visible parent takes over the role, which is the sensible default.
            var childLaneUsed = false

            operationCount += entry.parents.size
            for (parentId in entry.parents) {
                // Parents not present in the loaded entry set (filtered/off-screen) get no
                // lane/passthrough bookkeeping at all — a passthrough or reservation for
                // them would never be terminated/consumed (their row never gets processed),
                // leaking a lane for the rest of the graph.
                val parentRow = rowByChangeId[parentId] ?: continue
                val isAdjacent = parentRow == rowIndex + 1

                operationCount += childrenByParent[parentId]?.size ?: 0
                val parentHasOtherChildren = childrenByParent[parentId]
                    ?.any { it.id != entry.current } == true

                // Classification:
                // - Not a merge (child has 1 parent): use child's lane (simple/fork)
                // - Fork+Merge (merge + parent has other children): use child's lane
                // - Pure Merge (merge + parent has no other children):
                //     - First (visible) parent, lane still free: keep child's lane
                //       (mainline stays put)
                //     - Otherwise (any later parent, adjacent or not): allocate a NEW lane,
                //       in parent order, so siblings render left-to-right matching parentIds
                if (!childHasMultipleParents || parentHasOtherChildren) {
                    // Simple, Fork, or Fork+Merge: use child's lane
                    childLaneUsed = true
                    if (!isAdjacent) newPassthroughs.add(Passthrough(lane = lane, targetParentId = parentId))
                } else if (!childLaneUsed) {
                    // First pure-merge parent, lane still free: keep child's lane. If
                    // adjacent, its own row naturally picks up the (still-unclaimed) child's
                    // lane next — no reservation/passthrough needed here.
                    childLaneUsed = true
                    if (!isAdjacent) newPassthroughs.add(Passthrough(lane = lane, targetParentId = parentId))
                } else {
                    // Pure Merge, not the first (visible) parent: allocate a new lane and
                    // reserve it, so its own row-lane-assignment is pinned here regardless
                    // of adjacency.
                    val newLane = firstFreeLane(usedLanes)
                    usedLanes.add(newLane)
                    reservedLanes[parentId] = newLane
                    if (!isAdjacent) newPassthroughs.add(Passthrough(lane = newLane, targetParentId = parentId))
                }
            }

            lanes[entry.current] = lane
            passthroughs.addAll(newPassthroughs)
            if (newPassthroughs.isNotEmpty()) {
                passthroughLanesByEntry[entry.current] =
                    newPassthroughs.associate { it.targetParentId to it.lane }
            }
        }

        // Second pass: build RowLayout for each entry
        val rows = entries.map { entry ->
            val lane = lanes[entry.current] ?: 0
            val childLanes = childrenByParent[entry.current]
                ?.map { it.lane }
                ?: emptyList()
            val parentLanes = entry.parents.mapNotNull { lanes[it] }
            val entryPassthroughLanes = passthroughLanesByEntry[entry.current] ?: emptyMap()

            RowLayout(entry.current, lane, childLanes, parentLanes, entryPassthroughLanes)
        }

        return GraphLayout(rows)
    }

    /** Pick lowest available lane not occupied by a passthrough or reservation. */
    private fun laneFor(
        entry: GraphEntry<I>,
        currentPassthroughLanes: Set<Int>,
        reservedLanes: Map<I, Int>,
        childrenByParent: Map<I, Set<ChildInfo<I>>>
    ): Int {
        // If a lane was reserved for this entry (pure merge target), use it
        reservedLanes[entry.current]?.let { return it }

        val children = childrenByParent[entry.current]
        return if (children.isNullOrEmpty()) {
            // No children: pick lowest available lane
            firstFreeLane(currentPassthroughLanes)
        } else {
            // Has children: pick lowest child lane, unless occupied by passthrough
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
