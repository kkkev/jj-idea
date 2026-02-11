package `in`.kkkev.jjidea.ui.log

import com.intellij.ui.JBColor
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.ui.log.graph.GraphEntry
import `in`.kkkev.jjidea.ui.log.graph.LayoutCalculatorImpl
import java.awt.Color

/*
 * Commit graph layout algorithm and rendering data structures.
 *
 * Uses LayoutCalculatorImpl for the core layout algorithm.
 * See docs/LOG_GRAPH_ALGORITHM.md for details.
 */

/**
 * Represents a commit's position in the graph.
 *
 * @property lane Horizontal position (0 = leftmost)
 * @property color Color for this commit's line
 * @property parentLanes Lanes where parent commits are located
 * @property childLanes Lanes where child commits are located (for fork detection)
 * @property passthroughLanes For each non-adjacent parent, the passthrough lane used (parent ChangeId → lane)
 */
data class GraphNode(
    val lane: Int,
    val color: Color,
    val parentLanes: List<Int> = emptyList(),
    val childLanes: List<Int> = emptyList(),
    val passthroughLanes: Map<ChangeId, Int> = emptyMap()
)

/**
 * Interface for entries that can be laid out in a commit graph.
 * This allows testing without depending on full LogEntry with IntelliJ Platform classes.
 */
interface GraphableEntry {
    val id: ChangeId
    val parentIds: List<ChangeId>
}

/**
 * Builds graph layout for a list of commits.
 *
 * Delegates to [LayoutCalculatorImpl] for the core algorithm, then converts
 * the result to [GraphNode] objects for rendering.
 */
class CommitGraphBuilder {
    // Graph colors with light/dark theme variants for good contrast
    private val colors: List<Color> = listOf(
        JBColor(0x4285F4, 0x6AA1FF), // Blue
        JBColor(0xEA4335, 0xFF6B5E), // Red
        JBColor(0xC99700, 0xE0B800), // Yellow (darker for light theme visibility)
        JBColor(0x34A853, 0x5DCD73), // Green
        JBColor(0xFF6D00, 0xFF8A3D), // Orange
        JBColor(0x9C27B0, 0xC25ED0), // Purple
        JBColor(0x00ACC1, 0x4DD0E1), // Cyan
        JBColor(0x689F38, 0x8BC34A) // Light green (darker for light theme)
    )

    private val layoutCalculator = LayoutCalculatorImpl<ChangeId>()

    private fun colorForLane(lane: Int): Color = colors[lane % colors.size]

    /**
     * Build graph layout for commits.
     *
     * @param entries List of commits (newest first, as returned by jj log)
     * @return Map of changeId -> GraphNode
     */
    fun buildGraph(entries: List<GraphableEntry>): Map<ChangeId, GraphNode> {
        // Convert to GraphEntry for the layout calculator
        val graphEntries = entries.map { GraphEntry(it.id, it.parentIds) }

        // Calculate layout using the algorithm
        val layout = layoutCalculator.calculate(graphEntries)

        // Convert RowLayout to GraphNode
        return layout.rows.associate { row ->
            row.id to GraphNode(
                lane = row.lane,
                color = colorForLane(row.lane),
                parentLanes = row.parentLanes,
                childLanes = row.childLanes,
                passthroughLanes = row.passthroughLanes
            )
        }
    }
}
