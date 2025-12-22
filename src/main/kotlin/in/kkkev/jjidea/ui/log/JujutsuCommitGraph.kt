package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.LogEntry
import java.awt.Color

/**
 * Commit graph layout algorithm and rendering data structures.
 *
 * Calculates horizontal positions (lanes) for each commit based on parent
 * relationships, similar to `git log --graph`.
 */

/**
 * Represents a commit's position in the graph.
 *
 * @property lane Horizontal position (0 = leftmost)
 * @property color Color for this commit's line
 * @property parentLanes Lanes where parent commits are located
 * @property passThroughLanes Map of lane -> color for lanes with lines passing through this row
 */
data class GraphNode(
    val lane: Int,
    val color: Color,
    val parentLanes: List<Int> = emptyList(),
    val passThroughLanes: Map<Int, Color> = emptyMap()
)

/**
 * Interface for entries that can be laid out in a commit graph.
 * This allows testing without depending on full LogEntry with IntelliJ Platform classes.
 */
interface GraphableEntry {
    val changeId: ChangeId
    val parentIds: List<ChangeId>
}

/**
 * Builds graph layout for a list of commits.
 *
 * Algorithm:
 * 1. Process commits top-to-bottom (newest first)
 * 2. Assign each commit to a lane
 * 3. Track active lanes (lanes with ongoing branches)
 * 4. When a commit has parents, connect to parent lanes
 * 5. Merge branches when they converge
 */
class CommitGraphBuilder {

    private val colors = listOf(
        Color(0x4285F4), // Blue
        Color(0xEA4335), // Red
        Color(0xFBBC04), // Yellow
        Color(0x34A853), // Green
        Color(0xFF6D00), // Orange
        Color(0x9C27B0), // Purple
        Color(0x00ACC1), // Cyan
        Color(0x7CB342), // Light green
    )

    /**
     * Build graph layout for commits.
     *
     * @param entries List of commits (newest first, as returned by jj log)
     * @return Map of changeId -> GraphNode
     */
    fun buildGraph(entries: List<GraphableEntry>): Map<ChangeId, GraphNode> {
        /*
        Algorithm:
        * If not yet assigned, assign to a spare lane
        * Assign parent to min(whatever it has, this lane)
        * Lane becomes spare only after parent of all lanes is done
        * Inbound lines come from all children and point to the centre of the cell in the child's lane and previous row
        * Outbound lines go to all parents and point to the centre of the parent cell (if next row is the parent) or
        * the centre of the cell below and in the same lane
        * Because of this, need to know if the next item in the graph is a parent of any lane
        * Colour according to the branch - and hence lane
        * Draw lines for all lanes in progress
        *
        * So:
        * Each lane records the parent (not just if it is free)

         */
        val graph = mutableMapOf<ChangeId, GraphNode>()
        val laneAssignments = mutableMapOf<ChangeId, Int>() // changeId -> lane
        val laneColors = mutableMapOf<Int, Color>() // lane -> color for that lane
        val availableLanes = mutableSetOf<Int>() // Lanes that are free
        val childrenByParent = mutableMapOf<ChangeId, MutableList<Pair<ChangeId, Int>>>() // parent -> (child, childLane)
        var maxLane = 0

        for ((index, entry) in entries.withIndex()) {
            val changeId = entry.changeId

            // Determine lane for this commit
            val lane = if (laneAssignments.containsKey(changeId)) {
                // Already assigned by a child
                laneAssignments.remove(changeId)!!
            } else {
                // New commit, use leftmost available lane
                if (availableLanes.isNotEmpty()) {
                    availableLanes.min().also { availableLanes.remove(it) }
                } else {
                    maxLane.also { maxLane++ }  // Use current value, then increment
                }
            }

            // Free lanes from children that merged into this commit
            // If this commit has multiple children in different lanes, free the child lanes (except this lane)
            childrenByParent[changeId]?.forEach { (childId, childLane) ->
                if (childLane != lane) {
                    availableLanes.add(childLane)
                }
            }

            // Lane becomes available only if this commit has no parents (branch ends)
            if (entry.parentIds.isEmpty()) {
                availableLanes.add(lane)
            }

            // Choose color based on lane
            val color = colors[lane % colors.size]
            laneColors[lane] = color

            // Calculate pass-through lanes for this row BEFORE assigning parents
            // These are lanes with active lines from previous commits that don't have a commit in this row
            val passThroughLanes = laneAssignments.values
                .filter { it != lane } // Exclude this commit's lane
                .distinct() // Remove duplicates
                .associateWith { laneColors[it] ?: colors[it % colors.size] }

            // Assign lanes to parents and track children
            val parentLanes = entry.parentIds.map { parentId ->
                val parentLane = if (laneAssignments.containsKey(parentId)) {
                    val currentParentLane = laneAssignments[parentId]!!
                    // If this child's lane is less than parent's current lane, reassign parent to leftmost
                    if (lane < currentParentLane) {
                        laneAssignments[parentId] = lane
                        lane
                    } else {
                        currentParentLane
                    }
                } else {
                    // Parent not yet assigned, assign based on whether it's first parent
                    val newLane = if (parentId == entry.parentIds.firstOrNull()) {
                        lane // First parent continues in same lane
                    } else {
                        maxLane.also { maxLane++ } // Merge - additional parent gets new lane
                    }
                    laneAssignments[parentId] = newLane
                    newLane
                }
                // Track this child-parent relationship
                childrenByParent.getOrPut(parentId) { mutableListOf() }.add(changeId to lane)

                // If this child merges into a parent in a different lane, free this child's lane immediately
                // BUT: Only free if this is NOT a leaf node (has children), because leaf merges
                // should preserve lanes for visual clarity when multiple siblings merge consecutively
                if (parentLane != lane) {
                    val hasChildren = childrenByParent.containsKey(changeId)
                    if (hasChildren) {
                        availableLanes.add(lane)
                    }
                }

                parentLane
            }

            // Store graph node
            graph[changeId] = GraphNode(
                lane = lane,
                color = color,
                parentLanes = parentLanes,
                passThroughLanes = passThroughLanes
            )
        }

        return graph
    }

    /**
     * Simplified version that assigns sequential lanes.
     * Use this if the full algorithm is too complex initially.
     */
    fun buildSimpleGraph(entries: List<LogEntry>): Map<ChangeId, GraphNode> {
        val graph = mutableMapOf<ChangeId, GraphNode>()

        for ((index, entry) in entries.withIndex()) {
            // For now, just put everything in lane 0
            // This gives us a straight vertical line - good for initial testing
            graph[entry.changeId] = GraphNode(
                lane = 0,
                color = colors[0],
                parentLanes = entry.parentIds.indices.map { 0 }
            )
        }

        return graph
    }
}
