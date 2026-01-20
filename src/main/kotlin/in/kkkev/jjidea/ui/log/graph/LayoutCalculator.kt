package `in`.kkkev.jjidea.ui.log.graph

import `in`.kkkev.jjidea.jj.ChangeId

/**
 * Calculates the layout for a commit graph.
 *
 * @see <a href="../../../../../../../docs/LOG_GRAPH_ALGORITHM.md">Log Graph Algorithm</a>
 */
interface LayoutCalculator {
    fun calculate(entries: List<GraphEntry>): GraphLayout
}

private data class ChildInfo(
    val changeId: ChangeId,
    val lane: Int
)

private data class Passthrough(
    val lane: Int, // The lane this passthrough blocks (child's lane)
    val targetParentId: ChangeId
)

private data class State(
    val childrenByParent: Map<ChangeId, Set<ChildInfo>> = emptyMap(),
    val lanes: Map<ChangeId, Int> = emptyMap(),
    val passthroughs: Set<Passthrough> = emptySet(),
    /** row index -> set of passthrough lanes */
    val passthroughsByRow: Map<Int, Set<Int>> = emptyMap()
) {
    // Pick lowest available lane not occupied by a passthrough
    fun laneFor(
        entry: GraphEntry,
        currentPassthroughLanes: Set<Int>
    ): Int {
        val children = childrenByParent[entry.current]
        return if (children.isNullOrEmpty()) {
            // No children: pick lowest available lane
            generateSequence(0) { it + 1 }.first { it !in currentPassthroughLanes }
        } else {
            // Has children: pick lowest child lane, unless occupied by passthrough
            val lowestChildLane = children.minOf { it.lane }
            if (lowestChildLane !in currentPassthroughLanes) {
                lowestChildLane
            } else {
                generateSequence(0) { it + 1 }.first { it !in currentPassthroughLanes }
            }
        }
    }
}

class LayoutCalculatorImpl : LayoutCalculator {
    override fun calculate(entries: List<GraphEntry>): GraphLayout {
        // Pre-compute row index for each entry (needed to determine if parent is adjacent)
        val rowByChangeId = entries.withIndex().associate { (index, entry) -> entry.current to index }

        val initialState = State()

        // First pass: assign lanes, track children, manage passthroughs
        val finalState =
            entries.foldIndexed(initialState) { rowIndex, state, entry ->
                // Step 2: Terminate passthroughs that end at this entry
                val terminatingPassthroughs = state.passthroughs.filter { it.targetParentId == entry.current }
                val remainingPassthroughs = state.passthroughs - terminatingPassthroughs.toSet()

                // Step 1: Determine lane (after terminating passthroughs so lane may become available)
                val currentPassthroughLanes = remainingPassthroughs.map { it.lane }.toSet()
                val lane = state.laneFor(entry, currentPassthroughLanes)

                // Step 3: Register this entry as a child of each of its parents
                val updatedChildrenByParent =
                    entry.parents.fold(state.childrenByParent) { acc, parentId ->
                        val existingChildren = acc[parentId] ?: emptySet()
                        acc + (parentId to (existingChildren + ChildInfo(entry.current, lane)))
                    }

                // Step 4: Create passthroughs for non-adjacent parents
                // Passthroughs block the CHILD's lane (this entry's lane)
                val newPassthroughs =
                    entry.parents
                        .filter { parentId ->
                            val parentRow = rowByChangeId[parentId]
                            parentRow != null && parentRow > rowIndex + 1
                        }.map { parentId -> Passthrough(lane = lane, targetParentId = parentId) }
                        .toSet()

                // Update passthroughsByRow for rows between this entry and each non-adjacent parent
                val updatedPassthroughsByRow =
                    newPassthroughs.fold(state.passthroughsByRow) { acc, pt ->
                        val parentRow = rowByChangeId[pt.targetParentId] ?: return@fold acc
                        // Add passthrough lane to all rows between current row and parent row
                        ((rowIndex + 1) until parentRow).fold(acc) { innerAcc, row ->
                            val existing = innerAcc[row] ?: emptySet()
                            innerAcc + (row to (existing + pt.lane))
                        }
                    }

                State(
                    childrenByParent = updatedChildrenByParent,
                    lanes = state.lanes + (entry.current to lane),
                    passthroughs = remainingPassthroughs + newPassthroughs,
                    passthroughsByRow = updatedPassthroughsByRow
                )
            }

        // Second pass: build RowLayout for each entry
        val rows =
            entries.mapIndexed { rowIndex, entry ->
                val lane = finalState.lanes[entry.current] ?: 0
                val childLanes =
                    finalState.childrenByParent[entry.current]
                        ?.map { it.lane }
                        ?: emptyList()
                val parentLanes = entry.parents.mapNotNull { finalState.lanes[it] }
                val passthroughLanes = finalState.passthroughsByRow[rowIndex] ?: emptySet()

                RowLayout(entry.current, lane, childLanes, parentLanes, passthroughLanes)
            }

        return GraphLayout(rows)
    }
}
