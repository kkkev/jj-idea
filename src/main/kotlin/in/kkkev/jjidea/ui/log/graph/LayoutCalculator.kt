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

private data class ChildInfo(val changeId: ChangeId, val lane: Int)

private data class Passthrough(
    val lane: Int,
    val targetParentId: ChangeId,
    val sourceLane: Int
)

private data class State(
    val childrenByParent: Map<ChangeId, Set<ChildInfo>> = emptyMap(),
    val lanes: Map<ChangeId, Int> = emptyMap(),
    val preAssignedLanes: Map<ChangeId, Int> = emptyMap(),  // lanes pre-assigned to parents by children
    val passthroughs: Set<Passthrough> = emptySet(),
    val passthroughsByRow: Map<Int, Set<Int>> = emptyMap(),  // row index -> set of passthrough lanes
) {
    // Pick lowest available lane not occupied by a passthrough
    fun laneFor(entry: GraphEntry, currentPassthroughLanes: Set<Int>): Int {
        // If this entry was pre-assigned a lane by a child, use it
        preAssignedLanes[entry.current]?.let { return it }

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
        val finalState = entries.foldIndexed(initialState) { rowIndex, state, entry ->
            // Step 2: Terminate passthroughs that end at this entry
            val terminatingPassthroughs = state.passthroughs.filter { it.targetParentId == entry.current }
            val remainingPassthroughs = state.passthroughs - terminatingPassthroughs.toSet()

            // Step 1: Determine lane (after terminating passthroughs so lane may become available)
            val currentPassthroughLanes = remainingPassthroughs.map { it.lane }.toSet()
            val lane = state.laneFor(entry, currentPassthroughLanes)

            // Step 3: Register this entry as a child of each of its parents
            val updatedChildrenByParent = entry.parents.fold(state.childrenByParent) { acc, parentId ->
                val existingChildren = acc[parentId] ?: emptySet()
                acc + (parentId to (existingChildren + ChildInfo(entry.current, lane)))
            }

            // Step 4: Pre-assign lanes to parents (for merges, first parent gets same lane, others get new lanes)
            // Also create passthroughs for parents not in the immediately next row
            val occupiedLanes = currentPassthroughLanes + lane + state.preAssignedLanes.values.toSet()
            var nextNewLane = generateSequence(0) { it + 1 }.first { it !in occupiedLanes }

            data class ParentAssignment(val parentId: ChangeId, val assignedLane: Int, val needsPassthrough: Boolean)

            val parentAssignments = entry.parents.mapIndexed { index, parentId ->
                val parentRow = rowByChangeId[parentId]
                val needsPassthrough = parentRow != null && parentRow > rowIndex + 1

                // Check if parent already has a pre-assigned lane (from another child)
                val existingLane = state.preAssignedLanes[parentId]
                val assignedLane = when {
                    existingLane != null -> existingLane  // Already assigned
                    index == 0 -> lane  // First parent: same lane as child
                    else -> {
                        // Additional parents: allocate new lane
                        val newLane = nextNewLane
                        nextNewLane = generateSequence(newLane + 1) { it + 1 }
                            .first { it !in occupiedLanes && it != newLane }
                        newLane
                    }
                }
                ParentAssignment(parentId, assignedLane, needsPassthrough)
            }

            val updatedPreAssignedLanes = parentAssignments
                .filter { state.preAssignedLanes[it.parentId] == null }  // Only add new assignments
                .fold(state.preAssignedLanes) { acc, assignment ->
                    acc + (assignment.parentId to assignment.assignedLane)
                }

            val newPassthroughs = parentAssignments
                .filter { it.needsPassthrough }
                .map { Passthrough(lane = it.assignedLane, targetParentId = it.parentId, sourceLane = lane) }
                .toSet()

            // Update passthroughsByRow for rows between this entry and each non-adjacent parent
            val updatedPassthroughsByRow = newPassthroughs.fold(state.passthroughsByRow) { acc, pt ->
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
                preAssignedLanes = updatedPreAssignedLanes,
                passthroughs = remainingPassthroughs + newPassthroughs,
                passthroughsByRow = updatedPassthroughsByRow
            )
        }

        // Second pass: build RowLayout for each entry
        val rows = entries.mapIndexed { rowIndex, entry ->
            val lane = finalState.lanes[entry.current] ?: 0
            val childLanes = finalState.childrenByParent[entry.current]
                ?.map { it.lane }
                ?: emptyList()
            val parentLanes = entry.parents.mapNotNull { finalState.lanes[it] }
            val passthroughLanes = finalState.passthroughsByRow[rowIndex] ?: emptySet()

            RowLayout(entry.current, lane, childLanes, parentLanes, passthroughLanes)
        }

        return GraphLayout(rows)
    }
}
