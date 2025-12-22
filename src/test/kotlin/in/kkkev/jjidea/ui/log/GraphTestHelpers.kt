package `in`.kkkev.jjidea.ui.log

import java.awt.Color

/**
 * Test helpers for commit graph testing.
 * Shared between CommitGraphBuilderTest and GraphRenderingTest.
 */

/**
 * Simple graph builder that uses strings instead of ChangeId for testing.
 * This avoids IntelliJ Platform dependencies (ChangeId uses Hash/HashImpl).
 */
class GraphBuilder {
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

    data class Node(
        val id: String,
        val lane: Int,
        val color: Color,
        val parentLanes: List<Int>,
        val passThroughLanes: Map<Int, Color>
    )

    data class Entry(
        val id: String,
        val parentIds: List<String>
    )

    fun buildGraph(entries: List<Entry>): Map<String, Node> {
        val graph = mutableMapOf<String, Node>()
        val laneAssignments = mutableMapOf<String, Int>()
        val laneColors = mutableMapOf<Int, Color>()
        val availableLanes = mutableSetOf<Int>()
        val childrenByParent = mutableMapOf<String, MutableList<Pair<String, Int>>>() // parent -> (child, childLane)
        var maxLane = 0

        for ((index, entry) in entries.withIndex()) {
            val id = entry.id

            // Determine lane for this commit
            val lane = if (laneAssignments.containsKey(id)) {
                // Already assigned by a child
                laneAssignments.remove(id)!!
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
            childrenByParent[id]?.forEach { (childId, childLane) ->
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

            // Calculate pass-through lanes BEFORE assigning parents
            val passThroughLanes = laneAssignments.values
                .filter { it != lane }
                .distinct()
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
                        lane
                    } else {
                        maxLane.also { maxLane++ }
                    }
                    laneAssignments[parentId] = newLane
                    newLane
                }
                // Track this child-parent relationship
                childrenByParent.getOrPut(parentId) { mutableListOf() }.add(id to lane)

                // If this child merges into a parent in a different lane, free this child's lane immediately
                // BUT: Only free if this is NOT a leaf node (has children), because leaf merges
                // should preserve lanes for visual clarity when multiple siblings merge consecutively
                if (parentLane != lane) {
                    val hasChildren = childrenByParent.containsKey(id)
                    if (hasChildren) {
                        availableLanes.add(lane)
                    }
                }

                parentLane
            }

            graph[id] = Node(
                id = id,
                lane = lane,
                color = color,
                parentLanes = parentLanes,
                passThroughLanes = passThroughLanes
            )
        }

        return graph
    }
}

/**
 * Helper to create a test entry.
 */
fun entry(
    id: String,
    parentIds: List<String> = emptyList()
) = GraphBuilder.Entry(
    id = id,
    parentIds = parentIds
)
