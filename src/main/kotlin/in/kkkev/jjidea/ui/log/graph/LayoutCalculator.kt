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

private data class Lanes(val contents: Set<Int>) {
    fun firstFree() = generateSequence(0) { it + 1 }.first { it !in contents }

    fun firstFreePreferring(preference: Int) = if (preference in contents) firstFree() else preference

    operator fun plus(lane: Int) = Lanes(contents + lane)

    operator fun plus(lanes: Iterable<Int>) = Lanes(contents + lanes)
}

private data class Passthroughs<I : Any>(val contents: Set<Passthrough<I>> = emptySet()) {
    fun lanes() = Lanes(contents.map { it.lane }.toSet())

    fun withoutParent(parent: I) = Passthroughs(contents.filterNot { it.targetParentId == parent }.toSet())

    operator fun plus(passthroughs: Iterable<Passthrough<I>>) = Passthroughs(contents + passthroughs)
}

private data class State<I : Any>(
    val childrenByParent: Map<I, Set<ChildInfo<I>>> = emptyMap(),
    val lanes: Map<I, Int> = emptyMap(),
    val passthroughs: Passthroughs<I> = Passthroughs<I>(),
    /** entry ID -> (parent ID -> passthrough lane) for non-adjacent parents */
    val passthroughLanesByEntry: Map<I, Map<I, Int>> = emptyMap(),
    /** Reserved lanes for pure merge parents (parent ID -> reserved lane) */
    val reservedLanes: Map<I, Int> = emptyMap()
) {
    // Pick lowest available lane not occupied by a passthrough or reservation
    fun laneFor(entry: GraphEntry<I>, currentPassthroughLanes: Lanes): Int {
        // If a lane was reserved for this entry (pure merge target), use it
        reservedLanes[entry.current]?.let { return it }

        val children = childrenByParent[entry.current]
        return if (children.isNullOrEmpty()) {
            // No children: pick lowest available lane
            currentPassthroughLanes.firstFree()
        } else {
            // Has children: pick lowest child lane, unless occupied by passthrough
            currentPassthroughLanes.firstFreePreferring(children.minOf { it.lane })
        }
    }
}

class LayoutCalculatorImpl<I : Any> : LayoutCalculator<I> {
    override fun calculate(entries: List<GraphEntry<I>>): GraphLayout<I> {
        // Pre-compute row index for each entry (needed to determine if parent is adjacent)
        val rowByChangeId = entries.withIndex().associate { (index, entry) -> entry.current to index }

        val initialState = State<I>()

        // First pass: assign lanes, track children, manage passthroughs
        val finalState = entries.foldIndexed(initialState) { rowIndex, state, entry ->
            // Step 2: Terminate passthroughs that end at this entry
            val remainingPassthroughs = state.passthroughs.withoutParent(entry.current)

            // Step 1: Determine lane (after terminating passthroughs so lane may become available)
            val currentPassthroughLanes = remainingPassthroughs.lanes()
            val lane = state.laneFor(entry, currentPassthroughLanes)

            // If this entry used a reserved lane, remove the reservation
            val usedReservedLane = state.reservedLanes[entry.current]
            val clearedReservedLanes = if (usedReservedLane != null) {
                state.reservedLanes - entry.current
            } else {
                state.reservedLanes
            }

            // Step 3: Register this entry as a child of each of its parents
            val updatedChildrenByParent = entry.parents.fold(state.childrenByParent) { acc, parentId ->
                val existingChildren = acc[parentId] ?: emptySet()
                acc + (parentId to (existingChildren + ChildInfo(entry.current, lane)))
            }

            // Step 4: Create passthroughs for non-adjacent parents
            // Classify each connection:
            //   - Fork+Merge: parent already has children → use child's lane
            //   - Pure Merge: parent has no other children → use NEW lane, reserve it for parent
            val nonAdjacentParents = entry.parents.filter { parentId ->
                val parentRow = rowByChangeId[parentId]
                parentRow != null && parentRow > rowIndex + 1
            }

            // Check if there are any adjacent parents (need to avoid blocking them)
            val hasAdjacentParent = entry.parents.any { parentId ->
                val parentRow = rowByChangeId[parentId]
                parentRow == rowIndex + 1
            }

            // Track which lanes are already used (passthroughs + this entry's lane + remaining reservations)
            var usedLanes = currentPassthroughLanes + lane + clearedReservedLanes.values.toSet()
            val newPassthroughs = mutableSetOf<Passthrough<I>>()
            var newReservedLanes = clearedReservedLanes

            val childHasMultipleParents = entry.parents.size > 1
            // Track if ANY passthrough has used child's lane (prevents pure merge from also using it)
            var childLaneUsed = false

            for (parentId in nonAdjacentParents) {
                val parentHasOtherChildren = updatedChildrenByParent[parentId]
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
                    // (parent will find this lane via children, no reservation needed)
                    childLaneUsed = true
                    lane
                } else {
                    // Pure Merge: allocate new lane, reserve it for parent
                    val newLane = usedLanes.firstFree()
                    usedLanes += newLane
                    newReservedLanes += (parentId to newLane)
                    newLane
                }
                newPassthroughs.add(Passthrough(lane = passLane, targetParentId = parentId))
            }

            State(
                childrenByParent = updatedChildrenByParent,
                lanes = state.lanes + (entry.current to lane),
                passthroughs = remainingPassthroughs + newPassthroughs,
                reservedLanes = newReservedLanes,
                passthroughLanesByEntry = state.passthroughLanesByEntry +
                    (entry.current to newPassthroughs.associate { it.targetParentId to it.lane })
            )
        }

        // Second pass: build RowLayout for each entry
        val rows = entries.map { entry ->
            val lane = finalState.lanes[entry.current] ?: 0
            val childLanes = finalState.childrenByParent[entry.current]
                ?.map { it.lane }
                ?: emptyList()
            val parentLanes = entry.parents.mapNotNull { finalState.lanes[it] }
            val passthroughLanes = finalState.passthroughLanesByEntry[entry.current] ?: emptyMap()

            RowLayout(entry.current, lane, childLanes, parentLanes, passthroughLanes)
        }

        return GraphLayout(rows)
    }
}
