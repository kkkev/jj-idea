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

/**
 * Finds the lowest non-negative integer not in [occupied].
 */
private fun firstFreeLane(occupied: Set<Int>): Int {
    var lane = 0
    while (lane in occupied) lane++
    return lane
}

/**
 * Returns [preference] if it is not in [occupied], otherwise the lowest free lane.
 */
private fun firstFreeLanePreferring(preference: Int, occupied: Set<Int>) =
    if (preference in occupied) firstFreeLane(occupied) else preference

class LayoutCalculatorImpl<I : Any> : LayoutCalculator<I> {
    override fun calculate(entries: List<GraphEntry<I>>): GraphLayout<I> {
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
            passthroughs.removeAll { it.targetParentId == entry.current }

            // Step 1: Determine lane (after terminating passthroughs so lane may become available)
            val currentPassthroughLanes = passthroughs.mapTo(HashSet()) { it.lane }
            val lane = laneFor(entry, currentPassthroughLanes, reservedLanes, childrenByParent)

            // If this entry used a reserved lane, remove the reservation
            val usedReservedLane = reservedLanes.remove(entry.current)

            // Step 3: Register this entry as a child of each of its parents
            for (parentId in entry.parents) {
                childrenByParent.getOrPut(parentId) { mutableSetOf() }
                    .add(ChildInfo(entry.current, lane))
            }

            // Step 4: Create passthroughs for non-adjacent parents
            val nonAdjacentParents = entry.parents.filter { parentId ->
                val parentRow = rowByChangeId[parentId]
                parentRow != null && parentRow > rowIndex + 1
            }

            val hasAdjacentParent = entry.parents.any { parentId ->
                rowByChangeId[parentId] == rowIndex + 1
            }

            // Track which lanes are already used (passthroughs + this entry's lane + remaining reservations)
            val usedLanes = HashSet(currentPassthroughLanes)
            usedLanes.add(lane)
            usedLanes.addAll(reservedLanes.values)

            val newPassthroughs = mutableSetOf<Passthrough<I>>()

            val childHasMultipleParents = entry.parents.size > 1
            var childLaneUsed = false

            for (parentId in nonAdjacentParents) {
                val parentHasOtherChildren = childrenByParent[parentId]
                    ?.any { it.id != entry.current } == true

                // Classification:
                // - Not a merge (child has 1 parent): use child's lane (simple/fork)
                // - Fork+Merge (merge + parent has other children): use child's lane
                // - Pure Merge (merge + parent has no other children):
                //     - If there are adjacent parents: use NEW lane (avoid blocking adjacent parent)
                //     - If NO adjacent parents AND child's lane not yet used: first uses child's lane
                //     - Otherwise: use NEW lane
                val passLane = if (!childHasMultipleParents || parentHasOtherChildren) {
                    // Simple, Fork, or Fork+Merge: use child's lane
                    childLaneUsed = true
                    lane
                } else if (!hasAdjacentParent && !childLaneUsed) {
                    // Pure Merge with no adjacent parents and child's lane free: use child's lane
                    childLaneUsed = true
                    lane
                } else {
                    // Pure Merge: allocate new lane, reserve it for parent
                    val newLane = firstFreeLane(usedLanes)
                    usedLanes.add(newLane)
                    reservedLanes[parentId] = newLane
                    newLane
                }
                newPassthroughs.add(Passthrough(lane = passLane, targetParentId = parentId))
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
}
