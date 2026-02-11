package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.ui.log.graph.GraphEntry
import `in`.kkkev.jjidea.ui.log.graph.LayoutCalculatorImpl
import java.awt.Color

/**
 * Test helpers for commit graph testing.
 * Shared between CommitGraphBuilderTest and GraphRenderingTest.
 *
 * Simple graph builder that uses strings instead of ChangeId for testing.
 * Delegates to [LayoutCalculatorImpl] for the core algorithm.
 */
class GraphBuilder {
    private val colors =
        listOf(
            Color(0x4285F4), // Blue
            Color(0xEA4335), // Red
            Color(0xFBBC04), // Yellow
            Color(0x34A853), // Green
            Color(0xFF6D00), // Orange
            Color(0x9C27B0), // Purple
            Color(0x00ACC1), // Cyan
            Color(0x7CB342) // Light green
        )

    private val layoutCalculator = LayoutCalculatorImpl<String>()

    private fun colorForLane(lane: Int): Color = colors[lane % colors.size]

    data class Node(
        val id: String,
        val lane: Int,
        val color: Color,
        val parentLanes: List<Int>,
        /** Per-entry: parent ID → passthrough lane for non-adjacent parents */
        val passthroughLanes: Map<String, Int>,
        /** Per-row: lanes with vertical lines passing through (derived from all entries) */
        val rowPassthroughLanes: Map<Int, Color>
    )

    fun buildGraph(entries: List<GraphEntry<String>>): Map<String, Node> {
        // Calculate layout using the algorithm
        val layout = layoutCalculator.calculate(entries)

        // Build row index for per-row passthrough computation
        val rowByEntryId = layout.rows.withIndex().associate { (idx, row) -> row.id to idx }

        // Compute per-row passthrough lanes (derived from entries' passthroughLanes)
        val rowPassthroughs = mutableMapOf<Int, MutableSet<Int>>()
        for ((idx, row) in layout.rows.withIndex()) {
            for ((parentId, lane) in row.passthroughLanes) {
                val parentRow = rowByEntryId[parentId] ?: continue
                for (r in (idx + 1) until parentRow) {
                    rowPassthroughs.getOrPut(r) { mutableSetOf() }.add(lane)
                }
            }
        }

        // Convert RowLayout to Node
        return layout.rows.withIndex().associate { (idx, row) ->
            row.id to Node(
                id = row.id,
                lane = row.lane,
                color = colorForLane(row.lane),
                parentLanes = row.parentLanes,
                passthroughLanes = row.passthroughLanes,
                rowPassthroughLanes = (rowPassthroughs[idx] ?: emptySet()).associateWith { colorForLane(it) }
            )
        }
    }
}

/**
 * Helper to create a test entry.
 */
fun entry(id: String, parentIds: List<String> = emptyList()) = GraphEntry(current = id, parents = parentIds)
