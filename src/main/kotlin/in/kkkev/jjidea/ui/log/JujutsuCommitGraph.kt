package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.diagnostic.Logger
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeIdentity
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.ui.log.graph.ElidedAncestors
import `in`.kkkev.jjidea.ui.log.graph.GraphEntry
import `in`.kkkev.jjidea.ui.log.graph.IncrementalLayout
import `in`.kkkev.jjidea.ui.log.graph.LayoutCalculatorImpl
import `in`.kkkev.jjidea.ui.log.graph.ParentState
import `in`.kkkev.jjidea.ui.log.graph.RowLayout
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
    val stubLane: Int? = null,
    /** See [in.kkkev.jjidea.ui.log.graph.RowLayout.longEdgeCapLanes]. */
    val longEdgeCapLanes: Map<ChangeKey, Int> = emptyMap(),
    /**
     * Parents whose edge from this node bridges one or more filter-hidden commits (jj-idea-hlu3) -
     * drawn wavy. Always a subset of this node's laid-out parents.
     */
    val elidedParents: Set<ChangeKey> = emptySet()
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
 * Extends [ChangeIdentity] so entries are unique even across multiple repos, adding only the
 * parent-linkage a graph layout needs on top of plain repo-scoped identity.
 */
interface GraphableEntry : ChangeIdentity {
    val parentIds: List<ChangeId>
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
     * jj-idea-jnqi: a second, independent [IncrementalLayout] engine backing [appendGraph] -
     * the append-only fast path `UnifiedJujutsuLogDataLoader`'s paged `loadMore()` uses.
     * Separate from [layoutCalculator] (which stays the from-scratch path for every other
     * caller: filtered views, rebase/commit-picker previews) because the two have
     * incompatible state lifecycles - mixing calls into the same engine would make one
     * silently invalidate the other.
     *
     * [buildGraph]'s unfiltered overload reseeds this automatically (reset + a full append)
     * after every from-scratch layout, so a later [appendGraph] call always has a correct
     * base - **this was missing in jnqi's initial version**, which made the first
     * [appendGraph] after any load treat its delta as the entire log, silently dropping
     * every earlier row's [GraphNode]. [resetIncremental] remains for callers that skip
     * [buildGraph] entirely (e.g. an empty-repos short circuit) but still need to invalidate
     * a prior sequence.
     *
     * This instance is not internally synchronized - like [LayoutCalculatorImpl], "one
     * instance lays out one graph at a time" is a caller contract, not an enforced
     * invariant. `UnifiedJujutsuLogDataLoader` is the only concurrent caller and must
     * serialize every [buildGraph]/[appendGraph]/[resetIncremental] call on a given instance
     * through the same lock (its `mergeLock`) - see that class's jj-idea-jnqi bugfix notes.
     */
    private val incrementalEngine = IncrementalLayout<ChangeKey>()

    /** [IncrementalLayout.operationCount] of the last [appendGraph] call - see that field's doc. */
    val incrementalOperationCount: Long get() = incrementalEngine.operationCount

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
            var elided: Map<ChangeKey, Set<ChangeKey>> = emptyMap()
            val layout = if (entries === allEntries) {
                val graphEntries = entries.map { GraphEntry(it.key, it.parentKeys) }
                // jj-idea-jnqi bugfix: the unfiltered layout runs on the incremental engine so a later
                // appendGraph() call has a correct base to extend, instead of silently treating its first
                // delta as the whole log (losing every previously-loaded row's GraphNode). Only for the
                // unfiltered case - appendGraph()'s contract requires allIds to track entries' own ids
                // exactly, which only holds there. jj-idea-2570.15: this is the layout, not a second pass.
                incrementalEngine.reset()
                val appended = incrementalEngine.append(graphEntries)
                report.count("operations", incrementalEngine.operationCount)
                appended
            } else {
                // Filtered (e.g. UnifiedJujutsuLogPanel's own instance): leaves incrementalEngine untouched.
                // jj-idea-hlu3: re-point each visible entry at its nearest visible ancestors. Recomputed
                // from scratch on every call (filter change or on-demand row load), never patched.
                val allIds = allEntries.mapTo(HashSet()) { it.key }
                val visibleIds = entries.mapTo(HashSet()) { it.key }
                val parentsByKey = allEntries.associate { it.key to it.parentKeys }
                val remap = ElidedAncestors(entries.map { it.key }, parentsByKey::get) { it in visibleIds }
                report.count("elided-remap-operations", remap.operationCount)
                elided = remap.elided
                val graphEntries = entries.map { GraphEntry(it.key, remap.effectiveParents[it.key] ?: it.parentKeys) }
                val calculated = layoutCalculator.calculate(graphEntries, allIds)
                report.count("operations", layoutCalculator.operationCount)
                calculated
            }
            layout.rows.associate { it.id to it.toGraphNode(elided[it.id].orEmpty()) }
        }

    /**
     * Re-lays out [entries] - the full unfiltered list, replacing what [buildGraph]/[appendGraph] last
     * laid out - reusing the previous layout below the part that changed (jj-idea-2570.14). The post-write
     * path: `jj new` prepends a row, so everything below it keeps its lanes. Same result as [buildGraph],
     * at a layout cost of the changed rows plus about one checkpoint window instead of every loaded row;
     * see [IncrementalLayout.replace]. Same locking contract as [appendGraph].
     */
    fun relayoutGraph(entries: List<GraphableEntry>): Map<ChangeKey, GraphNode> =
        log.measurePerf("graph-layout-replace") { report ->
            report.count("rows", entries.size.toLong())
            val layout = incrementalEngine.replace(entries.map { GraphEntry(it.key, it.parentKeys) })
            report.count("operations", incrementalEngine.operationCount)
            layout.rows.associate { it.id to it.toGraphNode() }
        }

    /** Discards [appendGraph]'s accumulated state - see that field's doc. */
    fun resetIncremental() = incrementalEngine.reset()

    /**
     * Appends [delta] to the entries previously passed to [appendGraph] since the last
     * [resetIncremental] (jj-idea-jnqi), and returns the updated node map for the whole
     * accumulated set. [delta] must be in valid topological order relative to what came
     * before it (children before parents, and never a child of an already-appended entry) -
     * the caller (`UnifiedJujutsuLogDataLoader`'s append guard) is responsible for that; see
     * [IncrementalLayout.append]'s doc for the full contract and the algorithm itself.
     *
     * The returned map is a fresh linear pass over every row (same shape and cost as
     * [buildGraph]'s own `associate` - one hashmap insert per row, safe to publish to a
     * different thread) - what's *not* redone from scratch is the actual layout algorithm:
     * [IncrementalLayout.append] costs O(delta + a bounded checkpoint window), not O(total
     * rows), where the old choke point re-ran the whole passthrough/lane bookkeeping (and a
     * fresh `topologicalSort`) on every call.
     */
    fun appendGraph(delta: List<GraphableEntry>): Map<ChangeKey, GraphNode> =
        log.measurePerf("graph-layout-append") { report ->
            report.count("delta", delta.size.toLong())
            val graphEntries = delta.map { GraphEntry(it.key, it.parentKeys) }
            val layout = incrementalEngine.append(graphEntries)
            report.count("operations", incrementalEngine.operationCount)
            layout.rows.associate { it.id to it.toGraphNode() }
        }

    private fun RowLayout<ChangeKey>.toGraphNode(elidedParents: Set<ChangeKey> = emptySet()) = GraphNode(
        lane = lane,
        parentLanes = parentLanes,
        childLanes = childLanes,
        passthroughLanes = passthroughLanes,
        unresolvedParents = unresolvedParents,
        stubLane = stubLane,
        longEdgeCapLanes = longEdgeCapLanes,
        elidedParents = elidedParents
    )
}
