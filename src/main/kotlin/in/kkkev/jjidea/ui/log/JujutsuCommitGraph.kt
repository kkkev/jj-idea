package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.diagnostic.Logger
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.log.graph.GraphEntry
import `in`.kkkev.jjidea.ui.log.graph.LayoutCalculatorImpl
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import `in`.kkkev.jjidea.util.measurePerf
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
 * @property passthroughLanes For each non-adjacent parent, the passthrough lane used (parent ChangeKey → lane)
 */
data class GraphNode(
    val lane: Int,
    val parentLanes: List<Int> = emptyList(),
    val childLanes: List<Int> = emptyList(),
    val passthroughLanes: Map<ChangeKey, Int> = emptyMap(),
    /** Optional row highlight for preview (e.g., source/destination highlighting in rebase dialog). */
    val highlightColor: Color? = null,
    /** See [in.kkkev.jjidea.ui.log.graph.RowLayout.unresolvedParents]. */
    val unresolvedParents: Map<ChangeKey, ParentState> = emptyMap(),
    /** See [in.kkkev.jjidea.ui.log.graph.RowLayout.stubLane]. */
    val stubLane: Int? = null
) {
    /** Always [lane]'s color - there is no case where a node's line color differs from its own
     * lane's, so this is derived rather than a separate field callers could pass out of sync with
     * [lane] (jj-idea-a0wp: [CommitGraphBuilder] used to carry its own byte-identical copy of
     * [JujutsuGraphAndDescriptionRenderer]'s lane palette just to compute this once). */
    val color: Color get() = JujutsuGraphAndDescriptionRenderer.colorForLane(lane)
}

/**
 * Interface for entries that can be laid out in a commit graph.
 * This allows testing without depending on full LogEntry with IntelliJ Platform classes.
 * Includes repo so that entries are unique, even across multiple repos.
 */
interface GraphableEntry {
    val repo: JujutsuRepository
    val id: ChangeId
    val parentIds: List<ChangeId>
    val key: ChangeKey get() = ChangeKey(repo, id)
    val parentKeys: List<ChangeKey> get() = parentIds.map { ChangeKey(repo, it) }
}

/**
 * Builds graph layout for a list of commits.
 *
 * Delegates to [LayoutCalculatorImpl] for the core algorithm, then converts
 * the result to [GraphNode] objects for rendering.
 */
class CommitGraphBuilder {
    private val log = Logger.getInstance(javaClass)

    private val layoutCalculator = LayoutCalculatorImpl<ChangeKey>()

    /**
     * Build graph layout for [entries], with no filter concept - every unresolved parent is
     * classified [ParentState.NOT_LOADED] (see the two-arg overload for the filtered case).
     *
     * @param entries List of commits (newest first, as returned by jj log)
     * @return Map of ChangeKey -> GraphNode. Keyed by repo-scoped [ChangeKey], not bare [ChangeId],
     *   so entries from different repos whose ids coincidentally collide (e.g. the root commit's
     *   id) are never treated as the same graph node (jj-idea-1ra9).
     */
    fun buildGraph(entries: List<GraphableEntry>): Map<ChangeKey, GraphNode> = buildGraph(entries, entries)

    /**
     * Build graph layout for the filtered/visible [entries], distinguishing a parent that's
     * merely filtered out of [allEntries] ([ParentState.HIDDEN]) from one not loaded at all
     * ([ParentState.NOT_LOADED]) - see docs/design/jj-idea-hlu3-xi58-elided-ancestor-rendering.md.
     *
     * @param entries the visible/rendered subset to lay out (newest first)
     * @param allEntries the full loaded set [entries] was filtered from; pass the same list as
     *   [entries] when there's no filter
     */
    fun buildGraph(entries: List<GraphableEntry>, allEntries: List<GraphableEntry>): Map<ChangeKey, GraphNode> =
        log.measurePerf("graph-layout") { report ->
            report.count("rows", entries.size.toLong())
            val graphEntries = entries.map { GraphEntry(it.key, it.parentKeys) }
            val allIds = allEntries.mapTo(HashSet()) { it.key }
            val layout = layoutCalculator.calculate(graphEntries, allIds)
            report.count("operations", layoutCalculator.operationCount)
            layout.rows.associate { row ->
                row.id to GraphNode(
                    lane = row.lane,
                    parentLanes = row.parentLanes,
                    childLanes = row.childLanes,
                    passthroughLanes = row.passthroughLanes,
                    unresolvedParents = row.unresolvedParents,
                    stubLane = row.stubLane
                )
            }
        }
}
