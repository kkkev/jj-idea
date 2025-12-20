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
 */
data class GraphNode(
    val lane: Int,
    val color: Color,
    val parentLanes: List<Int> = emptyList()
)

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
    fun buildGraph(entries: List<LogEntry>): Map<ChangeId, GraphNode> {
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
        val availableLanes = mutableSetOf<Int>() // Lanes that are free
        var maxLane = 0

        for (entry in entries) {
            val changeId = entry.changeId

            // Determine lane for this commit
            val lane = if (laneAssignments.containsKey(changeId)) {
                // Already assigned by a child
                val assignedLane = laneAssignments.remove(changeId)!!

                // TODO This is not always the case. It only becomes available if no parent has been allocated here
                availableLanes.add(assignedLane) // This lane becomes available after this commit

                assignedLane
            } else {
                // New commit, use leftmost available lane
                if (availableLanes.isNotEmpty()) {
                    availableLanes.min().also { availableLanes.remove(it) }
                } else {
                    ++maxLane
                }
            }

            // Assign lanes to parents
            val parentLanes = entry.parentIds.map { parentId ->
                laneAssignments.getOrPut(parentId) {
                    // First parent continues in same lane, others get new lanes
                    if (parentId == entry.parentIds.firstOrNull()) {
                        lane
                    } else {
                        // Merge - additional parent gets new lane
                        ++maxLane
                    }
                }
            }

            // Choose color based on lane
            val color = colors[lane % colors.size]

            // Store graph node
            graph[changeId] = GraphNode(lane = lane, color = color, parentLanes = parentLanes)
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
